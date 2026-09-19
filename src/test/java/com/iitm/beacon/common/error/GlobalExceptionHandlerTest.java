package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Unit tests calling {@link GlobalExceptionHandler} handler methods directly
 * with a fixed {@link Clock} — no MockMvc/controllers exist yet in M1.
 */
class GlobalExceptionHandlerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:00:00Z");

    private final Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(fixedClock);

    private HttpServletRequest requestFor(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    @Test
    void notFoundException_mapsTo404WithExactTimestampFromClock() {
        NotFoundException ex = new NotFoundException("testimonial 42 not found");

        ResponseEntity<ErrorResponse> response = handler.handleNotFound(ex, requestFor("/api/testimonials/42"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.timestamp()).isEqualTo(FIXED_INSTANT);
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.message()).isEqualTo("testimonial 42 not found");
        assertThat(body.path()).isEqualTo("/api/testimonials/42");
    }

    @Test
    void methodArgumentNotValidException_mapsTo400WithFieldAndMessage() throws NoSuchMethodException {
        Method dummyMethod = DummyTarget.class.getDeclaredMethod("dummyMethod", String.class);
        MethodParameter methodParameter = new MethodParameter(dummyMethod, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "testimonialRequest");
        bindingResult.addError(new FieldError("testimonialRequest", "email", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex, requestFor("/api/submissions"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).contains("email").contains("must not be blank");
        assertThat(body.path()).isEqualTo("/api/submissions");
    }

    @Test
    void constraintViolationException_mapsTo400() {
        ConstraintViolationException ex =
                new ConstraintViolationException("recommendationScore: must be between 0 and 10", Collections.emptySet());

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex, requestFor("/api/testimonials"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("recommendationScore: must be between 0 and 10");
    }

    @Test
    void notFoundException_blankMessage_stillProducesNonBlankResponseMessage() {
        NotFoundException ex = new NotFoundException("");

        ResponseEntity<ErrorResponse> response = handler.handleNotFound(ex, requestFor("/api/testimonials/999"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isNotNull();
        assertThat(response.getBody().message()).isNotBlank();
    }

    @Test
    void genericException_neverLeaksInternalMessageToClient() {
        Exception sensitive = new RuntimeException(
                "java.sql.SQLException: connection failed at com.iitm.beacon.internal.Db (Db.java:42)");

        ResponseEntity<ErrorResponse> response = handler.handleGeneric(sensitive, requestFor("/api/testimonials"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).doesNotContain("SQLException");
        assertThat(body.message()).doesNotContain("Db.java");
        assertThat(body.message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void tooManyRequestsException_mapsTo429WithGivenMessage() {
        TooManyRequestsException ex = new TooManyRequestsException("Too many OTP requests in a short window.");

        ResponseEntity<ErrorResponse> response =
                handler.handleTooManyRequests(ex, requestFor("/api/admin/auth/otp/request"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(429);
        assertThat(body.message()).isEqualTo("Too many OTP requests in a short window.");
        assertThat(body.path()).isEqualTo("/api/admin/auth/otp/request");
    }

    @Test
    void tooManyRequestsException_blankMessage_fallsBackToDefaultMessage() {
        TooManyRequestsException ex = new TooManyRequestsException("");

        ResponseEntity<ErrorResponse> response =
                handler.handleTooManyRequests(ex, requestFor("/api/admin/auth/otp/request"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Too many OTP requests in a short window.");
    }

    @Test
    void otpVerificationFailedException_mapsTo401WithGivenMessage() {
        OtpVerificationFailedException ex = new OtpVerificationFailedException("Wrong or expired code.");

        ResponseEntity<ErrorResponse> response =
                handler.handleOtpVerificationFailed(ex, requestFor("/api/admin/auth/otp/verify"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(401);
        assertThat(body.message()).isEqualTo("Wrong or expired code.");
    }

    @Test
    void otpVerificationFailedException_blankMessage_fallsBackToDefaultMessage() {
        OtpVerificationFailedException ex = new OtpVerificationFailedException(null);

        ResponseEntity<ErrorResponse> response =
                handler.handleOtpVerificationFailed(ex, requestFor("/api/admin/auth/otp/verify"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Wrong or expired code.");
    }

    private static class DummyTarget {
        @SuppressWarnings("unused")
        void dummyMethod(String arg) {
        }
    }
}
