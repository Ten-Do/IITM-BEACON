package com.iitm.beacon.config;

import org.springframework.test.context.TestPropertySource;

/** No trusted proxy configured — the default: every {@code X-Forwarded-*} header is ignored. */
@TestPropertySource(
        properties = {
            "visitor.otp.request-limit-per-ip=1",
            "visitor.otp.request-limit-per-email=1000"
        })
class ClientAddressWithoutTrustedProxiesTest extends ForwardedHeadersIgnoredContract {
}
