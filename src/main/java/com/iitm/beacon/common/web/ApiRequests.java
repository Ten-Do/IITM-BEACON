package com.iitm.beacon.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * Tells the JSON API's requests from the site's pages: a request whose path
 * within the application is {@code /api} or lies under {@code /api/} is the
 * API's, everything else is a page (docs/architecture.md §15). An error is
 * answered by that: a JSON {@code ErrorResponse} for the API, the HTML error
 * page for a page — decided by the path rather than by the handler, because
 * some errors (an unsupported method, an unknown path) happen before any
 * handler is chosen.
 *
 * <p>The path is the one Spring MVC matches routes against: decoded,
 * without the context path, path parameters ({@code ;jsessionid=…}) or
 * duplicate slashes.
 */
public final class ApiRequests {

    private static final String API = "/api";

    private ApiRequests() {
    }

    public static boolean matches(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return path.equals(API) || path.startsWith(API + "/");
    }
}
