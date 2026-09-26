package com.iitm.beacon.gallery;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.contacttype.ContactType;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link GalleryViewController}'s Thymeleaf pages — every
 * route here is public (no auth), same as {@link GalleryController}'s JSON
 * API. Covers the happy paths plus the browser-facing (HTML, not JSON) 404
 * handling for an unknown/pending/rejected id, the GET-with-query-param
 * reveal-contact flow, and list-page filter/pagination boundaries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class GalleryViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private Testimonial.TestimonialBuilder baseBuilder(String email, String countryCode) {
        Country country = countryRepository.findById(countryCode).orElseThrow();
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(country)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private Testimonial approvedTestimonial(String email, String countryCode, String topicSlug, String answerText) {
        Testimonial t = baseBuilder(email, countryCode).build();
        Topic topic = topicRepository.findBySlug(topicSlug).orElseThrow();
        t.getSections()
                .add(TestimonialSection.builder()
                        .testimonial(t)
                        .topic(topic)
                        .answerText(answerText)
                        .modified(false)
                        .build());
        return testimonialRepository.saveAndFlush(t);
    }

    // -- GET / --

    @Test
    void home_redirectsToGallery() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/gallery"));
    }

    // -- GET /gallery --

    @Test
    void list_happyPath_rendersCardsAndFilterModel() throws Exception {
        approvedTestimonial("list-happy@example.com", "IN", "general", "Great time overall.");

        mockMvc.perform(get("/gallery"))
                .andExpect(status().isOk())
                .andExpect(view().name("gallery/list"))
                .andExpect(model().attributeExists("results", "countries", "topics"))
                .andExpect(content().string(containsString("Great time overall.")))
                .andExpect(content().string(containsString("India")));
    }

    @Test
    void list_noApprovedTestimonials_rendersEmptyState() throws Exception {
        mockMvc.perform(get("/gallery"))
                .andExpect(status().isOk())
                .andExpect(view().name("gallery/list"))
                .andExpect(content().string(containsString("No testimonials matched")));
    }

    @Test
    void list_filtersApplied_narrowsResultsAndPopulatesControls() throws Exception {
        Testimonial india = approvedTestimonial("list-filter-in@example.com", "IN", "networking", "elephant story");
        approvedTestimonial("list-filter-de@example.com", "DE", "general", "giraffe story");

        mockMvc.perform(get("/gallery").param("country", "IN"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("country", "IN"))
                .andExpect(content().string(containsString("elephant story")))
                .andExpect(content().string(not(containsString("giraffe story"))));

        Topic networking = topicRepository.findBySlug("networking").orElseThrow();
        mockMvc.perform(get("/gallery").param("topicIds", String.valueOf(networking.getId())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("topicIds", java.util.List.of(networking.getId())))
                .andExpect(content().string(containsString("elephant story")))
                .andExpect(content().string(not(containsString("giraffe story"))));

        mockMvc.perform(get("/gallery").param("q", "giraffe"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("q", "giraffe"))
                .andExpect(content().string(not(containsString("elephant story"))))
                .andExpect(content().string(containsString("giraffe story")));

        // sanity: the unfiltered id really exists and belongs to India
        org.assertj.core.api.Assertions.assertThat(
                        testimonialRepository.findById(india.getId()).orElseThrow().getCountry().getCode())
                .isEqualTo("IN");
    }

    @Test
    void list_negativePage_clampedToFirstPage() throws Exception {
        approvedTestimonial("list-negative-page@example.com", "IN", "general", "Some text.");

        mockMvc.perform(get("/gallery").param("page", "-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("gallery/list"));
    }

    @Test
    void list_singlePage_noPaginationRendered() throws Exception {
        approvedTestimonial("list-single-page@example.com", "IN", "general", "Some text.");

        mockMvc.perform(get("/gallery"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("gallery-pagination-status"))));
    }

    @Test
    void list_moreThanOnePage_paginationLinksRendered() throws Exception {
        for (int i = 0; i < 21; i++) {
            approvedTestimonial("list-page-" + i + "@example.com", "IN", "general", "Text number " + i);
        }

        mockMvc.perform(get("/gallery"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("gallery-pagination-status"), containsString("Next"))));
    }

    @Test
    void list_cardWithoutPhoto_rendersPlaceholderNotBrokenImage() throws Exception {
        approvedTestimonial("list-no-photo@example.com", "IN", "general", "No photo here.");

        mockMvc.perform(get("/gallery"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gallery-card-photo-placeholder")));
    }

    // -- GET /gallery/{id} --

    @Test
    void detail_happyPath_rendersArticleWithGroupedSectionsScoreAndAchievements() throws Exception {
        Testimonial t = baseBuilder("detail-happy@example.com", "IN").build();
        Topic academicsStandout = topicRepository.findBySlug("academics_standout").orElseThrow();
        Topic networking = topicRepository.findBySlug("networking").orElseThrow();
        TestimonialSection groupedSection = TestimonialSection.builder()
                .testimonial(t)
                .topic(academicsStandout)
                .answerText("My ML professor was fantastic.")
                .modified(true)
                .build();
        TestimonialSection standaloneSection = TestimonialSection.builder()
                .testimonial(t)
                .topic(networking)
                .answerText("Made great professional contacts.")
                .modified(false)
                .build();
        t.getSections().add(groupedSection);
        t.getSections().add(standaloneSection);
        Photo photo = Photo.builder()
                .section(groupedSection)
                .filePath("2026/01/photo.png")
                .displayOrder(0)
                .build();
        groupedSection.getPhotos().add(photo);
        Achievement achievement =
                achievementRepository.findBySlug("made_new_friends").orElseThrow();
        t.getAchievements()
                .add(TestimonialAchievement.builder().testimonial(t).achievement(achievement).build());
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        mockMvc.perform(get("/gallery/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("gallery/detail"))
                .andExpect(content().string(allOf(
                        containsString("David J."),
                        containsString("Academics"),
                        containsString("My ML professor was fantastic."),
                        containsString("Updated"),
                        containsString("Professional Networking"),
                        containsString("Made great professional contacts."),
                        containsString("/uploads/2026/01/photo.png"),
                        containsString("data-photo-viewer-item"),
                        containsString("Made new friends"),
                        containsString("A great experience, a lot to remember"))));
    }

    @Test
    void detail_unknownId_rendersNotFoundView() throws Exception {
        mockMvc.perform(get("/gallery/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(view().name("gallery/not-found"))
                .andExpect(content().string(containsString("Testimonial not found")));
    }

    @Test
    void detail_pendingTestimonial_rendersNotFoundView() throws Exception {
        Testimonial pending = baseBuilder("detail-pending@example.com", "IN")
                .status(TestimonialStatus.PENDING)
                .build();
        Topic general = topicRepository.findBySlug("general").orElseThrow();
        pending.getSections()
                .add(TestimonialSection.builder()
                        .testimonial(pending)
                        .topic(general)
                        .answerText("Text.")
                        .modified(false)
                        .build());
        Testimonial saved = testimonialRepository.saveAndFlush(pending);

        mockMvc.perform(get("/gallery/{id}", saved.getId()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("gallery/not-found"));
    }

    @Test
    void detail_rejectedTestimonial_rendersNotFoundView() throws Exception {
        Testimonial rejected = baseBuilder("detail-rejected@example.com", "IN")
                .status(TestimonialStatus.REJECTED)
                .build();
        Topic general = topicRepository.findBySlug("general").orElseThrow();
        rejected.getSections()
                .add(TestimonialSection.builder()
                        .testimonial(rejected)
                        .topic(general)
                        .answerText("Text.")
                        .modified(false)
                        .build());
        Testimonial saved = testimonialRepository.saveAndFlush(rejected);

        mockMvc.perform(get("/gallery/{id}", saved.getId()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("gallery/not-found"));
    }

    private Testimonial approvedWithContact(String email, boolean publicContact) {
        Testimonial t = baseBuilder(email, "IN").build();
        Topic general = topicRepository.findBySlug("general").orElseThrow();
        t.getSections()
                .add(TestimonialSection.builder()
                        .testimonial(t)
                        .topic(general)
                        .answerText("Text.")
                        .modified(false)
                        .build());
        ContactType emailType = contactTypeRepository.findBySlug("email").orElseThrow();
        t.getContactMethods()
                .add(ContactMethod.builder()
                        .testimonial(t)
                        .contactType(emailType)
                        .value("reachme@example.com")
                        .isPublic(publicContact)
                        .displayOrder(0)
                        .build());
        return testimonialRepository.saveAndFlush(t);
    }

    @Test
    void detail_withoutReveal_showsRevealButtonAndHidesContacts() throws Exception {
        Testimonial saved = approvedWithContact("detail-reveal-button@example.com", true);

        mockMvc.perform(get("/gallery/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reveal contact info")))
                .andExpect(content().string(not(containsString("reachme@example.com"))));
    }

    @Test
    void detail_revealTrue_rendersContactsInlineAndHidesButton() throws Exception {
        Testimonial saved = approvedWithContact("detail-reveal-true@example.com", true);

        mockMvc.perform(get("/gallery/{id}", saved.getId()).param("reveal", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("contacts"))
                .andExpect(content().string(containsString("reachme@example.com")))
                .andExpect(content().string(not(containsString("Reveal contact info"))));
    }

    @Test
    void detail_noPublicContact_hidesRevealButton() throws Exception {
        Testimonial saved = approvedWithContact("detail-no-public-contact@example.com", false);

        mockMvc.perform(get("/gallery/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Reveal contact info"))));
    }

    @Test
    void detail_revealTrueWithoutPublicContact_rendersWithoutErrorAndNoContacts() throws Exception {
        Testimonial saved = approvedWithContact("detail-reveal-no-public@example.com", false);

        mockMvc.perform(get("/gallery/{id}", saved.getId()).param("reveal", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("gallery/detail"))
                .andExpect(model().attributeDoesNotExist("contacts"))
                .andExpect(content().string(not(containsString("reachme@example.com"))));
    }

    @Test
    void detail_noAchievements_hidesAchievementsCard() throws Exception {
        Testimonial saved = approvedTestimonial("detail-no-achievements@example.com", "IN", "general", "Text.");

        mockMvc.perform(get("/gallery/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("gallery-achievement-chip"))));
    }
}
