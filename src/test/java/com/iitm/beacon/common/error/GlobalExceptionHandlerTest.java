package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.testsupport.TemplateEngines;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Unit tests calling {@link GlobalExceptionHandler} handler methods directly
 * with a fixed {@link Clock}, for requests under {@code /api/} (the JSON
 * answers); the HTML page answers and the client errors Spring MVC raises
 * itself are in {@link GlobalExceptionHandlerClientErrorsTest}.
 */
class GlobalExceptionHandlerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:00:00Z");

    private final Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(fixedClock, new ErrorPageRenderer(TemplateEngines.classpathTemplates()));

    private HttpServletRequest requestFor(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    @Test
    void notFoundException_mapsTo404WithExactTimestampFromClock() {
        NotFoundException ex = new NotFoundException("testimonial 42 not found");

        ResponseEntity<?> response = handler.handleNotFound(ex, requestFor("/api/testimonials/42"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponse body = body(response);
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

        ResponseEntity<?> response = handler.handleValidation(ex, requestFor("/api/submissions"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = body(response);
        assertThat(body).isNotNull();
        assertThat(body.message()).contains("email").contains("must not be blank");
        assertThat(body.path()).isEqualTo("/api/submissions");
    }

    @Test
    void notFoundException_blankMessage_stillProducesNonBlankResponseMessage() {
        NotFoundException ex = new NotFoundException("");

        ResponseEntity<?> response = handler.handleNotFound(ex, requestFor("/api/testimonials/999"));

        assertThat(response.getBody()).isNotNull();
        assertThat(body(response).message()).isNotNull();
        assertThat(body(response).message()).isNotBlank();
    }

    @Test
    void genericException_neverLeaksInternalMessageToClient() {
        Exception sensitive = new RuntimeException(
                "java.sql.SQLException: connection failed at com.iitm.beacon.internal.Db (Db.java:42)");

        ResponseEntity<?> response = handler.handleGeneric(sensitive, requestFor("/api/testimonials"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = body(response);
        assertThat(body).isNotNull();
        assertThat(body.message()).doesNotContain("SQLException");
        assertThat(body.message()).doesNotContain("Db.java");
        assertThat(body.message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void tooManyRequestsException_mapsTo429WithGivenMessage() {
        TooManyRequestsException ex = new TooManyRequestsException("Too many OTP requests in a short window.");

        ResponseEntity<?> response =
                handler.handleTooManyRequests(ex, requestFor("/api/admin/auth/otp/request"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        ErrorResponse body = body(response);
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(429);
        assertThat(body.message()).isEqualTo("Too many OTP requests in a short window.");
        assertThat(body.path()).isEqualTo("/api/admin/auth/otp/request");
    }

    @Test
    void tooManyRequestsException_blankMessage_fallsBackToDefaultMessage() {
        TooManyRequestsException ex = new TooManyRequestsException("");

        ResponseEntity<?> response =
                handler.handleTooManyRequests(ex, requestFor("/api/admin/auth/otp/request"));

        assertThat(response.getBody()).isNotNull();
        assertThat(body(response).message()).isEqualTo("Too many OTP requests in a short window.");
    }

    @Test
    void otpVerificationFailedException_mapsTo401WithGivenMessage() {
        OtpVerificationFailedException ex = new OtpVerificationFailedException("Wrong or expired code.");

        ResponseEntity<?> response =
                handler.handleOtpVerificationFailed(ex, requestFor("/api/admin/auth/otp/verify"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ErrorResponse body = body(response);
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(401);
        assertThat(body.message()).isEqualTo("Wrong or expired code.");
    }

    @Test
    void otpVerificationFailedException_blankMessage_fallsBackToDefaultMessage() {
        OtpVerificationFailedException ex = new OtpVerificationFailedException(null);

        ResponseEntity<?> response =
                handler.handleOtpVerificationFailed(ex, requestFor("/api/admin/auth/otp/verify"));

        assertThat(response.getBody()).isNotNull();
        assertThat(body(response).message()).isEqualTo("Wrong or expired code.");
    }

    @Test
    void submissionValidationException_mapsTo400WithGivenMessage() {
        SubmissionValidationException ex = new SubmissionValidationException("At least one section must be filled in.");

        ResponseEntity<?> response =
                handler.handleSubmissionValidation(ex, requestFor("/api/submissions"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = body(response);
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.message()).isEqualTo("At least one section must be filled in.");
        assertThat(body.path()).isEqualTo("/api/submissions");
    }

    @Test
    void submissionValidationException_withFieldViolations_joinsThemAsFieldColonMessagePairs() {
        SubmissionValidationException ex = new SubmissionValidationException(java.util.List.of(
                new FieldViolation("rollNumber", "must look like CS21B001"),
                new FieldViolation("contactMethods[0].value", "doesn't look like a valid Email contact")));

        ResponseEntity<?> response =
                handler.handleSubmissionValidation(ex, requestFor("/api/submissions"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(body(response).message()).isEqualTo("rollNumber: must look like CS21B001,"
                + " contactMethods[0].value: doesn't look like a valid Email contact");
    }

    @Test
    void submissionValidationException_emptyViolationList_fallsBackToDefaultMessage() {
        SubmissionValidationException ex = new SubmissionValidationException(java.util.List.of());

        ResponseEntity<?> response =
                handler.handleSubmissionValidation(ex, requestFor("/api/submissions"));

        assertThat(response.getBody()).isNotNull();
        assertThat(body(response).message()).isEqualTo("Validation failed");
    }

    @Test
    void submissionValidationException_blankMessage_fallsBackToDefaultMessage() {
        SubmissionValidationException ex = new SubmissionValidationException("");

        ResponseEntity<?> response =
                handler.handleSubmissionValidation(ex, requestFor("/api/submissions"));

        assertThat(response.getBody()).isNotNull();
        assertThat(body(response).message()).isEqualTo("Validation failed");
    }

    @Test
    void testimonialAlreadyExistsException_mapsTo409WithGivenMessage() {
        TestimonialAlreadyExistsException ex =
                new TestimonialAlreadyExistsException("A testimonial already exists for this visitor.");

        ResponseEntity<?> response =
                handler.handleTestimonialAlreadyExists(ex, requestFor("/api/submissions"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ErrorResponse body = body(response);
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.message()).isEqualTo("A testimonial already exists for this visitor.");
        assertThat(body.path()).isEqualTo("/api/submissions");
    }

    @Test
    void testimonialAlreadyExistsException_blankMessage_fallsBackToDefaultMessage() {
        TestimonialAlreadyExistsException ex = new TestimonialAlreadyExistsException(null);

        ResponseEntity<?> response =
                handler.handleTestimonialAlreadyExists(ex, requestFor("/api/submissions"));

        assertThat(response.getBody()).isNotNull();
        assertThat(body(response).message())
                .isEqualTo("A testimonial already exists for this visitor.");
    }

    @Test
    void testimonialNotPendingException_mapsTo409WithGivenMessage() {
        TestimonialNotPendingException ex =
                new TestimonialNotPendingException("Testimonial 42 is not pending and cannot be moderated again.");

        ResponseEntity<?> response =
                handler.handleTestimonialNotPending(ex, requestFor("/api/moderation/testimonials/42/approve"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ErrorResponse body = body(response);
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.message()).isEqualTo("Testimonial 42 is not pending and cannot be moderated again.");
        assertThat(body.path()).isEqualTo("/api/moderation/testimonials/42/approve");
    }

    @Test
    void testimonialNotPendingException_blankMessage_fallsBackToDefaultMessage() {
        TestimonialNotPendingException ex = new TestimonialNotPendingException(null);

        ResponseEntity<?> response =
                handler.handleTestimonialNotPending(ex, requestFor("/api/moderation/testimonials/42/approve"));

        assertThat(response.getBody()).isNotNull();
        assertThat(body(response).message()).isEqualTo("This testimonial is no longer pending.");
    }

    @Test
    void notificationNotSentException_mapsTo503WithGivenMessage() {
        NotificationNotSentException ex = new NotificationNotSentException(
                "The email to the submitter couldn't be sent, so the testimonial was not rejected. Try again later.");

        ResponseEntity<?> response =
                handler.handleNotificationNotSent(ex, requestFor("/api/moderation/testimonials/42/reject"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        ErrorResponse body = body(response);
        assertThat(body.status()).isEqualTo(503);
        assertThat(body.error()).isEqualTo("Service Unavailable");
        assertThat(body.message()).isEqualTo(
                "The email to the submitter couldn't be sent, so the testimonial was not rejected. Try again later.");
        assertThat(body.path()).isEqualTo("/api/moderation/testimonials/42/reject");
    }

    @Test
    void notificationNotSentException_blankMessage_fallsBackToDefaultMessage() {
        ResponseEntity<?> response = handler.handleNotificationNotSent(
                new NotificationNotSentException(" "), requestFor("/api/moderation/testimonials/42/reject"));

        assertThat(body(response).message())
                .isEqualTo("The email couldn't be sent, so nothing was changed. Try again later.");
    }

    /** Never a 500 and never logged as a server error: the service already warned, without the recipient. */
    @Test
    void notificationNotSentException_onAPage_isTheHtml503Page() {
        ResponseEntity<?> response = handler.handleNotificationNotSent(
                new NotificationNotSentException("Not sent."), requestFor("/moderation/queue/42/reject"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat((String) response.getBody()).doesNotContain("Not sent.");
    }

    // -- multipart failures (upload too large / too many parts / unparseable body) --

    @Test
    void maxUploadSizeExceededException_mapsTo413WithoutLeakingInternalDetail() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(
                10_485_760L,
                new IllegalStateException(
                        "org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException: attachment"));

        ResponseEntity<?> response =
                handler.handleMaxUploadSizeExceeded(ex, requestFor("/api/submissions"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        ErrorResponse body = body(response);
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(413);
        assertThat(body.error()).isEqualTo("Payload Too Large");
        assertThat(body.path()).isEqualTo("/api/submissions");
        assertThat(body.message()).isNotBlank();
        assertThat(body.message())
                .doesNotContain("10485760")
                .doesNotContain("tomcat")
                .doesNotContain("FileCountLimitExceededException")
                .doesNotContain("Maximum upload size");
    }

    @Test
    void maxUploadSizeExceededException_unknownLimit_stillMapsTo413() {
        // Spring reports -1 when the servlet container didn't say which limit
        // was hit (e.g. Tomcat's part-count limit rather than a byte size).
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(-1L);

        ResponseEntity<?> response =
                handler.handleMaxUploadSizeExceeded(ex, requestFor("/api/submissions/mine"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(body(response).message()).isNotBlank().doesNotContain("-1");
    }

    @Test
    void otherMultipartException_mapsTo400WithoutLeakingInternalDetail() {
        MultipartException ex = new MultipartException(
                "Failed to parse multipart servlet request",
                new IllegalStateException("org.apache.tomcat.util.http.fileupload.FileUploadException: Stream ended"));

        ResponseEntity<?> response = handler.handleMultipart(ex, requestFor("/api/submissions"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = body(response);
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.path()).isEqualTo("/api/submissions");
        assertThat(body.message())
                .isNotBlank()
                .doesNotContain("tomcat")
                .doesNotContain("Stream ended")
                .doesNotContain("Failed to parse multipart servlet request");
    }

    /**
     * With {@code resolve-lazily}, a {@code @RequestPart} argument makes
     * Spring read the body via {@code getMultipartHeaders}, which wraps
     * Tomcat's limit failure as a plain {@link MultipartException} ("Could
     * not access multipart servlet request") instead of a {@link
     * MaxUploadSizeExceededException} — the exact chain observed for the
     * JSON API. It is still "too large", not a malformed request.
     */
    @Test
    void genericMultipartException_causedByContainerPartCountLimit_mapsTo413() {
        MultipartException ex = new MultipartException(
                "Could not access multipart servlet request",
                new IllegalStateException(new FileCountLimitExceededException("attachment", 500)));

        ResponseEntity<?> response = handler.handleMultipart(ex, requestFor("/api/submissions"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(body(response).message()).doesNotContain("attachment").doesNotContain("tomcat");
    }

    @Test
    void genericMultipartException_causedByContainerFileSizeLimit_mapsTo413() {
        MultipartException ex = new MultipartException(
                "Could not access multipart servlet request",
                new IllegalStateException(
                        new FileSizeLimitExceededException("file too big", 11_000_000L, 10_485_760L)));

        assertThat(handler.handleMultipart(ex, requestFor("/api/submissions")).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void genericMultipartException_causedByContainerRequestSizeLimit_mapsTo413() {
        MultipartException ex = new MultipartException(
                "Could not access multipart servlet request",
                new IllegalStateException(
                        new SizeLimitExceededException("request too big", 120_000_000L, 115_343_360L)));

        assertThat(handler.handleMultipart(ex, requestFor("/api/submissions/mine")).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void genericMultipartException_wrappingMaxUploadSizeExceeded_mapsTo413() {
        MultipartException ex = new MultipartException(
                "Could not access multipart servlet request", new MaxUploadSizeExceededException(-1L));

        assertThat(handler.handleMultipart(ex, requestFor("/api/submissions")).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void multipartException_withoutAnyCause_mapsTo400() {
        MultipartException ex = new MultipartException("Current request is not a multipart request");

        assertThat(handler.handleMultipart(ex, requestFor("/api/submissions")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void multipartException_withCyclicCauseChain_terminatesAndMapsTo400() {
        Exception first = new Exception("first");
        Exception second = new Exception("second", first);
        first.initCause(second);
        MultipartException ex = new MultipartException("Failed to parse multipart servlet request", first);

        assertThat(handler.handleMultipart(ex, requestFor("/api/submissions")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void multipartException_isNotReportedAsGeneric500() throws NoSuchMethodException {
        // Spring picks the closest @ExceptionHandler by exception-type
        // distance, so the dedicated handlers must exist for both types;
        // otherwise these fall through to handleGeneric's 500.
        assertThat(GlobalExceptionHandler.class
                        .getMethod("handleMaxUploadSizeExceeded",
                                MaxUploadSizeExceededException.class, HttpServletRequest.class)
                        .getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class)
                        .value())
                .containsExactly(MaxUploadSizeExceededException.class);
        assertThat(GlobalExceptionHandler.class
                        .getMethod("handleMultipart", MultipartException.class, HttpServletRequest.class)
                        .getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class)
                        .value())
                .containsExactly(MultipartException.class);
    }

    private static class DummyTarget {
        @SuppressWarnings("unused")
        void dummyMethod(String arg) {
        }
    }

    private static ErrorResponse body(ResponseEntity<?> response) {
        return (ErrorResponse) response.getBody();
    }
}
