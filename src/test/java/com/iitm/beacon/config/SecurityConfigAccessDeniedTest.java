package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code SecurityConfig}'s answer to a logged-in user of the other role
 * (BL-033): an HTML page of one role, opened with the other role's session,
 * redirects to that page's own login page — where logging in replaces the
 * session's role — and a GET of it is saved for the return trip, exactly as
 * for a request without a session (decision 23). The JSON API keeps the
 * JSON 403; a path outside it that no route serves is the HTML 404 page
 * ({@code SecurityConfigUnknownPageTest}). The return trip itself is covered
 * by {@code LoginReturnFlowTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SecurityConfigAccessDeniedTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestCache requestCache;

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

    // -- a page of the other role: redirect to its own login page --

    static Stream<Arguments> otherRolesPageGets() {
        return Stream.of(
                Arguments.of("ADMIN", "/submissions/form", "/submissions/login"),
                Arguments.of("ADMIN", "/submissions/confirmation", "/submissions/login"),
                Arguments.of("VISITOR", "/moderation", "/admin/login"),
                Arguments.of("VISITOR", "/moderation/queue", "/admin/login"),
                Arguments.of("VISITOR", "/moderation/queue?page=2", "/admin/login"),
                Arguments.of("VISITOR", "/catalog", "/admin/login"),
                Arguments.of("VISITOR", "/catalog/topics", "/admin/login"),
                Arguments.of("VISITOR", "/catalog/topics/5", "/admin/login"),
                Arguments.of("VISITOR", "/catalog/achievements/3/delete", "/admin/login"));
    }

    @ParameterizedTest
    @MethodSource("otherRolesPageGets")
    void otherRolesPageGet_redirectsToThePagesOwnLoginPage_andIsSavedForTheReturn(
            String sessionRole, String page, String loginPage) throws Exception {
        MockHttpSession session = sessionOf(sessionRole);

        mockMvc.perform(get(page).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(loginPage));

        SavedRequest saved = savedRequestIn(session);
        assertThat(saved).isNotNull();
        assertThat(saved.getMethod()).isEqualTo("GET");
        assertThat(saved.getRedirectUrl()).endsWith(page);
    }

    @Test
    void otherRolesPageGet_keepsTheSessionsLogin_untilTheOtherLoginReplacesIt() throws Exception {
        MockHttpSession admin = sessionOf("ADMIN");

        mockMvc.perform(get("/submissions/form").session(admin)).andExpect(status().isFound());

        mockMvc.perform(get("/moderation/queue").session(admin)).andExpect(status().isOk());
    }

    static Stream<Arguments> otherRolesPagePosts() {
        return Stream.of(
                Arguments.of("VISITOR", post("/moderation/queue/1/approve").with(csrfField()), "/admin/login"),
                Arguments.of("VISITOR", post("/moderation/queue/1/reject").with(csrfField())
                        .param("reason", "x"), "/admin/login"),
                Arguments.of("VISITOR", post("/catalog/topics/5/active").with(csrfField())
                        .param("active", "false"), "/admin/login"),
                Arguments.of("VISITOR", post("/catalog/topic-groups/5/delete").with(csrfField()), "/admin/login"),
                Arguments.of(
                        "ADMIN",
                        multipart("/submissions/form").with(csrfField())
                                .param("firstName", "David")
                                .param("sections[0].topicSlug", "general")
                                .param("sections[0].answerText", "Text."),
                        "/submissions/login"));
    }

    @ParameterizedTest
    @MethodSource("otherRolesPagePosts")
    void otherRolesPagePost_redirectsToThePagesOwnLoginPage_butIsNotSaved(
            String sessionRole, MockHttpServletRequestBuilder request, String loginPage) throws Exception {
        MockHttpSession session = sessionOf(sessionRole);

        mockMvc.perform(request.session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(loginPage));

        assertThat(savedRequestIn(session)).isNull();
    }

    @Test
    void otherRolesPagePost_doesNotOverwriteAPageSavedEarlier() throws Exception {
        MockHttpSession visitor = sessionOf("VISITOR");
        mockMvc.perform(get("/moderation/queue").queryParam("page", "3").session(visitor));

        mockMvc.perform(post("/moderation/queue/1/approve").with(csrfField()).session(visitor));

        assertThat(savedRequestIn(visitor).getRedirectUrl()).endsWith("/moderation/queue?page=3");
    }

    // -- the JSON API keeps the JSON 403 --

    static Stream<Arguments> otherRolesApiRequests() {
        return Stream.of(
                Arguments.of("VISITOR", get("/api/moderation/session"), "/api/moderation/session"),
                Arguments.of(
                        "VISITOR",
                        get("/api/moderation/testimonials/pending"),
                        "/api/moderation/testimonials/pending"),
                Arguments.of("VISITOR", get("/api/catalog/topics"), "/api/catalog/topics"),
                Arguments.of(
                        "VISITOR",
                        post("/api/catalog/topics").with(csrfHeader())
                                .contentType(MediaType.APPLICATION_JSON).content("{}"),
                        "/api/catalog/topics"),
                Arguments.of("ADMIN", get("/api/submissions/session"), "/api/submissions/session"),
                Arguments.of("ADMIN", get("/api/submissions/mine"), "/api/submissions/mine"),
                Arguments.of(
                        "ADMIN",
                        put("/api/submissions/mine").with(csrfHeader())
                                .contentType(MediaType.APPLICATION_JSON).content("{}"),
                        "/api/submissions/mine"));
    }

    @ParameterizedTest
    @MethodSource("otherRolesApiRequests")
    void otherRolesApiRequest_keepsTheJson403_andIsNotSaved(
            String sessionRole, MockHttpServletRequestBuilder request, String path) throws Exception {
        MockHttpSession session = sessionOf(sessionRole);

        mockMvc.perform(request.session(session))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(jsonPath("$.path").value(path));

        assertThat(savedRequestIn(session)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/moderation/session", "/api/catalog/topics"})
    void otherRolesApiRequestFromABrowserAskingForHtml_stillGetsTheJson403(String path) throws Exception {
        mockMvc.perform(get(path).accept(MediaType.TEXT_HTML).session(sessionOf("VISITOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
