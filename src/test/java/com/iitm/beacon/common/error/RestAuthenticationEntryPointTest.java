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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.BadCredentialsException;

class RestAuthenticationEntryPointTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:00:00Z");
    private final Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final RestAuthenticationEntryPoint entryPoint =
            new RestAuthenticationEntryPoint(fixedClock, objectMapper);

    @Test
    void writesUnauthorizedErrorResponseJson() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/moderation/testimonials/pending");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));
        AuthenticationException authException = new BadCredentialsException("bad creds");

        entryPoint.commence(request, response, authException);

        org.mockito.Mockito.verify(response).setStatus(401);
        org.mockito.Mockito.verify(response).setContentType("application/json");

        ErrorResponse body = objectMapper.readValue(writer.toString(), ErrorResponse.class);
        assertThat(body.status()).isEqualTo(401);
        assertThat(body.error()).isEqualTo("Unauthorized");
        assertThat(body.message()).isEqualTo("Authentication required");
        assertThat(body.path()).isEqualTo("/api/moderation/testimonials/pending");
        assertThat(body.timestamp()).isEqualTo(FIXED_INSTANT);
    }
}
