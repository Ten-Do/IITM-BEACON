package com.iitm.beacon.config;

import org.springframework.test.context.TestPropertySource;

/**
 * A trusted proxy is configured, but the request comes from another address
 * (the test client's {@code 127.0.0.1}): its {@code X-Forwarded-*} headers
 * are ignored all the same — even though {@code 127.0.0.1} is in a range
 * Tomcat would trust by default.
 */
@TestPropertySource(
        properties = {
            "beacon.web.trusted-proxies=192.0.2.10",
            "visitor.otp.request-limit-per-ip=1",
            "visitor.otp.request-limit-per-email=1000"
        })
class ClientAddressFromUntrustedProxyTest extends ForwardedHeadersIgnoredContract {
}
