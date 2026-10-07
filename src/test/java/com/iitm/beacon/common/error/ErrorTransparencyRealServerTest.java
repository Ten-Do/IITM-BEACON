package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.gallery.GalleryService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * NFR-ERROR-TRANSPARENCY through the embedded Tomcat, not MockMvc: what a
 * client actually receives when a service fails with internals in its
 * message — for JSON clients and for browsers, on API URLs and on pages,
 * including the cases where the application's own JSON error body isn't
 * acceptable and the container's {@code /error} dispatch answers instead.
 * Every such response is a 500 that names no exception, class, stack frame
 * or SQL: the JSON {@code ErrorResponse} for the API, the site's HTML error
 * page for a page. The gallery service is replaced by a mock that always
 * fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorTransparencyRealServerTest {

    private static final String SECRET = "secret at com.x.Y(Y.java:1)";

    private static final String[] INTERNALS = {
        "secret", "at com.", "Exception", "Y.java", "java.lang", "Caused by", "trace"
    };

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GalleryService galleryService;

    @BeforeEach
    void everyGalleryCallFails() {
        RuntimeException failure = new IllegalStateException(SECRET, new RuntimeException(SECRET));
        when(galleryService.browse(any(), any(), any(), any(), any())).thenThrow(failure);
        when(galleryService.getDetail(anyLong())).thenThrow(failure);
        when(galleryService.listCountriesWithApproved()).thenThrow(failure);
    }

    private HttpResponse<String> get(String path, String accept) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", accept).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest
    @CsvSource({
        "/api/gallery/testimonials, application/json",
        "/api/gallery/testimonials/7, application/json",
        "/api/gallery/countries, application/json",
        "/api/gallery/testimonials, */*"
    })
    void jsonClient_getsTheGenericErrorResponse(String path, String accept) throws Exception {
        HttpResponse<String> response = get(path, accept);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain(INTERNALS);
        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {
        });
        assertThat(body).containsOnlyKeys("timestamp", "status", "error", "message", "path");
        assertThat(body).containsEntry("message", "An unexpected error occurred").containsEntry("path", path);
    }

    @ParameterizedTest
    @CsvSource({
        "/gallery, text/html",
        "/gallery/7, text/html",
        "'/gallery?trace=true&message=true', text/html",
        "/api/gallery/testimonials, text/html",
        "/gallery, 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'",
        "/api/gallery/testimonials, application/xml"
    })
    void browserOrOtherClient_getsA500WithNoInternals(String path, String accept) throws Exception {
        HttpResponse<String> response = get(path, accept);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain(INTERNALS);
    }

    /** A page answers the site's HTML error page whatever the client accepts; the API answers JSON. */
    @ParameterizedTest
    @CsvSource({
        "/gallery, text/html",
        "/gallery/7, 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'",
        "/gallery, application/json",
        "/gallery, */*"
    })
    void pageFailure_isTheSiteHtmlErrorPage(String path, String accept) throws Exception {
        HttpResponse<String> response = get(path, accept);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/html"));
        assertThat(response.body())
                .contains("<h1 class=\"gallery-empty-title\">Something went wrong</h1>", "Back to homepage")
                .doesNotContain("Whitelabel");
    }

    /** A client error on a page, through the real container: the HTML page, its status, and its headers. */
    @Test
    void pageWithAnUnsupportedMethod_isTheHtml405Page_withAllow() throws Exception {
        String token = UUID.randomUUID().toString();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/login"))
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .header("Accept", "*/*")
                        .header("Cookie", "XSRF-TOKEN=" + token)
                        .header("X-XSRF-TOKEN", token)
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(
                allow -> assertThat(allow).contains("GET").doesNotContain("PUT"));
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/html"));
        assertThat(response.body()).contains("<h1 class=\"gallery-empty-title\">Action not allowed</h1>");
    }

    @Test
    void unknownPage_isTheHtml404Page() throws Exception {
        HttpResponse<String> response = get("/admin/login/nothing-here", "application/json");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("<h1 class=\"gallery-empty-title\">Page not found</h1>");
    }

    @ParameterizedTest
    @CsvSource({"text/html", "application/xml", "*/*"})
    void apiFailure_isTheJsonErrorResponse_whateverTheClientAccepts(String accept) throws Exception {
        HttpResponse<String> response = get("/api/gallery/testimonials", accept);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(objectMapper.readValue(response.body(), Map.class)).containsEntry("status", 500);
    }
}
