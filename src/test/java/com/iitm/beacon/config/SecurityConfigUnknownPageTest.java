package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.ClientErrors.assertJsonError;
import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A request to a path outside {@code /api/**} that no route serves — one
 * that falls through to {@code SecurityConfig}'s {@code denyAll()} tail —
 * answers the site's HTML 404 page ("Page not found"), whoever sends it: no
 * session, an expired one, a visitor's or an admin's. Never a JSON body,
 * never a login redirect, nothing saved for the return trip after a login,
 * and no session created for it; the page carries the usual security
 * headers. The JSON API keeps its JSON 401 (no session) and 403 (a session
 * of the wrong role); a request without its CSRF token is still refused
 * with a 403 first. The login redirects of the login-protected pages are
 * covered by {@code SecurityConfigLoginRedirectTest} and {@code
 * SecurityConfigAccessDeniedTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SecurityConfigUnknownPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestCache requestCache;

    /** Paths outside the API that no route serves — some only a character or a segment away from one. */
    static Stream<String> unknownPages() {
        return Stream.of(
                "/no-such-page",
                "/no-such-page?page=2",
                "/gallery/1/extra",
                "/gallery/1/contact",
                "/favicon.ico",
                "/apple-touch-icon.png",
                "/moderationx",
                "/catalogx",
                "/catalog-topics",
                "/submissions/formatted",
                "/submissions/form/x",
                "/submissions/confirmation/extra",
                "/submissions",
                "/admin",
                // Not the API: neither /api itself nor under /api/ (common.web.ApiRequests).
                "/apix",
                "/apiary/topics",
                "/API/moderation/session");
    }

    static Stream<Arguments> rolesAndUnknownPages() {
        return Stream.of("VISITOR", "ADMIN")
                .flatMap(role -> unknownPages().map(path -> Arguments.of(role, path)));
    }

    /** A live session a user of {@code role} logged in to. */
    private static MockHttpSession sessionOf(String role) {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                role.toLowerCase() + "@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private SavedRequest savedRequestIn(MockHttpSession session) {
        MockHttpServletRequest probe = new MockHttpServletRequest();
        probe.setSession(session);
        return requestCache.getRequest(probe, new MockHttpServletResponse());
    }

    /** Answered by the security filter chain: no route — no controller, no resource handler — ever ran. */
    private static void assertNeverReachedSpringMvc(MvcResult result) {
        assertThat(result.getHandler()).as("handler").isNull();
    }

    private static void assertNotRedirected(MockHttpServletResponse response) {
        assertThat(response.getHeader("Location")).as("Location").isNull();
        assertThat(response.getRedirectedUrl()).as("redirect").isNull();
    }

    private static void assertSecurityHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeaders("Content-Security-Policy")).containsExactly(SecurityConfig.SITE_POLICY);
        assertThat(response.getHeaders("Referrer-Policy")).containsExactly("same-origin");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    // -- no session: the HTML 404 page --

    @ParameterizedTest
    @MethodSource("unknownPages")
    void anonymousGet_isTheHtml404Page_andCreatesNoSession(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertHtmlErrorPage(result.getResponse(), 404);
        assertNotRedirected(result.getResponse());
        assertThat(result.getRequest().getSession(false)).as("session").isNull();
        assertNeverReachedSpringMvc(result);
    }

    @ParameterizedTest
    @MethodSource("unknownPages")
    void anonymousHead_isTheHtml404Page_andCreatesNoSession(String path) throws Exception {
        MvcResult result = mockMvc.perform(head(path)).andReturn();

        assertHtmlErrorPage(result.getResponse(), 404);
        assertNotRedirected(result.getResponse());
        assertThat(result.getRequest().getSession(false)).as("session").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/no-such-page", "/favicon.ico", "/moderationx", "/submissions/formatted"})
    void anonymousGet_inABrowserSessionWithoutALogin_savesNothing(String path) throws Exception {
        MockHttpSession session = new MockHttpSession();

        assertHtmlErrorPage(mockMvc.perform(get(path).session(session)).andReturn().getResponse(), 404);

        assertThat(savedRequestIn(session)).isNull();
    }

    @Test
    void getWithAnExpiredSession_isTheHtml404Page() throws Exception {
        // The browser still sends the old cookie; the server no longer has that session.
        MockHttpSession expired = sessionOf("ADMIN");
        expired.invalidate();

        MockHttpServletResponse response = mockMvc.perform(get("/no-such-page").session(expired))
                .andReturn()
                .getResponse();

        assertHtmlErrorPage(response, 404);
        assertNotRedirected(response);
    }

    /** The representation is chosen by the path, not by what the client accepts (decision 33). */
    @Test
    void anonymousGetAcceptingOnlyJson_isStillTheHtml404Page() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/no-such-page").accept(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse();

        assertHtmlErrorPage(response, 404);
    }

    /** Not a CSRF failure — the token is there — so the method doesn't matter: nothing is served at the path. */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void anonymousStateChangingRequestWithTheToken_isTheHtml404Page(String method) throws Exception {
        MvcResult result = mockMvc.perform(request(HttpMethod.valueOf(method), "/no-such-page").with(csrfField()))
                .andReturn();

        assertHtmlErrorPage(result.getResponse(), 404);
        assertNotRedirected(result.getResponse());
        assertThat(result.getRequest().getSession(false)).as("session").isNull();
    }

    @Test
    void anonymousOptions_isTheHtml404Page() throws Exception {
        assertHtmlErrorPage(mockMvc.perform(request(HttpMethod.OPTIONS, "/no-such-page")).andReturn().getResponse(),
                404);
    }

    // -- a visitor's or an admin's session: the same HTML 404 page --

    @ParameterizedTest
    @MethodSource("rolesAndUnknownPages")
    void getWithASession_isTheHtml404Page_savesNothing_andKeepsTheLogin(String role, String path)
            throws Exception {
        MockHttpSession session = sessionOf(role);

        MvcResult result = mockMvc.perform(get(path).session(session)).andReturn();

        assertHtmlErrorPage(result.getResponse(), 404);
        assertNotRedirected(result.getResponse());
        assertNeverReachedSpringMvc(result);
        assertThat(savedRequestIn(session)).isNull();
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .as("the login")
                .isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"VISITOR", "ADMIN"})
    void headWithASession_isTheHtml404Page(String role) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(head("/no-such-page").session(sessionOf(role)))
                .andReturn()
                .getResponse();

        assertHtmlErrorPage(response, 404);
        assertNotRedirected(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VISITOR", "ADMIN"})
    void postWithASessionAndTheToken_isTheHtml404Page(String role) throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(post("/no-such-page").with(csrfField()).session(sessionOf(role)))
                        .andReturn()
                        .getResponse();

        assertHtmlErrorPage(response, 404);
        assertNotRedirected(response);
    }

    // -- the page saved before a login survives a stray unknown path --

    @Test
    void anonymousUnknownPage_afterALoginRedirect_doesNotReplaceTheSavedPage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/moderation/queue").queryParam("page", "3").session(session));

        mockMvc.perform(get("/no-such-page").session(session));

        assertThat(savedRequestIn(session).getRedirectUrl()).endsWith("/moderation/queue?page=3");
    }

    @Test
    void otherRolesUnknownPage_afterALoginRedirect_doesNotReplaceTheSavedPage() throws Exception {
        MockHttpSession visitor = sessionOf("VISITOR");
        mockMvc.perform(get("/catalog/topics").session(visitor));

        mockMvc.perform(get("/catalogx").session(visitor));

        assertThat(savedRequestIn(visitor).getRedirectUrl()).endsWith("/catalog/topics");
    }

    // -- the response headers are there --

    @Test
    void anonymous404Page_carriesTheSecurityHeaders() throws Exception {
        assertSecurityHeaders(mockMvc.perform(get("/no-such-page")).andReturn().getResponse());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VISITOR", "ADMIN"})
    void the404PageOfASession_carriesTheSecurityHeaders(String role) throws Exception {
        assertSecurityHeaders(
                mockMvc.perform(get("/no-such-page").session(sessionOf(role))).andReturn().getResponse());
    }

    // -- a CSRF failure is still checked first: a 403 --

    @Test
    void anonymousPostWithoutTheToken_isStillTheHtml403Page() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/no-such-page")).andReturn().getResponse();

        assertHtmlErrorPage(response, 403);
        assertNotRedirected(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VISITOR", "ADMIN"})
    void postWithASessionButWithoutTheToken_isStillTheHtml403Page(String role) throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(post("/no-such-page").session(sessionOf(role))).andReturn().getResponse();

        assertHtmlErrorPage(response, 403);
    }

    @Test
    void apiPostWithoutTheToken_isStillTheJson403() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(post("/api/no-such-endpoint").accept(MediaType.TEXT_HTML)).andReturn().getResponse();

        assertJsonError(response, 403, "Access denied", "/api/no-such-endpoint");
    }

    // -- the JSON API keeps its JSON 401 and 403 --

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/", "/api/no-such-endpoint", "/api/moderation/no-such", "/api/gallery-x"})
    void anonymousApiRequest_isStillTheJson401_evenFromABrowser(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).accept(MediaType.TEXT_HTML)).andReturn();

        assertJsonError(result.getResponse(), 401, "Authentication required", path);
        assertThat(result.getRequest().getSession(false)).as("session").isNull();
    }

    @Test
    void anonymousApiPostWithTheToken_isStillTheJson401() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(post("/api/no-such-endpoint").with(csrfHeader())).andReturn().getResponse();

        assertJsonError(response, 401, "Authentication required", "/api/no-such-endpoint");
    }

    @Test
    void anonymousApiHead_isStillA401() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(head("/api/no-such-endpoint")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_JSON))
                .isTrue();
    }

    static Stream<Arguments> wrongRoleApiRequests() {
        return Stream.of(
                Arguments.of("VISITOR", "/api/no-such-endpoint"),
                Arguments.of("ADMIN", "/api/no-such-endpoint"),
                Arguments.of("VISITOR", "/api"),
                Arguments.of("VISITOR", "/api/moderation/session"),
                Arguments.of("VISITOR", "/api/moderation/no-such"),
                Arguments.of("ADMIN", "/api/submissions/session"));
    }

    @ParameterizedTest
    @MethodSource("wrongRoleApiRequests")
    void apiRequestWithASessionThatMayNot_isStillTheJson403_evenFromABrowser(String role, String path)
            throws Exception {
        MockHttpSession session = sessionOf(role);

        MockHttpServletResponse response =
                mockMvc.perform(get(path).accept(MediaType.TEXT_HTML).session(session)).andReturn().getResponse();

        assertJsonError(response, 403, "Access denied", path);
        assertThat(savedRequestIn(session)).isNull();
    }
}
