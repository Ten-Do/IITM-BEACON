package com.iitm.beacon.submission;

import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link SubmissionController}: the four endpoints plus
 * the security-matcher behavior added to {@code SecurityConfig} (401
 * unauthenticated, 403 wrong role) — same conventions as {@code
 * VisitorAuthControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class SubmissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static Authentication visitor(String email) {
        return new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static byte[] realPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private TestimonialSubmissionRequest validRequest() {
        return new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput("general", "Great time overall.", List.of())),
                List.of(),
                List.of(),
                true);
    }

    private MockMultipartFile jsonPayload(Object body) throws Exception {
        return new MockMultipartFile(
                "payload", "", "application/json", objectMapper.writeValueAsBytes(body));
    }

    @Test
    void contactTypes_isPublicAndReturnsSeededTypes() throws Exception {
        mockMvc.perform(get("/api/submissions/contact-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").exists());
    }

    @Test
    void contactTypes_includeDisplayNameAlongsidePlaceholderLabel() throws Exception {
        mockMvc.perform(get("/api/submissions/contact-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("email"))
                .andExpect(jsonPath("$[0].name").value("Email"))
                .andExpect(jsonPath("$[0].label").value("email address"))
                .andExpect(jsonPath("$[?(@.slug == 'twitter')].name").value("X (Twitter)"));
    }

    @Test
    void contactTypes_includeEachTypesValuePattern() throws Exception {
        mockMvc.perform(get("/api/submissions/contact-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].valuePattern").value("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
                .andExpect(jsonPath("$[?(@.slug == 'twitter')].valuePattern").value(
                        "@?[A-Za-z0-9_]{1,15}|(?:https?://)?(?:www\\.)?(?:x|twitter)\\.com/[A-Za-z0-9_]{1,15}/?"));
    }

    @Test
    void achievements_isPublicAndReturnsSeededAchievements() throws Exception {
        mockMvc.perform(get("/api/submissions/achievements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").exists());
    }

    @Test
    void createSubmission_authenticatedVisitor_returns201WithPendingStatus() throws Exception {
        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(visitor("controller-create@example.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void createSubmission_withPhotoFilePart_persistsPhoto() throws Exception {
        MockMultipartFile photo = new MockMultipartFile("photo-0", "cat.png", "image/png", realPngBytes());
        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput(
                        "general",
                        "Great time overall.",
                        List.of(new TestimonialSubmissionRequest.PhotoInput("photo-0", List.of())))),
                List.of(),
                List.of(),
                true);

        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(request))
                        .file(photo).with(csrfHeader())
                        .with(authentication(visitor("controller-photo@example.com"))))
                .andExpect(status().isCreated());
    }

    /** A valid request whose "general" section carries {@code count} new photos, parts {@code p0..p<count-1>}. */
    private TestimonialSubmissionRequest requestWithGeneralPhotos(int count) {
        List<TestimonialSubmissionRequest.PhotoInput> photos = IntStream.range(0, count)
                .mapToObj(n -> new TestimonialSubmissionRequest.PhotoInput("p" + n, List.of()))
                .toList();
        return new TestimonialSubmissionRequest("David", "Jones", "GE26Z001", 2024, "IN", 8,
                List.of(new SectionInput("general", "Great time overall.", photos)), List.of(), List.of(), true);
    }

    @Test
    void createSubmission_sixPhotosInOneTopic_returns400NamingThePerTopicLimit() throws Exception {
        var request = multipart("/api/submissions").file(jsonPayload(requestWithGeneralPhotos(6)));
        for (int n = 0; n < 6; n++) {
            request.file(new MockMultipartFile("p" + n, "p" + n + ".png", "image/png", realPngBytes()));
        }

        mockMvc.perform(request.with(csrfHeader()).with(authentication(visitor("controller-six-photos@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("sections[0].photos: At most 5 photos per topic.")));
    }

    @Test
    void editMine_sixPhotosInOneTopic_returns400NamingThePerTopicLimit() throws Exception {
        Authentication auth = visitor("controller-edit-six-photos@example.com");
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isCreated());
        var request = multipart(HttpMethod.PUT, "/api/submissions/mine")
                .file(jsonPayload(requestWithGeneralPhotos(6)));
        for (int n = 0; n < 6; n++) {
            request.file(new MockMultipartFile("p" + n, "p" + n + ".png", "image/png", realPngBytes()));
        }

        mockMvc.perform(request.with(csrfHeader()).with(authentication(auth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("sections[0].photos: At most 5 photos per topic.")));
    }

    @Test
    void createSubmission_unauthenticated_returns401() throws Exception {
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(csrfHeader()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createSubmission_adminRole_returns403() throws Exception {
        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(admin())))
                .andExpect(status().isForbidden());
    }

    @Test
    void createSubmission_missingConsent_returns400() throws Exception {
        TestimonialSubmissionRequest noConsent = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput("general", "Text.", List.of())),
                List.of(),
                List.of(),
                false);

        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(noConsent)).with(csrfHeader())
                        .with(authentication(visitor("controller-no-consent@example.com"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSubmission_badRollNumberAndBadContact_returns400ReportingBothFieldsAtOnce() throws Exception {
        // One is a Bean Validation rule, the other a service rule: both must
        // come back in the same response, not one per round trip.
        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "CS21B01",
                2024,
                "IN",
                8,
                List.of(new SectionInput("general", "Great time overall.", List.of())),
                List.of(),
                List.of(new TestimonialSubmissionRequest.ContactMethodInput("whatsapp", "call me", true)),
                true);

        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(request)).with(csrfHeader())
                        .with(authentication(visitor("controller-two-violations@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("rollNumber: must look like CS21B001"
                                + " (two letters, two digits, a letter, three digits)"),
                        org.hamcrest.Matchers.containsString(
                                "contactMethods[0].value: doesn't look like a valid WhatsApp contact"
                                        + " — expected: phone number, or a wa.me link"))));
    }

    /** {@link #validRequest()} as JSON, with {@code countryCode} set to {@code value} — or dropped if null. */
    private MockMultipartFile payloadWithCountryCode(String value) throws Exception {
        ObjectNode json = objectMapper.valueToTree(validRequest());
        if (value == null) {
            json.remove("countryCode");
        } else {
            json.put("countryCode", value);
        }
        return new MockMultipartFile("payload", "", "application/json", objectMapper.writeValueAsBytes(json));
    }

    @ParameterizedTest(name = "countryCode {0} is a 400 with one readable message")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void createSubmission_blankOrMissingCountry_returns400WithOneReadableMessage(String countryCode)
            throws Exception {
        mockMvc.perform(multipart("/api/submissions")
                        .file(payloadWithCountryCode(countryCode)).with(csrfHeader())
                        .with(authentication(visitor("controller-blank-country@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("countryCode: Please select your country."));
    }

    @Test
    void editMine_blankCountry_returns400WithOneReadableMessage() throws Exception {
        Authentication auth = visitor("controller-edit-blank-country@example.com");
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                        .file(payloadWithCountryCode("")).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("countryCode: Please select your country."));
    }

    @Test
    void createSubmission_unknownCountry_returns400NamingTheCode() throws Exception {
        mockMvc.perform(multipart("/api/submissions")
                        .file(payloadWithCountryCode("ZZ")).with(csrfHeader())
                        .with(authentication(visitor("controller-unknown-country@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("countryCode: Unknown country code: ZZ"));
    }

    @Test
    void editMine_badRollNumberAndBadContact_returns400ReportingBothFieldsAtOnce() throws Exception {
        Authentication auth = visitor("controller-edit-two-violations@example.com");
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isCreated());
        TestimonialSubmissionRequest editRequest = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "XX",
                2024,
                "IN",
                8,
                List.of(new SectionInput("general", "Updated text.", List.of())),
                List.of(),
                List.of(new TestimonialSubmissionRequest.ContactMethodInput("email", "nope", false)),
                true);

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                        .file(jsonPayload(editRequest)).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("rollNumber: "),
                        org.hamcrest.Matchers.containsString("contactMethods[0].value: "))));
    }

    @Test
    void createSubmission_lowercaseRollNumber_isAcceptedAndReturnedUppercased() throws Exception {
        Authentication auth = visitor("controller-lowercase-roll@example.com");
        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "ge26z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput("general", "Great time overall.", List.of())),
                List.of(),
                List.of(),
                true);

        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(request)).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/submissions/mine").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rollNumber").value("GE26Z001"));
    }

    @Test
    void createSubmission_secondTimeForSameVisitor_returns409() throws Exception {
        Authentication auth = visitor("controller-duplicate@example.com");
        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isConflict());
    }

    @Test
    void mine_noExistingTestimonial_returns404() throws Exception {
        mockMvc.perform(get("/api/submissions/mine").with(authentication(visitor("controller-no-mine@example.com"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void mine_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/submissions/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void mine_adminRole_returns403() throws Exception {
        mockMvc.perform(get("/api/submissions/mine").with(authentication(admin())))
                .andExpect(status().isForbidden());
    }

    @Test
    void mine_afterCreate_returnsCurrentShape() throws Exception {
        Authentication auth = visitor("controller-mine@example.com");
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/submissions/mine").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("David"))
                .andExpect(jsonPath("$.sections[0].topicSlug").value("general"));
    }

    @Test
    void editMine_authenticatedVisitor_returns200() throws Exception {
        Authentication auth = visitor("controller-edit@example.com");
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isCreated());

        TestimonialSubmissionRequest editRequest = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput("general", "Updated text.", List.of())),
                List.of(),
                List.of(),
                true);

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                        .file(jsonPayload(editRequest)).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void editMine_noExistingTestimonial_returns404() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                        .file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(visitor("controller-edit-missing@example.com"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void editMine_unauthenticated_returns401() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                        .file(jsonPayload(validRequest())).with(csrfHeader()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editMine_adminRole_returns403() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                        .file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(admin())))
                .andExpect(status().isForbidden());
    }

    // -- CSRF (BL-004); the token's other variants are in config.CsrfProtectionTest --

    @Test
    void createSubmission_withoutTheCsrfHeader_isAJson403AndCreatesNothing() throws Exception {
        Authentication auth = visitor("controller-create-no-csrf@example.com");

        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(authentication(auth)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // Nothing was created: the same visitor's first create with the token succeeds, not a 409.
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isCreated());
    }

    @Test
    void editMine_withoutTheCsrfHeader_isAJson403() throws Exception {
        Authentication auth = visitor("controller-edit-no-csrf@example.com");
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(csrfHeader())
                        .with(authentication(auth)))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                        .file(jsonPayload(validRequest()))
                        .with(authentication(auth)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
