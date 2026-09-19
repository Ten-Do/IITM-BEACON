package com.iitm.beacon.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized error handling: maps known exceptions to a consistent
 * {@link ErrorResponse} shape with an appropriate HTTP status. Never leaks a
 * stack trace or internal exception detail to the client
 * (NFR-ERROR-TRANSPARENCY) — unexpected exceptions are logged server-side at
 * ERROR level and replaced with a generic client-facing message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String GENERIC_ERROR_MESSAGE = "An unexpected error occurred";
    private static final String NOT_FOUND_FALLBACK_MESSAGE = "Resource not found";
    private static final String VALIDATION_FALLBACK_MESSAGE = "Validation failed";
    private static final String TOO_MANY_REQUESTS_FALLBACK_MESSAGE = "Too many OTP requests in a short window.";
    private static final String OTP_VERIFICATION_FAILED_FALLBACK_MESSAGE = "Wrong or expired code.";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, safeMessage(ex.getMessage(), NOT_FOUND_FALLBACK_MESSAGE), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, safeMessage(message, VALIDATION_FALLBACK_MESSAGE), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, safeMessage(ex.getMessage(), VALIDATION_FALLBACK_MESSAGE), request);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(
            TooManyRequestsException ex, HttpServletRequest request) {
        return build(
                HttpStatus.TOO_MANY_REQUESTS,
                safeMessage(ex.getMessage(), TOO_MANY_REQUESTS_FALLBACK_MESSAGE),
                request);
    }

    @ExceptionHandler(OtpVerificationFailedException.class)
    public ResponseEntity<ErrorResponse> handleOtpVerificationFailed(
            OtpVerificationFailedException ex, HttpServletRequest request) {
        return build(
                HttpStatus.UNAUTHORIZED,
                safeMessage(ex.getMessage(), OTP_VERIFICATION_FAILED_FALLBACK_MESSAGE),
                request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while processing request {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR_MESSAGE, request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(clock),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    private String safeMessage(String message, String fallback) {
        return (message == null || message.isBlank()) ? fallback : message;
    }
}
