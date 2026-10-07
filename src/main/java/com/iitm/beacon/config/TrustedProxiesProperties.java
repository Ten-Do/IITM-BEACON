package com.iitm.beacon.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code beacon.web.trusted-proxies} ({@code BEACON_TRUSTED_PROXIES},
 * comma-separated): the IP addresses of the reverse proxies in front of the
 * app, whose {@code X-Forwarded-For} and {@code X-Forwarded-Proto} headers
 * the app believes ({@link ClientAddressConfig}). Empty by default — no
 * proxy is trusted, and a request's address is its connection's.
 */
@ConfigurationProperties(prefix = "beacon.web")
public record TrustedProxiesProperties(List<String> trustedProxies) {

    public TrustedProxiesProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(
                trustedProxies.stream().map(address -> address == null ? "" : address).toList());
    }
}
