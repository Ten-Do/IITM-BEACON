package com.iitm.beacon.moderation;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTag;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTagsWithAttribute;
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
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.AssetUrls;
import com.iitm.beacon.testsupport.Csrf;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
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
 * Role enforcement comes from {@code SecurityConfig}'s {@code /moderation/**}
 * matcher — these tests confirm it applies to the view routes: without a
 * session (or with an expired one) a page redirects to the admin login page
 * ({@code config.SecurityConfigLoginRedirectTest} covers the entry point
 * itself), while a visitor session still gets the JSON 403.
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

    /** An {@code <img>} tag whose {@code src} is exactly {@code url}, whatever its other attributes. */
    private static Pattern imgWithSrc(String url) {
        return Pattern.compile("<img\\b[^>]*\\ssrc=\"" + Pattern.quote(url) + "\"[^>]*>");
    }

    @Test
    void queue_pendingTestimonialWithPhoto_rendersAnImgPointingAtTheUploadsUrl() throws Exception {
        String filePath = UUID.randomUUID() + ".png";
        Testimonial saved = pendingTestimonial("view-queue-photo@example.com");
        TestimonialSection section = saved.getSections().get(0);
        section.getPhotos().add(Photo.builder().section(section).filePath(filePath).displayOrder(0).build());
        testimonialRepository.saveAndFlush(saved);

        String html = mockMvc.perform(get("/moderation/queue").with(authentication(admin())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).containsPattern(imgWithSrc("/uploads/" + filePath));
    }

    // -- GET /moderation/queue: photos for the fullscreen viewer (PhotoSwipe) --

    private String queueHtml() throws Exception {
        return mockMvc.perform(get("/moderation/queue").with(authentication(admin())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** A pending testimonial whose only section has one photo, stored under {@code filePath}. */
    private Photo pendingPhoto(String email, String filePath) {
        Testimonial saved = pendingTestimonial(email);
        TestimonialSection section = saved.getSections().get(0);
        Photo photo = Photo.builder().section(section).filePath(filePath).displayOrder(0).build();
        section.getPhotos().add(photo);
        return photo;
    }

    @Test
    void queue_convertedPhoto_isALinkToTheFullSizePhoto_wrappingItsThumbnail_withSizeAndCaption() throws Exception {
        String id = UUID.randomUUID().toString();
        Photo photo = pendingPhoto("view-queue-pswp@example.com", id + ".webp");
        photo.setThumbnailPath(id + "-thumb.webp");
        photo.setWidth(1440);
        photo.setHeight(2560);
        testimonialRepository.flush();

        List<String> figures = elements(queueHtml(), "figure");

        assertThat(figures).hasSize(1);
        String link = openingTag(figures.get(0), "a");
        assertThat(attribute(link, "href")).contains("/uploads/" + id + ".webp");
        assertThat(attribute(link, "target")).contains("_blank");
        assertThat(attribute(link, "data-photo-viewer-item")).isPresent();
        assertThat(attribute(link, "data-pswp-width")).contains("1440");
        assertThat(attribute(link, "data-pswp-height")).contains("2560");
        assertThat(attribute(link, "data-caption")).contains("General");
        String img = openingTag(figures.get(0), "img");
        assertThat(attribute(img, "src")).contains("/uploads/" + id + "-thumb.webp");
        assertThat(attribute(img, "class")).contains("moderation-section-photo");
        assertThat(attribute(img, "alt")).hasValueSatisfying(alt -> assertThat(alt).isNotBlank());
    }

    @Test
    void queue_legacyPhotoOfUnknownSize_hasNoSizeAttributes() throws Exception {
        String filePath = UUID.randomUUID() + ".png";
        pendingPhoto("view-queue-pswp-legacy@example.com", filePath);
        testimonialRepository.flush();

        String link = openingTag(elements(queueHtml(), "figure").get(0), "a");

        assertThat(attribute(link, "href")).contains("/uploads/" + filePath);
        assertThat(attribute(link, "data-pswp-width")).isEmpty();
        assertThat(attribute(link, "data-pswp-height")).isEmpty();
    }

    @Test
    void queue_photoTags_areEscapedChipsUnderThePhoto_forTheAdminToModerate() throws Exception {
        Photo photo = pendingPhoto("view-queue-pswp-tags@example.com", UUID.randomUUID() + ".png");
        photo.getTags().add(PhotoTag.builder().photo(photo).tagText("sunset").build());
        photo.getTags().add(PhotoTag.builder().photo(photo).tagText("<img src=x onerror=alert(1)>").build());
        testimonialRepository.flush();

        String html = queueHtml();

        String figure = elements(html, "figure").get(0);
        assertThat(figure.indexOf("<ul")).isGreaterThan(figure.indexOf("</a>"));
        assertThat(elements(figure, "li"))
                .allSatisfy(chip -> assertThat(attribute(openingTag(chip, "li"), "class")).contains("photo-tag-chip"))
                .extracting(chip -> chip.replaceAll("<[^>]+>", ""))
                .containsExactlyInAnyOrder("sunset", "&lt;img src=x onerror=alert(1)&gt;");
        assertThat(html).doesNotContain("<img src=x");
    }

    @Test
    void queue_photoWithoutTags_rendersNoTagList() throws Exception {
        pendingPhoto("view-queue-pswp-no-tags@example.com", UUID.randomUUID() + ".png");
        testimonialRepository.flush();

        assertThat(queueHtml()).doesNotContain("photo-tag-list", "photo-tag-chip");
    }

    /** Arrows in the viewer must never step from one testimonial's photos into another's. */
    @Test
    void queue_eachTestimonialIsItsOwnPhotoGallery() throws Exception {
        pendingPhoto("view-queue-pswp-first@example.com", "first-" + UUID.randomUUID() + ".png");
        pendingPhoto("view-queue-pswp-second@example.com", "second-" + UUID.randomUUID() + ".png");
        testimonialRepository.flush();

        String html = queueHtml();

        List<String> galleries = openingTagsWithAttribute(html, "data-photo-gallery");
        assertThat(galleries)
                .hasSize(2)
                .allSatisfy(tag -> assertThat(attribute(tag, "class")).contains("moderation-queue-item"));
        int first = html.indexOf(galleries.get(0));
        int second = html.indexOf(galleries.get(1), first + 1);
        String firstItem = html.substring(first, second);
        String secondItem = html.substring(second);
        assertThat(elements(firstItem, "figure")).singleElement().asString().contains("href=\"/uploads/");
        assertThat(elements(secondItem, "figure")).singleElement().asString().contains("href=\"/uploads/");
        assertThat(firstItem).doesNotContain(openingTag(elements(secondItem, "figure").get(0), "a"));
    }

    @Test
    void queue_loadsPhotoSwipesStylesheetAndTheViewerAsAModule() throws Exception {
        String html = queueHtml();

        assertThat(openingTags(html, "link"))
                .filteredOn(tag -> attribute(tag, "href").map(AssetUrls::plain).orElse("").equals(
                        "/webjars/photoswipe/dist/photoswipe.css"))
                .singleElement()
                .satisfies(tag -> assertThat(attribute(tag, "rel")).contains("stylesheet"));
        assertThat(openingTags(html, "script"))
                .filteredOn(tag -> attribute(tag, "src").map(AssetUrls::plain).orElse("").equals("/js/photo-viewer.js"))
                .singleElement()
                .satisfies(tag -> assertThat(attribute(tag, "type")).contains("module"));
    }

    @Test
    void queue_emptyQueue_returns200WithEmptyContent() throws Exception {
        mockMvc.perform(get("/moderation/queue").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("moderation/queue"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Nothing waiting for review")));
    }

    @Test
    void queue_unauthenticated_redirectsToAdminLogin() throws Exception {
        mockMvc.perform(get("/moderation/queue"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void queue_visitorRole_redirectsToAdminLogin() throws Exception {
        mockMvc.perform(get("/moderation/queue").with(authentication(visitor())))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
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

        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId()).with(csrfField())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
    }

    @Test
    void approve_unknownId_redirectsBackToQueueWithoutError() throws Exception {
        mockMvc.perform(post("/moderation/queue/{id}/approve", 999_999).with(csrfField()).with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void approve_alreadyApproved_secondCallRedirectsBackWithoutError() throws Exception {
        Testimonial saved = pendingTestimonial("view-approve-conflict@example.com");
        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId()).with(csrfField())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection());

        // Second admin double-clicking (or a stale page) — must not surface a
        // JSON error body to a browser form POST.
        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId()).with(csrfField())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void approve_unauthenticated_redirectsToAdminLoginWithoutApproving() throws Exception {
        Testimonial saved = pendingTestimonial("view-approve-unauth@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId()).with(csrfField()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));

        assertThat(testimonialRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(TestimonialStatus.PENDING);
    }

    @Test
    void approve_visitorRole_redirectsToAdminLogin_andLeavesItPending() throws Exception {
        Testimonial saved = pendingTestimonial("view-approve-visitor@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId()).with(csrfField())
                        .with(authentication(visitor())))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
        assertThat(testimonialRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(TestimonialStatus.PENDING);
    }

    // -- POST /moderation/queue/{id}/reject --

    @Test
    void reject_withReason_redirectsToQueueAndPersistsRejectedStatus() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()).with(csrfField())
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

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()).with(csrfField())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
    }

    @Test
    void reject_blankReason_treatedSameAsNoReason() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject-blank-reason@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()).with(csrfField())
                        .param("reason", "   ")
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.REJECTED);
    }

    @Test
    void reject_unknownId_redirectsBackToQueueWithoutError() throws Exception {
        mockMvc.perform(post("/moderation/queue/{id}/reject", 999_999).with(csrfField()).with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void reject_alreadyRejected_secondCallRedirectsBackWithoutError() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject-conflict@example.com");
        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()).with(csrfField())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()).with(csrfField())
                        .with(authentication(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void reject_unauthenticated_redirectsToAdminLoginWithoutRejecting() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject-unauth@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()).with(csrfField())
                        .param("reason", "Too short."))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));

        assertThat(testimonialRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(TestimonialStatus.PENDING);
    }

    @Test
    void reject_visitorRole_redirectsToAdminLogin_andLeavesItPending() throws Exception {
        Testimonial saved = pendingTestimonial("view-reject-visitor@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/reject", saved.getId()).with(csrfField())
                        .with(authentication(visitor())))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
        assertThat(testimonialRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(TestimonialStatus.PENDING);
    }

    // -- CSRF (BL-004) --

    @Test
    void queue_approveAndRejectForms_carryTheCsrfToken() throws Exception {
        pendingTestimonial("view-csrf-forms@example.com");

        String html = mockMvc.perform(get("/moderation/queue").with(authentication(admin())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Csrf.assertEveryPostFormCarriesTheToken(html, 2);
    }

    @Test
    void approve_withoutTheCsrfToken_isRefused_andLeavesItPending() throws Exception {
        Testimonial saved = pendingTestimonial("view-approve-no-csrf@example.com");

        mockMvc.perform(post("/moderation/queue/{id}/approve", saved.getId()).with(authentication(admin())))
                .andExpect(status().isForbidden());

        assertThat(testimonialRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(TestimonialStatus.PENDING);
    }
}
