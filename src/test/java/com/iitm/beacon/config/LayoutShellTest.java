package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * The shared {@code layout/shell.html} {@code head} fragment. Browsers
 * request {@code /favicon.ico} on their own whenever a page declares no icon;
 * that path is (deliberately) caught by {@code SecurityConfig}'s {@code
 * denyAll()} tail, so a logged-in visitor saw a 403 in the console on every
 * page. An inline empty icon stops the request from being made at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class LayoutShellTest {

    private static final String INLINE_EMPTY_ICON = "<link rel=\"icon\" href=\"data:,\"";

    private static final String NAV_TOGGLE_SCRIPT = "/js/nav-toggle.js";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @ParameterizedTest
    @ValueSource(strings = {"/gallery", "/submissions/login", "/admin/login", "/admin/login/code?email=a@example.com"})
    void anonymousPages_declareAnInlineIcon(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(INLINE_EMPTY_ICON)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/gallery", "/submissions/login", "/admin/login", "/admin/login/code?email=a@example.com"})
    void pagesWithoutInteractiveWidgets_doNotLoadAlpine(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/webjars/alpinejs"))));
    }

    /**
     * PhotoSwipe (its stylesheet and the viewer module) is loaded only by the
     * two pages that open photos fullscreen — the testimonial article and the
     * moderation queue — and by no shared layout fragment. The gallery list's
     * cover photos are links to the article, not viewer items.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/gallery",
                "/gallery/999999",
                "/submissions/login",
                "/admin/login",
                "/admin/login/code?email=a@example.com"
            })
    void pagesThatOpenNoPhotos_doNotLoadThePhotoViewer(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(content().string(not(containsString("photoswipe"))))
                .andExpect(content().string(not(containsString("/js/photo-viewer.js"))));
    }

    @Test
    void visitorSubmissionForm_doesNotLoadThePhotoViewer() throws Exception {
        var visitor = new UsernamePasswordAuthenticationToken(
                "shell-viewer@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));

        mockMvc.perform(get("/submissions/form").with(authentication(visitor)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("photoswipe"))))
                .andExpect(content().string(not(containsString("/js/photo-viewer.js"))));
    }

    @Test
    void visitorSubmissionForm_declaresAnInlineIcon() throws Exception {
        var visitor = new UsernamePasswordAuthenticationToken(
                "shell-icon@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));

        mockMvc.perform(get("/submissions/form").with(authentication(visitor)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(INLINE_EMPTY_ICON)));
    }

    @Test
    void faviconPath_itselfStaysDeniedForALoggedInVisitor() throws Exception {
        // The fix is to stop browsers asking, not to open up the path.
        var visitor = new UsernamePasswordAuthenticationToken(
                "shell-icon-denied@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));

        mockMvc.perform(get("/favicon.ico").with(authentication(visitor))).andExpect(status().isForbidden());
    }

    // -- the burger script (static/js/nav-toggle.js), loaded by the shell head on every page --

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication visitor() {
        return new UsernamePasswordAuthenticationToken(
                "shell-nav-toggle@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
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
                .email("shell-nav-toggle-detail@example.com")
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

    /**
     * Exactly one {@code <script>} for the burger script on the page, in its
     * {@code <head>}, deferred (it needs the parsed header; the page must not
     * wait for it) and a classic script, not a module.
     */
    private static void assertLoadsTheNavToggleScriptOnceDeferredInTheHead(String html) {
        List<String> scripts = openingTags(html, "script").stream()
                .filter(tag -> attribute(tag, "src").filter(NAV_TOGGLE_SCRIPT::equals).isPresent())
                .toList();
        assertThat(scripts).as("<script src=\"" + NAV_TOGGLE_SCRIPT + "\">").hasSize(1);
        String script = scripts.get(0);
        assertThat(attribute(script, "defer")).as("defer").isPresent();
        assertThat(attribute(script, "async")).as("async").isEmpty();
        assertThat(attribute(script, "type")).as("type").isEmpty();

        List<String> heads = elements(html, "head");
        assertThat(heads).hasSize(1);
        assertThat(heads.get(0)).contains(script);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/gallery",
                "/submissions/login",
                "/submissions/login/code?email=a@example.com",
                "/admin/login",
                "/admin/login/code?email=a@example.com"
            })
    void anonymousPages_loadTheNavToggleScriptOnceDeferred(String path) throws Exception {
        assertLoadsTheNavToggleScriptOnceDeferredInTheHead(html(get(path)));
    }

    @Test
    void notFoundPage_loadsTheNavToggleScriptOnceDeferred() throws Exception {
        assertLoadsTheNavToggleScriptOnceDeferredInTheHead(html(get("/gallery/999999"), 404));
    }

    @Test
    void galleryDetailPage_loadsTheNavToggleScriptOnceDeferred() throws Exception {
        Testimonial saved = approvedTestimonial();

        assertLoadsTheNavToggleScriptOnceDeferredInTheHead(html(get("/gallery/{id}", saved.getId())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/submissions/form", "/submissions/confirmation"})
    void visitorPages_loadTheNavToggleScriptOnceDeferred(String path) throws Exception {
        assertLoadsTheNavToggleScriptOnceDeferredInTheHead(html(get(path).with(authentication(visitor()))));
    }

    @Test
    void moderationQueue_loadsTheNavToggleScriptOnceDeferred() throws Exception {
        assertLoadsTheNavToggleScriptOnceDeferredInTheHead(
                html(get("/moderation/queue").with(authentication(admin()))));
    }

    /**
     * The burger is plain JS: loading it on every page must not bring Alpine
     * along to pages that have no Alpine widget (only the submission form
     * does).
     */
    @Test
    void pagesWithTheBurgerButNoWidgets_doNotLoadAlpine() throws Exception {
        Testimonial saved = approvedTestimonial();

        assertThat(html(get("/gallery/{id}", saved.getId()))).doesNotContain("/webjars/alpinejs");
        assertThat(html(get("/gallery/999999"), 404)).doesNotContain("/webjars/alpinejs");
        assertThat(html(get("/submissions/login/code?email=a@example.com"))).doesNotContain("/webjars/alpinejs");
        assertThat(html(get("/submissions/confirmation").with(authentication(visitor()))))
                .doesNotContain("/webjars/alpinejs");
        assertThat(html(get("/moderation/queue").with(authentication(admin())))).doesNotContain("/webjars/alpinejs");
    }
}
