package com.iitm.beacon.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Decides whether a request was sent by one of this site's own pages, from
 * its headers alone (no token, no login): the {@code Origin} header must be
 * present and name this site, and {@code Sec-Fetch-Site}, when the browser
 * sends it, must be {@code same-origin}. Browsers send {@code Origin} with
 * every POST, a {@code fetch} and a plain form submission alike, and never
 * let a page set either header itself — so another website can't make a
 * visitor's browser pass this check. A hand-written client can, by setting
 * the headers; that is accepted (see {@link SameOriginOnly}).
 *
 * <p>"This site" is each request's own scheme, host and port — what the
 * browser used to reach the app directly — unless {@link
 * SameOriginProperties#allowedOrigins()} lists the site's origins, which
 * then replace it (needed behind a reverse proxy, where the request the app
 * sees is the proxy's). Scheme and host compare case-insensitively, and a
 * scheme's default port matches whether it is spelled out or not.
 *
 * <p>Only a browser-serialized origin is taken for this site's: {@code
 * http} or {@code https}, a host, an optional port, nothing else (no path,
 * query, fragment or user info). The opaque origin {@code null} never
 * matches.
 */
@Component
public final class SameOriginGuard {

    private static final Logger log = LoggerFactory.getLogger(SameOriginGuard.class);

    static final String SEC_FETCH_SITE = "Sec-Fetch-Site";
    private static final String SAME_ORIGIN = "same-origin";
    private static final String PROPERTY = "beacon.web.allowed-origins";
    private static final int MAX_PORT = 65_535;

    /** The outcome of {@link #check(HttpServletRequest)}; every value but {@link #ALLOWED} is a refusal. */
    public enum Verdict {
        ALLOWED,
        /** No {@code Origin} header, or a blank one. */
        NO_ORIGIN,
        /** An {@code Origin} that isn't this site's: another site, {@code null}, malformed, or sent twice. */
        OTHER_ORIGIN,
        /** This site's {@code Origin}, but a {@code Sec-Fetch-Site} other than {@code same-origin}. */
        NOT_SAME_ORIGIN_FETCH;

        public boolean allowed() {
            return this == ALLOWED;
        }
    }

    /** This site's origins from config; empty means "each request's own". */
    private final Set<SiteOrigin> allowedOrigins;

    /**
     * @throws IllegalArgumentException if an allow-list entry isn't an
     *     origin ({@code scheme://host[:port]}, an optional trailing {@code
     *     /} aside) — so a typo stops the app at startup instead of silently
     *     refusing every request
     */
    public SameOriginGuard(SameOriginProperties properties) {
        Set<SiteOrigin> origins = new LinkedHashSet<>();
        for (String entry : properties.allowedOrigins()) {
            String origin = entry.strip();
            if (!origin.isEmpty()) {
                origins.add(SiteOrigin.parse(origin, true).orElseThrow(() -> new IllegalArgumentException(
                        "Invalid entry in " + PROPERTY + ": '" + origin
                                + "' (expected scheme://host[:port], e.g. https://beacon.example)")));
            }
        }
        this.allowedOrigins = Collections.unmodifiableSet(origins);
        if (allowedOrigins.isEmpty()) {
            log.info("Same-origin check: each request's own scheme, host and port is the site's origin");
        } else {
            log.info("Same-origin check: the site's origins are {}", allowedOrigins);
        }
    }

    public Verdict check(HttpServletRequest request) {
        List<String> origins = headerValues(request, HttpHeaders.ORIGIN);
        if (origins.isEmpty() || origins.size() == 1 && origins.get(0).isBlank()) {
            return Verdict.NO_ORIGIN;
        }
        if (origins.size() > 1) {
            return Verdict.OTHER_ORIGIN;
        }
        Optional<SiteOrigin> origin = SiteOrigin.parse(origins.get(0), false);
        if (origin.isEmpty() || !isThisSite(origin.get(), request)) {
            return Verdict.OTHER_ORIGIN;
        }
        for (String site : headerValues(request, SEC_FETCH_SITE)) {
            if (!SAME_ORIGIN.equals(site)) {
                return Verdict.NOT_SAME_ORIGIN_FETCH;
            }
        }
        return Verdict.ALLOWED;
    }

    private boolean isThisSite(SiteOrigin origin, HttpServletRequest request) {
        if (!allowedOrigins.isEmpty()) {
            return allowedOrigins.contains(origin);
        }
        return SiteOrigin.of(request.getScheme(), request.getServerName(), request.getServerPort())
                .map(origin::equals)
                .orElse(false);
    }

    private static List<String> headerValues(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }

    /** A normalized origin: lowercase scheme and host, and an explicit port (the scheme's default if omitted). */
    private record SiteOrigin(String scheme, String host, int port) {

        static Optional<SiteOrigin> parse(String value, boolean allowRootPath) {
            URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                return Optional.empty();
            }
            String path = uri.getRawPath();
            boolean noPath = path == null || path.isEmpty() || allowRootPath && "/".equals(path);
            if (uri.getScheme() == null
                    || uri.getRawUserInfo() != null
                    || !noPath
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return Optional.empty();
            }
            return of(uri.getScheme(), uri.getHost(), uri.getPort());
        }

        /** {@code port} is -1 when not given. Empty for anything but an http(s) origin with a host. */
        static Optional<SiteOrigin> of(String scheme, String host, int port) {
            if (scheme == null || host == null || host.isEmpty() || port == 0 || port > MAX_PORT) {
                return Optional.empty();
            }
            String lowerScheme = scheme.toLowerCase(Locale.ROOT);
            int defaultPort = switch (lowerScheme) {
                case "http" -> 80;
                case "https" -> 443;
                default -> -1;
            };
            if (defaultPort < 0) {
                return Optional.empty();
            }
            return Optional.of(
                    new SiteOrigin(lowerScheme, host.toLowerCase(Locale.ROOT), port < 0 ? defaultPort : port));
        }

        @Override
        public String toString() {
            return scheme + "://" + host + ":" + port;
        }
    }
}
