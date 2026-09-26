package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link ModerationViewController}'s Thymeleaf pages —
 * happy paths, empty-queue rendering, pagination boundaries, and the
 * browser-facing (redirect, not JSON) handling of a stale approve/reject.
 * Role enforcement (401/403) is already covered end-to-end by {@code
 * SecurityConfig}'s {@code /moderation/**} matcher — these tests just
 * confirm it also applies to the new view routes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ModerationViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private AchievementRepository achievementRepository;

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

    // -- GET /moderation/queue --

    @Test
    void queue_withAdminAuth_returns200WithQueueModel() throws Exception {
        pendingTestimonial("view-queue@example.com");

        mockMvc.perform(get("/moderation/queue").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("moderation/queue"))
                .andExpect(model().attributeExists("queue"));
    }

    @Test
    void queue_withFullDetail_rendersSectionsAchievementsContactsPhotosAndResubmittedBadge() throws Exception {
        Testimonial t = Testimonial.builder()
                .firstName("Aisha")
                .lastName("Mwangi")
                .rollNumber("EE24X0871")
                .admissionYear(2024)
                .email("aisha.full-detail@example.com")
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(9)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.now())
                .identityModified(true)
                .scoreModified(true)
                .build();
        // Simulates a reject-then-resubmit case, so the "Resubmitted edit"
        // badge (rather than "New submission") should render.
        t.setReviewedAt(Instant.parse("2026-01-01T00:00:00Z"));
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("Great time overall.")
                .modified(true)
                .build();
        t.getSections().add(section);
        ContactMethod contactMethod = ContactMethod.builder()
                .testimonial(t)
                .contactType(contactTypeRepository.findBySlug("whatsapp").orElseThrow())
                .value("+1234567890")
                .isPublic(true)
                .displayOrder(0)
                .build();
        t.getContactMethods().add(contactMethod);
        Achievement achievement =
                achievementRepository.findBySlug("made_new_friends").orElseThrow();
        t.getAchievements()
                .add(TestimonialAchievement.builder().testimonial(t).achievement(achievement).build());
        Photo photo = Photo.builder()
                .section(section)
                .filePath("2026/01/photo.png")
                .displayOrder(0)
                .build();
        section.getPhotos().add(photo);
        testimonialRepository.save(t);

        mockMvc.perform(get("/moderation/queue").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("Resubmitted edit"),
                        org.hamcrest.Matchers.containsString("Identity changed"),
                        org.hamcrest.Matchers.containsString("Score changed"),
                        org.hamcrest.Matchers.containsString("Changed"),
                        org.hamcrest.Matchers.containsString(achievement.getLabel()),
                        org.hamcrest.Matchers.containsString("whatsapp"),
                        org.hamcrest.Matchers.containsString("/uploads/2026/01/photo.png"))));
    }

    @Test
    void queue_emptyQueue_returns200WithEmptyContent() throws Exception {
        mockMvc.perform(get("/moderation/queue").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("moderation/queue"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Nothing waiting for review")));
    }

    @Test
    void queue_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/moderation/queue")).andExpect(status().isUnauthorized());
    }

    @Test
    void queue_visitorRole_returns403() throws Exception {
        mockMvc.perform(get("/moderation/queue").with(authentication(visitor())))
                .andExpect(status().isForbidden());
    }

    @Test
    void queue_negativePage_clampedToFirstPage() throws Exception {
        pendingTestimonial("view-queue-negative-page@example.com");

        mockMvc.perform(get("/moderation/queue").param("page", "-1").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("moderation/queue"));
    }

    @Test
    void queue_pageBeyondLastPage_returns200WithEmptyContent() throws Exception {
        pendingTestimonial("view-queue-beyond-page@example.com");

        mockMvc.perform(get("/moderation/queue").param("page", "999").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Nothing waiting for review")));
    }

    // -- POST /moderation/queue/{id}/approve --

    @Test
    void approve_withAdminAuth_redirectsToQueueAndPersistsApprovedStatus() throws Exception {
        Testimonial saved = pendingTestimonial("view-approve@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
    }

    @Test
    void approve_unknownId_redirectsBackToQueueWithoutError() throws Exception {
        mockMvc.perform(post("/moderation/queue/{id}/approve", 999_999).with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void approve_alreadyApproved_secondCallRedirectsBackWithoutError() throws Exception {
        Testimonial saved = pendingTestimonial("view-approve-conflict@example.com");
        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection());

        // Second admin double-clicking (or a stale page) — must not surface a
        // JSON error body to a browser form POST.
        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void approve_unauthenticated_returns401() throws Exception {
        Testimonial saved = pendingTestimonial("view-approve-unauth@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void approve_visitorRole_returns403() throws Exception {
        Testimonial saved = pendingTestimonial("view-approve-visitor@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId())
                        .with(authentication(visitor())))
                .andExpect(status().isForbidden());
    }

    // -- POST /moderation/queue/{id}/reject --

    @Test
    void reject_withReason_redirectsToQueueAndPersistsRejectedStatus() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId())
                        .param("reason", "Needs more detail.")
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
    }

    @Test
    void reject_withoutReason_redirectsToQueueAndPersistsRejectedStatus() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject-no-reason@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
    }

    @Test
    void reject_blankReason_treatedSameAsNoReason() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject-blank-reason@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId())
                        .param("reason", "   ")
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
    }

    @Test
    void reject_unknownId_redirectsBackToQueueWithoutError() throws Exception {
        mockMvc.perform(post("/moderation/queue/{id}/reject", 999_999).with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void reject_alreadyRejected_secondCallRedirectsBackWithoutError() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject-conflict@example.com");
        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void reject_unauthenticated_returns401() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject-unauth@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reject_visitorRole_returns403() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject-visitor@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId())
                        .with(authentication(visitor())))
                .andExpect(status().isForbidden());
    }
}
