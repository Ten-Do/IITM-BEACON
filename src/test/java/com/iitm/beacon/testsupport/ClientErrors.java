package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.common.error.ErrorPage;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.HtmlUtils;

/**
 * Assertions for error answers in MockMvc tests: the JSON {@code
 * ErrorResponse} an {@code /api/**} request gets, and the HTML error page a
 * page gets. Both must be free of anything an exception would have said —
 * Java type names, converter messages, stack frames.
 */
public final class ClientErrors {

    /** Text that only an exception could have put into a response. */
    public static final String[] INTERNALS = {
        "Exception", "java.", "Failed to convert", "NumberFormat", "For input string", "at com.",
        "org.springframework", "Whitelabel", "Index of out of bounds", "SubmissionFormCommand"
    };

    private static final ObjectMapper JSON = new ObjectMapper();

    private ClientErrors() {
    }

    /** The JSON {@code ErrorResponse}: exactly its five fields, the status, its reason phrase, the message. */
    public static void assertJsonError(MockHttpServletResponse response, int status, String message, String path)
            throws Exception {
        assertThat(response.getStatus()).as("status").isEqualTo(status);
        assertThat(response.getContentType()).as("content type").isNotNull();
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_JSON))
                .as("JSON, not %s", response.getContentType())
                .isTrue();
        String text = response.getContentAsString();
        assertThat(text).doesNotContain(INTERNALS);
        Map<String, Object> body = JSON.readValue(text, new TypeReference<>() {
        });
        assertThat(body).containsOnlyKeys("timestamp", "status", "error", "message", "path");
        assertThat(body)
                .containsEntry("status", status)
                .containsEntry("error", HttpStatus.valueOf(status).getReasonPhrase())
                .containsEntry("message", message)
                .containsEntry("path", path);
    }

    /** The site's HTML error page for {@code status}, never JSON. */
    public static void assertHtmlErrorPage(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as("status").isEqualTo(status);
        assertThat(response.getContentType()).as("content type").isNotNull();
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.TEXT_HTML))
                .as("HTML, not %s", response.getContentType())
                .isTrue();
        ErrorPage page = ErrorPage.forStatus(status);
        assertThat(response.getContentAsString())
                .contains("<h1 class=\"gallery-empty-title\">" + HtmlUtils.htmlEscape(page.title()) + "</h1>")
                .contains(HtmlUtils.htmlEscape(page.message()))
                .contains("Back to homepage")
                .doesNotContain("\"timestamp\"")
                .doesNotContain(INTERNALS);
    }
}
