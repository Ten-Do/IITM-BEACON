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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Every redirect the app sends keeps a relative {@code Location} — the login
 * redirect of a page that needs a login, the return to the saved page after
 * the login, the redirect of another role's session to the page's own login,
 * and a controller's post-redirect-get — so a browser behind a proxy that
 * terminates TLS stays on {@code https://} (decision 23). Real HTTP: the
 * servlet container, not the application, decides whether a {@code
 * sendRedirect} becomes absolute, which {@code MockMvc} never shows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RelativeRedirectsTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String VISITOR_EMAIL = "relative-redirects@example.com";

    @LocalServerPort
    private int port;

    @MockitoBean
    private OtpMailer otpMailer;

    private CookieManager cookies;
    private HttpClient browser;

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
    @CsvSource({
        "/submissions/form, /submissions/login",
        "/submissions/confirmation, /submissions/login",
        "/moderation/queue, /admin/login",
        "/catalog/topics, /admin/login"
    })
    void pageThatNeedsALogin_redirectsAnonymousVisitorsToItsLoginPage_relatively(String page, String loginPage)
            throws Exception {
        HttpResponse<String> response = get(page);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).isEqualTo(loginPage);
    }

    @Test
    void adminLogin_redirectsToTheCodePage_thenBackToTheSavedPage_relatively() throws Exception {
        assertThat(location(get("/moderation/queue?page=2"))).isEqualTo("/admin/login");

        HttpResponse<String> requested = postForm("/admin/login/request", "email=" + encode(ADMIN_EMAIL));
        assertThat(requested.statusCode()).isEqualTo(302);
        assertThat(location(requested)).isEqualTo("/admin/login/code");

        HttpResponse<String> verified = postForm("/admin/login/verify", "code=" + encode(mailedCode(ADMIN_EMAIL)));
        assertThat(verified.statusCode()).isEqualTo(302);
        assertThat(location(verified)).isEqualTo("/moderation/queue?page=2");
    }

    @Test
    void visitorLogin_withNothingSaved_redirectsToTheForm_relatively() throws Exception {
        get("/submissions/login");

        HttpResponse<String> requested = postForm("/submissions/login", "email=" + encode(VISITOR_EMAIL));
        assertThat(requested.statusCode()).isEqualTo(302);
        assertThat(location(requested)).isEqualTo("/submissions/login/code");

        HttpResponse<String> verified =
                postForm("/submissions/login/code", "code=" + encode(mailedCode(VISITOR_EMAIL)));
        assertThat(verified.statusCode()).isEqualTo(302);
        assertThat(location(verified)).isEqualTo("/submissions/form");
    }

    @Test
    void adminSessionOnTheVisitorsForm_isSentToTheVisitorLogin_relatively() throws Exception {
        get("/admin/login");
        postForm("/admin/login/request", "email=" + encode(ADMIN_EMAIL));
        postForm("/admin/login/verify", "code=" + encode(mailedCode(ADMIN_EMAIL)));

        HttpResponse<String> response = get("/submissions/form");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).isEqualTo("/submissions/login");
    }

    // -- helpers --

    private String mailedCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), code.capture());
        return code.getValue();
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
