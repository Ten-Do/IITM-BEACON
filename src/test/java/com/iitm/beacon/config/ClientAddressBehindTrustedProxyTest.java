package com.iitm.beacon.config;

import static com.iitm.beacon.config.ProxiedRequests.get;
import static com.iitm.beacon.config.ProxiedRequests.requestOtp;
import static com.iitm.beacon.config.ProxiedRequests.revealContactOfUnknownTestimonial;
import static com.iitm.beacon.config.ProxiedRequests.setCookie;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Behind a trusted reverse proxy ({@code beacon.web.trusted-proxies} names
 * the test client's {@code 127.0.0.1}): the client address is the one the
 * proxy appended to {@code X-Forwarded-For} — the per-IP OTP request limit
 * (1 per window here) counts each visitor separately instead of everyone as
 * the proxy, and whatever a visitor put in the header before the proxy
 * appended to it changes nothing. {@code X-Forwarded-Proto: https} makes the
 * request https for the app: both cookies {@code Secure}, and the
 * same-origin check expects an {@code https} origin. Every test spends the
 * limit of its own visitor addresses: the limiter outlives the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "beacon.web.trusted-proxies=" + ProxiedRequests.PROXY_ADDRESS,
            "visitor.otp.request-limit-per-ip=1",
            "visitor.otp.request-limit-per-email=1000"
        })
class ClientAddressBehindTrustedProxyTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private OtpMailer otpMailer;

    @Test
    void forwardedClient_isCountedByItsOwnAddress() throws Exception {
        assertThat(requestOtp(port, "203.0.113.1", "proxied-1a@example.com")).isEqualTo(202);

        assertThat(requestOtp(port, "203.0.113.1", "proxied-1b@example.com")).isEqualTo(429);
    }

    @Test
    void twoForwardedClients_doNotShareALimit_althoughBothComeThroughTheProxy() throws Exception {
        assertThat(requestOtp(port, "203.0.113.2", "proxied-2@example.com")).isEqualTo(202);

        assertThat(requestOtp(port, "203.0.113.3", "proxied-3@example.com")).isEqualTo(202);
    }

    /** The proxy appends the address it saw; anything to its left came from the client and is ignored. */
    @Test
    void addressesTheClientForgedBeforeTheProxysOwnEntry_areIgnored() throws Exception {
        assertThat(requestOtp(port, "198.51.100.7, 203.0.113.4", "proxied-4a@example.com")).isEqualTo(202);

        assertThat(requestOtp(port, "198.51.100.8, 203.0.113.4", "proxied-4b@example.com")).isEqualTo(429);
        assertThat(requestOtp(port, "203.0.113.4", "proxied-4c@example.com")).isEqualTo(429);
        assertThat(requestOtp(port, "198.51.100.7", "proxied-4d@example.com")).isEqualTo(202);
    }

    @Test
    void forwardedHttps_makesBothCookiesSecure() throws Exception {
        assertThat(setCookie(get(port, "/", "X-Forwarded-Proto", "https"), "XSRF-TOKEN"))
                .containsIgnoringCase("; Secure");
        assertThat(setCookie(get(port, "/moderation/queue", "X-Forwarded-Proto", "https"), "JSESSIONID"))
                .containsIgnoringCase("; Secure");
    }

    @Test
    void forwardedHttp_orNoProto_leavesTheCookiesAsTheyAre() throws Exception {
        assertThat(setCookie(get(port, "/", "X-Forwarded-Proto", "http"), "XSRF-TOKEN"))
                .doesNotContainIgnoringCase("Secure");
        assertThat(setCookie(get(port, "/"), "XSRF-TOKEN")).doesNotContainIgnoringCase("Secure");
    }

    /**
     * With no allowed origins configured, this site's origin is the request's
     * own — scheme from the proxy, host from {@code Host}, the scheme's
     * default port.
     */
    @Test
    void forwardedHttps_isTheSchemeTheSameOriginCheckExpects() throws Exception {
        String host = ProxiedRequests.PROXY_ADDRESS;

        assertThat(revealContactOfUnknownTestimonial(port, "https://" + host, "X-Forwarded-Proto", "https"))
                .isEqualTo(404);
        assertThat(revealContactOfUnknownTestimonial(port, "http://" + host + ":" + port, "X-Forwarded-Proto", "https"))
                .isEqualTo(403);
    }
}
