package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code SecurityConfig}'s handling of an unauthenticated request (no
 * session, or an expired one): an HTML page is redirected to its role's
 * login page, and a GET of such a page is saved in the {@link RequestCache}
 * so the login can return to it; everything else — the JSON API, the
 * {@code denyAll()} tail — keeps the JSON 401 and is never saved. The
 * return itself is covered by {@code LoginReturnFlowTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SecurityConfigLoginRedirectTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestCache requestCache;

    private SavedRequest savedRequestIn(MockHttpSession session) {
        MockHttpServletRequest probe = new MockHttpServletRequest();
        probe.setSession(session);
        return requestCache.getRequest(probe, new MockHttpServletResponse());
    }

    private static MockHttpServletRequestBuilder anonymousFormPost() {
        return multipart("/submissions/form")
                .param("firstName", "David")
                .param("sections[0].topicSlug", "general")
                .param("sections[0].answerText", "Text.")
                .param("dataProcessingConsent", "true");
    }

    // -- pages redirect to their login page --

    static Stream<Arguments> pagesAndTheirLoginPage() {
        return Stream.of(
                Arguments.of(get("/moderation/queue"), "/admin/login"),
                Arguments.of(get("/moderation/queue").param("page", "2"), "/admin/login"),
                Arguments.of(get("/moderation"), "/admin/login"),
                Arguments.of(post("/moderation/queue/1/approve"), "/admin/login"),
                Arguments.of(post("/moderation/queue/1/reject").param("reason", "x"), "/admin/login"),
                Arguments.of(get("/submissions/form"), "/submissions/login"),
                Arguments.of(anonymousFormPost(), "/submissions/login"),
                Arguments.of(get("/submissions/confirmation"), "/submissions/login"));
    }

    @ParameterizedTest
    @MethodSource("pagesAndTheirLoginPage")
    void anonymousPageRequest_redirectsToTheRolesLoginPageWithARelativeUrl(
            MockHttpServletRequestBuilder request, String loginPage) throws Exception {
        mockMvc.perform(request).andExpect(status().isFound()).andExpect(redirectedUrl(loginPage));
    }

    @Test
    void pageRequestWithAnExpiredAdminSession_redirectsToTheLoginPage() throws Exception {
        // A browser whose session timed out keeps sending the old cookie; the
        // server no longer has that session, so it is exactly like no session.
        MockHttpSession expired = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        expired.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        expired.invalidate();

        mockMvc.perform(get("/moderation/queue").session(expired))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    // -- everything else keeps the JSON 401 --

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/moderation/testimonials/pending",
        "/api/moderation/session",
        "/api/submissions/mine",
        "/api/submissions/session",
        "/favicon.ico",
        "/submissions/formatted",
        "/submissions/confirmation/extra",
        "/moderationx",
    })
    void anonymousNonPageRequest_keepsTheJson401(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value(path));
    }

    @Test
    void anonymousApiRequestFromABrowserAskingForHtml_stillGetsTheJson401() throws Exception {
        mockMvc.perform(get("/api/moderation/session").accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // -- what gets saved for the return trip --

    @Test
    void anonymousPageGet_isSavedWithItsQueryAndWithoutAContinueParameter() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/moderation/queue").queryParam("page", "2").session(session))
                .andExpect(status().isFound());

        SavedRequest saved = savedRequestIn(session);
        assertThat(saved).isNotNull();
        assertThat(saved.getMethod()).isEqualTo("GET");
        assertThat(saved.getRedirectUrl()).endsWith("/moderation/queue?page=2");
    }

    @Test
    void anonymousVisitorPageGet_isSaved() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/submissions/confirmation").session(session)).andExpect(status().isFound());

        assertThat(savedRequestIn(session)).isNotNull();
        assertThat(savedRequestIn(session).getRedirectUrl()).endsWith("/submissions/confirmation");
    }

    static Stream<MockHttpServletRequestBuilder> anonymousRequestsNotWorthReturningTo() {
        return Stream.of(
                anonymousFormPost(),
                post("/moderation/queue/1/approve"),
                get("/api/moderation/testimonials/pending"),
                get("/api/moderation/session"),
                get("/api/submissions/session"),
                get("/api/submissions/mine"),
                get("/favicon.ico"),
                get("/apple-touch-icon.png"));
    }

    @ParameterizedTest
    @MethodSource("anonymousRequestsNotWorthReturningTo")
    void anonymousNonGetOrNonPageRequest_isNotSaved(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(request.session(session));

        assertThat(savedRequestIn(session)).isNull();
    }

    @ParameterizedTest
    @MethodSource("anonymousRequestsNotWorthReturningTo")
    void laterRequestNotWorthReturningTo_doesNotOverwriteTheSavedPage(MockHttpServletRequestBuilder request)
            throws Exception {
        // e.g. a browser fetching an icon, or a stale tab posting, between
        // the redirect to the login page and the login itself.
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/moderation/queue").queryParam("page", "3").session(session))
                .andExpect(status().isFound());

        mockMvc.perform(request.session(session));

        assertThat(savedRequestIn(session).getRedirectUrl()).endsWith("/moderation/queue?page=3");
    }

    @Test
    void laterAnonymousPageGet_replacesTheSavedPage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/moderation/queue").queryParam("page", "3").session(session));

        mockMvc.perform(get("/submissions/confirmation").session(session));

        assertThat(savedRequestIn(session).getRedirectUrl()).endsWith("/submissions/confirmation");
    }

    @Test
    void anonymousApiRequestWithoutASession_createsNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/moderation/session")).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }
}
