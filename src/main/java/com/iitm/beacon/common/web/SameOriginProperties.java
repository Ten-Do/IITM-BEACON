package com.iitm.beacon.common.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code beacon.web.*} config for {@link SameOriginGuard}.
 *
 * <p>{@code allowedOrigins}: the origins ({@code scheme://host[:port]}) a
 * browser uses to reach this site. Empty by default, which means "each
 * request's own scheme, host and port" — right whenever the browser talks to
 * the app directly. Behind a reverse proxy the app sees the proxy's request
 * (e.g. {@code http://app:8080} for a browser on {@code
 * https://beacon.example}), so there the site's public origin(s) must be
 * listed ({@code BEACON_ALLOWED_ORIGINS}, comma-separated). A non-empty list
 * replaces the request-derived origin; it never adds to it.
 */
@ConfigurationProperties(prefix = "beacon.web")
public record SameOriginProperties(List<String> allowedOrigins) {

    public SameOriginProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : List.copyOf(allowedOrigins.stream().map(origin -> origin == null ? "" : origin).toList());
    }
}
