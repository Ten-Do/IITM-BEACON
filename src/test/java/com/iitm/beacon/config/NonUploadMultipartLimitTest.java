package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.iitm.beacon.testsupport.Csrf;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.unit.DataSize;

/**
 * A multipart body anywhere but the three submission endpoints is refused
 * before anything reads it, unless it is small ({@code
 * beacon.web.non-upload-multipart-max-size}, 16 KB): the CSRF check reads
 * the token from the body, so the servlet container used to parse any such
 * body — anonymous, on any path — up to the 1010 MB upload limit, writing
 * its files to disk. Real HTTP over a raw socket: each large request
 * declares a few MB but sends only its first 64 KB, so an answer at all
 * proves nothing waited for — let alone parsed — the rest of the body. The
 * submission endpoints still take large bodies ({@code
 * submission.SubmissionUploadLimitsTest} uploads real photos through them).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NonUploadMultipartLimitTest {

    private static final String BOUNDARY = "----beacon-limit-" + UUID.randomUUID();
    private static final long FEW_MEGABYTES = 5L * 1024 * 1024;
    private static final int SENT_BYTES = 64 * 1024;

    @LocalServerPort
    private int port;

    @Value("${beacon.web.non-upload-multipart-max-size}")
    private DataSize limit;

    @MockitoBean
    private OtpMailer otpMailer;

    private CookieManager cookies;
    private HttpClient browser;

    @BeforeEach
    void newBrowser() {
        cookies = new CookieManager();
        browser = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @ParameterizedTest
    @CsvSource({
        "POST, /admin/login/request, text/html",
        "POST, /submissions/login, text/html",
        "POST, /gallery/1/contact, text/html",
        "POST, /moderation/queue/1/reject, text/html",
        "POST, /no/such/path, text/html",
        "GET, /submissions/form, text/html",
        "POST, /api/catalog/topics, application/json",
        "POST, /api/admin/auth/otp/request, application/json",
        "PATCH, /api/submissions/mine, application/json"
    })
    void largeMultipartBody_elsewhere_isRefused413_beforeTheBodyIsRead(String method, String path, String type)
            throws Exception {
        RawResponse response = sendTheStartOf(method, path, false);

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.header("Content-Type")).startsWith(type);
        // Answered inside the security filter chain: the usual security headers are there.
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    /** The attack itself: no token anywhere but (supposedly) in the body, which the CSRF check would parse. */
    @ParameterizedTest
    @CsvSource({"POST, /gallery/1/contact", "POST, /api/catalog/topics", "PUT, /no/such/path"})
    void largeMultipartBody_withoutAHeaderToken_isRefused413_beforeTheCsrfCheckReadsIt(String method, String path)
            throws Exception {
        assertThat(sendTheStartOf(method, path, false, false).status()).isEqualTo(413);
    }

    @Test
    void multipartBodyOfUnknownLength_elsewhere_isRefused411_beforeTheBodyIsRead() throws Exception {
        RawResponse response = sendTheStartOf("POST", "/admin/login/request", true);

        assertThat(response.status()).isEqualTo(411);
    }

    /** Not refused by the limit: an anonymous client then gets the endpoint's usual 401 or login redirect. */
    @ParameterizedTest
    @CsvSource({"POST, /api/submissions, 401", "PUT, /api/submissions/mine, 401", "POST, /submissions/form, 302"})
    void largeMultipartBody_toASubmissionEndpoint_isNotRefusedByTheLimit(String method, String path, int status)
            throws Exception {
        assertThat(sendTheStartOf(method, path, false).status()).isEqualTo(status);
    }

    /** A small multipart form is still read in full: its CSRF field and email are used as from any form. */
    @Test
    void multipartBodyOfExactlyTheLimit_elsewhere_isReadAsUsual() throws Exception {
        byte[] body = adminLoginFormOfExactly(limit.toBytes());

        HttpResponse<String> response = postMultipart("/admin/login/request", body);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).startsWith("/admin/login/code"));
    }

    @Test
    void multipartBodyOfOneByteOverTheLimit_elsewhere_isRefused() throws Exception {
        byte[] body = adminLoginFormOfExactly(limit.toBytes() + 1);

        assertThat(postMultipart("/admin/login/request", body).statusCode()).isEqualTo(413);
    }

    // -- helpers --

    private record RawResponse(int status, Map<String, String> headers) {

        String header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
        }
    }

    /**
     * Sends the head of a multipart request declaring {@link #FEW_MEGABYTES}
     * (or chunked), then only the first {@link #SENT_BYTES} of its body, and
     * reads the answer: a server still waiting for the rest would let the
     * read time out.
     */
    private RawResponse sendTheStartOf(String method, String path, boolean chunked) throws IOException {
        return sendTheStartOf(method, path, chunked, true);
    }

    /**
     * {@code headerToken}: with a valid CSRF token in the header (the CSRF
     * check then has no reason to read the body, but a controller would);
     * without, the CSRF check looks for the token in the body.
     */
    private RawResponse sendTheStartOf(String method, String path, boolean chunked, boolean headerToken)
            throws IOException {
        String token = UUID.randomUUID().toString();
        StringBuilder head = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(port).append("\r\n")
                .append("Content-Type: multipart/form-data; boundary=").append(BOUNDARY).append("\r\n")
                .append("Connection: close\r\n");
        if (headerToken) {
            head.append("Cookie: XSRF-TOKEN=").append(token).append("\r\n")
                    .append("X-XSRF-TOKEN: ").append(token).append("\r\n");
        }
        head.append(chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: " + FEW_MEGABYTES + "\r\n");
        head.append("\r\n");
        ByteArrayOutputStream start = new ByteArrayOutputStream();
        start.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.bin\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        start.writeBytes(new byte[SENT_BYTES - start.size()]);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            if (chunked) {
                out.write((Integer.toHexString(start.size()) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(start.toByteArray());
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            } else {
                out.write(start.toByteArray());
            }
            out.flush();
            try {
                return readHead(socket.getInputStream());
            } catch (SocketTimeoutException ex) {
                return fail("No answer while the rest of the body was missing: the server waited for it");
            }
        }
    }

    private static RawResponse readHead(InputStream in) throws IOException {
        String statusLine = readLine(in);
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        return new RawResponse(Integer.parseInt(statusLine.split(" ")[1]), headers);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != -1 && c != '\n'; c = in.read()) {
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    /**
     * The admin login form as a multipart body of exactly {@code size}
     * bytes — the rendered page's CSRF token, the email, and a padding field
     * the controller ignores.
     */
    private byte[] adminLoginFormOfExactly(long size) throws Exception {
        HttpResponse<String> page = browser.send(
                HttpRequest.newBuilder(uri("/admin/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String csrf = Csrf.hiddenFieldValues(page.body()).get(0);
        byte[] withoutPadding = adminLoginForm(csrf, "");
        int padding = Math.toIntExact(size - withoutPadding.length);
        assertThat(padding).as("room for padding").isNotNegative();
        byte[] body = adminLoginForm(csrf, "x".repeat(padding));
        assertThat(body).hasSize(Math.toIntExact(size));
        return body;
    }

    private static byte[] adminLoginForm(String csrf, String padding) {
        String body = field(Csrf.PARAMETER, csrf) + field("email", "admin@example.com") + field("padding", padding)
                + "--" + BOUNDARY + "--\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String field(String name, String value) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    private HttpResponse<String> postMultipart(String path, byte[] body) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
