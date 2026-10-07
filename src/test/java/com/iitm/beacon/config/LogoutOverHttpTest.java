package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.iitm.beacon.testsupport.Csrf;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Logging out with the header's "Log out" button, as a browser does it, over
 * real HTTP against the embedded Tomcat (only a real container shows what an
 * old session id is worth afterwards): the form's own token is accepted, the
 * browser is sent on with a relative {@code Location}, the session cookie and
 * the token cookie are expired, the old session id no longer authenticates
 * anything, and the next page hands out a new token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LogoutOverHttpTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String VISITOR_EMAIL = "logout-over-http@example.com";

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

    @Test
    void admin_logsOutFromAnAdminPage_andLandsOnTheAdminLogin() throws Exception {
        String sessionId = loginAsAdmin();
        String oldToken = cookie(Csrf.COOKIE).orElseThrow();

        HttpResponse<String> loggedOut = logOutFrom("/moderation/queue");

        assertThat(loggedOut.statusCode()).isEqualTo(302);
        assertThat(location(loggedOut)).isEqualTo("/admin/login");
        assertCookiesExpired(loggedOut);
        assertThat(ping("/api/moderation/session", sessionId)).isEqualTo(401);
        assertTheNextPageHandsOutANewToken("/admin/login", oldToken);
    }

    @Test
    void visitor_logsOutFromAVisitorPage_andLandsOnTheHomepage() throws Exception {
        String sessionId = loginAsVisitor();
        String oldToken = cookie(Csrf.COOKIE).orElseThrow();

        HttpResponse<String> loggedOut = logOutFrom("/submissions/confirmation");

        assertThat(loggedOut.statusCode()).isEqualTo(302);
        assertThat(location(loggedOut)).isEqualTo("/");
        assertCookiesExpired(loggedOut);
        assertThat(ping("/api/submissions/session", sessionId)).isEqualTo(401);
        assertTheNextPageHandsOutANewToken("/", oldToken);
    }

    /** The logout form a public page shows a logged-in visitor works the same way. */
    @Test
    void visitor_logsOutFromAPublicPage() throws Exception {
        String sessionId = loginAsVisitor();

        HttpResponse<String> loggedOut = logOutFrom("/gallery");

        assertThat(location(loggedOut)).isEqualTo("/");
        assertThat(ping("/api/submissions/session", sessionId)).isEqualTo(401);
    }

    // -- helpers --

    private String loginAsAdmin() throws Exception {
        get("/admin/login");
        assertThat(postForm("/admin/login/request", "email=" + encode(ADMIN_EMAIL)).statusCode()).isEqualTo(302);
        HttpResponse<String> verified = postForm("/admin/login/verify", "code=" + encode(mailedCode(ADMIN_EMAIL)));
        assertThat(location(verified)).isEqualTo("/moderation/queue");
        assertThat(ping("/api/moderation/session", cookie("JSESSIONID").orElseThrow())).isEqualTo(204);
        return cookie("JSESSIONID").orElseThrow();
    }

    private String loginAsVisitor() throws Exception {
        get("/submissions/login");
        assertThat(postForm("/submissions/login", "email=" + encode(VISITOR_EMAIL)).statusCode()).isEqualTo(302);
        HttpResponse<String> verified =
                postForm("/submissions/login/code", "code=" + encode(mailedCode(VISITOR_EMAIL)));
        assertThat(location(verified)).isEqualTo("/submissions/form");
        assertThat(ping("/api/submissions/session", cookie("JSESSIONID").orElseThrow())).isEqualTo(204);
        return cookie("JSESSIONID").orElseThrow();
    }

    /** Opens {@code page} and submits its "Log out" form with the token rendered into it. */
    private HttpResponse<String> logOutFrom(String page) throws Exception {
        HttpResponse<String> opened = get(page);
        assertThat(opened.statusCode()).isEqualTo(200);
        List<String> logoutForms = elements(opened.body(), "form").stream()
                .filter(form -> attribute(openingTag(form, "form"), "action").filter("/logout"::equals).isPresent())
                .toList();
        assertThat(logoutForms).as("logout forms on " + page).hasSize(1);
        String token = Csrf.hiddenFieldValues(logoutForms.get(0)).get(0);
        return send(HttpRequest.newBuilder(uri("/logout"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(Csrf.PARAMETER + "=" + encode(token))));
    }

    private static void assertCookiesExpired(HttpResponse<String> response) {
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        assertThat(setCookies).filteredOn(cookie -> cookie.startsWith("JSESSIONID=")).singleElement()
                .satisfies(cookie -> assertThat(cookie)
                        .startsWith("JSESSIONID=;")
                        .contains("Max-Age=0")
                        .contains("Path=/")
                        .containsIgnoringCase("HttpOnly")
                        .containsIgnoringCase("SameSite=Lax"));
        assertThat(setCookies).filteredOn(cookie -> cookie.startsWith(Csrf.COOKIE + "=")).singleElement()
                .satisfies(cookie -> assertThat(cookie).startsWith(Csrf.COOKIE + "=;").contains("Max-Age=0"));
    }

    private void assertTheNextPageHandsOutANewToken(String page, String oldToken) throws Exception {
        assertThat(cookie(Csrf.COOKIE)).isEmpty();
        assertThat(cookie("JSESSIONID")).isEmpty();

        assertThat(get(page).statusCode()).isEqualTo(200);

        assertThat(cookie(Csrf.COOKIE)).hasValueSatisfying(token -> assertThat(token).isNotEqualTo(oldToken));
    }

    private String mailedCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), code.capture());
        return code.getValue();
    }

    /** The role's session ping sent with only the given session id — as someone holding just that id would. */
    private int ping(String path, String sessionId) throws Exception {
        HttpClient noCookieJar = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest ping = HttpRequest.newBuilder(uri(path))
                .header("Cookie", "JSESSIONID=" + sessionId)
                .header("Accept", "application/json")
                .GET()
                .build();
        return noCookieJar.send(ping, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private Optional<String> cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> name.equals(cookie.getName()))
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
                .header(Csrf.HEADER, cookie(Csrf.COOKIE).orElse(""))
                .POST(HttpRequest.BodyPublishers.ofString(body)));
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
