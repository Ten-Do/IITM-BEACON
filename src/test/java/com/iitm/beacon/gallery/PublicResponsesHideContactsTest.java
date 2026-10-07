package com.iitm.beacon.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * NFR-CONTACT-CONFIDENTIALITY on the public read paths: for an approved
 * testimonial that has a public contact, a private contact and a login
 * email, none of the three — nor a recognisable part of them, nor the
 * email's lookup hash — appears in any public page or API response. The
 * article's explicit, same-origin reveal ({@code GalleryContactRevealTest})
 * is the only place a public contact may ever be shown. Where a response
 * lists testimonials, it is first checked to really contain this one, so
 * an empty page can't pass for a clean one. Also: keyword search doesn't
 * match on the login email or a private contact, which would let anyone
 * confirm a guessed address belongs to a testimonial.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class PublicResponsesHideContactsTest {

    private static final String FIRST_NAME = "Zebulon";
    /** Shown on every page or response that lists or shows the testimonial: cards carry no name. */
    private static final String ANSWER_MARKER = "monsoon semester in Chennai";
    private static final String ANSWER = "The " + ANSWER_MARKER + " was unforgettable.";
    private static final String PUBLIC_LOCAL_PART = "leak-public-pangolin";
    private static final String PUBLIC_CONTACT = PUBLIC_LOCAL_PART + "@example.net";
    private static final String PRIVATE_CONTACT = "+44 7700 900123";
    private static final String LOGIN_LOCAL_PART = "leak-login-axolotl";
    private static final String LOGIN_EMAIL = LOGIN_LOCAL_PART + "@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private Testimonial testimonial;

    @BeforeEach
    void approvedTestimonialWithEveryKindOfContact() {
        Testimonial t = Testimonial.builder()
                .firstName(FIRST_NAME)
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(LOGIN_EMAIL)
                .emailLookupHash(emailLookupHashService.hash(LOGIN_EMAIL))
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                // Newest approval of all, so it is on the gallery's first page.
                .reviewedAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
        t.getSections().add(TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText(ANSWER)
                .modified(false)
                .build());
        t.getContactMethods().add(contact(t, "email", PUBLIC_CONTACT, true, 0));
        t.getContactMethods().add(contact(t, "whatsapp", PRIVATE_CONTACT, false, 1));
        testimonial = testimonialRepository.saveAndFlush(t);
    }

    private ContactMethod contact(Testimonial t, String typeSlug, String value, boolean isPublic, int order) {
        return ContactMethod.builder()
                .testimonial(t)
                .contactType(contactTypeRepository.findBySlug(typeSlug).orElseThrow())
                .value(value)
                .isPublic(isPublic)
                .displayOrder(order)
                .build();
    }

    /** A public URL ({@code {id}} = the testimonial's id), and whether its response shows that testimonial. */
    static Stream<Arguments> publicResponses() {
        return Stream.of(
                Arguments.of("/api/gallery/testimonials", true),
                Arguments.of("/api/gallery/testimonials?q=" + FIRST_NAME, true),
                Arguments.of("/api/gallery/testimonials?country=IN", true),
                Arguments.of("/api/gallery/testimonials/{id}", true),
                Arguments.of("/gallery", true),
                Arguments.of("/gallery?q=" + FIRST_NAME, true),
                Arguments.of("/gallery/{id}", true),
                Arguments.of("/", false),
                Arguments.of("/api/analytics/summary", false),
                Arguments.of("/api/gallery/countries", false),
                Arguments.of("/api/gallery/topics", false));
    }

    @ParameterizedTest
    @MethodSource("publicResponses")
    void publicResponse_neverCarriesAContactValueOrTheLoginEmail(String url, boolean showsTheTestimonial)
            throws Exception {
        String body = mockMvc.perform(get(url.replace("{id}", String.valueOf(testimonial.getId()))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        if (showsTheTestimonial) {
            assertThat(body).contains(ANSWER_MARKER);
        }
        assertThat(body).doesNotContain(
                PUBLIC_CONTACT,
                PUBLIC_LOCAL_PART,
                PRIVATE_CONTACT,
                PRIVATE_CONTACT.replace(" ", ""),
                "900123",
                LOGIN_EMAIL,
                LOGIN_LOCAL_PART,
                testimonial.getEmailLookupHash());
    }

    @ParameterizedTest
    @ValueSource(strings = {LOGIN_EMAIL, LOGIN_LOCAL_PART, PRIVATE_CONTACT, "900123"})
    void keywordSearch_forTheLoginEmailOrAPrivateContact_findsNothing(String query) throws Exception {
        mockMvc.perform(get("/api/gallery/testimonials").param("q", query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {LOGIN_EMAIL, PRIVATE_CONTACT})
    void keywordSearchPage_forTheLoginEmailOrAPrivateContact_listsNoTestimonial(String query) throws Exception {
        String html = mockMvc.perform(get("/gallery").param("q", query))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).doesNotContain(ANSWER_MARKER, "/gallery/" + testimonial.getId() + "\"");
    }

    /** Not even an empty or null contact or email field: the article only says whether a reveal is possible. */
    @ParameterizedTest
    @ValueSource(strings = {"contactMethods", "contacts", "email", "emailLookupHash", "value"})
    void detailJson_hasNoContactOrEmailField(String field) throws Exception {
        String json = mockMvc.perform(get("/api/gallery/testimonials/{id}", testimonial.getId()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(json).contains("\"hasRevealableContact\":true").doesNotContain("\"" + field + "\"");
    }
}
