package com.iitm.beacon.config;

import com.iitm.beacon.common.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /logout} takes a POST only — Spring Security's {@code LogoutFilter}
 * answers it — so a link, a prefetch or an image pointing at it never logs
 * anyone out. Any other method gets what Spring MVC gives an unsupported
 * method anywhere else (decision 33): a 405 with {@code Allow: POST}, as the
 * HTML error page. Without this filter such a request would fall through to
 * the {@code denyAll()} tail and get a JSON 401 or 403. Placed after the
 * CSRF check, like every other 405: a PUT without a token is a 403 first.
 */
final class LogoutMethodFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LogoutMethodFilter.class);
    private static final RequestMatcher LOGOUT = PathPatternRequestMatcher.withDefaults().matcher("/logout");
    private static final String METHOD_NOT_SUPPORTED_MESSAGE = "This method is not supported for this resource.";

    private final ErrorResponseWriter errorResponses;

    LogoutMethodFilter(ErrorResponseWriter errorResponses) {
        this.errorResponses = errorResponses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (LOGOUT.matches(request) && !HttpMethod.POST.matches(request.getMethod())) {
            log.debug("Rejected request to {}: method not allowed", request.getRequestURI());
            response.setHeader(HttpHeaders.ALLOW, HttpMethod.POST.name());
            errorResponses.write(HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_SUPPORTED_MESSAGE, request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
