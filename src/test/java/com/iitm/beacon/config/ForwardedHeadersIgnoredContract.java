package com.iitm.beacon.config;

import static com.iitm.beacon.config.ProxiedRequests.get;
import static com.iitm.beacon.config.ProxiedRequests.requestOtp;
import static com.iitm.beacon.config.ProxiedRequests.revealContactOfUnknownTestimonial;
import static com.iitm.beacon.config.ProxiedRequests.setCookie;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * What a client that isn't a trusted proxy gets for {@code X-Forwarded-*}
 * headers it sets itself: nothing (decision 27). The per-IP OTP request
 * limit (1 per window here, the per-email limit out of the way) keeps
 * counting the connection's own address however the header names another
 * one, a forged {@code https} doesn't make the cookies {@code Secure}, and
 * the same-origin check keeps using the request's own scheme. Real HTTP:
 * only the servlet container's valve could change the address. Each
 * subclass is its own context, so the limit starts fresh there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ForwardedHeadersIgnoredContract {

    @LocalServerPort
    private int port;

    @MockitoBean
    private OtpMailer otpMailer;

    /** The only test of the class that spends the per-IP budget of the connection's address. */
    @Test
    void forgedXForwardedFor_isIgnored_theConnectionsAddressIsCounted() throws Exception {
        assertThat(requestOtp(port, "203.0.113.1", "forged-for-1@example.com")).isEqualTo(202);

        assertThat(requestOtp(port, "203.0.113.2", "forged-for-2@example.com")).isEqualTo(429);
        assertThat(requestOtp(port, null, "forged-for-3@example.com")).isEqualTo(429);
    }

    @Test
    void forgedXForwardedProto_doesNotMakeTheCookiesSecure() throws Exception {
        assertThat(setCookie(get(port, "/", "X-Forwarded-Proto", "https"), "XSRF-TOKEN"))
                .doesNotContainIgnoringCase("Secure");
        assertThat(setCookie(get(port, "/moderation/queue", "X-Forwarded-Proto", "https"), "JSESSIONID"))
                .doesNotContainIgnoringCase("Secure");
    }

    @Test
    void forgedXForwardedProto_doesNotChangeTheOriginTheSameOriginCheckExpects() throws Exception {
        String ownOrigin = "http://" + ProxiedRequests.PROXY_ADDRESS + ":" + port;

        assertThat(revealContactOfUnknownTestimonial(port, ownOrigin, "X-Forwarded-Proto", "https")).isEqualTo(404);
        assertThat(revealContactOfUnknownTestimonial(port, "https://" + ProxiedRequests.PROXY_ADDRESS,
                        "X-Forwarded-Proto", "https"))
                .isEqualTo(403);
    }
}
