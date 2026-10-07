package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

/**
 * {@link GlobalExceptionHandler}'s catalog handlers (decision 28) — 400 for
 * a broken field rule, 409 for a clash with the existing catalog — and its
 * 400 for a request body that can't be read as JSON at all.
 */
class GlobalExceptionHandlerCatalogTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:00:00Z");

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

    private static HttpServletRequest requestFor(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    @Test
    void catalogValidationException_mapsTo400WithTheJoinedViolations() {
        CatalogValidationException ex = new CatalogValidationException(List.of(
                new FieldViolation("topicGroupId", "names no existing topic group"),
                new FieldViolation("label", "must not be blank")));

        ResponseEntity<ErrorResponse> response = handler.handleCatalogValidation(ex, requestFor("/api/catalog/topics"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.timestamp()).isEqualTo(FIXED_INSTANT);
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.message()).isEqualTo("topicGroupId: names no existing topic group, label: must not be blank");
        assertThat(body.path()).isEqualTo("/api/catalog/topics");
    }

    @Test
    void catalogConflictException_mapsTo409WithItsMessage() {
        CatalogConflictException ex = new CatalogConflictException("slug", "This slug is already in use.");

        ResponseEntity<ErrorResponse> response =
                handler.handleCatalogConflict(ex, requestFor("/api/catalog/achievements/3"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.error()).isEqualTo("Conflict");
        assertThat(body.message()).isEqualTo("This slug is already in use.");
        assertThat(body.path()).isEqualTo("/api/catalog/achievements/3");
    }

    @Test
    void unreadableBody_mapsTo400WithoutTheParsersOwnMessage() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character ('}' (code 125)) at [Source: REDACTED; line: 1]"
                        + " com.fasterxml.jackson.core.JsonParseException",
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ErrorResponse> response = handler.handleUnreadableBody(ex, requestFor("/api/catalog/topics"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.message()).isNotBlank().doesNotContain("Jackson").doesNotContain("jackson")
                .doesNotContain("JSON parse error").doesNotContain("Source");
        assertThat(body.path()).isEqualTo("/api/catalog/topics");
    }
}
