package com.iitm.beacon.config;

import static com.iitm.beacon.config.ProxiedRequests.revealContactOfUnknownTestimonial;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Behind a trusted proxy with {@code beacon.web.allowed-origins} set, the
 * list still replaces the request-derived origin (decision 27): the
 * forwarded {@code https} makes the request's own origin {@code
 * https://127.0.0.1}, which the check refuses all the same, and only the
 * listed origin passes. Real HTTP: the forwarded scheme is applied by the
 * servlet container's valve.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "beacon.web.trusted-proxies=" + ProxiedRequests.PROXY_ADDRESS,
            "beacon.web.allowed-origins=https://beacon.example"
        })
class TrustedProxyWithAllowedOriginsTest {

    @LocalServerPort
    private int port;

    @Test
    void theListedOrigin_isTheOnlyOneThatPasses_whateverTheProxyForwards() throws Exception {
        String own = "https://" + ProxiedRequests.PROXY_ADDRESS;

        assertThat(revealContactOfUnknownTestimonial(port, "https://beacon.example", "X-Forwarded-Proto", "https"))
                .isEqualTo(404);
        assertThat(revealContactOfUnknownTestimonial(port, own, "X-Forwarded-Proto", "https")).isEqualTo(403);
        assertThat(revealContactOfUnknownTestimonial(port, "http://" + ProxiedRequests.PROXY_ADDRESS + ":" + port))
                .isEqualTo(403);
    }
}
