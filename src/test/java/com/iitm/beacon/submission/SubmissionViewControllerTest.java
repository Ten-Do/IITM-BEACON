package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link SubmissionViewController}'s Thymeleaf pages — the
 * two-step visitor OTP login flow, the create/edit submission form, and the
 * confirmation page. Role enforcement for {@code /submissions/form} and
 * {@code /submissions/confirmation} is the same {@code SecurityConfig}
 * matcher already covering the JSON API (plain 401 JSON for an
 * unauthenticated browser GET, same as {@code ModerationViewControllerTest}
 * for the equally-new {@code /moderation/**} view routes) — these tests just
 * confirm it also applies to the new view routes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class SubmissionViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @MockitoBean
    private OtpMailer otpMailer;

    private static Authentication visitor(String email) {
        return new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private String requestAndCaptureCode(String email) throws Exception {
        mockMvc.perform(post("/submissions/login").param("email", email)).andExpect(status().is3xxRedirection());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), captor.capture());
        return captor.getValue();
    }

    private TestimonialSubmissionRequest.SectionInput section(String topicSlug, String answer) {
        return new TestimonialSubmissionRequest.SectionInput(topicSlug, answer, List.of());
    }

    private void persistTestimonialFor(String email) {
        submissionService.create(
                email,
                new TestimonialSubmissionRequest(
                        "David",
                        "Jones",
                        "GE26Z001",
                        2024,
                        "IN",
                        8,
                        List.of(section("general", "Great time overall.")),
                        List.of(),
                        List.of(),
                        true),
                Map.of());
    }

    // -- GET/POST /submissions/login --

    @Test
    void loginEmailForm_get_returns200AndRendersView() throws Exception {
        mockMvc.perform(get("/submissions/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-email"));
    }

    @Test
    void loginEmailForm_post_redirectsToCodePageWithEmail() throws Exception {
        mockMvc.perform(post("/submissions/login").param("email", "new-visitor@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/submissions/login/code?email=*"));
    }

    @Test
    void loginEmailForm_post_blankEmail_rerendersWithErrorInsteadOfCrashing() throws Exception {
        mockMvc.perform(post("/submissions/login").param("email", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-email"))
                .andExpect(model().attributeExists("error"));
    }

    // -- GET/POST /submissions/login/code --

    @Test
    void loginCodeForm_get_returns200AndRendersViewWithEmail() throws Exception {
        mockMvc.perform(get("/submissions/login/code").param("email", "someone@example.com"))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-code"))
                .andExpect(model().attribute("email", "someone@example.com"));
    }

    @Test
    void loginCodeForm_get_missingEmail_redirectsBackToLoginEmail() throws Exception {
        mockMvc.perform(get("/submissions/login/code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/login"));
    }

    @Test
    void loginCodeForm_post_correctCode_redirectsToFormAndEstablishesVisitorSession() throws Exception {
        String email = "view-login-success@example.com";
        String code = requestAndCaptureCode(email);

        var result = mockMvc.perform(post("/submissions/login/code")
                        .param("email", email)
                        .param("code", code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/form"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        SecurityContext securityContext = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(securityContext).isNotNull();
        assertThat(securityContext.getAuthentication().getPrincipal()).isEqualTo(email);
    }

    @Test
    void loginCodeForm_post_wrongCode_rerendersWithError() throws Exception {
        String email = "view-login-wrong-code@example.com";
        requestAndCaptureCode(email);

        mockMvc.perform(post("/submissions/login/code")
                        .param("email", email)
                        .param("code", "ZZZZZZ"))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-code"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("email", email));
    }

    // -- GET /submissions/form --

    @Test
    void form_get_newVisitor_rendersEmptyFormForCreateMode() throws Exception {
        mockMvc.perform(get("/submissions/form").with(authentication(visitor("view-form-new@example.com"))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(model().attribute("editing", false));
    }

    @Test
    void form_get_existingVisitor_prefillsFormForEditMode() throws Exception {
        String email = "view-form-existing@example.com";
        persistTestimonialFor(email);

        mockMvc.perform(get("/submissions/form").with(authentication(visitor(email))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(model().attribute("editing", true));
    }

    @Test
    void form_get_existingVisitorWithPhoto_rendersExistingPhotoTileWithoutError() throws Exception {
        // Exercises the template's nested existingPhotoUrls/existingPhotoTags
        // indexed-list rendering branch, which the plain (photo-less)
        // pre-fill test above never reaches.
        String email = "view-form-existing-photo@example.com";
        MockMultipartFile photo = new MockMultipartFile("photo", "cat.png", "image/png", realPngBytes());
        submissionService.create(
                email,
                new TestimonialSubmissionRequest(
                        "David",
                        "Jones",
                        "GE26Z001",
                        2024,
                        "IN",
                        8,
                        List.of(new TestimonialSubmissionRequest.SectionInput(
                                "general",
                                "Great time overall.",
                                List.of(new TestimonialSubmissionRequest.PhotoInput("photo-0", List.of("sunset"))))),
                        List.of(),
                        List.of(),
                        true),
                Map.of("photo-0", photo));

        mockMvc.perform(get("/submissions/form").with(authentication(visitor(email))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(content().string(containsString("sunset")));
    }

    private static byte[] realPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void form_get_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/submissions/form")).andExpect(status().isUnauthorized());
    }

    @Test
    void form_get_adminRole_returns403() throws Exception {
        mockMvc.perform(get("/submissions/form").with(authentication(admin())))
                .andExpect(status().isForbidden());
    }

    // -- POST /submissions/form --

    @Test
    void form_post_createMode_persistsAndRedirectsToConfirmation() throws Exception {
        String email = "view-form-post-create@example.com";

        mockMvc.perform(multipart("/submissions/form")
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[0].topicSlug", "general")
                        .param("sections[0].answerText", "Great time overall.")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        Testimonial saved = testimonialRepository
                .findAll()
                .stream()
                .filter(t -> email.equals(t.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(saved.getSections().get(0).getAnswerText()).isEqualTo("Great time overall.");
    }

    @Test
    void form_post_editMode_updatesAndRedirectsToConfirmation() throws Exception {
        String email = "view-form-post-edit@example.com";
        persistTestimonialFor(email);

        mockMvc.perform(multipart("/submissions/form")
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[0].topicSlug", "general")
                        .param("sections[0].answerText", "Updated via form.")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        Testimonial saved = testimonialRepository
                .findAll()
                .stream()
                .filter(t -> email.equals(t.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(saved.getSections().get(0).getAnswerText()).isEqualTo("Updated via form.");
    }

    @Test
    void form_post_unauthenticated_returns401() throws Exception {
        mockMvc.perform(multipart("/submissions/form")
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[0].topicSlug", "general")
                        .param("sections[0].answerText", "Text.")
                        .param("dataProcessingConsent", "true"))
                .andExpect(status().isUnauthorized());
    }

    // -- GET /submissions/confirmation --

    @Test
    void confirmation_get_authenticatedVisitor_returns200() throws Exception {
        mockMvc.perform(
                        get("/submissions/confirmation").with(authentication(visitor("view-confirm@example.com"))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/confirmation"));
    }

    @Test
    void confirmation_get_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/submissions/confirmation")).andExpect(status().isUnauthorized());
    }
}
