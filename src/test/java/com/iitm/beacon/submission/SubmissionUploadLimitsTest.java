package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.testsupport.TestImages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-HTTP tests of the servlet container's multipart limits (bug: the
 * submission form posted ~400 parts, Tomcat's default {@code max-part-count}
 * is 50, and the resulting exception surfaced as a JSON 500). MockMvc never
 * goes through Tomcat's multipart parser, so these run against the embedded
 * server on a random port, logged in through the real visitor OTP flow.
 *
 * <p>Not {@code @Transactional} (the server handles requests on its own
 * threads), so every testimonial a test manages to create is deleted again
 * in {@link #deleteCreatedTestimonials()} to keep the shared in-memory
 * database clean for other test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionUploadLimitsTest {

    private static final int CONFIGURED_MAX_PART_COUNT = 500;
    private static final String UPLOAD_ERROR =
            "Your upload was too large or contained too many files. Please try again with fewer or smaller photos.";

    @LocalServerPort
    private int port;

    @MockitoBean
    private OtpMailer otpMailer;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private HttpClient client;
    private final List<String> usedEmails = new ArrayList<>();

    @BeforeEach
    void newBrowser() {
        client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @AfterEach
    void deleteCreatedTestimonials() {
        for (String email : usedEmails) {
            testimonialRepository
                    .findByEmailLookupHash(emailLookupHashService.hash(email))
                    .map(Testimonial::getId)
                    .ifPresent(testimonialRepository::deleteById);
        }
    }

    // -- HTML form: POST /submissions/form --

    @Test
    void noJsFormWithEveryTopicAndItsEmptyPhotoInput_farAbove50Parts_isAccepted() throws Exception {
        String email = loginAsVisitor("limits-all-topics@example.com");
        MultipartBody body = validFormBasics();
        List<String> slugs = allTopicSlugs();
        for (int i = 0; i < slugs.size(); i++) {
            addSectionAsTheNoJsFormSendsIt(body, i, slugs.get(i), "general".equals(slugs.get(i)) ? "Great." : "");
        }
        assertThat(body.partCount()).isGreaterThan(100);

        HttpResponse<String> response = post("/submissions/form", body);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).endsWith("/submissions/confirmation");
        assertThat(testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email))).isPresent();
    }

    @Test
    void exactlyMaxPartCount_isAccepted() throws Exception {
        loginAsVisitor("limits-at-max-parts@example.com");
        MultipartBody body = validFormBasics();
        addSectionAsTheNoJsFormSendsIt(body, 0, "general", "Great.");
        body.padToPartCount(CONFIGURED_MAX_PART_COUNT);

        HttpResponse<String> response = post("/submissions/form", body);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).endsWith("/submissions/confirmation");
    }

    @Test
    void oneMoreThanMaxPartCount_redirectsBackToFormWithReadableError() throws Exception {
        String email = loginAsVisitor("limits-over-max-parts@example.com");
        MultipartBody body = validFormBasics();
        addSectionAsTheNoJsFormSendsIt(body, 0, "general", "Great.");
        body.padToPartCount(CONFIGURED_MAX_PART_COUNT + 1);

        HttpResponse<String> response = post("/submissions/form", body);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).endsWith("/submissions/form");
        HttpResponse<String> formPage = get(location(response));
        assertThat(formPage.statusCode()).isEqualTo(200);
        assertThat(formPage.body()).contains(UPLOAD_ERROR).doesNotContain("FileCountLimitExceeded");
        assertThat(testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email))).isEmpty();
    }

    @Test
    void realPhotoOfSixMegabytes_formerlyOverTheLimit_isAcceptedAndStoredAsWebp() throws Exception {
        String email = loginAsVisitor("limits-6mb-photo@example.com");
        byte[] sixMegabytePng = TestImages.png(TestImages.noise(1450, 1450, 42));
        assertThat(sixMegabytePng.length).isGreaterThan(6 * 1024 * 1024);
        MultipartBody body = validFormBasics();
        body.field("sections[0].topicSlug", "general");
        body.field("sections[0].answerText", "Great.");
        body.file("sections[0].photos", "big.png", "image/png", sixMegabytePng);
        body.field("sections[0].photoTags[0]", "");

        HttpResponse<String> response = post("/submissions/form", body);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).endsWith("/submissions/confirmation");
        assertThat(storedPhotoPathsOf(email)).singleElement().satisfies(path -> assertThat(path).endsWith(".webp"));
    }

    @Test
    void twoPhotosTogetherOverTwentyMegabytes_eachUnderIt_areAccepted_theLimitIsPerPhoto() throws Exception {
        String email = loginAsVisitor("limits-per-photo@example.com");
        byte[] first = TestImages.png(TestImages.noise(1950, 1950, 1));
        byte[] second = TestImages.png(TestImages.noise(1950, 1950, 2));
        assertThat((long) first.length + second.length).isGreaterThan(20L * 1024 * 1024);
        assertThat(Math.max(first.length, second.length)).isLessThan(20 * 1024 * 1024);
        MultipartBody body = validFormBasics();
        body.field("sections[0].topicSlug", "general");
        body.field("sections[0].answerText", "Great.");
        body.file("sections[0].photos", "first.png", "image/png", first);
        body.file("sections[0].photos", "second.png", "image/png", second);
        body.field("sections[0].photoTags[0]", "first");
        body.field("sections[0].photoTags[1]", "second");

        HttpResponse<String> response = post("/submissions/form", body);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).endsWith("/submissions/confirmation");
        assertThat(storedPhotoPathsOf(email)).hasSize(2);
    }

    @Test
    void photoAboveBusinessLimitButBelowMultipartLimit_getsTheFriendlyPhotoSizeError() throws Exception {
        String email = loginAsVisitor("limits-21mb-photo@example.com");
        MultipartBody body = validFormBasics();
        body.field("sections[0].topicSlug", "general");
        body.field("sections[0].answerText", "Great.");
        body.file("sections[0].photos", "big.png", "image/png", new byte[21 * 1024 * 1024]);
        body.field("sections[0].photoTags[0]", "");

        HttpResponse<String> response = post("/submissions/form", body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Photo exceeds the maximum allowed file size (20 MB).");
        assertThat(testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email))).isEmpty();
    }

    @Test
    void photoAboveMultipartFileLimit_redirectsBackToFormWithReadableError() throws Exception {
        loginAsVisitor("limits-over-25mb-photo@example.com");
        MultipartBody body = validFormBasics();
        body.field("sections[0].topicSlug", "general");
        body.field("sections[0].answerText", "Great.");
        body.file("sections[0].photos", "huge.png", "image/png", new byte[25 * 1024 * 1024 + 1024]);

        HttpResponse<String> response = post("/submissions/form", body);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).endsWith("/submissions/form");
        assertThat(get(location(response)).body()).contains(UPLOAD_ERROR);
    }

    // -- JSON API: POST /api/submissions --

    @Test
    void apiCreate_moreThanMaxPartCount_returns413JsonWithoutInternals() throws Exception {
        loginAsVisitor("limits-api-over-max-parts@example.com");
        MultipartBody body = new MultipartBody();
        body.file("payload", "", "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        body.padToPartCount(CONFIGURED_MAX_PART_COUNT + 1);

        HttpResponse<String> response = post("/api/submissions", body);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        assertThat(response.body())
                .contains("\"status\":413")
                .doesNotContain("tomcat")
                .doesNotContain("Exception");
    }

    // -- helpers --

    private String loginAsVisitor(String email) throws Exception {
        usedEmails.add(email);
        HttpResponse<String> requested = send(HttpRequest.newBuilder(uri("/submissions/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)))
                .build());
        assertThat(requested.statusCode()).isEqualTo(302);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), code.capture());

        HttpResponse<String> verified = send(HttpRequest.newBuilder(uri("/submissions/login/code"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                        + "&code=" + URLEncoder.encode(code.getValue(), StandardCharsets.UTF_8)))
                .build());
        assertThat(verified.statusCode()).isEqualTo(302);
        assertThat(location(verified)).endsWith("/submissions/form");
        return email;
    }

    private List<String> storedPhotoPathsOf(String email) {
        return transactionTemplate.execute(status -> testimonialRepository
                .findByEmailLookupHash(emailLookupHashService.hash(email))
                .orElseThrow()
                .getSections()
                .stream()
                .flatMap(section -> section.getPhotos().stream())
                .map(photo -> photo.getFilePath())
                .toList());
    }

    private List<String> allTopicSlugs() {
        List<String> slugs = new ArrayList<>();
        for (TopicCatalogEntryDto entry : submissionService.listTopicCatalog()) {
            if ("GROUP".equals(entry.kind())) {
                entry.subtopics().forEach(pick -> slugs.add(pick.slug()));
            } else {
                slugs.add(entry.slug());
            }
        }
        return slugs;
    }

    private static MultipartBody validFormBasics() {
        return new MultipartBody()
                .field("firstName", "David")
                .field("lastName", "Jones")
                .field("rollNumber", "GE26Z001")
                .field("admissionYear", "2024")
                .field("countryCode", "IN")
                .field("recommendationScore", "8")
                .field("dataProcessingConsent", "true");
    }

    /** One topic block exactly as a browser without JS posts it: text plus its photo input with no file chosen. */
    private static void addSectionAsTheNoJsFormSendsIt(MultipartBody body, int index, String slug, String answer) {
        body.field("sections[" + index + "].topicSlug", slug);
        body.field("sections[" + index + "].answerText", answer);
        body.file("sections[" + index + "].photos", "", "application/octet-stream", new byte[0]);
    }

    private HttpResponse<String> post(String path, MultipartBody body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", body.contentType())
                .POST(body.publisher())
                .build());
    }

    private HttpResponse<String> get(String pathOrUrl) throws Exception {
        URI target = pathOrUrl.startsWith("http") ? URI.create(pathOrUrl) : uri(pathOrUrl);
        return send(HttpRequest.newBuilder(target).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location").orElse("");
    }

    /** Minimal hand-rolled multipart/form-data body so the exact number of parts is under the test's control. */
    private static final class MultipartBody {

        private final String boundary = "----beacon-test-" + UUID.randomUUID();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int parts;

        MultipartBody field(String name, String value) {
            writeHeader("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
            out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
            parts++;
            return this;
        }

        MultipartBody file(String name, String filename, String contentType, byte[] content) {
            writeHeader("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n");
            out.writeBytes(content);
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
            parts++;
            return this;
        }

        /** Adds harmless unknown fields (ignored by data binding) until the body has exactly {@code target} parts. */
        void padToPartCount(int target) {
            while (parts < target) {
                field("padding", "");
            }
        }

        int partCount() {
            return parts;
        }

        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        HttpRequest.BodyPublisher publisher() {
            ByteArrayOutputStream complete = new ByteArrayOutputStream();
            complete.writeBytes(out.toByteArray());
            complete.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            return HttpRequest.BodyPublishers.ofByteArray(complete.toByteArray());
        }

        private void writeHeader(String headerLines) {
            out.writeBytes(("--" + boundary + "\r\n" + headerLines).getBytes(StandardCharsets.UTF_8));
        }
    }
}
