package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Requests to the embedded server as a reverse proxy in front of the app
 * sends them — from {@code 127.0.0.1}, with {@code X-Forwarded-*} headers —
 * for the {@code ClientAddress*Test} classes, which differ only in which
 * proxies the app is told to trust ({@code beacon.web.trusted-proxies}).
 */
final class ProxiedRequests {

    /** The address every request here comes from: the URIs name it, never {@code localhost}. */
    static final String PROXY_ADDRESS = "127.0.0.1";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ProxiedRequests() {
    }

    static URI uri(int port, String path) {
        return URI.create("http://" + PROXY_ADDRESS + ":" + port + path);
    }

    /**
     * A visitor OTP request (its per-IP limit is what tells which address the
     * app counts), with {@code X-Forwarded-For} if {@code forwardedFor} isn't
     * null; answers its status (202, or 429 once the counted address is over
     * its limit).
     */
    static int requestOtp(int port, String forwardedFor, String email) throws IOException, InterruptedException {
        String token = UUID.randomUUID().toString();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(port, "/api/submissions/otp/request"))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\"}"));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** A GET with the given extra headers (name, value, name, value, ...). */
    static HttpResponse<String> get(int port, String path, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(port, path)).GET();
        if (headers.length > 0) {
            request.headers(headers);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The article's contact reveal for a testimonial that doesn't exist, with
     * a valid CSRF token, the given {@code Origin} and extra headers: 404
     * once the same-origin check lets it through, 403 if the check refuses it.
     */
    static int revealContactOfUnknownTestimonial(int port, String origin, String... headers)
            throws IOException, InterruptedException {
        String token = UUID.randomUUID().toString();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(port, "/gallery/999999999/contact"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) {
            request.headers(headers);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** The one {@code Set-Cookie} header that sets {@code name}. */
    static String setCookie(HttpResponse<?> response, String name) {
        List<String> cookies = response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith(name + "="))
                .toList();
        assertThat(cookies).as("Set-Cookie " + name).hasSize(1);
        return cookies.get(0);
    }
}
