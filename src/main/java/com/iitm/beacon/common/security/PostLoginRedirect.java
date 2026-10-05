package com.iitm.beacon.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * Decides where a login verify handler sends the user after a successful
 * OTP login: back to the page they originally asked for, if it is one of
 * their role's own pages, otherwise to the role's default page. Shared by
 * {@code adminauth.AdminAuthViewController} and {@code
 * submission.SubmissionViewController} the same way {@link
 * SessionAuthenticator} is (a {@code common} class, never one slice
 * importing another, decision 9).
 *
 * <p>The originally requested page is the one {@code config.SecurityConfig}
 * saved in the {@link RequestCache} before redirecting an unauthenticated
 * page request to the login page. It is removed here whether it is used or
 * not, so a stale page never resurfaces on a later login.
 *
 * <p>Only the saved request's path and query are ever returned — never its
 * scheme or host, which come from the original request's own Host header —
 * and only for a saved GET whose path is well formed, has no dot segments,
 * and lies under one of the allowed prefixes. The result is therefore
 * always a page of this site (no open redirect) within the role's pages,
 * relative to the servlet context path, ready for a {@code redirect:} view
 * name.
 */
@Component
public class PostLoginRedirect {

    private static final Logger log = LoggerFactory.getLogger(PostLoginRedirect.class);

    private final RequestCache requestCache;

    public PostLoginRedirect(RequestCache requestCache) {
        this.requestCache = requestCache;
    }

    /**
     * Returns the path (plus query, if any) to redirect to after login, and
     * removes the saved request.
     *
     * @param allowedPathPrefixes the role's pages. A prefix matches on a
     *     path-segment boundary: {@code /submissions/form} matches {@code
     *     /submissions/form} and {@code /submissions/form/...} but not
     *     {@code /submissions/formatted}; a prefix ending in {@code /}
     *     matches anything below it
     * @param fallbackPath where to go when there is no usable saved page
     * @throws IllegalArgumentException if the fallback or a prefix is not a
     *     site-relative path starting with a single {@code /}
     */
    public String resolve(
            HttpServletRequest request,
            HttpServletResponse response,
            Collection<String> allowedPathPrefixes,
            String fallbackPath) {
        requireSiteRelativePath(fallbackPath, "fallbackPath");
        if (allowedPathPrefixes == null) {
            throw new IllegalArgumentException("allowedPathPrefixes must not be null");
        }
        allowedPathPrefixes.forEach(prefix -> requireSiteRelativePath(prefix, "allowed path prefix"));

        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved == null) {
            return fallbackPath;
        }
        requestCache.removeRequest(request, response);

        Optional<String> target = returnPath(saved, request.getContextPath(), allowedPathPrefixes);
        if (target.isEmpty()) {
            log.debug("Discarded saved request {} after login: not a page of this role", saved.getRedirectUrl());
        }
        return target.orElse(fallbackPath);
    }

    private static Optional<String> returnPath(
            SavedRequest saved, String contextPath, Collection<String> allowedPathPrefixes) {
        if (!"GET".equals(saved.getMethod())) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(saved.getRedirectUrl());
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.startsWith(contextPath)) {
            return Optional.empty();
        }
        String path = rawPath.substring(contextPath.length());
        if (!isSiteRelativePath(path)
                || hasDotSegment(path)
                || allowedPathPrefixes.stream().noneMatch(prefix -> isUnder(path, prefix))) {
            return Optional.empty();
        }
        String query = uri.getRawQuery();
        return Optional.of(query == null || query.isEmpty() ? path : path + "?" + query);
    }

    private static boolean isUnder(String path, String prefix) {
        return path.startsWith(prefix)
                && (prefix.endsWith("/") || path.length() == prefix.length() || path.charAt(prefix.length()) == '/');
    }

    /** Browsers resolve {@code .}/{@code ..} segments, percent-encoded or not, before requesting. */
    private static boolean hasDotSegment(String rawPath) {
        for (String segment : rawPath.split("/", -1)) {
            String decoded;
            try {
                decoded = UriUtils.decode(segment, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                return true;
            }
            if (".".equals(decoded) || "..".equals(decoded)) {
                return true;
            }
        }
        return false;
    }

    /** A single leading slash: {@code //host} and {@code /\host} are protocol-relative to a browser. */
    private static boolean isSiteRelativePath(String path) {
        return path.startsWith("/") && !path.startsWith("//") && !path.startsWith("/\\");
    }

    private static void requireSiteRelativePath(String path, String name) {
        if (path == null || !isSiteRelativePath(path)) {
            throw new IllegalArgumentException(name + " must be a site-relative path starting with a single '/'");
        }
    }
}
