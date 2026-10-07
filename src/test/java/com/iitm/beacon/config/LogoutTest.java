package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.testsupport.Csrf;
import com.iitm.beacon.testsupport.LoginCodeSteps;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Logging out ({@code POST /logout}, the header's "Log out" button): the
 * session is invalidated, the security context cleared, the session cookie
 * expired and the CSRF token cookie cleared, so the next page gets a new one.
 * Where the browser lands depends on who logged out — an admin on the admin
 * login page, a visitor (or a browser that wasn't logged in) on the
 * homepage. Like every POST it needs the CSRF token: without one it is the
 * HTML 403 page and nobody is logged out. Any other method is the HTML 405
 * page allowing {@code POST} (decision 33) and logs nobody out either. The
 * sessions here come from real OTP logins.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class LogoutTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String VISITOR_EMAIL = "logout@example.com";
    private static final String ADMIN_PING = "/api/moderation/session";
    private static final String VISITOR_PING = "/api/submissions/session";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtpMailer otpMailer;

    private String codeMailedTo(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), code.capture());
        return code.getValue();
    }

    private MockHttpSession loggedInAdmin() throws Exception {
        MockHttpSession session = LoginCodeSteps.adminAskedForACode(mockMvc, ADMIN_EMAIL);
        mockMvc.perform(post("/admin/login/verify").with(csrfField())
                        .param("code", codeMailedTo(ADMIN_EMAIL))
                        .session(session))
                .andExpect(redirectedUrl("/moderation/queue"));
        return session;
    }

    private MockHttpSession loggedInVisitor() throws Exception {
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, VISITOR_EMAIL);
        mockMvc.perform(post("/submissions/login/code").with(csrfField())
                        .param("code", codeMailedTo(VISITOR_EMAIL))
                        .session(session))
                .andExpect(redirectedUrl("/submissions/form"));
        return session;
    }

    private int ping(String url, MockHttpSession session) throws Exception {
        return mockMvc.perform(get(url).session(session)).andReturn().getResponse().getStatus();
    }

    private MockHttpServletResponse logOut(MockHttpSession session) throws Exception {
        return mockMvc.perform(post("/logout").with(csrfField()).session(session)).andReturn().getResponse();
    }

    // -- who logged out decides where they land --

    @Test
    void admin_landsOnTheAdminLogin_loggedOut() throws Exception {
        MockHttpSession session = loggedInAdmin();
        assertThat(ping(ADMIN_PING, session)).isEqualTo(204);

        MockHttpServletResponse response = logOut(session);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/admin/login");
        assertThat(session.isInvalid()).isTrue();
        assertThat(ping(ADMIN_PING, session)).isEqualTo(401);
        mockMvc.perform(get("/moderation/queue").session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void visitor_landsOnTheHomepage_loggedOut() throws Exception {
        MockHttpSession session = loggedInVisitor();
        assertThat(ping(VISITOR_PING, session)).isEqualTo(204);

        MockHttpServletResponse response = logOut(session);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(session.isInvalid()).isTrue();
        assertThat(ping(VISITOR_PING, session)).isEqualTo(401);
        mockMvc.perform(get("/submissions/form").session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));
    }

    /** A stale "Log out" button — the session already expired — or a crafted POST: just the homepage. */
    @Test
    void browserThatIsNotLoggedIn_landsOnTheHomepage_withoutASessionBeingCreated() throws Exception {
        MvcResult result = mockMvc.perform(post("/logout").with(csrfField()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void sessionWithAPendingLoginButNoLogin_landsOnTheHomepage_andForgetsIt() throws Exception {
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, VISITOR_EMAIL);

        assertThat(logOut(session).getRedirectedUrl()).isEqualTo("/");

        mockMvc.perform(get(LoginCodeSteps.VISITOR_CODE_PAGE).session(session))
                .andExpect(redirectedUrl("/submissions/login"));
    }

    // -- cookies --

    @Test
    void logout_expiresTheSessionCookie_onTheWholeSite() throws Exception {
        MockHttpServletResponse response = logOut(loggedInAdmin());

        Cookie sessionCookie = response.getCookie("JSESSIONID");
        assertThat(sessionCookie).isNotNull();
        assertThat(sessionCookie.getMaxAge()).isZero();
        assertThat(sessionCookie.getPath()).isEqualTo("/");
        assertThat(sessionCookie.isHttpOnly()).isTrue();
    }

    @Test
    void logout_clearsTheCsrfTokenCookie_soTheNextPageGetsANewOne() throws Exception {
        String oldToken = UUID.randomUUID().toString();
        MockHttpServletResponse response = mockMvc.perform(post("/logout")
                        .cookie(new Cookie(Csrf.COOKIE, oldToken))
                        .param(Csrf.PARAMETER, Csrf.masked(oldToken))
                        .session(loggedInVisitor()))
                .andReturn()
                .getResponse();

        Cookie tokenCookie = response.getCookie(Csrf.COOKIE);
        assertThat(tokenCookie).isNotNull();
        assertThat(tokenCookie.getMaxAge()).isZero();
        assertThat(tokenCookie.getValue()).isEmpty();

        Cookie next = mockMvc.perform(get("/")).andReturn().getResponse().getCookie(Csrf.COOKIE);
        assertThat(next).isNotNull();
        assertThat(next.getValue()).isNotBlank().isNotEqualTo(oldToken);
    }

    /** A script or REST client sends the token in the header, as for any other POST. */
    @Test
    void logout_withTheTokenInTheHeader_worksToo() throws Exception {
        MockHttpSession session = loggedInVisitor();

        mockMvc.perform(post("/logout").with(csrfHeader()).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));

        assertThat(session.isInvalid()).isTrue();
    }

    // -- refused: nobody is logged out --

    @Test
    void withoutTheCsrfToken_isTheHtml403Page_andTheAdminStaysLoggedIn() throws Exception {
        MockHttpSession session = loggedInAdmin();

        assertHtmlErrorPage(mockMvc.perform(post("/logout").session(session)).andReturn().getResponse(), 403);

        assertThat(session.isInvalid()).isFalse();
        assertThat(ping(ADMIN_PING, session)).isEqualTo(204);
    }

    @Test
    void withAWrongCsrfToken_isTheHtml403Page_andTheVisitorStaysLoggedIn() throws Exception {
        MockHttpSession session = loggedInVisitor();

        MockHttpServletResponse response = mockMvc.perform(post("/logout")
                        .cookie(new Cookie(Csrf.COOKIE, UUID.randomUUID().toString()))
                        .param(Csrf.PARAMETER, Csrf.masked(UUID.randomUUID().toString()))
                        .session(session))
                .andReturn()
                .getResponse();

        assertHtmlErrorPage(response, 403);
        assertThat(response.getCookie("JSESSIONID")).isNull();
        assertThat(ping(VISITOR_PING, session)).isEqualTo(204);
    }

    static Stream<Arguments> otherMethods() {
        return Stream.of(
                Arguments.of("GET", get("/logout")),
                Arguments.of("PUT", put("/logout").with(csrfField())),
                Arguments.of("DELETE", delete("/logout").with(csrfField())));
    }

    /** A link, a prefetch or an image pointing at /logout must not log anyone out. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("otherMethods")
    void anyOtherMethod_isTheHtml405PageAllowingPost_andTheAdminStaysLoggedIn(
            String method, MockHttpServletRequestBuilder request) throws Exception {
        MockHttpSession session = loggedInAdmin();

        MockHttpServletResponse response = mockMvc.perform(request.session(session)).andReturn().getResponse();

        assertHtmlErrorPage(response, 405);
        assertThat(response.getHeader("Allow")).isEqualTo("POST");
        assertThat(response.getCookie("JSESSIONID")).isNull();
        assertThat(ping(ADMIN_PING, session)).isEqualTo(204);
    }

    @Test
    void get_withoutASession_isTheHtml405Page_too() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/logout")).andReturn().getResponse();

        assertHtmlErrorPage(response, 405);
        assertThat(response.getHeader("Allow")).isEqualTo("POST");
    }
}
