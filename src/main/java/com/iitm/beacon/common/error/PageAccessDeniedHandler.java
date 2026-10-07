package com.iitm.beacon.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * The page counterpart of {@link RestAccessDeniedHandler}: answers a refused
 * request to one of the site's pages — a CSRF failure, which {@code
 * config.SecurityConfig} sends here for anything outside {@code /api/**} —
 * with the HTML error page and a 403 instead of a JSON body. Runs in the
 * security filter chain, before Spring MVC, so it writes the page itself
 * ({@link ErrorPageRenderer}).
 */
@Component
public class PageAccessDeniedHandler implements AccessDeniedHandler {

    private final ErrorPageRenderer errorPages;

    public PageAccessDeniedHandler(ErrorPageRenderer errorPages) {
        this.errorPages = errorPages;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        errorPages.write(HttpStatus.FORBIDDEN, request, response);
    }
}
