package com.iitm.beacon.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * Unit tests for {@link PostLoginRedirect}: where a verify handler sends the
 * user after a successful login — back to the page saved in the request
 * cache when it belongs to the role's own pages, otherwise to the role's
 * fallback page. Saved requests are created through a real {@link
 * HttpSessionRequestCache} configured like the production one (no {@code
 * continue} parameter), but with its default match-everything matcher, so
 * the helper's own checks can be exercised with requests the production
 * cache would never save.
 */
class PostLoginRedirectTest {

    private static final Set<String> ADMIN_PAGES = Set.of("/moderation/");
    private static final Set<String> VISITOR_PAGES = Set.of("/submissions/form", "/submissions/confirmation");
    private static final String ADMIN_FALLBACK = "/moderation/queue";
    private static final String VISITOR_FALLBACK = "/submissions/form";

    private final HttpSessionRequestCache requestCache = newRequestCache();
    private final PostLoginRedirect postLoginRedirect = new PostLoginRedirect(requestCache);

    private static HttpSessionRequestCache newRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setMatchingRequestParameterName(null);
        return cache;
    }

    private MockHttpSession sessionWithSaved(MockHttpServletRequest original) {
        MockHttpSession session = new MockHttpSession();
        original.setSession(session);
        requestCache.saveRequest(original, new MockHttpServletResponse());
        assertThat(requestCache.getRequest(original, new MockHttpServletResponse())).as("saved").isNotNull();
        return session;
    }

    private MockHttpSession sessionWithSavedGet(String uri, String query) {
        MockHttpServletRequest original = new MockHttpServletRequest("GET", uri);
        original.setQueryString(query);
        return sessionWithSaved(original);
    }

    private static MockHttpServletRequest loginRequest(MockHttpSession session) {
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/admin/login/verify");
        login.setSession(session);
        return login;
    }

    private String resolveAdmin(MockHttpSession session) {
        return postLoginRedirect.resolve(
                loginRequest(session), new MockHttpServletResponse(), ADMIN_PAGES, ADMIN_FALLBACK);
    }

    private String resolveVisitor(MockHttpSession session) {
        return postLoginRedirect.resolve(
                loginRequest(session), new MockHttpServletResponse(), VISITOR_PAGES, VISITOR_FALLBACK);
    }

    // -- nothing saved --

    @Test
    void resolve_noSessionAtAll_returnsFallbackWithoutCreatingASession() {
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/admin/login/verify");

        String target = postLoginRedirect.resolve(login, new MockHttpServletResponse(), ADMIN_PAGES, ADMIN_FALLBACK);

        assertThat(target).isEqualTo(ADMIN_FALLBACK);
        assertThat(login.getSession(false)).isNull();
    }

    @Test
    void resolve_sessionWithoutASavedRequest_returnsFallback() {
        assertThat(resolveAdmin(new MockHttpSession())).isEqualTo(ADMIN_FALLBACK);
    }

    // -- saved page of the role's own --

    @Test
    void resolve_savedPageUnderAnAllowedPrefix_returnsItsPath() {
        // Deliberately not the fallback page itself, or this would pass without a saved request.
        assertThat(resolveAdmin(sessionWithSavedGet("/moderation/history", null))).isEqualTo("/moderation/history");
    }

    @Test
    void resolve_savedPageWithAQueryString_keepsTheQueryVerbatim() {
        MockHttpSession session = sessionWithSavedGet("/moderation/queue", "page=2&q=caf%C3%A9+x%26y");

        assertThat(resolveAdmin(session)).isEqualTo("/moderation/queue?page=2&q=caf%C3%A9+x%26y");
    }

    @Test
    void resolve_savedPageWithAnEmptyQueryString_dropsTheDanglingQuestionMark() {
        assertThat(resolveAdmin(sessionWithSavedGet("/moderation/queue", ""))).isEqualTo("/moderation/queue");
    }

    @Test
    void resolve_savedPageMatchingTheSecondOfSeveralPrefixes_returnsItsPath() {
        MockHttpSession session = sessionWithSavedGet("/submissions/confirmation", null);

        assertThat(resolveVisitor(session)).isEqualTo("/submissions/confirmation");
    }

    @Test
    void resolve_savedPageExactlyEqualToAPrefixWithoutTrailingSlash_returnsItsPath() {
        MockHttpSession session = sessionWithSavedGet("/submissions/form", "draft=1");

        assertThat(resolveVisitor(session)).isEqualTo("/submissions/form?draft=1");
    }

    @Test
    void resolve_savedPageBelowAPrefixWithoutTrailingSlash_returnsItsPath() {
        assertThat(resolveVisitor(sessionWithSavedGet("/submissions/form/step-2", null)))
                .isEqualTo("/submissions/form/step-2");
    }

    // -- consumed exactly once --

    @Test
    void resolve_consumesTheSavedRequest_soASecondLoginGetsTheFallback() {
        MockHttpSession session = sessionWithSavedGet("/moderation/queue", "page=3");

        assertThat(resolveAdmin(session)).isEqualTo("/moderation/queue?page=3");
        assertThat(resolveAdmin(session)).isEqualTo(ADMIN_FALLBACK);
    }

    @Test
    void resolve_rejectedSavedRequest_isRemovedToo() {
        MockHttpSession session = sessionWithSavedGet("/submissions/form", null);

        assertThat(resolveAdmin(session)).isEqualTo(ADMIN_FALLBACK);

        assertThat(requestCache.getRequest(loginRequest(session), new MockHttpServletResponse())).isNull();
    }

    // -- saved page outside the role's pages --

    @Test
    void resolve_savedPageOfTheOtherRole_returnsFallback() {
        assertThat(resolveAdmin(sessionWithSavedGet("/submissions/confirmation", null))).isEqualTo(ADMIN_FALLBACK);
        assertThat(resolveVisitor(sessionWithSavedGet("/moderation/queue", null))).isEqualTo(VISITOR_FALLBACK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/moderation/testimonials/pending",
        "/moderation",
        "/moderationx/queue",
        "/gallery",
        "/",
    })
    void resolve_adminSavedPathNotOnASegmentBoundaryUnderTheModerationPrefix_returnsFallback(String path) {
        assertThat(resolveAdmin(sessionWithSavedGet(path, null))).isEqualTo(ADMIN_FALLBACK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/submissions/formatted",
        "/submissions/confirmations",
        "/submissions/login",
        "/api/submissions/mine",
    })
    void resolve_visitorSavedPathThatOnlySharesATextPrefix_returnsFallback(String path) {
        assertThat(resolveVisitor(sessionWithSavedGet(path, null))).isEqualTo(VISITOR_FALLBACK);
    }

    @Test
    void resolve_noAllowedPrefixesAtAll_returnsFallbackAndStillConsumesTheSavedRequest() {
        MockHttpSession session = sessionWithSavedGet("/moderation/queue", null);

        String target = postLoginRedirect.resolve(
                loginRequest(session), new MockHttpServletResponse(), Set.of(), ADMIN_FALLBACK);

        assertThat(target).isEqualTo(ADMIN_FALLBACK);
        assertThat(requestCache.getRequest(loginRequest(session), new MockHttpServletResponse())).isNull();
    }

    // -- only a page load is worth returning to --

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void resolve_savedNonGetRequestUnderAnAllowedPrefix_returnsFallback(String method) {
        MockHttpSession session = sessionWithSaved(new MockHttpServletRequest(method, "/moderation/queue/7/approve"));

        assertThat(resolveAdmin(session)).isEqualTo(ADMIN_FALLBACK);
    }

    // -- never an open redirect --

    @Test
    void resolve_savedRequestFromAForeignHostAndScheme_returnsOnlyItsSameSitePath() {
        MockHttpServletRequest original = new MockHttpServletRequest("GET", "/moderation/queue");
        original.setScheme("https");
        original.setServerName("evil.example");
        original.setServerPort(8443);
        original.setQueryString("page=1");

        assertThat(resolveAdmin(sessionWithSaved(original))).isEqualTo("/moderation/queue?page=1");
    }

    @Test
    void resolve_savedProtocolRelativePath_returnsFallback() {
        assertThat(resolveAdmin(sessionWithSavedGet("//evil.example/moderation/queue", null)))
                .isEqualTo(ADMIN_FALLBACK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/moderation/../api/moderation/testimonials/pending",
        "/moderation/%2e%2e/submissions/form",
        "/moderation/%2E%2E/submissions/form",
        "/moderation/.%2e/submissions/form",
        "/moderation/./queue",
        "/moderation/%2e/queue",
        "/moderation/..",
    })
    void resolve_savedPathWithDotSegments_returnsFallback(String path) {
        // A browser resolves these (encoded or not) before requesting, which
        // could land outside the role's pages despite the prefix check.
        assertThat(resolveAdmin(sessionWithSavedGet(path, null))).isEqualTo(ADMIN_FALLBACK);
    }

    @Test
    void resolve_savedQueryThatIsNotAValidUri_returnsFallback() {
        assertThat(resolveAdmin(sessionWithSavedGet("/moderation/queue", "page={1}"))).isEqualTo(ADMIN_FALLBACK);
    }

    @Test
    void resolve_savedPathThatIsNotAValidUri_returnsFallback() {
        assertThat(resolveAdmin(sessionWithSavedGet("/moderation/my queue", null))).isEqualTo(ADMIN_FALLBACK);
    }

    @Test
    void resolve_savedPathWithMalformedPercentEncoding_returnsFallback() {
        assertThat(resolveAdmin(sessionWithSavedGet("/moderation/%zz", null))).isEqualTo(ADMIN_FALLBACK);
    }

    // -- servlet context path --

    @Test
    void resolve_underAContextPath_returnsThePathRelativeToIt() {
        MockHttpServletRequest original = new MockHttpServletRequest("GET", "/beacon/moderation/queue");
        original.setContextPath("/beacon");
        original.setQueryString("page=2");
        MockHttpSession session = sessionWithSaved(original);
        MockHttpServletRequest login = loginRequest(session);
        login.setContextPath("/beacon");
        login.setRequestURI("/beacon/admin/login/verify");

        String target = postLoginRedirect.resolve(login, new MockHttpServletResponse(), ADMIN_PAGES, ADMIN_FALLBACK);

        assertThat(target).isEqualTo("/moderation/queue?page=2");
    }

    @Test
    void resolve_savedPathOutsideTheCurrentContextPath_returnsFallback() {
        MockHttpSession session = sessionWithSavedGet("/moderation/queue", null);
        MockHttpServletRequest login = loginRequest(session);
        login.setContextPath("/beacon");
        login.setRequestURI("/beacon/admin/login/verify");

        String target = postLoginRedirect.resolve(login, new MockHttpServletResponse(), ADMIN_PAGES, ADMIN_FALLBACK);

        assertThat(target).isEqualTo(ADMIN_FALLBACK);
    }

    // -- caller contract --

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "moderation/queue", "//evil.example/queue", "https://evil.example/queue"})
    void resolve_fallbackThatIsNotASiteRelativePath_isRejected(String fallback) {
        MockHttpServletRequest login = loginRequest(new MockHttpSession());

        assertThatThrownBy(() -> postLoginRedirect.resolve(login, new MockHttpServletResponse(), ADMIN_PAGES, fallback))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_nullAllowedPrefixes_isRejected() {
        MockHttpServletRequest login = loginRequest(new MockHttpSession());

        assertThatThrownBy(() -> postLoginRedirect.resolve(login, new MockHttpServletResponse(), null, ADMIN_FALLBACK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "moderation/", "//moderation/"})
    void resolve_allowedPrefixThatIsNotASiteRelativePath_isRejected(String prefix) {
        MockHttpServletRequest login = loginRequest(new MockHttpSession());
        List<String> prefixes = Arrays.asList("/submissions/form", prefix);

        assertThatThrownBy(() -> postLoginRedirect.resolve(login, new MockHttpServletResponse(), prefixes, "/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
