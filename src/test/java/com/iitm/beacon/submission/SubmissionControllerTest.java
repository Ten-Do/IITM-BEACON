package com.iitm.beacon.submission;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
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
    void achievements_isPublicAndReturnsSeededAchievements() throws Exception {
        mockMvc.perform(get("/api/submissions/achievements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").exists());
    }

    @Test
    void createSubmission_authenticatedVisitor_returns201WithPendingStatus() throws Exception {
        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(validRequest()))
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
                        .file(photo)
                        .with(authentication(visitor("controller-photo@example.com"))))
                .andExpect(status().isCreated());
    }

    @Test
    void createSubmission_unauthenticated_returns401() throws Exception {
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createSubmission_adminRole_returns403() throws Exception {
        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(validRequest()))
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
                        .file(jsonPayload(noConsent))
                        .with(authentication(visitor("controller-no-consent@example.com"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSubmission_secondTimeForSameVisitor_returns409() throws Exception {
        Authentication auth = visitor("controller-duplicate@example.com");
        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(validRequest()))
                        .with(authentication(auth)))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/submissions")
                        .file(jsonPayload(validRequest()))
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
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(authentication(auth)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/submissions/mine").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("David"))
                .andExpect(jsonPath("$.sections[0].topicSlug").value("general"));
    }

    @Test
    void editMine_authenticatedVisitor_returns200() throws Exception {
        Authentication auth = visitor("controller-edit@example.com");
        mockMvc.perform(multipart("/api/submissions").file(jsonPayload(validRequest())).with(authentication(auth)))
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
                        .file(jsonPayload(editRequest))
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void editMine_noExistingTestimonial_returns404() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                        .file(jsonPayload(validRequest()))
                        .with(authentication(visitor("controller-edit-missing@example.com"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void editMine_unauthenticated_returns401() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine").file(jsonPayload(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editMine_adminRole_returns403() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                        .file(jsonPayload(validRequest()))
                        .with(authentication(admin())))
                .andExpect(status().isForbidden());
    }
}
