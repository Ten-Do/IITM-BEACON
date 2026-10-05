package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTagsWithAttribute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The burger button of the shared headers ({@code layout/nav-toggle.html},
 * driven by {@code static/js/nav-toggle.js}): every page with a header nav
 * renders exactly one toggle, collapsed, pointing at that nav by id — which
 * is all the script needs to find the menu it opens. The admin login pages'
 * inline header has no nav, so no toggle either. How the menu looks and
 * behaves in a browser is the e2e suite's job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class HeaderNavToggleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication visitor() {
        return new UsernamePasswordAuthenticationToken(
                "nav-toggle@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return html(request, 200);
    }

    private String html(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private Testimonial approvedTestimonial() {
        Testimonial t = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email("nav-toggle-detail@example.com")
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        t.getSections().add(TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("Great time overall.")
                .modified(false)
                .build());
        return testimonialRepository.saveAndFlush(t);
    }

    /** The one {@code <header>} element of the page. */
    private static String header(String html) {
        assertThat(elements(html, "header")).as("<header> elements").hasSize(1);
        return elements(html, "header").get(0);
    }

    /** The header's one {@code <nav>} (the page may have others, e.g. its pagination). */
    private static String headerNavTag(String header) {
        List<String> navs = openingTags(header, "nav");
        assertThat(navs).as("<nav> in the header").hasSize(1);
        return navs.get(0);
    }

    /**
     * The page's one toggle is a real {@code <button type="button">} (never
     * a submit), collapsed, labelled, and controls the header's nav — whose
     * id is unique on the page and which comes right after the button, so
     * Tab moves from the button into the open menu.
     */
    private static void assertTogglesTheHeaderNav(String html, String expectedNavId) {
        List<String> toggles = openingTagsWithAttribute(html, "data-nav-toggle");
        assertThat(toggles).as("[data-nav-toggle] elements").hasSize(1);
        String toggle = toggles.get(0);

        assertThat(toggle).startsWith("<button");
        assertThat(attribute(toggle, "type")).contains("button");
        assertThat(attribute(toggle, "class")).contains("nav-toggle");
        assertThat(attribute(toggle, "aria-expanded")).contains("false");
        assertThat(attribute(toggle, "aria-label")).contains("Menu");
        assertThat(attribute(toggle, "aria-controls")).contains(expectedNavId);

        String header = header(html);
        String nav = headerNavTag(header);
        assertThat(attribute(nav, "id")).contains(expectedNavId);
        assertThat(header).contains(toggle);
        assertThat(header.indexOf(toggle)).isLessThan(header.indexOf(nav));

        List<String> ids = openingTagsWithAttribute(html, "id").stream()
                .map(tag -> attribute(tag, "id").orElseThrow())
                .toList();
        assertThat(ids).as("ids on the page").doesNotHaveDuplicates().contains(expectedNavId);
    }

    // -- visitor header --

    @ParameterizedTest
    @ValueSource(strings = {
        "/gallery",
        "/gallery?q=nothing-matches-this-at-all",
        "/submissions/login",
        "/submissions/login/code?email=a@example.com",
    })
    void publicVisitorPages_haveOneCollapsedToggleForTheHeaderNav(String path) throws Exception {
        assertTogglesTheHeaderNav(html(get(path)), "visitor-nav");
    }

    @Test
    void notFoundPage_hasOneCollapsedToggleForTheHeaderNav() throws Exception {
        assertTogglesTheHeaderNav(html(get("/gallery/999999"), 404), "visitor-nav");
    }

    @Test
    void galleryDetailPage_hasOneCollapsedToggleForTheHeaderNav() throws Exception {
        Testimonial saved = approvedTestimonial();

        assertTogglesTheHeaderNav(html(get("/gallery/{id}", saved.getId())), "visitor-nav");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/submissions/form", "/submissions/confirmation"})
    void visitorProtectedPages_haveOneCollapsedToggleForTheHeaderNav(String path) throws Exception {
        assertTogglesTheHeaderNav(html(get(path).with(authentication(visitor()))), "visitor-nav");
    }

    @Test
    void publicPageViewedWithAVisitorSession_stillHasExactlyOneToggle() throws Exception {
        assertTogglesTheHeaderNav(html(get("/gallery").with(authentication(visitor()))), "visitor-nav");
    }

    @Test
    void visitorHeaderNav_keepsBothLinksInsideTheMenu() throws Exception {
        String header = header(html(get("/gallery")));
        String nav = elements(header, "nav").get(0);

        assertThat(nav).contains("href=\"/gallery\"", "href=\"/submissions/login\"");
    }

    // -- admin header --

    @Test
    void moderationQueue_hasOneCollapsedToggleForTheAdminHeaderNav() throws Exception {
        assertTogglesTheHeaderNav(html(get("/moderation/queue").with(authentication(admin()))), "admin-nav");
    }

    @Test
    void moderationQueue_emptyQueueWithPaginationOutOfRange_stillHasTheToggle() throws Exception {
        assertTogglesTheHeaderNav(
                html(get("/moderation/queue").param("page", "50").with(authentication(admin()))), "admin-nav");
    }

    @Test
    void adminHeader_keepsTheModeratorBadgeTheQueueLinkAndTheSessionCheck() throws Exception {
        String header = header(html(get("/moderation/queue").with(authentication(admin()))));

        assertThat(header).contains("Moderator");
        assertThat(elements(header, "nav").get(0)).contains("href=\"/moderation/queue\"");
        assertThat(header).contains("src=\"/js/session-check.js\"");
    }

    // -- admin login pages: inline header, no nav --

    @ParameterizedTest
    @ValueSource(strings = {"/admin/login", "/admin/login/code?email=a@example.com"})
    void adminLoginPages_haveNoToggleAndNoHeaderNav(String path) throws Exception {
        String html = html(get(path));

        assertThat(openingTagsWithAttribute(html, "data-nav-toggle")).isEmpty();
        assertThat(html).doesNotContain("nav-toggle\"", "aria-controls");
        assertThat(openingTags(header(html), "nav")).isEmpty();
    }
}
