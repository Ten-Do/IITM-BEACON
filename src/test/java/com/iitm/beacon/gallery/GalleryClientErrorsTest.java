package com.iitm.beacon.gallery;

import static com.iitm.beacon.testsupport.ClientErrors.INTERNALS;
import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.ClientErrors.assertJsonError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.LogCapture;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Client errors on the gallery's routes (BL-029, BL-037): a path id that
 * isn't a number — or doesn't fit an id — is a 404 like an unknown id (the
 * article page shows its own "not found" page), a malformed query value is
 * a 400, and a client that accepts no JSON gets a 406. The API answers the
 * JSON {@code ErrorResponse}, the list page the HTML error page; none is a
 * 500, none names an exception, none is logged as a server error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class GalleryClientErrorsTest {

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @AfterEach
    void nothingWasLoggedAsAServerError() {
        assertThat(logs.errors()).isEmpty();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private Testimonial approved() {
        String email = "client-errors-" + System.nanoTime() + "@example.com";
        Testimonial t = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        t.getSections().add(TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("A readable answer.")
                .modified(false)
                .build());
        return testimonialRepository.saveAndFlush(t);
    }

    // -- REST: GET /api/gallery/testimonials/{id} --

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999", "9223372036854775808", "1e3", "%20"})
    void apiDetail_idThatIsNoLong_isA404_likeAnUnknownId(String id) throws Exception {
        // A raw URI: get(String) would encode the '%' of "%20" once more.
        assertJsonError(perform(get(URI.create("/api/gallery/testimonials/" + id))),
                404, "Resource not found", "/api/gallery/testimonials/" + id);
    }

    @Test
    void apiDetail_largestLong_isAnUnknownId_soA404Too() throws Exception {
        assertJsonError(perform(get("/api/gallery/testimonials/" + Long.MAX_VALUE)),
                404, "Testimonial not found.", "/api/gallery/testimonials/" + Long.MAX_VALUE);
    }

    // -- REST: GET /api/gallery/testimonials?... --

    @ParameterizedTest
    @CsvSource({
        "topicIds, abc",
        "topicIds, '1,abc'",
        "groupIds, abc",
        "groupIds, 99999999999999999999",
        "page, abc",
        "page, 1.5",
        "page, 99999999999",
        "size, abc"
    })
    void apiBrowse_queryValueOfTheWrongType_isA400NamingTheParameter(String parameter, String value)
            throws Exception {
        assertJsonError(perform(get("/api/gallery/testimonials").param(parameter, value)),
                400, "Invalid value for parameter '" + parameter + "'.", "/api/gallery/testimonials");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "page | -1  | page: must be greater than or equal to 0",
        "size | 0   | size: must be greater than or equal to 1",
        "size | -1  | size: must be greater than or equal to 1",
        "size | 101 | size: must be less than or equal to 100"
    })
    void apiBrowse_pagingOutOfRange_isA400NamingTheParameter_notTheJavaMethod(
            String parameter, String value, String message) throws Exception {
        assertJsonError(perform(get("/api/gallery/testimonials").param(parameter, value)),
                400, message, "/api/gallery/testimonials");
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "0, 100"})
    void apiBrowse_pagingAtItsBounds_isAccepted(String page, String size) throws Exception {
        assertThat(perform(get("/api/gallery/testimonials").param("page", page).param("size", size)).getStatus())
                .isEqualTo(200);
    }

    /**
     * A page is only addressable while its first row's offset, page × size,
     * fits an {@code int} (Spring Data's limit; beyond it the query used to
     * fail as a 500). The largest such page is simply an empty page.
     */
    @ParameterizedTest
    @CsvSource({"21474836, 100", "107374182, 20", "1073741823, 2", "2147483647, 1"})
    void apiBrowse_largestPageWhoseOffsetFitsAnInt_isAnEmptyPage(String page, String size) throws Exception {
        MockHttpServletResponse response = perform(get("/api/gallery/testimonials").param("page", page)
                .param("size", size));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains("\"content\":[]", "\"page\":" + page, "\"size\":" + size);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "21474837   | 100 | page: must be less than or equal to 21474836",
        "99999999   | 100 | page: must be less than or equal to 21474836",
        "107374183  | 20  | page: must be less than or equal to 107374182",
        "1073741824 | 2   | page: must be less than or equal to 1073741823",
        "2147483647 | 20  | page: must be less than or equal to 107374182"
    })
    void apiBrowse_pageOneBeyondThat_isA400NamingTheLargestPage(String page, String size, String message)
            throws Exception {
        assertJsonError(perform(get("/api/gallery/testimonials").param("page", page).param("size", size)),
                400, message, "/api/gallery/testimonials");
    }

    @Test
    void apiBrowse_pageTooLargeForTheDefaultSizeOf20_isA400Too() throws Exception {
        assertJsonError(perform(get("/api/gallery/testimonials").param("page", "107374183")),
                400, "page: must be less than or equal to 107374182", "/api/gallery/testimonials");
    }

    @Test
    void apiBrowse_clientAcceptingNoJson_isA406_stillAnsweredAsJson() throws Exception {
        assertJsonError(perform(get("/api/gallery/testimonials").accept(MediaType.APPLICATION_XML)),
                406, "This resource is only available as JSON.", "/api/gallery/testimonials");
    }

    // -- page: GET /gallery/{id} --

    @ParameterizedTest
    @ValueSource(strings = {
        "abc", "1.5", "-1", "99999999999999999999", "9223372036854775807", "1e3", "%20", "1%20", "%2B1"
    })
    void articlePage_idThatIsNoId_isTheGallerysOwnNotFoundPage(String id) throws Exception {
        MvcResult result = mockMvc.perform(get(URI.create("/gallery/" + id))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getModelAndView()).isNotNull();
        assertThat(result.getModelAndView().getViewName()).isEqualTo("gallery/not-found");
        assertThat(result.getResponse().getContentAsString())
                .contains("Testimonial not found")
                .doesNotContain(INTERNALS);
    }

    @Test
    void articlePage_hexOrSignedSpellingOfARealId_isNotThatArticle() throws Exception {
        Testimonial saved = approved();
        String hex = "0x" + Long.toHexString(saved.getId());

        for (String id : new String[] {hex, "+" + saved.getId(), " " + saved.getId()}) {
            MvcResult result = mockMvc.perform(get("/gallery/{id}", id)).andReturn();
            assertThat(result.getResponse().getStatus()).as(id).isEqualTo(404);
            assertThat(result.getModelAndView().getViewName()).as(id).isEqualTo("gallery/not-found");
        }
    }

    @Test
    void articlePage_idOf18Digits_leadingZerosIncluded_isStillTheArticle_19DigitsIsNot() throws Exception {
        Testimonial saved = approved();
        String eighteen = String.format("%018d", saved.getId());
        String nineteen = "0" + eighteen;

        MvcResult found = mockMvc.perform(get("/gallery/" + eighteen)).andReturn();
        MvcResult notFound = mockMvc.perform(get("/gallery/" + nineteen)).andReturn();

        assertThat(found.getResponse().getStatus()).isEqualTo(200);
        assertThat(found.getModelAndView().getViewName()).isEqualTo("gallery/detail");
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(notFound.getModelAndView().getViewName()).isEqualTo("gallery/not-found");
    }

    // -- page: GET /gallery?... --

    @ParameterizedTest
    @CsvSource({
        "page, abc",
        "page, 99999999999",
        "topicIds, abc",
        "topicIds, '7,abc'",
        "groupIds, abc",
        "groupIds, 99999999999999999999"
    })
    void listPage_queryValueOfTheWrongType_isTheHtml400Page(String parameter, String value) throws Exception {
        assertHtmlErrorPage(perform(get("/gallery").param(parameter, value)), 400);
    }

    /** The list page shows 20 cards a page: page 107374182 is the last whose offset fits an int. */
    @Test
    void listPage_largestAddressablePage_isAnEmptyList() throws Exception {
        MockHttpServletResponse response = perform(get("/gallery").param("page", "107374182"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).doesNotContain("class=\"gallery-card\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"107374183", "2147483647"})
    void listPage_pageBeyondThat_isTheHtml400Page(String page) throws Exception {
        assertHtmlErrorPage(perform(get("/gallery").param("page", page)), 400);
    }

    @Test
    void listPage_errorIsHtml_evenForAClientAskingForJson() throws Exception {
        assertHtmlErrorPage(perform(get("/gallery").param("page", "abc").accept(MediaType.APPLICATION_JSON)), 400);
    }
}
