package com.iitm.beacon.common.error;

import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.ClientErrors.assertJsonError;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iitm.beacon.testsupport.TemplateEngines;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link ErrorResponseWriter}: an error answered outside Spring MVC — by a
 * filter, before any handler — in the same two shapes as {@link
 * GlobalExceptionHandler}'s: the JSON {@link ErrorResponse} under {@code
 * /api/**}, the site's HTML error page anywhere else (decision 33).
 */
class ErrorResponseWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ErrorResponseWriter writer = new ErrorResponseWriter(Clock.fixed(NOW, ZoneOffset.UTC),
            objectMapper, new ErrorPageRenderer(TemplateEngines.classpathTemplates()));

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/catalog/topics", "/api/no-such-thing"})
    void apiPath_getsTheJsonErrorResponse(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("Accept", "text/html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(HttpStatus.PAYLOAD_TOO_LARGE, "The request body is too large.", request, response);

        assertJsonError(response, 413, "The request body is too large.", path);
        assertThat(objectMapper.readValue(response.getContentAsString(), ErrorResponse.class).timestamp())
                .isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/admin/login/request", "/gallery/1/contact", "/apis", "/no/such/page"})
    void pagePath_getsTheHtmlErrorPage_whateverTheClientAccepts(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("Accept", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(HttpStatus.PAYLOAD_TOO_LARGE, "The request body is too large.", request, response);

        assertHtmlErrorPage(response, 413);
        assertThat(response.getContentAsString()).doesNotContain("The request body is too large.");
    }

    @Test
    void jsonBody_isUtf8() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/catalog/topics");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(HttpStatus.LENGTH_REQUIRED, "The request must state its Content-Length.", request, response);

        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertJsonError(response, 411, "The request must state its Content-Length.", "/api/catalog/topics");
    }
}
