package com.iitm.beacon.perf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The cookies one perf client keeps, like a browser: every {@code
 * Set-Cookie} response header is applied in header order — a new value
 * replaces the old one, a {@code Max-Age} of 0 or less deletes the cookie —
 * and all of them go back in one {@code Cookie} request header. Only name
 * and value are kept: every perf request goes to the same host and path
 * {@code /}. Not thread-safe; each client thread uses its own {@link #copy()}.
 */
final class CookieJar {

    private final Map<String, String> cookies = new LinkedHashMap<>();

    /** Applies {@code setCookieHeaders}, the values of a response's {@code Set-Cookie} headers, in order. */
    void accept(List<String> setCookieHeaders) {
        Objects.requireNonNull(setCookieHeaders, "setCookieHeaders");
        setCookieHeaders.forEach(this::accept);
    }

    private void accept(String setCookie) {
        String[] parts = setCookie.split(";");
        int equals = parts[0].indexOf('=');
        if (equals < 0) {
            return;
        }
        String name = parts[0].substring(0, equals).trim();
        String value = parts[0].substring(equals + 1).trim();
        if (name.isEmpty()) {
            return;
        }
        if (expires(parts)) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }

    private static boolean expires(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i].trim().toLowerCase(Locale.ROOT);
            if (attribute.startsWith("max-age=")) {
                try {
                    return Long.parseLong(attribute.substring("max-age=".length()).trim()) <= 0;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    Optional<String> get(String name) {
        return Optional.ofNullable(cookies.get(name));
    }

    /** The {@code Cookie} request header's value, or empty with no cookie to send. */
    Optional<String> cookieHeader() {
        if (cookies.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(cookies.entrySet().stream()
                .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                .collect(Collectors.joining("; ")));
    }

    CookieJar copy() {
        CookieJar copy = new CookieJar();
        copy.cookies.putAll(cookies);
        return copy;
    }
}
