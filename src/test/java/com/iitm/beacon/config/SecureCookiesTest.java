package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * With {@code BEACON_COOKIE_SECURE=true} — production's default ({@link
 * SecureCookieConfigTest}) — both cookies the app sets carry {@code Secure}
 * although the request itself is plain {@code http}, as the app sees it
 * behind a proxy that terminates TLS: the session cookie and the CSRF token
 * cookie, on first contact and again when a login replaces both. Real HTTP:
 * the servlet container writes {@code JSESSIONID}. Cookies are handled by
 * hand, since a client's cookie jar never sends a {@code Secure} cookie over
 * {@code http}. {@code SessionFixationTest} checks neither is {@code
 * Secure} by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "BEACON_COOKIE_SECURE=true")
class SecureCookiesTest {

    private static final String VISITOR_EMAIL = "secure-cookies@example.com";

    @LocalServerPort
    private int port;

    @MockitoBean
    private OtpMailer otpMailer;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void csrfCookie_isSecure_onAPlainHttpRequest() throws Exception {
        HttpResponse<String> page = client.send(get("/").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(setCookie(page, "XSRF-TOKEN"))
                .containsIgnoringCase("; Secure")
                .containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void sessionCookie_isSecure_onAPlainHttpRequest() throws Exception {
        HttpResponse<String> redirected =
                client.send(get("/moderation/queue").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(redirected.statusCode()).isEqualTo(302);
        assertThat(setCookie(redirected, "JSESSIONID"))
                .containsIgnoringCase("; Secure")
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax");
    }

    /** A login moves the session to a new id and renews the token: both new cookies are Secure too. */
    @Test
    void bothCookiesALoginSets_areSecure() throws Exception {
        String token = UUID.randomUUID().toString();
        HttpResponse<String> requested = client.send(
                postJson("/api/submissions/otp/request", token, "{\"email\":\"" + VISITOR_EMAIL + "\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(requested.statusCode()).isEqualTo(202);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(otpMailer).sendOtp(eq(VISITOR_EMAIL), code.capture());

        HttpResponse<String> verified = client.send(
                postJson("/api/submissions/otp/verify", token,
                        "{\"email\":\"" + VISITOR_EMAIL + "\",\"code\":\"" + code.getValue() + "\"}"),
                HttpResponse.BodyHandlers.ofString());

        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(setCookie(verified, "JSESSIONID")).containsIgnoringCase("; Secure");
        assertThat(setCookie(verified, "XSRF-TOKEN")).containsIgnoringCase("; Secure");
    }

    /** Logging out expires both cookies with the attributes they were set with — Secure included. */
    @Test
    void bothCookiesALogoutExpires_areSecure() throws Exception {
        String token = UUID.randomUUID().toString();

        HttpResponse<String> loggedOut = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/logout"))
                        .header("Cookie", "XSRF-TOKEN=" + token)
                        .header("X-XSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(loggedOut.statusCode()).isEqualTo(302);
        assertThat(expiringCookie(loggedOut, "JSESSIONID"))
                .containsIgnoringCase("; Secure")
                .containsIgnoringCase("HttpOnly");
        assertThat(expiringCookie(loggedOut, "XSRF-TOKEN")).containsIgnoringCase("; Secure");
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    }

    /** A REST client sending the CSRF token it chose as cookie and header. */
    private HttpRequest postJson(String path, String token, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    /** The one {@code Set-Cookie} header that expires {@code name}. */
    private static String expiringCookie(HttpResponse<?> response, String name) {
        List<String> cookies = response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith(name + "=;") && cookie.contains("Max-Age=0"))
                .toList();
        assertThat(cookies).as("expired Set-Cookie " + name).hasSize(1);
        return cookies.get(0);
    }

    /** The one {@code Set-Cookie} header that sets {@code name} (to a non-empty value). */
    private static String setCookie(HttpResponse<?> response, String name) {
        List<String> cookies = response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith(name + "=") && !cookie.startsWith(name + "=;"))
                .toList();
        assertThat(cookies).as("Set-Cookie " + name).hasSize(1);
        return cookies.get(0);
    }
}
