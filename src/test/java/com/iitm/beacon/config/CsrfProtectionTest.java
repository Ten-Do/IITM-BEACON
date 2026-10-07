package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.Csrf.COOKIE;
import static com.iitm.beacon.testsupport.Csrf.HEADER;
import static com.iitm.beacon.testsupport.Csrf.PARAMETER;
import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static com.iitm.beacon.testsupport.Csrf.hiddenFieldValues;
import static com.iitm.beacon.testsupport.Csrf.masked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CSRF protection, cookie-to-header pattern (BL-004). Every response sets
 * the {@code XSRF-TOKEN} cookie (path {@code /}, readable by scripts,
 * {@code SameSite=Lax}) when the request doesn't carry one yet. A
 * state-changing request must send the token back: a REST client as the
 * cookie's value in the {@code X-XSRF-TOKEN} header, a server-rendered form
 * as the masked (BREACH-safe) token in its hidden {@code _csrf} field.
 * Without a valid token it gets a 403 — the JSON one for the API, the HTML
 * error page for a page — never a login redirect, not even on a
 * login-protected page. A successful login renews the token.
 * The per-slice checks live in each slice's own tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CsrfProtectionTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String OTP_REQUEST = "/api/admin/auth/otp/request";
    private static final String OTP_REQUEST_BODY = "{\"email\":\"" + ADMIN_EMAIL + "\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtpMailer otpMailer;

    private static MockHttpServletRequestBuilder otpRequest() {
        return post(OTP_REQUEST).contentType(MediaType.APPLICATION_JSON).content(OTP_REQUEST_BODY);
    }

    private static MockHttpSession sessionOf(String role) {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                role.toLowerCase() + "@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private static List<String> tokenCookieHeaders(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(COOKIE + "="))
                .toList();
    }

    /** The value the last {@code XSRF-TOKEN} Set-Cookie header leaves in the browser. */
    private static String tokenCookieValue(MockHttpServletResponse response) {
        List<String> headers = tokenCookieHeaders(response);
        assertThat(headers).as("XSRF-TOKEN Set-Cookie headers").isNotEmpty();
        String last = headers.get(headers.size() - 1);
        return last.substring(COOKIE.length() + 1, last.indexOf(';'));
    }

    // -- the cookie --

    /** The cookie itself: MockMvc's Set-Cookie text leaves out SameSite (SessionFixationTest sees it over HTTP). */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/gallery", "/admin/login", "/api/analytics/summary", "/api/gallery/testimonials"})
    void getWithoutTheCookie_setsIt_readableByScripts_onTheWholeSite_sameSiteLax(String path) throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();

        assertThat(tokenCookieHeaders(response)).hasSize(1);
        Cookie cookie = response.getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    void getWithTheCookie_keepsIt() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/").cookie(new Cookie(COOKIE, "existing-token")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(tokenCookieHeaders(response)).isEmpty();
    }

    @Test
    void anUnauthenticatedJsonRequestThatIsRefused_stillGetsTheCookie() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/moderation/session"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse();

        assertThat(tokenCookieHeaders(response)).hasSize(1);
    }

    // -- REST clients: cookie-to-header --

    @Test
    void jsonPost_withTheCookieValueInTheHeader_isAccepted() throws Exception {
        mockMvc.perform(otpRequest().with(csrfHeader())).andExpect(status().isAccepted());

        verify(otpMailer).sendOtp(eq(ADMIN_EMAIL), anyString());
    }

    @Test
    void jsonPost_withTheCookieTakenFromAGetResponse_isAccepted() throws Exception {
        String token = tokenCookieValue(mockMvc.perform(get("/api/analytics/summary")).andReturn().getResponse());

        mockMvc.perform(otpRequest().cookie(new Cookie(COOKIE, token)).header(HEADER, token))
                .andExpect(status().isAccepted());
    }

    static Stream<Arguments> jsonPostsWithoutAValidToken() {
        String token = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("no token at all", otpRequest()),
                Arguments.of("cookie, no header", otpRequest().cookie(new Cookie(COOKIE, token))),
                Arguments.of("header, no cookie", otpRequest().header(HEADER, token)),
                Arguments.of(
                        "header differs from the cookie",
                        otpRequest().cookie(new Cookie(COOKIE, token)).header(HEADER, UUID.randomUUID().toString())),
                Arguments.of(
                        "header carries the masked form token",
                        otpRequest().cookie(new Cookie(COOKIE, token)).header(HEADER, masked(token))),
                Arguments.of(
                        "blank header, no field",
                        otpRequest().cookie(new Cookie(COOKIE, token)).header(HEADER, " ")),
                Arguments.of(
                        "empty cookie and header",
                        otpRequest().cookie(new Cookie(COOKIE, "")).header(HEADER, "")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jsonPostsWithoutAValidToken")
    void jsonPost_withoutAValidToken_isAJson403_andDoesNothing(
            String description, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(jsonPath("$.path").value(OTP_REQUEST));

        verify(otpMailer, never()).sendOtp(anyString(), anyString());
    }

    // -- server-rendered forms: the masked token in a hidden field --

    @Test
    void formPost_withTheMaskedTokenInTheField_isAccepted() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", ADMIN_EMAIL).with(csrfField()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login/code"));
    }

    @Test
    void formPost_withTheRawCookieValueInTheField_isRefused() throws Exception {
        String token = UUID.randomUUID().toString();

        mockMvc.perform(post("/admin/login/request")
                        .param("email", ADMIN_EMAIL)
                        .cookie(new Cookie(COOKIE, token))
                        .param(PARAMETER, token))
                .andExpect(status().isForbidden());
    }

    @Test
    void formPost_withABlankHeader_isRefused_evenWithAValidField() throws Exception {
        // A header that is present wins, blank or not: a client sends it only with a value.
        String token = UUID.randomUUID().toString();

        mockMvc.perform(post("/admin/login/request")
                        .param("email", ADMIN_EMAIL)
                        .cookie(new Cookie(COOKIE, token))
                        .header(HEADER, "")
                        .param(PARAMETER, masked(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void renderedForm_carriesADifferentlyMaskedTokenEachTime_andEachIsAccepted() throws Exception {
        Cookie cookie = new Cookie(COOKIE, UUID.randomUUID().toString());
        String first = hiddenFieldValues(mockMvc.perform(get("/admin/login").cookie(cookie))
                        .andReturn().getResponse().getContentAsString())
                .get(0);
        String second = hiddenFieldValues(mockMvc.perform(get("/admin/login").cookie(cookie))
                        .andReturn().getResponse().getContentAsString())
                .get(0);

        assertThat(first).isNotEqualTo(second).isNotEqualTo(cookie.getValue());
        for (String field : List.of(first, second)) {
            mockMvc.perform(post("/admin/login/request")
                            .param("email", ADMIN_EMAIL)
                            .cookie(cookie)
                            .param(PARAMETER, field))
                    .andExpect(status().isFound());
        }
    }

    // -- a CSRF failure is a 403, never a login redirect --

    static Stream<Arguments> pagePostsWithoutAToken() {
        return Stream.of(
                Arguments.of("anonymous", null, post("/moderation/queue/1/approve")),
                Arguments.of("anonymous", null, post("/catalog/topics/5/delete")),
                Arguments.of("anonymous", null, multipart("/submissions/form").param("firstName", "David")),
                Arguments.of("the other role", "VISITOR", post("/moderation/queue/1/approve")),
                Arguments.of("the other role", "ADMIN", multipart("/submissions/form").param("firstName", "David")),
                Arguments.of("the page's own role", "ADMIN", post("/moderation/queue/1/approve")),
                Arguments.of("the page's own role", "VISITOR", multipart("/submissions/form").param("firstName", "D")));
    }

    /** A page answers the HTML error page, whatever the request accepts; the API keeps its JSON 403 (above). */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pagePostsWithoutAToken")
    void pagePostWithoutAToken_isTheHtml403Page_notALoginRedirect(
            String who, String sessionRole, MockHttpServletRequestBuilder request) throws Exception {
        if (sessionRole != null) {
            request.session(sessionOf(sessionRole));
        }

        mockMvc.perform(request.accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<h1 class=\"gallery-empty-title\">Access denied</h1>")))
                .andExpect(content().string(not(containsString("\"status\""))));
    }

    @Test
    void contactRevealFetchWithoutAToken_isTheHtml403Page_too() throws Exception {
        // A page route, though a script sends it: the script shows its own inline error for any failure.
        mockMvc.perform(post("/gallery/1/contact")
                        .header("Origin", "http://localhost")
                        .header("X-Requested-With", "fetch"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Access denied")));
    }

    @Test
    void apiPostWithoutAToken_staysTheJson403_evenWhenTheClientAsksForHtml() throws Exception {
        mockMvc.perform(otpRequest().accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void pagePostWithAToken_butNoSession_stillRedirectsToTheLoginPage() throws Exception {
        // The token lives in a cookie, not in the session: an expired session doesn't invalidate it.
        mockMvc.perform(post("/moderation/queue/1/approve").with(csrfField()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    // -- a login renews the token --

    @Test
    void successfulPageLogin_replacesTheTokenCookie() throws Exception {
        Cookie old = new Cookie(COOKIE, UUID.randomUUID().toString());
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/admin/login/request")
                .param("email", ADMIN_EMAIL)
                .cookie(old)
                .param(PARAMETER, masked(old.getValue()))
                .session(session));
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(ADMIN_EMAIL), code.capture());

        MockHttpServletResponse response = mockMvc.perform(post("/admin/login/verify")
                        .param("code", code.getValue())
                        .cookie(old)
                        .param(PARAMETER, masked(old.getValue()))
                        .session(session))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse();

        String renewed = tokenCookieValue(response);
        assertThat(renewed).isNotBlank().isNotEqualTo(old.getValue());
    }

    @Test
    void successfulJsonLogin_replacesTheTokenCookie() throws Exception {
        String old = UUID.randomUUID().toString();
        mockMvc.perform(otpRequest().cookie(new Cookie(COOKIE, old)).header(HEADER, old));
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(ADMIN_EMAIL), code.capture());

        MockHttpServletResponse response = mockMvc.perform(post("/api/admin/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"code\":\"" + code.getValue() + "\"}")
                        .cookie(new Cookie(COOKIE, old))
                        .header(HEADER, old))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(tokenCookieValue(response)).isNotBlank().isNotEqualTo(old);
    }

    @Test
    void failedLogin_keepsTheTokenCookie() throws Exception {
        String old = UUID.randomUUID().toString();
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/admin/login/request")
                        .param("email", ADMIN_EMAIL)
                        .cookie(new Cookie(COOKIE, old))
                        .param(PARAMETER, masked(old))
                        .session(session))
                .andExpect(status().isFound());

        MockHttpServletResponse response = mockMvc.perform(post("/admin/login/verify")
                        .param("code", "ZZZZZZ")
                        .cookie(new Cookie(COOKIE, old))
                        .param(PARAMETER, masked(old))
                        .session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(tokenCookieHeaders(response)).isEmpty();
    }
}
