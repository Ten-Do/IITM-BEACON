package com.iitm.beacon.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.common.web.ApiRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Answers an error from outside Spring MVC — a servlet filter that refuses
 * a request before any handler runs — the way {@link GlobalExceptionHandler}
 * answers one inside it (decision 33): the JSON {@link ErrorResponse} with
 * the given fixed message for a request under {@code /api/} ({@link
 * ApiRequests}), the site's HTML error page for the status anywhere else.
 */
@Component
public class ErrorResponseWriter {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ErrorPageRenderer errorPages;

    public ErrorResponseWriter(Clock clock, ObjectMapper objectMapper, ErrorPageRenderer errorPages) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.errorPages = errorPages;
    }

    /** {@code message} must be a fixed text of the application's own: it is sent to the client as is. */
    public void write(HttpStatus status, String message, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!ApiRequests.matches(request)) {
            errorPages.write(status, request, response);
            return;
        }
        ErrorResponse body = new ErrorResponse(
                Instant.now(clock), status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
