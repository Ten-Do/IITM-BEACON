package com.iitm.beacon.config;

import com.iitm.beacon.common.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps large multipart bodies to the three endpoints that take photos:
 * {@code POST /submissions/form}, {@code POST /api/submissions} and {@code
 * PUT /api/submissions/mine}. Any other request with a {@code multipart/*}
 * body is refused from its headers alone — before anything reads the body —
 * unless it declares a {@code Content-Length} within {@code
 * beacon.web.non-upload-multipart-max-size} ({@link
 * NonUploadMultipartProperties}): 413 above it, 411 without a declared
 * length (a chunked body can't be bounded in advance). Answered as JSON
 * under {@code /api/**} and as the HTML error page elsewhere ({@link
 * ErrorResponseWriter}).
 *
 * <p>Why: the CSRF check reads a form's token from the request parameters,
 * and for a multipart body that makes the servlet container parse it — up
 * to the submission form's upload limit (1010 MB), its files written to
 * disk — for any request, on any path, logged in or not, before security
 * refuses it. {@code config.SecurityConfig} runs this filter in the security
 * filter chain right after the security headers are set up and before the
 * CSRF check, the first thing that reads a body. The submission endpoints
 * keep the container's multipart limits, and their oversized-upload
 * handling (decisions 22, 32). Any other body type passes: the CSRF check
 * only parses form-encoded bodies, which the container caps at 2 MB.
 */
final class NonUploadMultipartFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(NonUploadMultipartFilter.class);

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();
    private static final RequestMatcher SUBMISSION_ENDPOINTS = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.POST, "/submissions/form"),
            PATHS.matcher(HttpMethod.POST, "/api/submissions"),
            PATHS.matcher(HttpMethod.PUT, "/api/submissions/mine"));

    static final String TOO_LARGE_MESSAGE = "The request body is too large.";
    static final String LENGTH_REQUIRED_MESSAGE = "The request must state its Content-Length.";

    private final long maxBytes;
    private final ErrorResponseWriter errors;

    NonUploadMultipartFilter(DataSize maxSize, ErrorResponseWriter errors) {
        this.maxBytes = Objects.requireNonNull(maxSize, "beacon.web.non-upload-multipart-max-size").toBytes();
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isMultipart(request) || SUBMISSION_ENDPOINTS.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        long length = request.getContentLengthLong();
        if (length < 0) {
            log.debug("Refused a multipart request of unknown length to {}", request.getRequestURI());
            errors.write(HttpStatus.LENGTH_REQUIRED, LENGTH_REQUIRED_MESSAGE, request, response);
        } else if (length > maxBytes) {
            log.debug("Refused a multipart request of {} bytes to {}", length, request.getRequestURI());
            errors.write(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE_MESSAGE, request, response);
        } else {
            chain.doFilter(request, response);
        }
    }

    /** Any {@code multipart/*} type, however spelled: what the container and Spring parse as multipart. */
    private static boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.strip().toLowerCase(Locale.ROOT).startsWith("multipart/");
    }
}
