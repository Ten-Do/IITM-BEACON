package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class RestAccessDeniedHandlerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:00:00Z");
    private final Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final RestAccessDeniedHandler deniedHandler = new RestAccessDeniedHandler(fixedClock, objectMapper);

    @Test
    void writesForbiddenErrorResponseJson() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/moderation/testimonials/1/approve");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));
        AccessDeniedException deniedException = new AccessDeniedException("denied");

        deniedHandler.handle(request, response, deniedException);

        org.mockito.Mockito.verify(response).setStatus(403);
        org.mockito.Mockito.verify(response).setContentType("application/json");

        ErrorResponse body = objectMapper.readValue(writer.toString(), ErrorResponse.class);
        assertThat(body.status()).isEqualTo(403);
        assertThat(body.error()).isEqualTo("Forbidden");
        assertThat(body.message()).isEqualTo("Access denied");
        assertThat(body.path()).isEqualTo("/api/moderation/testimonials/1/approve");
        assertThat(body.timestamp()).isEqualTo(FIXED_INSTANT);
    }
}
