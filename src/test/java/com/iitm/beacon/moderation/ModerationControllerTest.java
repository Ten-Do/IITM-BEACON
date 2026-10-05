package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link ModerationController}'s three endpoints,
 * including the {@code SecurityConfig} matcher behavior for {@code
 * /api/moderation/**} (401 unauthenticated, 403 wrong role) — same
 * convention as {@code SubmissionControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ModerationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication visitor() {
        return new UsernamePasswordAuthenticationToken(
                "visitor@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private Testimonial pendingTestimonial(String email) {
        Testimonial t = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("Great time overall.")
                .modified(false)
                .build();
        t.getSections().add(section);
        return testimonialRepository.save(t);
    }

    @Test
    void pending_withAdminAuth_returns200WithFullDetailShape() throws Exception {
        pendingTestimonial("controller-pending@example.com");

        mockMvc.perform(get("/api/moderation/testimonials/pending").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("controller-pending@example.com"))
                .andExpect(jsonPath("$.content[0].sections[0].topicSlug").value("general"))
                .andExpect(jsonPath("$.content[0].identityModified").value(false))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void pending_photos_includeThumbnailUrlAndSize() throws Exception {
        Testimonial saved = pendingTestimonial("controller-pending-photo@example.com");
        TestimonialSection section = saved.getSections().get(0);
        section.getPhotos().add(Photo.builder()
                .section(section)
                .filePath("abc.webp")
                .thumbnailPath("abc-thumb.webp")
                .width(2560)
                .height(1707)
                .displayOrder(0)
                .build());
        testimonialRepository.saveAndFlush(saved);

        mockMvc.perform(get("/api/moderation/testimonials/pending").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sections[0].photos[0].url").value("/uploads/abc.webp"))
                .andExpect(jsonPath("$.content[0].sections[0].photos[0].thumbnailUrl")
                        .value("/uploads/abc-thumb.webp"))
                .andExpect(jsonPath("$.content[0].sections[0].photos[0].width").value(2560))
                .andExpect(jsonPath("$.content[0].sections[0].photos[0].height").value(1707))
                .andExpect(jsonPath("$.content[0].sections[0].photos[0].tags").isArray());
    }

    @Test
    void pending_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/moderation/testimonials/pending")).andExpect(status().isUnauthorized());
    }

    @Test
    void pending_visitorRole_returns403() throws Exception {
        mockMvc.perform(get("/api/moderation/testimonials/pending").with(authentication(visitor())))
                .andExpect(status().isForbidden());
    }

    @Test
    void pending_emptyQueue_returns200WithEmptyContent() throws Exception {
        mockMvc.perform(get("/api/moderation/testimonials/pending").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void approve_withAdminAuth_returns204AndPersistsApprovedStatus() throws Exception {
        Testimonial saved = pendingTestimonial("controller-approve@example.com");

        mockMvc.perform(post("/api/moderation/testimonials/{id}/approve", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().isNoContent());

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
        assertThat(reloaded.getReviewedAt()).isNotNull();
    }

    @Test
    void approve_unauthenticated_returns401() throws Exception {
        Testimonial saved = pendingTestimonial("controller-approve-unauth@example.com");

        mockMvc.perform(post("/api/moderation/testimonials/{id}/approve", saved.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void approve_visitorRole_returns403() throws Exception {
        Testimonial saved = pendingTestimonial("controller-approve-visitor@example.com");

        mockMvc.perform(post("/api/moderation/testimonials/{id}/approve", saved.getId())
                        .with(authentication(visitor())))
                .andExpect(status().isForbidden());
    }

    @Test
    void approve_unknownId_returns404() throws Exception {
        mockMvc.perform(post("/api/moderation/testimonials/{id}/approve", 999_999).with(authentication(admin())))
                .andExpect(status().isNotFound());
    }

    @Test
    void approve_alreadyApproved_returns409() throws Exception {
        Testimonial saved = pendingTestimonial("controller-approve-conflict@example.com");
        mockMvc.perform(post("/api/moderation/testimonials/{id}/approve", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/moderation/testimonials/{id}/approve", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().isConflict());
    }

    @Test
    void reject_withReasonBody_returns204AndPersistsRejectedStatus() throws Exception {
        Testimonial saved = pendingTestimonial("controller-reject@example.com");

        mockMvc.perform(post("/api/moderation/testimonials/{id}/reject", saved.getId())
                        .with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RejectRequest("Needs more detail."))))
                .andExpect(status().isNoContent());

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
        assertThat(reloaded.getRejectedAt()).isNotNull();
        assertThat(reloaded.getReviewedAt()).isNotNull();
    }

    @Test
    void reject_withoutBody_returns204() throws Exception {
        Testimonial saved = pendingTestimonial("controller-reject-no-body@example.com");

        mockMvc.perform(post("/api/moderation/testimonials/{id}/reject", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().isNoContent());

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
    }

    @Test
    void reject_unauthenticated_returns401() throws Exception {
        Testimonial saved = pendingTestimonial("controller-reject-unauth@example.com");

        mockMvc.perform(post("/api/moderation/testimonials/{id}/reject", saved.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reject_visitorRole_returns403() throws Exception {
        Testimonial saved = pendingTestimonial("controller-reject-visitor@example.com");

        mockMvc.perform(post("/api/moderation/testimonials/{id}/reject", saved.getId())
                        .with(authentication(visitor())))
                .andExpect(status().isForbidden());
    }

    @Test
    void reject_unknownId_returns404() throws Exception {
        mockMvc.perform(post("/api/moderation/testimonials/{id}/reject", 999_999).with(authentication(admin())))
                .andExpect(status().isNotFound());
    }

    @Test
    void reject_alreadyRejected_returns409() throws Exception {
        Testimonial saved = pendingTestimonial("controller-reject-conflict@example.com");
        mockMvc.perform(post("/api/moderation/testimonials/{id}/reject", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/moderation/testimonials/{id}/reject", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().isConflict());
    }

    // -- GET /api/moderation/session (session ping for session-check.js) --

    @Test
    void sessionPing_adminSession_returns204WithNoBody() throws Exception {
        mockMvc.perform(get("/api/moderation/session").with(authentication(admin())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void sessionPing_anonymous_returnsJson401() throws Exception {
        mockMvc.perform(get("/api/moderation/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/moderation/session"));
    }

    @Test
    void sessionPing_visitorSession_returnsJson403() throws Exception {
        mockMvc.perform(get("/api/moderation/session").with(authentication(visitor())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
