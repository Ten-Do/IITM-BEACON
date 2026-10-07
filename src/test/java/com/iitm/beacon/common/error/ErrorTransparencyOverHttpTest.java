package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.gallery.GalleryService;
import jakarta.servlet.RequestDispatcher;
import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * NFR-ERROR-TRANSPARENCY over HTTP, in the full context: an unexpected
 * exception deep in a service — its message naming internals, a stack-frame
 * look-alike, SQL — reaches a JSON client only as the documented {@code
 * ErrorResponse} (exactly its five fields, status 500, a generic message).
 * Spring Boot's own {@code /error} path, where the servlet container sends
 * what escapes the application (e.g. a failing page, whose JSON error body
 * a browser doesn't accept), adds no trace, exception or message either —
 * not even when the caller asks for them with {@code ?trace=true}. MockMvc
 * never performs that container dispatch itself, so it is simulated here
 * with the servlet error attributes; {@link ErrorTransparencyRealServerTest}
 * goes through the real one. A page's failure is the site's HTML error page
 * instead, and so is what reaches {@code /error} from a browser. The gallery
 * service is replaced by a mock that fails on demand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ErrorTransparencyOverHttpTest {

    private static final String SECRET = "secret at com.x.Y(Y.java:1)";

    /** Nothing of these may reach a client. */
    private static final String[] INTERNALS = {
        "secret", "at com.", "Exception", "Y.java", "java.lang", "SELECT", "testimonial_email_key", "Caused by"
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GalleryService galleryService;

    static Stream<Arguments> unexpectedFailures() {
        return Stream.of(
                Arguments.of(Named.of("RuntimeException naming a stack frame", new RuntimeException(SECRET))),
                Arguments.of(Named.of("wrapped SQLException",
                        new IllegalStateException(SECRET, new SQLException("SELECT secret FROM testimonial")))),
                Arguments.of(Named.of("constraint violation from the database",
                        new DataIntegrityViolationException("could not execute statement; secret: duplicate key"
                                + " value violates unique constraint \"testimonial_email_key\""))),
                Arguments.of(Named.of("NullPointerException without a message", new NullPointerException())));
    }

    private Map<String, Object> json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readValue(response.getContentAsString(), new TypeReference<>() {
        });
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private void assertGeneric500(MockHttpServletResponse response, String path) throws Exception {
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).doesNotContain(INTERNALS);
        Map<String, Object> body = json(response);
        assertThat(body).containsOnlyKeys("timestamp", "status", "error", "message", "path");
        assertThat(body).containsEntry("status", 500)
                .containsEntry("error", "Internal Server Error")
                .containsEntry("message", "An unexpected error occurred")
                .containsEntry("path", path);
    }

    @ParameterizedTest
    @MethodSource("unexpectedFailures")
    void restEndpoint_unexpectedException_isAGeneric500ErrorResponse_withNoInternals(RuntimeException failure)
            throws Exception {
        when(galleryService.browse(any(), any(), any(), any(), any())).thenThrow(failure);

        assertGeneric500(
                perform(get("/api/gallery/testimonials").accept(MediaType.APPLICATION_JSON)),
                "/api/gallery/testimonials");
    }

    @ParameterizedTest
    @MethodSource("unexpectedFailures")
    void restEndpointWithAPathVariable_unexpectedException_isAGeneric500ErrorResponse(RuntimeException failure)
            throws Exception {
        when(galleryService.getDetail(anyLong())).thenThrow(failure);

        assertGeneric500(perform(get("/api/gallery/testimonials/7")), "/api/gallery/testimonials/7");
    }

    // -- Spring Boot's /error path --

    private static MockHttpServletRequestBuilder errorDispatch(MediaType accept) {
        return get("/error")
                .accept(accept)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(SECRET))
                .requestAttr(RequestDispatcher.ERROR_MESSAGE, SECRET)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/gallery/testimonials");
    }

    @Test
    void errorPath_json_hasNoTraceExceptionOrMessage() throws Exception {
        MockHttpServletResponse response = perform(errorDispatch(MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).doesNotContain(INTERNALS);
        assertThat(json(response)).doesNotContainKeys("trace", "exception", "message", "errors");
    }

    @ParameterizedTest
    @ValueSource(strings = {"trace", "message", "errors"})
    void errorPath_json_askingForDetailsWithAParameter_stillGetsNone(String parameter) throws Exception {
        MockHttpServletResponse response = perform(errorDispatch(MediaType.APPLICATION_JSON).param(parameter, "true"));

        assertThat(response.getContentAsString()).doesNotContain(INTERNALS);
        assertThat(json(response)).doesNotContainKeys("trace", "exception", "message", "errors");
    }

    @Test
    void errorPath_html_showsNoTraceExceptionOrMessage() throws Exception {
        MockHttpServletResponse response =
                perform(errorDispatch(MediaType.TEXT_HTML).param("trace", "true").param("message", "true"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isNotBlank().doesNotContain(INTERNALS);
    }

    /** What escapes the application and reaches a browser through /error is the site's error page, not Boot's. */
    @ParameterizedTest
    @CsvSource({"500, Something went wrong", "404, Page not found", "403, Access denied", "400, Bad request"})
    void errorPath_html_isTheSiteErrorPageForTheStatus(int status, String title) throws Exception {
        MockHttpServletResponse response = perform(get("/error")
                .accept(MediaType.TEXT_HTML)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/gallery"));

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getContentAsString())
                .contains("<h1 class=\"gallery-empty-title\">" + title + "</h1>", "Back to homepage")
                .doesNotContain("Whitelabel");
    }

    // -- a page: the HTML error page, never JSON --

    @ParameterizedTest
    @MethodSource("unexpectedFailures")
    void page_unexpectedException_isTheHtml500Page_withNoInternals(RuntimeException failure) throws Exception {
        when(galleryService.getDetail(anyLong())).thenThrow(failure);

        MockHttpServletResponse response = perform(get("/gallery/7").accept(MediaType.APPLICATION_JSON));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getContentAsString())
                .contains("<h1 class=\"gallery-empty-title\">Something went wrong</h1>")
                .doesNotContain(INTERNALS)
                .doesNotContain("\"timestamp\"");
    }
}
