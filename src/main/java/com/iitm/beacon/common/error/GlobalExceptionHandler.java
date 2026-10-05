package com.iitm.beacon.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
    private static final String TESTIMONIAL_ALREADY_EXISTS_FALLBACK_MESSAGE =
            "A testimonial already exists for this visitor.";
    private static final String TESTIMONIAL_NOT_PENDING_FALLBACK_MESSAGE =
            "This testimonial is no longer pending.";
    private static final String UPLOAD_TOO_LARGE_MESSAGE =
            "The upload is too large or contains too many parts. Send fewer or smaller photos.";
    private static final String MALFORMED_MULTIPART_MESSAGE =
            "The multipart request could not be read. Check the request body and try again.";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, safeMessage(ex.getMessage(), NOT_FOUND_FALLBACK_MESSAGE), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND_FALLBACK_MESSAGE, request);
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

    /**
     * Every violation of the submission in one {@link ErrorResponse}: the
     * exception's message already joins them as {@code field: message,
     * field2: message2} (a global violation contributes just its message),
     * all of them written by the application itself, never an exception's
     * internals.
     */
    @ExceptionHandler(SubmissionValidationException.class)
    public ResponseEntity<ErrorResponse> handleSubmissionValidation(
            SubmissionValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, safeMessage(ex.getMessage(), VALIDATION_FALLBACK_MESSAGE), request);
    }

    @ExceptionHandler(TestimonialAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleTestimonialAlreadyExists(
            TestimonialAlreadyExistsException ex, HttpServletRequest request) {
        return build(
                HttpStatus.CONFLICT,
                safeMessage(ex.getMessage(), TESTIMONIAL_ALREADY_EXISTS_FALLBACK_MESSAGE),
                request);
    }

    @ExceptionHandler(TestimonialNotPendingException.class)
    public ResponseEntity<ErrorResponse> handleTestimonialNotPending(
            TestimonialNotPendingException ex, HttpServletRequest request) {
        return build(
                HttpStatus.CONFLICT,
                safeMessage(ex.getMessage(), TESTIMONIAL_NOT_PENDING_FALLBACK_MESSAGE),
                request);
    }

    /**
     * Upload over a servlet-container multipart limit — per-file size,
     * whole-request size, or (Tomcat's {@code max-part-count}) number of
     * parts, which Spring also reports as this type. Never echoes the
     * container's own message, which names internal classes and limits.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.warn("Rejected oversized multipart request to {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.PAYLOAD_TOO_LARGE, UPLOAD_TOO_LARGE_MESSAGE, request);
    }

    /**
     * Any other multipart failure. Spring does not always classify a
     * container limit breach as {@link MaxUploadSizeExceededException}: with
     * lazy multipart resolution, a {@code @RequestPart} argument reads the
     * body via {@code getMultipartHeaders}, which wraps e.g. Tomcat's {@code
     * FileCountLimitExceededException} in a plain {@link
     * MultipartException}. Those are still answered with 413; everything
     * else (a truncated or malformed body) is a 400.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex, HttpServletRequest request) {
        if (isUploadLimitBreach(ex)) {
            log.warn("Rejected oversized multipart request to {}: {}", request.getRequestURI(), ex.getMessage());
            return build(HttpStatus.PAYLOAD_TOO_LARGE, UPLOAD_TOO_LARGE_MESSAGE, request);
        }
        log.warn("Rejected unreadable multipart request to {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, MALFORMED_MULTIPART_MESSAGE, request);
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

    /**
     * True if the cause chain holds a {@link MaxUploadSizeExceededException}
     * or a servlet container's upload-limit exception. Tomcat's (file size,
     * request size, part count) share no common limit supertype but are all
     * named {@code *LimitExceededException}; matching on the name keeps this
     * class free of a compile-time dependency on container internals.
     */
    private static boolean isUploadLimitBreach(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = ex; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof MaxUploadSizeExceededException
                    || cause.getClass().getSimpleName().endsWith("LimitExceededException")) {
                return true;
            }
        }
        return false;
    }

    private String safeMessage(String message, String fallback) {
        return (message == null || message.isBlank()) ? fallback : message;
    }
}
