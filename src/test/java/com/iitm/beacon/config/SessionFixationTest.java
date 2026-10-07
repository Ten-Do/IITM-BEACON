package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Session fixation (BL-034): every successful OTP login — admin and
 * visitor, page and JSON flow — moves the session to a new id, so a session
 * id known before the login (e.g. planted in the victim's browser) is worth
 * nothing afterwards. The session's attributes move with it: the page saved
 * for the return trip (decision 23) is still there. Real HTTP against the
 * embedded Tomcat, since only a real container invalidates the old id.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionFixationTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String VISITOR_EMAIL = "session-fixation@example.com";

    @LocalServerPort
    private int port;

    @MockitoBean
    private OtpMailer otpMailer;

    private CookieManager cookies;
    private HttpClient browser;

    /** A login flow, the page a session is planted with, and a ping that needs the role. */
    enum Flow {
        ADMIN_PAGE("/moderation/queue?page=2", "/api/moderation/session"),
        ADMIN_API("/moderation/queue?page=2", "/api/moderation/session"),
        VISITOR_PAGE("/submissions/confirmation", "/api/submissions/session"),
        VISITOR_API("/submissions/confirmation", "/api/submissions/session");

        final String protectedPage;
        final String sessionPing;

        Flow(String protectedPage, String sessionPing) {
            this.protectedPage = protectedPage;
            this.sessionPing = sessionPing;
        }
    }

    @BeforeEach
    void newBrowser() {
        cookies = new CookieManager();
        browser = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @ParameterizedTest
    @EnumSource(Flow.class)
    void successfulLogin_movesTheSessionToANewId_andThePlantedIdNoLongerAuthenticates(Flow flow) throws Exception {
        String planted = plantSession(flow);

        login(flow);

        String afterLogin = sessionId().orElseThrow();
        assertThat(afterLogin).isNotEqualTo(planted);
        assertThat(pingWithSessionId(flow, afterLogin)).isEqualTo(204);
        assertThat(pingWithSessionId(flow, planted)).isEqualTo(401);
    }

    /** Not {@code Secure} by default: dev and the tests run on plain http ({@code SecureCookiesTest}). */
    @Test
    void sessionCookie_isSentSameSiteLax_andNotSecureByDefault() throws Exception {
        HttpResponse<String> redirected = get(Flow.ADMIN_PAGE.protectedPage);

        assertThat(redirected.headers().allValues("Set-Cookie"))
                .filteredOn(cookie -> cookie.startsWith("JSESSIONID="))
                .singleElement()
                .satisfies(cookie -> assertThat(cookie)
                        .containsIgnoringCase("SameSite=Lax")
                        .doesNotContainIgnoringCase("Secure"));
    }

    @Test
    void csrfCookie_isSentSameSiteLax_onTheWholeSite_readableByScripts_andNotSecureByDefault() throws Exception {
        HttpResponse<String> page = get("/");

        assertThat(page.headers().allValues("Set-Cookie"))
                .filteredOn(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .singleElement()
                .satisfies(cookie -> {
                    assertThat(cookie).containsIgnoringCase("SameSite=Lax").contains("Path=/");
                    assertThat(cookie).doesNotContainIgnoringCase("HttpOnly").doesNotContainIgnoringCase("Secure");
                });
    }

    @Test
    void adminPageLogin_stillReturnsToThePageSavedBeforeTheIdChanged() throws Exception {
        plantSession(Flow.ADMIN_PAGE);

        HttpResponse<String> verified = login(Flow.ADMIN_PAGE);

        assertThat(verified.statusCode()).isEqualTo(302);
        assertThat(location(verified)).endsWith("/moderation/queue?page=2");
    }

    @Test
    void visitorPageLogin_stillReturnsToThePageSavedBeforeTheIdChanged() throws Exception {
        plantSession(Flow.VISITOR_PAGE);

        HttpResponse<String> verified = login(Flow.VISITOR_PAGE);

        assertThat(verified.statusCode()).isEqualTo(302);
        assertThat(location(verified)).endsWith("/submissions/confirmation");
    }

    @Test
    void wrongCode_keepsTheSessionId_andTheSessionStillHoldsTheSavedPage() throws Exception {
        String planted = plantSession(Flow.ADMIN_PAGE);
        String code = requestAdminCodeFromPage();

        HttpResponse<String> rejected =
                postForm("/admin/login/verify", "code=ZZZZZZ");

        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(rejected.headers().allValues("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("JSESSIONID="));
        assertThat(sessionId()).contains(planted);

        HttpResponse<String> verified = postForm("/admin/login/verify", "code=" + encode(code));
        assertThat(location(verified)).endsWith("/moderation/queue?page=2");
        assertThat(sessionId().orElseThrow()).isNotEqualTo(planted);
    }

    @Test
    void secondLoginInTheSameBrowser_movesTheSessionAgain() throws Exception {
        plantSession(Flow.ADMIN_PAGE);
        login(Flow.ADMIN_PAGE);
        String firstLogin = sessionId().orElseThrow();

        login(Flow.VISITOR_PAGE);

        String secondLogin = sessionId().orElseThrow();
        assertThat(secondLogin).isNotEqualTo(firstLogin);
        assertThat(pingWithSessionId(Flow.VISITOR_PAGE, firstLogin)).isEqualTo(401);
        assertThat(pingWithSessionId(Flow.VISITOR_PAGE, secondLogin)).isEqualTo(204);
    }

    // -- helpers --

    /** An unauthenticated visit of a protected page: creates the session (saving the page) the login starts with. */
    private String plantSession(Flow flow) throws Exception {
        HttpResponse<String> redirected = get(flow.protectedPage);
        assertThat(redirected.statusCode()).isEqualTo(302);
        return sessionId().orElseThrow(() -> new AssertionError("no session was created"));
    }

    private HttpResponse<String> login(Flow flow) throws Exception {
        return switch (flow) {
            case ADMIN_PAGE -> {
                String code = requestAdminCodeFromPage();
                HttpResponse<String> verified = postForm("/admin/login/verify", "code=" + encode(code));
                assertThat(verified.statusCode()).isEqualTo(302);
                yield verified;
            }
            case ADMIN_API -> {
                assertThat(postJson("/api/admin/auth/otp/request", "{\"email\":\"" + ADMIN_EMAIL + "\"}").statusCode())
                        .isEqualTo(202);
                HttpResponse<String> verified = postJson("/api/admin/auth/otp/verify",
                        "{\"email\":\"" + ADMIN_EMAIL + "\",\"code\":\"" + mailedCode(ADMIN_EMAIL) + "\"}");
                assertThat(verified.statusCode()).isEqualTo(200);
                yield verified;
            }
            case VISITOR_PAGE -> {
                assertThat(postForm("/submissions/login", "email=" + encode(VISITOR_EMAIL)).statusCode())
                        .isEqualTo(302);
                HttpResponse<String> verified =
                        postForm("/submissions/login/code", "code=" + encode(mailedCode(VISITOR_EMAIL)));
                assertThat(verified.statusCode()).isEqualTo(302);
                yield verified;
            }
            case VISITOR_API -> {
                assertThat(postJson("/api/submissions/otp/request", "{\"email\":\"" + VISITOR_EMAIL + "\"}")
                                .statusCode())
                        .isEqualTo(202);
                HttpResponse<String> verified = postJson("/api/submissions/otp/verify",
                        "{\"email\":\"" + VISITOR_EMAIL + "\",\"code\":\"" + mailedCode(VISITOR_EMAIL) + "\"}");
                assertThat(verified.statusCode()).isEqualTo(200);
                yield verified;
            }
        };
    }

    private String requestAdminCodeFromPage() throws Exception {
        assertThat(postForm("/admin/login/request", "email=" + encode(ADMIN_EMAIL)).statusCode()).isEqualTo(302);
        return mailedCode(ADMIN_EMAIL);
    }

    private String mailedCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), code.capture());
        return code.getValue();
    }

    /** The role's session ping sent with only the given session id — as someone holding just that id would. */
    private int pingWithSessionId(Flow flow, String sessionId) throws Exception {
        HttpClient noCookieJar = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest ping = HttpRequest.newBuilder(uri(flow.sessionPing))
                .header("Cookie", "JSESSIONID=" + sessionId)
                .header("Accept", "application/json")
                .GET()
                .build();
        return noCookieJar.send(ping, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private Optional<String> sessionId() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    /** Sends the CSRF token the way a REST client does: the {@code XSRF-TOKEN} cookie's value in the header. */
    private HttpResponse<String> postForm(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", csrfCookie())
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfCookie())
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    /** The token the server last set; every flow here starts with a GET, which sets it. */
    private String csrfCookie() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse("");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location").orElse("");
    }
}
