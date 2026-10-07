package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import com.iitm.beacon.testsupport.Csrf;
import com.iitm.beacon.testsupport.SpoofedUploads;
import com.iitm.beacon.testsupport.TestImages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NFR-UPLOAD-SPOOFING at the submission endpoints, end to end through the
 * real context: a non-image posing as {@code fake.jpg}/{@code image/jpeg} is
 * refused "the same way as any other invalid upload" (UC-CREATE-TESTIMONIAL,
 * UC-EDIT-TESTIMONIAL alternate flows) — a 400 with the unsupported-format
 * message from {@code POST /api/submissions} and {@code PUT
 * /api/submissions/mine}, the form re-rendered with the message from {@code
 * POST /submissions/form} — nothing is persisted, and the uploads root stays
 * empty, even when a valid photo was stored before the spoofed one (BL-017).
 *
 * <p>Uploads go to this class's own temp directory; a valid upload is
 * stored there first, so an empty directory can't be mistaken for files
 * written elsewhere. Not {@code @Transactional}: the rollback that removes
 * an earlier photo's files must really happen, inside the request. Rows a
 * test commits are deleted again afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SubmissionUploadSpoofingWebTest {

    private static final String UNSUPPORTED =
            "Unsupported image format. Please upload JPEG, PNG, WebP, GIF, TIFF or BMP.";

    @TempDir
    static Path uploadsRoot;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<String> emails = new ArrayList<>();

    @DynamicPropertySource
    static void uploadsRoot(DynamicPropertyRegistry registry) {
        registry.add("beacon.storage.root-path", () -> uploadsRoot.toString());
    }

    @AfterEach
    void deleteCommittedTestimonialsAndFiles() throws IOException {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> emails.forEach(email ->
                testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email))
                        .ifPresent(testimonialRepository::delete)));
        try (Stream<Path> files = Files.walk(uploadsRoot)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(uploadsRoot)).toList()) {
                Files.delete(file);
            }
        }
    }

    // -- fixtures --

    private String email(String name) {
        String email = name + "@example.com";
        emails.add(email);
        return email;
    }

    private static Authentication visitor(String email) {
        return new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private static byte[] png() {
        return TestImages.png(TestImages.quadrants(40, 30));
    }

    private static MockMultipartFile part(String name, String filename, String contentType, byte[] bytes) {
        return new MockMultipartFile(name, filename, contentType, bytes);
    }

    private MockMultipartFile payload(String... photoRefs) throws Exception {
        List<PhotoInput> photos = Stream.of(photoRefs).map(ref -> new PhotoInput(ref, List.of())).toList();
        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest(
                "David", "Jones", "GE26Z001", 2024, "IN", 8,
                List.of(new SectionInput("general", "Great time overall.", photos)),
                List.of(), List.of(), true);
        return new MockMultipartFile("payload", "", "application/json", objectMapper.writeValueAsBytes(request));
    }

    private List<Path> uploadedFiles() throws IOException {
        try (Stream<Path> files = Files.walk(uploadsRoot)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private boolean testimonialExists(String email) {
        return testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email)).isPresent();
    }

    static Stream<Arguments> nonImages() {
        return Stream.of(
                Arguments.of(Named.of("Windows executable (MZ/PE)", SpoofedUploads.windowsExecutable())),
                Arguments.of(Named.of("Linux executable (ELF)", SpoofedUploads.linuxExecutable())),
                Arguments.of(Named.of("SVG with a script", SpoofedUploads.svgWithScript())),
                Arguments.of(Named.of("HTML with a script", SpoofedUploads.htmlWithScript())),
                Arguments.of(Named.of("PDF", SpoofedUploads.pdf())),
                Arguments.of(Named.of("ZIP archive", SpoofedUploads.zip())));
    }

    // -- control: where a valid upload goes --

    @Test
    void control_aValidPhoto_isStoredInThisTestsUploadsRoot() throws Exception {
        String email = email("spoof-control");

        mockMvc.perform(multipart("/api/submissions")
                        .file(payload("p0"))
                        .file(part("p0", "real.png", "image/png", png()))
                        .with(authentication(visitor(email)))
                        .with(Csrf.csrfHeader()))
                .andExpect(status().isCreated());

        assertThat(uploadedFiles()).hasSize(2);
        assertThat(testimonialExists(email)).isTrue();
    }

    // -- REST create --

    @ParameterizedTest
    @MethodSource("nonImages")
    void restCreate_nonImagePostedAsAJpeg_is400WithTheUnsupportedFormatMessage_andNothingIsKept(byte[] bytes)
            throws Exception {
        String email = email("spoof-rest-create");

        mockMvc.perform(multipart("/api/submissions")
                        .file(payload("p0"))
                        .file(part("p0", "fake.jpg", "image/jpeg", bytes))
                        .with(authentication(visitor(email)))
                        .with(Csrf.csrfHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(UNSUPPORTED));

        assertThat(testimonialExists(email)).isFalse();
        assertThat(uploadedFiles()).isEmpty();
    }

    @Test
    void restCreate_validPhotoThenASpoofedOne_is400_andTheValidPhotosFilesAreRemovedToo() throws Exception {
        String email = email("spoof-rest-mixed");

        mockMvc.perform(multipart("/api/submissions")
                        .file(payload("p0", "p1"))
                        .file(part("p0", "real.png", "image/png", png()))
                        .file(part("p1", "fake.jpg", "image/jpeg", SpoofedUploads.windowsExecutable()))
                        .with(authentication(visitor(email)))
                        .with(Csrf.csrfHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(UNSUPPORTED));

        assertThat(testimonialExists(email)).isFalse();
        assertThat(uploadedFiles()).isEmpty();
    }

    // -- REST edit --

    @Test
    void restEdit_spoofedPhoto_is400_theSavedTestimonialIsUnchanged_andNoFileIsWritten() throws Exception {
        String email = email("spoof-rest-edit");
        mockMvc.perform(multipart("/api/submissions")
                        .file(payload())
                        .with(authentication(visitor(email)))
                        .with(Csrf.csrfHeader()))
                .andExpect(status().isCreated());

        TestimonialSubmissionRequest edit = new TestimonialSubmissionRequest(
                "David", "Jones", "GE26Z001", 2024, "IN", 8,
                List.of(new SectionInput("general", "Edited text.", List.of(new PhotoInput("p0", List.of())))),
                List.of(), List.of(), true);
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                        .file(new MockMultipartFile(
                                "payload", "", "application/json", objectMapper.writeValueAsBytes(edit)))
                        .file(part("p0", "fake.jpg", "image/jpeg", SpoofedUploads.windowsExecutable()))
                        .with(authentication(visitor(email)))
                        .with(Csrf.csrfHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(UNSUPPORTED));

        assertThat(uploadedFiles()).isEmpty();
        String answer = new TransactionTemplate(transactionManager).execute(status -> testimonialRepository
                .findByEmailLookupHash(emailLookupHashService.hash(email))
                .orElseThrow()
                .getSections()
                .get(0)
                .getAnswerText());
        assertThat(answer).isEqualTo("Great time overall.");
    }

    // -- the HTML form --

    private MockMultipartHttpServletRequestBuilder formPost(String email) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/submissions/form");
        builder.param("firstName", "David")
                .param("lastName", "Jones")
                .param("rollNumber", "GE26Z001")
                .param("admissionYear", "2024")
                .param("countryCode", "IN")
                .param("recommendationScore", "8")
                .param("sections[0].topicSlug", "general")
                .param("sections[0].answerText", "Great time overall.")
                .param("dataProcessingConsent", "true")
                .with(authentication(visitor(email)))
                .with(Csrf.csrfField());
        return builder;
    }

    @Test
    void form_nonImagePostedAsAJpeg_rerendersTheFormWithTheUnsupportedFormatMessage_andNothingIsKept()
            throws Exception {
        String email = email("spoof-form-create");

        String html = mockMvc.perform(formPost(email)
                        .file(part("sections[0].photos", "fake.jpg", "image/jpeg",
                                SpoofedUploads.windowsExecutable())))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(model().attribute("error", UNSUPPORTED))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains(UNSUPPORTED);
        assertThat(testimonialExists(email)).isFalse();
        assertThat(uploadedFiles()).isEmpty();
    }

    @Test
    void form_validPhotoThenASpoofedOne_rerendersWithTheMessage_andTheValidPhotosFilesAreRemovedToo()
            throws Exception {
        String email = email("spoof-form-mixed");

        mockMvc.perform(formPost(email)
                        .file(part("sections[0].photos", "real.png", "image/png", png()))
                        .file(part("sections[0].photos", "fake.jpg", "image/jpeg",
                                SpoofedUploads.windowsExecutable())))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(model().attribute("error", UNSUPPORTED));

        assertThat(testimonialExists(email)).isFalse();
        assertThat(uploadedFiles()).isEmpty();
    }
}
