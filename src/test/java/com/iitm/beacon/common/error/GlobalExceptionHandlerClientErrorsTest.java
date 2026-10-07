package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iitm.beacon.testsupport.TemplateEngines;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.InvalidPropertyException;
import org.springframework.beans.NullValueInNestedPathException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * {@link GlobalExceptionHandler} for client errors Spring MVC raises itself
 * (BL-029, BL-037, BL-014) — before a handler runs, or while it binds its
 * arguments — and the choice of representation: a request under {@code
 * /api/} gets the JSON {@link ErrorResponse}, any other request (a page)
 * the HTML error page, with the same status and headers. Every message is
 * fixed: never the exception's own text, which names Java types, methods
 * and the rejected input. None of these is logged as a server error.
 */
class GlobalExceptionHandlerClientErrorsTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:00:00Z");

    /** Text of the exceptions below that must never reach a client. */
    private static final String[] INTERNALS = {
        "Exception", "java.", "Failed to convert", "NumberFormat", "For input string", "abc", "Long",
        "Index of out of bounds", "sections[256]", "SubmissionFormCommand", "pending.", "text/plain", "PUT"
    };

    private final ErrorPageRenderer renderer = new ErrorPageRenderer(TemplateEngines.classpathTemplates());
    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), renderer);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger handlerLogger;

    @BeforeEach
    void captureLogs() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        handlerLogger.detachAppender(logs);
    }

    private List<ILoggingEvent> errorLogs() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    // -- fixtures --

    /** Handler methods whose parameters the exceptions below are about. */
    @SuppressWarnings("unused")
    private static final class Endpoints {
        void detail(@PathVariable Long id) {
        }

        void browse(@RequestParam(required = false) List<Long> topicIds, int page) {
        }

        void pending(@Min(0) int page, @Min(1) @Max(100) int size) {
        }
    }

    private static MethodParameter parameter(String method, int index) {
        for (Method candidate : Endpoints.class.getDeclaredMethods()) {
            if (candidate.getName().equals(method)) {
                return new MethodParameter(candidate, index);
            }
        }
        throw new IllegalArgumentException(method);
    }

    private static MethodArgumentTypeMismatchException pathIdMismatch(String value) {
        return new MethodArgumentTypeMismatchException(value, Long.class, "id", parameter("detail", 0),
                new NumberFormatException("For input string: \"" + value + "\""));
    }

    private static MethodArgumentTypeMismatchException topicIdsMismatch() {
        return new MethodArgumentTypeMismatchException("abc", List.class, "topicIds", parameter("browse", 0),
                new NumberFormatException("For input string: \"abc\""));
    }

    private static InvalidPropertyException autoGrowLimit() {
        return new InvalidPropertyException(Endpoints.class, "sections[256]",
                "Index of out of bounds in property path 'sections[256]' of SubmissionFormCommand",
                new IndexOutOfBoundsException("Index 256 out of bounds for length 256"));
    }

    private static ConstraintViolationException pagingViolations(int page, int size) throws Exception {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Method pending = Endpoints.class.getDeclaredMethod("pending", int.class, int.class);
            Set<ConstraintViolation<Endpoints>> violations = factory.getValidator().forExecutables()
                    .validateParameters(new Endpoints(), pending, new Object[] {page, size});
            return new ConstraintViolationException(violations);
        }
    }

    private static MockHttpServletRequest api(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private static MockHttpServletRequest page(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setPreferredLocales(List.of(Locale.ENGLISH));
        return request;
    }

    private static ErrorResponse json(ResponseEntity<?> response) {
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);
        return (ErrorResponse) response.getBody();
    }

    private static void assertNoInternals(String text) {
        assertThat(text).doesNotContain(INTERNALS);
    }

    // -- the API: JSON with a fixed message --

    @ParameterizedTest
    @ValueSource(strings = {"abc", "99999999999999999999", "1.5", "-", "0x"})
    void pathIdThatIsNoNumber_isA404_asForAnUnknownId(String id) {
        ResponseEntity<?> response =
                handler.handleArgumentTypeMismatch(pathIdMismatch(id), api("/api/gallery/testimonials/" + id));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponse body = json(response);
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.message()).isEqualTo("Resource not found");
        assertThat(body.timestamp()).isEqualTo(FIXED_INSTANT);
        assertThat(body.path()).isEqualTo("/api/gallery/testimonials/" + id);
    }

    @Test
    void queryParameterThatCantBeConverted_isA400NamingTheParameterOnly() {
        ResponseEntity<?> response =
                handler.handleArgumentTypeMismatch(topicIdsMismatch(), api("/api/gallery/testimonials"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = json(response);
        assertThat(body.error()).isEqualTo("Bad Request");
        assertThat(body.message()).isEqualTo("Invalid value for parameter 'topicIds'.");
        assertNoInternals(body.message());
    }

    @Test
    void parameterWithoutAnyAnnotation_isAQueryParameter_soA400() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", int.class, "page", parameter("browse", 1), new NumberFormatException("abc"));

        ResponseEntity<?> response = handler.handleArgumentTypeMismatch(ex, api("/api/gallery/testimonials"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).message()).isEqualTo("Invalid value for parameter 'page'.");
    }

    @Test
    void unsupportedMethod_isA405_withTheAllowHeader() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "POST"));

        ResponseEntity<?> response = handler.handleMethodNotSupported(ex, api("/api/submissions/otp/request"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        ErrorResponse body = json(response);
        assertThat(body.error()).isEqualTo("Method Not Allowed");
        assertThat(body.message()).isEqualTo("This method is not supported for this resource.");
    }

    @Test
    void unsupportedContentType_isA415_listingTheAcceptedTypes() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON), HttpMethod.POST);

        ResponseEntity<?> response =
                handler.handleMediaTypeNotSupported(ex, api("/api/admin/auth/otp/request"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
        ErrorResponse body = json(response);
        assertThat(body.message()).isEqualTo("This content type is not supported for this resource.");
        assertNoInternals(body.message());
    }

    @Test
    void unacceptableResponseType_isA406_stillAnsweredAsJson() {
        HttpMediaTypeNotAcceptableException ex =
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<?> response =
                handler.handleMediaTypeNotAcceptable(ex, api("/api/analytics/summary"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        ErrorResponse body = json(response);
        assertThat(body.error()).isEqualTo("Not Acceptable");
        assertThat(body.message()).isEqualTo("This resource is only available as JSON.");
    }

    @Test
    void missingRequiredParameter_isA400NamingIt() {
        ResponseEntity<?> response = handler.handleMissingParameter(
                new MissingServletRequestParameterException("code", "String"), api("/api/somewhere"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).message()).isEqualTo("Required parameter 'code' is missing.");
    }

    @Test
    void missingRequiredPart_isA400NamingIt() {
        ResponseEntity<?> response = handler.handleMissingPart(
                new MissingServletRequestPartException("payload"), api("/api/submissions"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).message()).isEqualTo("Required part 'payload' is missing.");
    }

    static Stream<Arguments> unbindableProperties() {
        return Stream.of(
                Arguments.of(Named.of("index past the binder's auto-grow limit", autoGrowLimit())),
                Arguments.of(Named.of("null in a nested path", new NullValueInNestedPathException(
                        Endpoints.class, "sections[0].photos", "Value of nested property 'sections[0]' is null"))));
    }

    @ParameterizedTest
    @MethodSource("unbindableProperties")
    void fieldTheDataBinderCantBind_isA400_withAFixedMessage(InvalidPropertyException ex) {
        ResponseEntity<?> response = handler.handleInvalidProperty(ex, api("/api/somewhere"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = json(response);
        assertThat(body.message()).isEqualTo("The request has a field that can't be read.");
        assertNoInternals(body.message());
    }

    // -- Bean Validation of method parameters: the parameter, never the Java method --

    @Test
    void constraintViolationOfAParameter_namesTheParameter_notTheJavaMethod() throws Exception {
        ConstraintViolationException ex = pagingViolations(-1, 20);
        assertThat(ex.getMessage()).as("precondition: the exception's own text").contains("pending.page");

        ResponseEntity<?> response = handler.handleConstraintViolation(ex, api("/api/moderation/testimonials/pending"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).message()).isEqualTo("page: must be greater than or equal to 0");
    }

    @Test
    void severalViolations_areAllListed_inAStableOrder() throws Exception {
        ResponseEntity<?> response = handler.handleConstraintViolation(
                pagingViolations(-1, 101), api("/api/moderation/testimonials/pending"));

        assertThat(json(response).message())
                .isEqualTo("page: must be greater than or equal to 0, size: must be less than or equal to 100");
    }

    @Test
    void constraintViolationWithoutViolations_fallsBackToAFixedMessage_neverItsOwnText() {
        ConstraintViolationException ex = new ConstraintViolationException(
                "pending.page: java.lang.Integer at com.x.Y(Y.java:1)", Set.of());

        ResponseEntity<?> response = handler.handleConstraintViolation(ex, api("/api/somewhere"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).message()).isEqualTo("Validation failed");
    }

    // -- any other client error Spring reports with its own status --

    @Test
    void otherSpringClientError_keepsItsStatus_withAFixedMessage() throws Exception {
        UnsatisfiedServletRequestParameterException ex = new UnsatisfiedServletRequestParameterException(
                new String[] {"mode=edit"}, Map.of("mode", new String[] {"abc"}));

        ResponseEntity<?> response = handler.handleGeneric(ex, api("/api/somewhere"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = json(response);
        assertThat(body.message()).isEqualTo("The request could not be processed.");
        assertNoInternals(body.message());
        assertThat(errorLogs()).isEmpty();
    }

    @Test
    void pathIdThatConvertsToNothing_isA404_likeAMalformedOne() {
        // A blank id ("/api/gallery/testimonials/%20") converts to null, which Spring reports as missing.
        MissingPathVariableException ex = new MissingPathVariableException("id", parameter("detail", 0), true);

        ResponseEntity<?> response = handler.handleMissingPathVariable(ex, api("/api/gallery/testimonials/%20"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(response).message()).isEqualTo("Resource not found");
        assertThat(errorLogs()).isEmpty();
    }

    @Test
    void pathVariableTheRouteDoesntHave_isOurBug_soTheGeneric500_loggedAsAnError() {
        MissingPathVariableException ex = new MissingPathVariableException("id", parameter("detail", 0));

        ResponseEntity<?> response = handler.handleMissingPathVariable(ex, api("/api/gallery/testimonials/1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(json(response).message()).isEqualTo("An unexpected error occurred");
        assertThat(errorLogs()).hasSize(1);
    }

    @Test
    void springServerErrorWithoutAHandlerOfItsOwn_isStillTheGeneric500_loggedAsAnError() {
        MissingPathVariableException ex = new MissingPathVariableException("id", parameter("detail", 0));

        ResponseEntity<?> response = handler.handleGeneric(ex, api("/api/gallery/testimonials/1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(json(response).message()).isEqualTo("An unexpected error occurred");
        assertThat(errorLogs()).hasSize(1);
    }

    // -- none of the client errors is logged as a server error --

    static Stream<Arguments> clientErrors() {
        return Stream.of(
                clientError("path id", (h, r) -> h.handleArgumentTypeMismatch(pathIdMismatch("abc"), r)),
                clientError("blank path id", (h, r) -> h.handleMissingPathVariable(
                        new MissingPathVariableException("id", parameter("detail", 0), true), r)),
                clientError("query parameter", (h, r) -> h.handleArgumentTypeMismatch(topicIdsMismatch(), r)),
                clientError("method", (h, r) -> h.handleMethodNotSupported(
                        new HttpRequestMethodNotSupportedException("PUT", List.of("GET")), r)),
                clientError("content type", (h, r) -> h.handleMediaTypeNotSupported(
                        new HttpMediaTypeNotSupportedException(
                                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON), HttpMethod.POST), r)),
                clientError("accept", (h, r) -> h.handleMediaTypeNotAcceptable(
                        new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), r)),
                clientError("missing parameter", (h, r) -> h.handleMissingParameter(
                        new MissingServletRequestParameterException("code", "String"), r)),
                clientError("missing part", (h, r) -> h.handleMissingPart(
                        new MissingServletRequestPartException("payload"), r)),
                clientError("unbindable field", (h, r) -> h.handleInvalidProperty(autoGrowLimit(), r)),
                clientError("unknown path", (h, r) -> h.handleNoResourceFound(
                        new NoResourceFoundException(HttpMethod.GET, "catalog/topics/abc"), r)));
    }

    private static Arguments clientError(
            String name, BiFunction<GlobalExceptionHandler, MockHttpServletRequest, ResponseEntity<?>> handling) {
        return Arguments.of(Named.of(name, handling));
    }

    @ParameterizedTest
    @MethodSource("clientErrors")
    void clientError_isNeverLoggedAsAnError_onTheApiOrAPage(
            BiFunction<GlobalExceptionHandler, MockHttpServletRequest, ResponseEntity<?>> handling) {
        ResponseEntity<?> apiAnswer = handling.apply(handler, api("/api/somewhere"));
        ResponseEntity<?> pageAnswer = handling.apply(handler, page("/somewhere"));

        assertThat(apiAnswer.getStatusCode().is4xxClientError()).isTrue();
        assertThat(pageAnswer.getStatusCode()).isEqualTo(apiAnswer.getStatusCode());
        assertThat(errorLogs()).isEmpty();
    }

    // -- a page: the HTML error page, same status and headers --

    @ParameterizedTest
    @MethodSource("clientErrors")
    void onAPage_everyClientError_isTheHtmlErrorPageForItsStatus(
            BiFunction<GlobalExceptionHandler, MockHttpServletRequest, ResponseEntity<?>> handling) {
        ResponseEntity<?> response = handling.apply(handler, page("/gallery"));

        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("text/html;charset=UTF-8"));
        assertThat(response.getBody()).isInstanceOf(String.class)
                .isEqualTo(renderer.html(response.getStatusCode(), Locale.ENGLISH));
        assertNoInternals((String) response.getBody());
    }

    @Test
    void onAPage_aPathIdThatIsNoNumber_isThe404Page() {
        ResponseEntity<?> response = handler.handleArgumentTypeMismatch(
                pathIdMismatch("abc"), page("/moderation/queue/abc/approve"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((String) response.getBody()).contains("Page not found");
    }

    @Test
    void onAPage_anUnsupportedMethod_isThe405Page_withTheAllowHeader() {
        ResponseEntity<?> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "POST")), page("/admin/login"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat((String) response.getBody()).contains("Action not allowed");
    }

    @Test
    void onAPage_unacceptableAndUnsupportedMediaTypes_areThe406And415Pages() {
        ResponseEntity<?> notAcceptable = handler.handleMediaTypeNotAcceptable(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_HTML)), page("/gallery"));
        ResponseEntity<?> unsupported = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
                        List.of(MediaType.APPLICATION_FORM_URLENCODED), HttpMethod.POST),
                page("/admin/login/request"));

        assertThat(notAcceptable.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat((String) notAcceptable.getBody()).contains("Format not available");
        assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(unsupported.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_FORM_URLENCODED);
        assertThat((String) unsupported.getBody()).contains("Unsupported format");
    }

    @Test
    void onAPage_anApplicationNotFound_isThe404Page_notJson() {
        ResponseEntity<?> response =
                handler.handleNotFound(new NotFoundException("Testimonial 7 not found"), page("/gallery/7"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((String) response.getBody()).contains("Page not found").doesNotContain("Testimonial 7");
    }

    @Test
    void onAPage_anUnexpectedFailure_isThe500Page_loggedAsAnError() {
        ResponseEntity<?> response = handler.handleGeneric(
                new IllegalStateException("secret at com.x.Y(Y.java:1)"), page("/gallery"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat((String) response.getBody()).contains("Something went wrong").doesNotContain("secret", "Y.java");
        assertThat(errorLogs()).hasSize(1);
    }
}
