package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.common.error.ErrorPage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Through the embedded Tomcat, as a browser sends it: a path outside {@code
 * /api/**} that no route serves — e.g. a mistyped link, or the icon a
 * browser asks for on its own — answers the site's HTML 404 page, not the
 * JSON 401 the {@code denyAll()} tail used to give, nor a login redirect;
 * and no session cookie for it. The JSON API's unknown endpoints keep their
 * JSON 401. MockMvc can't show what the container itself does to such a
 * response (a HEAD body, the error dispatch), so this goes over real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnknownPageOverHttpTest {

    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    @LocalServerPort
    private int port;

    private HttpClient browser;

    @BeforeEach
    void newBrowser() {
        browser = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private HttpResponse<String> send(String method, String path, String accept) throws Exception {
        return browser.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Accept", accept)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static boolean setsTheSessionCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("JSESSIONID="));
    }

    @Test
    void browserOpeningAnUnknownPage_getsTheHtml404Page() throws Exception {
        HttpResponse<String> response = send("GET", "/no-such-page", BROWSER_ACCEPT);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(header(response, "Content-Type")).startsWith("text/html");
        assertThat(response.body())
                .contains("<h1 class=\"gallery-empty-title\">" + ErrorPage.forStatus(404).title() + "</h1>")
                .contains("Back to homepage")
                .doesNotContain("\"timestamp\"", "Authentication required", "Exception");
        assertThat(header(response, "Location")).isEmpty();
        assertThat(setsTheSessionCookie(response)).as("JSESSIONID set").isFalse();
        assertThat(header(response, "Content-Security-Policy")).isEqualTo(SecurityConfig.SITE_POLICY);
        assertThat(header(response, "Referrer-Policy")).isEqualTo("same-origin");
        assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(header(response, "Cache-Control")).contains("no-store");
    }

    /** A browser asking for its icon accepts images only: the path still chooses the HTML page. */
    @Test
    void browserAskingForAnIcon_getsTheHtml404Page() throws Exception {
        HttpResponse<String> response = send("GET", "/favicon.ico", "image/avif,image/webp,image/*,*/*;q=0.8");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(header(response, "Content-Type")).startsWith("text/html");
    }

    @Test
    void headOfAnUnknownPage_isA404WithoutABody() throws Exception {
        HttpResponse<String> response = send("HEAD", "/no-such-page", BROWSER_ACCEPT);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(header(response, "Content-Type")).startsWith("text/html");
        assertThat(response.body()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/no-such-endpoint", "/api/moderation/testimonials/pending"})
    void browserOpeningAnApiUrlWithoutASession_stillGetsTheJson401(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, BROWSER_ACCEPT);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(header(response, "Content-Type")).startsWith("application/json");
        assertThat(response.body()).contains("\"status\":401", "Authentication required");
    }
}
