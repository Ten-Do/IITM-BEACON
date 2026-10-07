package com.iitm.beacon.common.error;

import com.iitm.beacon.common.web.ApiRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.InvalidPropertyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized error handling: maps known exceptions to an appropriate HTTP
 * status and a fixed, readable message. Never leaks a stack trace or
 * internal exception detail to the client (NFR-ERROR-TRANSPARENCY) — the
 * client errors Spring MVC raises itself (a path id that isn't a number, a
 * malformed or missing parameter, an unsupported method or media type) are
 * answered with messages of this class's own, and unexpected exceptions are
 * logged server-side at ERROR level and replaced with a generic message.
 *
 * <p>How the error is answered depends on where the request went ({@link
 * ApiRequests}): a request under {@code /api/} gets the JSON {@link
 * ErrorResponse}, whatever it accepts; any other request — a page — gets
 * the site's HTML error page ({@link ErrorPageRenderer}) with the same
 * status and headers. Decided by the path, not by the handler, because some
 * of these errors (an unsupported method or media type, an unknown path)
 * happen before Spring MVC has chosen a handler. A page's View-Controller
 * still handles its own expected errors itself — a redirect, an inline form
 * error, an in-slice not-found page (docs/architecture.md §17); what reaches
 * this class from a page is what no View-Controller handles.
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
    private static final String NOTIFICATION_NOT_SENT_FALLBACK_MESSAGE =
            "The email couldn't be sent, so nothing was changed. Try again later.";
    private static final String UPLOAD_TOO_LARGE_MESSAGE =
            "The upload is too large or contains too many parts. Send fewer or smaller photos.";
    private static final String MALFORMED_MULTIPART_MESSAGE =
            "The multipart request could not be read. Check the request body and try again.";
    private static final String UNREADABLE_BODY_MESSAGE =
            "The request body could not be read. Send valid JSON with the expected field types.";
    private static final String CATALOG_CONFLICT_FALLBACK_MESSAGE =
            "The change conflicts with the existing catalog.";
    private static final String METHOD_NOT_SUPPORTED_MESSAGE = "This method is not supported for this resource.";
    private static final String MEDIA_TYPE_NOT_SUPPORTED_MESSAGE =
            "This content type is not supported for this resource.";
    private static final String MEDIA_TYPE_NOT_ACCEPTABLE_MESSAGE = "This resource is only available as JSON.";
    private static final String UNBINDABLE_FIELD_MESSAGE = "The request has a field that can't be read.";
    private static final String CLIENT_ERROR_FALLBACK_MESSAGE = "The request could not be processed.";

    private final Clock clock;
    private final ErrorPageRenderer errorPages;

    public GlobalExceptionHandler(Clock clock, ErrorPageRenderer errorPages) {
        this.clock = clock;
        this.errorPages = errorPages;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, safeMessage(ex.getMessage(), NOT_FOUND_FALLBACK_MESSAGE), request);
    }

    /** No handler and no static resource for the path. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND_FALLBACK_MESSAGE, request);
    }

    /**
     * A path, query or form value that can't be converted to its parameter's
     * type — text or a number beyond {@code Long} for an id, say. In the path
     * (BL-029, BL-037) it names no resource that could exist: a 404, exactly
     * as for an unknown id. Anywhere else it is a malformed request: a 400
     * naming the parameter — never the value, nor the converter's message,
     * which names Java types.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        logClientError(request, ex);
        if (ex.getParameter().hasParameterAnnotation(PathVariable.class)) {
            return build(HttpStatus.NOT_FOUND, NOT_FOUND_FALLBACK_MESSAGE, request);
        }
        return build(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'.", request);
    }

    /**
     * A path id that converts to nothing — a blank one — is no resource
     * either: a 404, like a malformed id. A path variable the route itself
     * lacks is a mistake in the code: the generic 500.
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<?> handleMissingPathVariable(MissingPathVariableException ex, HttpServletRequest request) {
        if (!ex.isMissingAfterConversion()) {
            return handleGeneric(ex, request);
        }
        logClientError(request, ex);
        return build(HttpStatus.NOT_FOUND, NOT_FOUND_FALLBACK_MESSAGE, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        logClientError(request, ex);
        return build(
                HttpStatus.BAD_REQUEST, "Required parameter '" + ex.getParameterName() + "' is missing.", request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<?> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        logClientError(request, ex);
        return build(HttpStatus.BAD_REQUEST, "Required part '" + ex.getRequestPartName() + "' is missing.", request);
    }

    /** With the {@code Allow} header listing the methods the URL does support. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        logClientError(request, ex);
        return build(HttpStatus.METHOD_NOT_ALLOWED, ex.getHeaders(), METHOD_NOT_SUPPORTED_MESSAGE, request);
    }

    /** With the {@code Accept} (or {@code Accept-Patch}) header listing the content types the URL takes. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        logClientError(request, ex);
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getHeaders(), MEDIA_TYPE_NOT_SUPPORTED_MESSAGE, request);
    }

    /**
     * The request accepts nothing the handler can produce. The answer is
     * still the JSON {@link ErrorResponse} (or, for a page, the HTML page):
     * an error body in the one format the URL has beats an empty one.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<?> handleMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        logClientError(request, ex);
        return build(HttpStatus.NOT_ACCEPTABLE, ex.getHeaders(), MEDIA_TYPE_NOT_ACCEPTABLE_MESSAGE, request);
    }

    /**
     * A form field the data binder can't bind at all (BL-014): an index past
     * its auto-grow limit of 256 ({@code sections[256]}), a malformed index,
     * a nested path through a null. The exception's message names the bound
     * class and echoes the field.
     */
    @ExceptionHandler(InvalidPropertyException.class)
    public ResponseEntity<?> handleInvalidProperty(InvalidPropertyException ex, HttpServletRequest request) {
        logClientError(request, ex);
        return build(HttpStatus.BAD_REQUEST, UNBINDABLE_FIELD_MESSAGE, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, safeMessage(message, VALIDATION_FALLBACK_MESSAGE), request);
    }

    /**
     * A Bean Validation constraint on a handler's parameter (a {@code
     * @Validated} controller), e.g. a negative {@code page}. Each violation
     * as {@code parameter: message} — the exception's own message prefixes
     * the parameter with the Java method's name ({@code browse.page}).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
        String message = violations == null ? "" : violations.stream()
                .map(GlobalExceptionHandler::describe)
                .sorted()
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, safeMessage(message, VALIDATION_FALLBACK_MESSAGE), request);
    }

    /**
     * A page beyond the last one its page size can address ({@code
     * common.web.PageRequests}): the message names the largest page, like a
     * {@code @Max} on the parameter would.
     */
    @ExceptionHandler(PageOutOfRangeException.class)
    public ResponseEntity<?> handlePageOutOfRange(PageOutOfRangeException ex, HttpServletRequest request) {
        logClientError(request, ex);
        return build(HttpStatus.BAD_REQUEST, safeMessage(ex.getMessage(), VALIDATION_FALLBACK_MESSAGE), request);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<?> handleTooManyRequests(TooManyRequestsException ex, HttpServletRequest request) {
        return build(
                HttpStatus.TOO_MANY_REQUESTS,
                safeMessage(ex.getMessage(), TOO_MANY_REQUESTS_FALLBACK_MESSAGE),
                request);
    }

    @ExceptionHandler(OtpVerificationFailedException.class)
    public ResponseEntity<?> handleOtpVerificationFailed(
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
    public ResponseEntity<?> handleSubmissionValidation(SubmissionValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, safeMessage(ex.getMessage(), VALIDATION_FALLBACK_MESSAGE), request);
    }

    @ExceptionHandler(TestimonialAlreadyExistsException.class)
    public ResponseEntity<?> handleTestimonialAlreadyExists(
            TestimonialAlreadyExistsException ex, HttpServletRequest request) {
        return build(
                HttpStatus.CONFLICT,
                safeMessage(ex.getMessage(), TESTIMONIAL_ALREADY_EXISTS_FALLBACK_MESSAGE),
                request);
    }

    @ExceptionHandler(TestimonialNotPendingException.class)
    public ResponseEntity<?> handleTestimonialNotPending(
            TestimonialNotPendingException ex, HttpServletRequest request) {
        return build(
                HttpStatus.CONFLICT,
                safeMessage(ex.getMessage(), TESTIMONIAL_NOT_PENDING_FALLBACK_MESSAGE),
                request);
    }

    /**
     * An action that must email someone couldn't send the email, so it
     * wasn't carried out (a reject — UC-REJECT-TESTIMONIAL): the mail
     * server, not the request, is at fault, and trying again later may work.
     * The service has already logged it, without the recipient.
     */
    @ExceptionHandler(NotificationNotSentException.class)
    public ResponseEntity<?> handleNotificationNotSent(NotificationNotSentException ex, HttpServletRequest request) {
        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                safeMessage(ex.getMessage(), NOTIFICATION_NOT_SENT_FALLBACK_MESSAGE),
                request);
    }

    /**
     * A catalog field rule the service checks itself (decision 28), e.g. a
     * {@code topicGroupId} naming no existing group. Like {@link
     * #handleSubmissionValidation}, the message already joins every violation
     * as {@code field: message} pairs written by the application.
     */
    @ExceptionHandler(CatalogValidationException.class)
    public ResponseEntity<?> handleCatalogValidation(CatalogValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, safeMessage(ex.getMessage(), VALIDATION_FALLBACK_MESSAGE), request);
    }

    /** A slug already in use, or a change the protected {@code general} topic refuses (decision 28). */
    @ExceptionHandler(CatalogConflictException.class)
    public ResponseEntity<?> handleCatalogConflict(CatalogConflictException ex, HttpServletRequest request) {
        return build(
                HttpStatus.CONFLICT,
                safeMessage(ex.getMessage(), CATALOG_CONFLICT_FALLBACK_MESSAGE),
                request);
    }

    /**
     * A JSON body that can't be read — malformed, missing where required, or
     * with a value of the wrong type (e.g. text for a number). A client
     * error, not a 500; the parser's own message names internal classes and
     * echoes the input, so it is only logged.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Rejected unreadable request body to {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, UNREADABLE_BODY_MESSAGE, request);
    }

    /**
     * Upload over a servlet-container multipart limit — per-file size,
     * whole-request size, or (Tomcat's {@code max-part-count}) number of
     * parts, which Spring also reports as this type. Never echoes the
     * container's own message, which names internal classes and limits.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSizeExceeded(
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
    public ResponseEntity<?> handleMultipart(MultipartException ex, HttpServletRequest request) {
        if (isUploadLimitBreach(ex)) {
            log.warn("Rejected oversized multipart request to {}: {}", request.getRequestURI(), ex.getMessage());
            return build(HttpStatus.PAYLOAD_TOO_LARGE, UPLOAD_TOO_LARGE_MESSAGE, request);
        }
        log.warn("Rejected unreadable multipart request to {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, MALFORMED_MULTIPART_MESSAGE, request);
    }

    /**
     * Everything else. A client error Spring reports with its own 4xx status
     * and no handler above (e.g. a request parameter condition that isn't
     * met) keeps that status and headers, with a fixed message; anything else
     * is an unexpected server error: logged, and a generic 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneric(Exception ex, HttpServletRequest request) {
        if (ex instanceof org.springframework.web.ErrorResponse framework
                && framework.getStatusCode().is4xxClientError()) {
            logClientError(request, ex);
            return build(framework.getStatusCode(), framework.getHeaders(), CLIENT_ERROR_FALLBACK_MESSAGE, request);
        }
        log.error("Unhandled exception while processing request {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR_MESSAGE, request);
    }

    private ResponseEntity<?> build(HttpStatusCode status, String message, HttpServletRequest request) {
        return build(status, HttpHeaders.EMPTY, message, request);
    }

    /**
     * The answer for the request: the HTML error page for a page, the JSON
     * {@link ErrorResponse} for the API — its content type set here, so it
     * is written whatever the request accepts.
     */
    private ResponseEntity<?> build(
            HttpStatusCode status, HttpHeaders headers, String message, HttpServletRequest request) {
        if (!ApiRequests.matches(request)) {
            return errorPages.response(status, headers, request);
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        ErrorResponse body = new ErrorResponse(
                Instant.now(clock),
                status.value(),
                resolved != null ? resolved.getReasonPhrase() : "Error",
                message,
                request.getRequestURI());
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** A client error is the client's, not the server's: never logged above DEBUG, never with its input. */
    private static void logClientError(HttpServletRequest request, Exception ex) {
        log.debug("Rejected request to {}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
    }

    /**
     * {@code parameter: message}, the parameter (or field) being the last
     * node of the violation's path — without the Java method in front of it.
     */
    private static String describe(ConstraintViolation<?> violation) {
        String name = null;
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name == null || name.isBlank() ? violation.getMessage() : name + ": " + violation.getMessage();
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
