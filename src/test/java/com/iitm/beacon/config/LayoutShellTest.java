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
import com.iitm.beacon.testsupport.AssetUrls;
import com.iitm.beacon.testsupport.LoginCodeSteps;
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
 * no route serves that path ({@code SecurityConfig}'s {@code denyAll()} tail
 * answers it with the HTML 404 page), so every page left a failed request in
 * the console. An inline empty icon stops the request from being made at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class LayoutShellTest {

    private static final String INLINE_EMPTY_ICON = "<link rel=\"icon\" href=\"data:,\"";

    private static final String NAV_TOGGLE_SCRIPT = "/js/nav-toggle.js";

    private static final String FORM_SUBMIT_SCRIPT = "/js/form-submit.js";

    private static final String BEACON_CSS = "/css/beacon.css";

    private static final String NOSCRIPT_CSS = "/css/noscript.css";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @ParameterizedTest
    @ValueSource(
            strings = {"/", "/gallery", "/submissions/login", "/admin/login", "/admin/login/code"})
    void anonymousPages_declareAnInlineIcon(String path) throws Exception {
        mockMvc.perform(LoginCodeSteps.page(mockMvc, path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(INLINE_EMPTY_ICON)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/", "/gallery", "/submissions/login", "/admin/login", "/admin/login/code"})
    void pagesWithoutInteractiveWidgets_doNotLoadAlpine(String path) throws Exception {
        mockMvc.perform(LoginCodeSteps.page(mockMvc, path))
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
                "/",
                "/gallery",
                "/gallery/999999",
                "/submissions/login",
                "/admin/login",
                "/admin/login/code"
            })
    void pagesThatOpenNoPhotos_doNotLoadThePhotoViewer(String path) throws Exception {
        mockMvc.perform(LoginCodeSteps.page(mockMvc, path))
                .andExpect(content().string(not(containsString("photoswipe"))))
                .andExpect(content().string(not(containsString("/js/photo-viewer"))));
    }

    @Test
    void visitorSubmissionForm_doesNotLoadThePhotoViewer() throws Exception {
        var visitor = new UsernamePasswordAuthenticationToken(
                "shell-viewer@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));

        mockMvc.perform(get("/submissions/form").with(authentication(visitor)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("photoswipe"))))
                .andExpect(content().string(not(containsString("/js/photo-viewer"))));
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
    void faviconPath_itselfStaysUnservedForALoggedInVisitor() throws Exception {
        // The fix is to stop browsers asking, not to serve the path.
        var visitor = new UsernamePasswordAuthenticationToken(
                "shell-icon-denied@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));

        mockMvc.perform(get("/favicon.ico").with(authentication(visitor))).andExpect(status().isNotFound());
    }

    // -- what the shell head loads on every page: the burger script (static/js/nav-toggle.js),
    // -- the submit buttons' busy state (static/js/form-submit.js) and, for browsers without
    // -- JavaScript, static/css/noscript.css --

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

    /** The page's one {@code <head>} element. */
    private static String head(String html) {
        List<String> heads = elements(html, "head");
        assertThat(heads).as("<head> elements").hasSize(1);
        return heads.get(0);
    }

    /** Opening {@code <tag>}s of {@code html} whose {@code attributeName}, unversioned, is {@code path}. */
    private static List<String> tagsLinking(String html, String tag, String attributeName, String path) {
        return openingTags(html, tag).stream()
                .filter(each -> attribute(each, attributeName).map(AssetUrls::plain).filter(path::equals).isPresent())
                .toList();
    }

    /**
     * Exactly one {@code <script>} for {@code path} on the page, in its
     * {@code <head>}, deferred (it needs the parsed page; the page must not
     * wait for it) and a classic script, not a module.
     */
    private static void assertLoadsOnceDeferredInTheHead(String html, String path) {
        List<String> scripts = tagsLinking(html, "script", "src", path);
        assertThat(scripts).as("<script src=\"" + path + "\">").hasSize(1);
        String script = scripts.get(0);
        assertThat(attribute(script, "defer")).as("defer").isPresent();
        assertThat(attribute(script, "async")).as("async").isEmpty();
        assertThat(attribute(script, "type")).as("type").isEmpty();
        assertThat(head(html)).contains(script);
    }

    /**
     * Exactly one link to the no-JavaScript stylesheet on the page — inside
     * the page's one {@code <noscript>}, in the {@code <head>}, so only a
     * browser without JavaScript applies it — and after the {@code
     * beacon.css} link, so its rules win over beacon.css's at the same
     * specificity (on phones: the nav shown, the burger hidden).
     */
    private static void assertLinksTheNoscriptStylesheetOnceInTheHeadAfterBeaconCss(String html) {
        String head = head(html);
        List<String> noscripts = elements(html, "noscript");
        assertThat(noscripts).as("<noscript> elements").hasSize(1);
        String noscript = noscripts.get(0);
        assertThat(head).contains(noscript);

        assertThat(tagsLinking(html, "link", "href", NOSCRIPT_CSS)).as("links to " + NOSCRIPT_CSS).hasSize(1);
        assertThat(tagsLinking(noscript, "link", "href", NOSCRIPT_CSS)).singleElement()
                .satisfies(link -> assertThat(attribute(link, "rel")).contains("stylesheet"));

        List<String> beaconCss = tagsLinking(head, "link", "href", BEACON_CSS);
        assertThat(beaconCss).as("links to " + BEACON_CSS).hasSize(1);
        assertThat(head.indexOf(noscript)).isGreaterThan(head.indexOf(beaconCss.get(0)));
    }

    /** What the shell's {@code head(title)} fragment puts on every page. */
    private static void assertTheSharedHead(String html) {
        assertLoadsOnceDeferredInTheHead(html, NAV_TOGGLE_SCRIPT);
        assertLoadsOnceDeferredInTheHead(html, FORM_SUBMIT_SCRIPT);
        assertLinksTheNoscriptStylesheetOnceInTheHeadAfterBeaconCss(html);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/",
                "/gallery",
                "/submissions/login",
                "/submissions/login/code",
                "/admin/login",
                "/admin/login/code"
            })
    void anonymousPages_haveTheSharedHead(String path) throws Exception {
        assertTheSharedHead(html(LoginCodeSteps.page(mockMvc, path)));
    }

    @Test
    void notFoundPage_hasTheSharedHead() throws Exception {
        assertTheSharedHead(html(get("/gallery/999999"), 404));
    }

    /** The error page is rendered by the security filter chain, without a web request context: same head. */
    @Test
    void errorPageOfAnUnknownPath_hasTheSharedHead() throws Exception {
        assertTheSharedHead(html(get("/no-such-page"), 404));
    }

    /** The dashboard with data (map, chips, lists): the shared head, once. */
    @Test
    void dashboardWithData_hasTheSharedHead() throws Exception {
        approvedTestimonial();

        String html = html(get("/"));

        assertThat(html).contains("<svg");
        assertTheSharedHead(html);
    }

    @Test
    void galleryDetailPage_hasTheSharedHead() throws Exception {
        Testimonial saved = approvedTestimonial();

        assertTheSharedHead(html(get("/gallery/{id}", saved.getId())));
    }

    /** The submission form used to link the no-JS stylesheet itself: now only the head does, once. */
    @ParameterizedTest
    @ValueSource(strings = {"/submissions/form", "/submissions/confirmation"})
    void visitorPages_haveTheSharedHead(String path) throws Exception {
        assertTheSharedHead(html(get(path).with(authentication(visitor()))));
    }

    @Test
    void moderationQueue_hasTheSharedHead() throws Exception {
        assertTheSharedHead(html(get("/moderation/queue").with(authentication(admin()))));
    }

    /**
     * The dashboard's disclosure script is the dashboard's own
     * ({@code analytics.AnalyticsViewControllerTest}): no shared fragment
     * brings it to any other page.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/gallery", "/submissions/login", "/submissions/login/code", "/admin/login",
        "/admin/login/code"})
    void anonymousPagesOtherThanTheDashboard_doNotLoadTheDisclosureScript(String path) throws Exception {
        assertThat(html(LoginCodeSteps.page(mockMvc, path))).doesNotContain("/js/collapsible");
    }

    @Test
    void loggedInAndErrorPages_doNotLoadTheDisclosureScript() throws Exception {
        Testimonial saved = approvedTestimonial();

        assertThat(html(get("/gallery/{id}", saved.getId()))).doesNotContain("/js/collapsible");
        assertThat(html(get("/gallery/999999"), 404)).doesNotContain("/js/collapsible");
        assertThat(html(get("/no-such-page"), 404)).doesNotContain("/js/collapsible");
        assertThat(html(get("/submissions/form").with(authentication(visitor())))).doesNotContain("/js/collapsible");
        assertThat(html(get("/moderation/queue").with(authentication(admin())))).doesNotContain("/js/collapsible");
    }

    /**
     * The burger is plain JS: loading it on every page must not bring Alpine
     * along to pages that have no Alpine widget (only the submission form
     * does).
     */
    @Test
    void pagesWithTheBurgerButNoWidgets_doNotLoadAlpine() throws Exception {
        Testimonial saved = approvedTestimonial();

        assertThat(html(get("/"))).doesNotContain("/webjars/alpinejs");
        assertThat(html(get("/gallery/{id}", saved.getId()))).doesNotContain("/webjars/alpinejs");
        assertThat(html(get("/gallery/999999"), 404)).doesNotContain("/webjars/alpinejs");
        assertThat(html(LoginCodeSteps.page(mockMvc, "/submissions/login/code"))).doesNotContain("/webjars/alpinejs");
        assertThat(html(get("/submissions/confirmation").with(authentication(visitor()))))
                .doesNotContain("/webjars/alpinejs");
        assertThat(html(get("/moderation/queue").with(authentication(admin())))).doesNotContain("/webjars/alpinejs");
    }
}
