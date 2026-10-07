package com.iitm.beacon.gallery;

import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTag;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTagsWithAttribute;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * handling for an unknown/pending/rejected id, and list-page
 * filter/pagination boundaries. The reveal-contact flow has its own class,
 * {@link GalleryContactRevealTest}.
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
                .andExpect(content().string(
                        allOf(containsString("gallery-pagination-status"), containsString("Next"))));
    }

    @Test
    void list_cardWithoutPhoto_rendersPlaceholderNotBrokenImage() throws Exception {
        approvedTestimonial("list-no-photo@example.com", "IN", "general", "No photo here.");

        mockMvc.perform(get("/gallery"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gallery-card-photo-placeholder")));
    }

    /** An {@code <img>} tag whose {@code src} is exactly {@code url}, whatever its other attributes. */
    private static Pattern imgWithSrc(String url) {
        return Pattern.compile("<img\\b[^>]*\\ssrc=\"" + Pattern.quote(url) + "\"[^>]*>");
    }

    /** Approved single-section testimonial with one photo stored under {@code filePath}. */
    private Testimonial approvedTestimonialWithPhoto(String email, String filePath) {
        Testimonial t = baseBuilder(email, "IN").build();
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("Look at this view.")
                .modified(false)
                .build();
        section.getPhotos().add(Photo.builder().section(section).filePath(filePath).displayOrder(0).build());
        t.getSections().add(section);
        return testimonialRepository.saveAndFlush(t);
    }

    @Test
    void list_cardWithPhoto_rendersAnImgPointingAtTheUploadsUrl() throws Exception {
        String filePath = UUID.randomUUID() + ".png";
        approvedTestimonialWithPhoto("list-photo@example.com", filePath);

        String html = mockMvc.perform(get("/gallery"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).containsPattern(imgWithSrc("/uploads/" + filePath));
    }

    @Test
    void list_cardWithConvertedPhoto_showsItsThumbnail_notTheFullSizeImage() throws Exception {
        String id = UUID.randomUUID().toString();
        Testimonial t = approvedTestimonialWithPhoto("list-photo-thumb@example.com", id + ".webp");
        t.getSections().get(0).getPhotos().get(0).setThumbnailPath(id + "-thumb.webp");
        testimonialRepository.saveAndFlush(t);

        String html = mockMvc.perform(get("/gallery"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).containsPattern(imgWithSrc("/uploads/" + id + "-thumb.webp"));
        assertThat(html).doesNotContain("/uploads/" + id + ".webp");
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

    /**
     * Heading outline: h1 name, h2 per topic card, h3 per subtopic of a
     * grouped card. A standalone topic's label is already its card's h2, so
     * it gets no subtopic heading at all.
     */
    @Test
    void detail_groupedSubtopicHeadingIsAnH3UnderItsGroupsH2_standaloneTopicHasNone() throws Exception {
        Testimonial t = baseBuilder("detail-headings@example.com", "IN").build();
        Topic academicsStandout = topicRepository.findBySlug("academics_standout").orElseThrow();
        Topic networking = topicRepository.findBySlug("networking").orElseThrow();
        t.getSections().add(TestimonialSection.builder()
                .testimonial(t).topic(academicsStandout).answerText("Grouped answer.").modified(true).build());
        t.getSections().add(TestimonialSection.builder()
                .testimonial(t).topic(networking).answerText("Standalone answer.").modified(false).build());
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        String html = mockMvc.perform(get("/gallery/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Matcher h3 = Pattern.compile(
                        "<h3 class=\"gallery-subtopic-heading\">([\\s\\S]*?)</h3>")
                .matcher(html);
        assertThat(h3.find()).as("subtopic h3").isTrue();
        assertThat(h3.group(1))
                .contains(">" + academicsStandout.getLabel() + "<")
                .contains("gallery-updated-badge");
        assertThat(h3.find()).as("only the grouped section gets a subtopic heading").isFalse();
        assertThat(html).doesNotContain("<div class=\"gallery-subtopic-heading\"");
        assertThat(html.indexOf("<h2 class=\"gallery-group-heading\">Academics</h2>"))
                .as("group h2")
                .isNotNegative()
                .isLessThan(html.indexOf("<h3 class=\"gallery-subtopic-heading\">"));
    }

    @Test
    void detail_approvedTestimonialWithPhoto_rendersAnImgPointingAtTheUploadsUrl() throws Exception {
        String filePath = UUID.randomUUID() + ".png";
        Testimonial saved = approvedTestimonialWithPhoto("detail-photo@example.com", filePath);

        String html = mockMvc.perform(get("/gallery/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("gallery/detail"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).containsPattern(imgWithSrc("/uploads/" + filePath));
    }

    // -- GET /gallery/{id}: photos for the fullscreen viewer (PhotoSwipe) --

    private String detailHtml(Long id) throws Exception {
        return mockMvc.perform(get("/gallery/{id}", id))
                .andExpect(status().isOk())
                .andExpect(view().name("gallery/detail"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** Stores a converted photo's thumbnail and full-size pixel size on the testimonial's only photo. */
    private Testimonial converted(Testimonial t, String id, int width, int height) {
        Photo photo = t.getSections().get(0).getPhotos().get(0);
        photo.setThumbnailPath(id + "-thumb.webp");
        photo.setWidth(width);
        photo.setHeight(height);
        return testimonialRepository.saveAndFlush(t);
    }

    private Testimonial withTags(Testimonial t, String... tags) {
        Photo photo = t.getSections().get(0).getPhotos().get(0);
        for (String tag : tags) {
            photo.getTags().add(PhotoTag.builder().photo(photo).tagText(tag).build());
        }
        return testimonialRepository.saveAndFlush(t);
    }

    private TestimonialSection sectionWithPhoto(Testimonial t, String topicSlug, String filePath) {
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug(topicSlug).orElseThrow())
                .answerText("Answer for " + topicSlug + ".")
                .modified(false)
                .build();
        section.getPhotos().add(Photo.builder().section(section).filePath(filePath).displayOrder(0).build());
        return section;
    }

    @Test
    void detail_convertedPhoto_isALinkToTheFullSizePhoto_wrappingItsLazyThumbnail_withTheFullSize()
            throws Exception {
        String id = UUID.randomUUID().toString();
        Testimonial saved =
                converted(approvedTestimonialWithPhoto("detail-pswp@example.com", id + ".webp"), id, 2560, 1440);

        List<String> figures = elements(detailHtml(saved.getId()), "figure");

        assertThat(figures).hasSize(1);
        String figure = figures.get(0);
        String link = openingTag(figure, "a");
        assertThat(attribute(link, "href")).contains("/uploads/" + id + ".webp");
        assertThat(attribute(link, "target")).contains("_blank");
        assertThat(attribute(link, "data-photo-viewer-item")).isPresent();
        assertThat(attribute(link, "data-pswp-width")).contains("2560");
        assertThat(attribute(link, "data-pswp-height")).contains("1440");
        String img = openingTag(figure, "img");
        assertThat(attribute(img, "src")).contains("/uploads/" + id + "-thumb.webp");
        assertThat(attribute(img, "loading")).contains("lazy");
        assertThat(attribute(img, "alt")).contains("General");
        assertThat(figure.indexOf("<img"))
                .as("the thumbnail sits inside the link")
                .isGreaterThan(figure.indexOf("<a"))
                .isLessThan(figure.indexOf("</a>"));
    }

    @Test
    void detail_legacyPhotoOfUnknownSize_hasNoSizeAttributes_andItsFullImageIsTheThumbnail() throws Exception {
        String filePath = UUID.randomUUID() + ".png";
        Testimonial saved = approvedTestimonialWithPhoto("detail-pswp-legacy@example.com", filePath);

        String figure = elements(detailHtml(saved.getId()), "figure").get(0);

        String link = openingTag(figure, "a");
        assertThat(attribute(link, "href")).contains("/uploads/" + filePath);
        assertThat(attribute(link, "data-pswp-width")).isEmpty();
        assertThat(attribute(link, "data-pswp-height")).isEmpty();
        assertThat(attribute(openingTag(figure, "img"), "src")).contains("/uploads/" + filePath);
    }

    @Test
    void detail_photoCaption_namesGroupAndSubtopic_orJustAStandaloneTopic_escaped() throws Exception {
        Testimonial t = baseBuilder("detail-pswp-captions@example.com", "IN").build();
        t.getSections().add(sectionWithPhoto(t, "housing_food", "2026/01/food.png"));
        t.getSections().add(sectionWithPhoto(t, "networking", "2026/01/net.png"));
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        List<String> links = elements(detailHtml(saved.getId()), "figure").stream()
                .map(figure -> openingTag(figure, "a"))
                .toList();

        assertThat(links)
                .extracting(link -> attribute(link, "data-caption").orElseThrow())
                .containsExactly("Housing &amp; Food — Mess/Canteen Food", "Professional Networking");
    }

    @Test
    void detail_photoTags_areChipsUnderTheThumbnail_insideThePhotosFigure() throws Exception {
        Testimonial saved = withTags(
                approvedTestimonialWithPhoto("detail-pswp-tags@example.com", "2026/01/t.png"), "sunset", "campus");

        String figure = elements(detailHtml(saved.getId()), "figure").get(0);

        List<String> lists = elements(figure, "ul");
        assertThat(lists).hasSize(1);
        assertThat(attribute(openingTag(lists.get(0), "ul"), "class")).contains("photo-tag-list");
        assertThat(elements(lists.get(0), "li"))
                .allSatisfy(chip -> assertThat(attribute(openingTag(chip, "li"), "class")).contains("photo-tag-chip"))
                // A chip under a thumbnail is cut short with an ellipsis if too long; the title has it in full.
                .allSatisfy(chip -> assertThat(attribute(openingTag(chip, "li"), "title"))
                        .contains(chip.replaceAll("<[^>]+>", "")))
                .extracting(chip -> chip.replaceAll("<[^>]+>", ""))
                .containsExactlyInAnyOrder("sunset", "campus");
        assertThat(figure.indexOf("<ul")).as("chips come after the photo link").isGreaterThan(figure.indexOf("</a>"));
    }

    @Test
    void detail_photoTagWithMarkup_isRenderedAsText() throws Exception {
        Testimonial saved = withTags(
                approvedTestimonialWithPhoto("detail-pswp-tag-xss@example.com", "2026/01/x.png"),
                "<script>alert('tag')</script>");

        String html = detailHtml(saved.getId());

        assertThat(html).doesNotContain("<script>alert");
        String chip = elements(elements(html, "figure").get(0), "li").get(0);
        assertThat(chip.replaceAll("<[^>]+>", "")).isEqualTo("&lt;script&gt;alert(&#39;tag&#39;)&lt;/script&gt;");
        assertThat(attribute(openingTag(chip, "li"), "title"))
                .contains("&lt;script&gt;alert(&#39;tag&#39;)&lt;/script&gt;");
    }

    @Test
    void detail_photoWithoutTags_rendersNoTagList() throws Exception {
        Testimonial saved = approvedTestimonialWithPhoto("detail-pswp-no-tags@example.com", "2026/01/n.png");

        assertThat(detailHtml(saved.getId())).doesNotContain("photo-tag-list", "photo-tag-chip");
    }

    @Test
    void detail_wholeArticleIsOnePhotoGallery_spanningAllItsSections() throws Exception {
        Testimonial t = baseBuilder("detail-pswp-one-gallery@example.com", "IN").build();
        t.getSections().add(sectionWithPhoto(t, "housing_food", "2026/01/first.png"));
        t.getSections().add(sectionWithPhoto(t, "networking", "2026/01/second.png"));
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        String html = detailHtml(saved.getId());

        List<String> galleries = openingTagsWithAttribute(html, "data-photo-gallery");
        assertThat(galleries).hasSize(1);
        assertThat(attribute(galleries.get(0), "class")).contains("gallery-detail-main");
        int galleryStart = html.indexOf(galleries.get(0));
        int articleEnd = html.indexOf("gallery-sidebar");
        assertThat(html.indexOf("href=\"/uploads/2026/01/first.png\"")).isBetween(galleryStart, articleEnd);
        assertThat(html.indexOf("href=\"/uploads/2026/01/second.png\"")).isBetween(galleryStart, articleEnd);
    }

    @Test
    void detail_loadsPhotoSwipesStylesheetAndTheViewerAsAModule_andNoLongerTheOldViewerTemplate()
            throws Exception {
        Testimonial saved = approvedTestimonialWithPhoto("detail-pswp-assets@example.com", "2026/01/a.png");

        String html = detailHtml(saved.getId());

        assertThat(openingTags(html, "link"))
                .filteredOn(tag -> attribute(tag, "href").orElse("").equals("/webjars/photoswipe/dist/photoswipe.css"))
                .singleElement()
                .satisfies(tag -> assertThat(attribute(tag, "rel")).contains("stylesheet"));
        assertThat(openingTags(html, "script"))
                .filteredOn(tag -> attribute(tag, "src").orElse("").equals("/js/photo-viewer.js"))
                .singleElement()
                .satisfies(tag -> assertThat(attribute(tag, "type")).contains("module"));
        assertThat(html).doesNotContain("photo-viewer-template");
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
    void detail_noPublicContact_hidesRevealButton() throws Exception {
        Testimonial saved = approvedWithContact("detail-no-public-contact@example.com", false);

        mockMvc.perform(get("/gallery/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Reveal contact info"))));
    }

    @Test
    void detail_noAchievements_hidesAchievementsCard() throws Exception {
        Testimonial saved = approvedTestimonial("detail-no-achievements@example.com", "IN", "general", "Text.");

        mockMvc.perform(get("/gallery/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("gallery-achievement-chip"))));
    }
}
