package com.iitm.beacon.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Answers a request to a path outside {@code /api/**} that no route serves
 * — one {@code config.SecurityConfig}'s {@code denyAll()} tail refuses —
 * with the site's HTML 404 page ("Page not found"), the same page {@link
 * GlobalExceptionHandler} renders for an unknown page. Nothing is served
 * there, so "not found" is the truthful answer, for an anonymous request (as
 * the {@link AuthenticationEntryPoint}) and a logged-in one (as the {@link
 * AccessDeniedHandler}) alike — never a JSON body, never a login redirect.
 * Runs in the security filter chain, before Spring MVC, so it writes the
 * page itself ({@link ErrorPageRenderer}), like {@link
 * PageAccessDeniedHandler} does for a 403.
 */
@Component
public class PageNotFoundHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ErrorPageRenderer errorPages;

    public PageNotFoundHandler(ErrorPageRenderer errorPages) {
        this.errorPages = errorPages;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        errorPages.write(HttpStatus.NOT_FOUND, request, response);
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        errorPages.write(HttpStatus.NOT_FOUND, request, response);
    }
}
