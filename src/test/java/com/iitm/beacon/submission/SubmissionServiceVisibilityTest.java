package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.error.FieldViolation;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import com.iitm.beacon.testsupport.TestImages;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cascading visibility on the author's side (decision 28,
 * UC-EDIT-TESTIMONIAL): the edit form's data ({@code loadMine}) leaves out
 * sections of invisible topics and ticks of inactive achievements, a saved
 * edit leaves them untouched (rows, photos, files on disk, diff flags) and
 * never lets them affect validation or the decision-18 re-moderation rule,
 * and an incoming section or tick naming an invisible topic or achievement
 * is rejected. Photos are stored in this test's own uploads root, with at
 * most {@value #MAX_PHOTOS} per testimonial and {@value #MAX_PHOTOS_PER_SECTION} per
 * section, so files and limits can be checked.
 */
@SpringBootTest
@Transactional
class SubmissionServiceVisibilityTest {

    private static final int MAX_PHOTOS = 3;
    private static final int MAX_PHOTOS_PER_SECTION = 3;

    private static final List<String> BOTH_ACHIEVEMENTS = List.of(
            CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG, CatalogVisibilityFixture.INACTIVE_ACHIEVEMENT_SLUG);

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @Autowired
    private Clock clock;

    @Autowired
    private Validator validator;

    @Autowired
    private EntityManager entityManager;

    @TempDir
    Path uploadsRoot;

    private SubmissionService submissionService;

    private CatalogVisibilityFixture catalog;

    @BeforeEach
    void setUp() {
        PhotoStorageProperties properties =
                TestPhotoStorage.photoCountLimits(uploadsRoot, MAX_PHOTOS, MAX_PHOTOS_PER_SECTION);
        submissionService = new SubmissionService(
                testimonialRepository,
                topicRepository,
                achievementRepository,
                contactTypeRepository,
                countryRepository,
                emailLookupHashService,
                new PhotoStorageService(
                        properties,
                        new PhotoUrlResolver(),
                        new PhotoImageProcessor(properties),
                        new PhotoFileDeleter(properties)),
                properties,
                clock,
                topicGroupRepository,
                validator);
        catalog = CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
    }

    private static MockMultipartFile png(String part) {
        return new MockMultipartFile(
                part, part + ".png", "image/png", TestImages.png(TestImages.solid(40, 30, TestImages.BLUE)));
    }

    private static TestimonialSubmissionRequest request(List<String> achievements, SectionInput... sections) {
        return request(8, achievements, sections);
    }

    private static TestimonialSubmissionRequest request(
            int score, List<String> achievements, SectionInput... sections) {
        return new TestimonialSubmissionRequest(
                "David", "Jones", "GE26Z001", 2024, "IN", score, List.of(sections), achievements, List.of(), true);
    }

    private static SectionInput section(String topic, String answer, String... fileRefs) {
        return new SectionInput(
                topic, answer, Stream.of(fileRefs).map(ref -> new PhotoInput(ref, List.of("tag"))).toList());
    }

    /**
     * Creates a testimonial while every fixture topic and achievement is
     * visible — a general section, one photo-carrying section under each
     * soon-to-be-hidden topic, and both achievement ticks — then hides them.
     */
    private void createThenHide(String email) {
        catalog.reactivateAll(topicGroupRepository, topicRepository, achievementRepository);
        submissionService.create(
                email,
                request(
                        BOTH_ACHIEVEMENTS,
                        section("general", "General words."),
                        section(CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG, "Inactive words.", "a"),
                        section(CatalogVisibilityFixture.TOPIC_IN_INACTIVE_GROUP_SLUG, "Hidden group words.", "b")),
                Map.of("a", png("a"), "b", png("b")));
        catalog.deactivateAgain(topicGroupRepository, topicRepository, achievementRepository);
        entityManager.flush();
        entityManager.clear();
    }

    private Testimonial reload(String email) {
        entityManager.flush();
        entityManager.clear();
        return testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email)).orElseThrow();
    }

    private static TestimonialSection sectionFor(Testimonial testimonial, String topic) {
        return testimonial.getSections().stream()
                .filter(s -> topic.equals(s.getTopic().getSlug()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No section for " + topic));
    }

    private List<String> uploadedFiles() throws IOException {
        try (Stream<Path> files = Files.list(uploadsRoot)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Full-size and thumbnail file names of every photo in the testimonial's hidden sections. */
    private static List<String> hiddenPhotoFiles(Testimonial testimonial) {
        return testimonial.getSections().stream()
                .filter(s -> !s.getTopic().isVisible())
                .flatMap(s -> s.getPhotos().stream())
                .flatMap(p -> Stream.of(p.getFilePath(), p.getThumbnailPath()))
                .toList();
    }

    private static List<String> tickedSlugs(Testimonial testimonial) {
        return testimonial.getAchievements().stream()
                .map(TestimonialAchievement::getAchievement)
                .map(a -> a.getSlug())
                .toList();
    }

    private void approve(String email) {
        Testimonial testimonial = reload(email);
        testimonial.setStatus(TestimonialStatus.APPROVED);
        testimonial.setIdentityModified(false);
        testimonial.setScoreModified(false);
        testimonial.getSections().forEach(s -> s.setModified(false));
        testimonialRepository.saveAndFlush(testimonial);
    }

    private static List<String> violationFields(SubmissionValidationException ex) {
        return ex.getViolations().stream().map(FieldViolation::field).toList();
    }

    // -- loadMine (edit form, GET /api/submissions/mine) --

    @Test
    void loadMine_leavesOutHiddenSectionsAndHiddenAchievementTicks() {
        createThenHide("vis-mine@example.com");

        TestimonialSubmissionView view = submissionService.loadMine("vis-mine@example.com");

        assertThat(view.sections())
                .extracting(TestimonialSubmissionView.SectionView::topicSlug)
                .containsExactly("general");
        assertThat(view.achievementSlugs()).containsExactly(CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG);
    }

    // -- edit: hidden content is left untouched --

    @Test
    void edit_keepsHiddenSectionsWithTheirPhotosFilesAndHiddenTicks_whileApplyingTheVisibleChanges() throws Exception {
        String email = "vis-edit-keep@example.com";
        createThenHide(email);
        Testimonial before = reload(email);
        List<String> hiddenFiles = hiddenPhotoFiles(before);
        assertThat(hiddenFiles).hasSize(4);

        submissionService.edit(email, request(List.of(), section("general", "Edited general words.")), Map.of());

        Testimonial after = reload(email);
        assertThat(after.getSections())
                .extracting(s -> s.getTopic().getSlug(), TestimonialSection::getAnswerText)
                .containsExactlyInAnyOrder(
                        tuple("general", "Edited general words."),
                        tuple(CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG, "Inactive words."),
                        tuple(CatalogVisibilityFixture.TOPIC_IN_INACTIVE_GROUP_SLUG, "Hidden group words."));
        assertThat(hiddenPhotoFiles(after)).containsExactlyInAnyOrderElementsOf(hiddenFiles);
        assertThat(sectionFor(after, CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG).getPhotos())
                .singleElement()
                .satisfies(p -> assertThat(p.getTags()).extracting(t -> t.getTagText()).containsExactly("tag"));
        assertThat(uploadedFiles()).containsExactlyInAnyOrderElementsOf(hiddenFiles);
        assertThat(tickedSlugs(after)).containsExactly(CatalogVisibilityFixture.INACTIVE_ACHIEVEMENT_SLUG);
    }

    @Test
    void edit_thenReactivation_bringsTheUntouchedHiddenContentBack() {
        String email = "vis-edit-then-reactivate@example.com";
        createThenHide(email);

        submissionService.edit(email, request(List.of(), section("general", "Edited general words.")), Map.of());
        catalog.reactivateAll(topicGroupRepository, topicRepository, achievementRepository);
        entityManager.clear();

        TestimonialSubmissionView view = submissionService.loadMine(email);
        assertThat(view.sections())
                .extracting(TestimonialSubmissionView.SectionView::answer)
                .containsExactlyInAnyOrder("Edited general words.", "Inactive words.", "Hidden group words.");
        assertThat(view.achievementSlugs()).containsExactly(CatalogVisibilityFixture.INACTIVE_ACHIEVEMENT_SLUG);
    }

    @Test
    void edit_ofAnApprovedTestimonial_changingOnlyTheScore_staysApproved_andLeavesHiddenFlagsAlone() {
        String email = "vis-edit-approved@example.com";
        createThenHide(email);
        approve(email);
        // A hidden section still flagged from an earlier edit must neither be
        // cleared nor count as "this edit modified a section" (decision 18).
        Testimonial approved = reload(email);
        sectionFor(approved, CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG).setModified(true);
        testimonialRepository.saveAndFlush(approved);

        SubmissionResultResponse result = submissionService.edit(
                email,
                request(
                        5,
                        List.of(CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG),
                        section("general", "General words.")),
                Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.APPROVED);
        Testimonial after = reload(email);
        assertThat(after.getRecommendationScore()).isEqualTo(5);
        assertThat(sectionFor(after, CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG).isModified()).isTrue();
        assertThat(sectionFor(after, CatalogVisibilityFixture.TOPIC_IN_INACTIVE_GROUP_SLUG).isModified()).isFalse();
        assertThat(sectionFor(after, "general").isModified()).isFalse();
    }

    @Test
    void edit_ofAnApprovedTestimonial_changingVisibleText_goesPending_andLeavesHiddenSectionsUnflagged() {
        String email = "vis-edit-approved-text@example.com";
        createThenHide(email);
        approve(email);

        SubmissionResultResponse result = submissionService.edit(
                email,
                request(List.of(CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG), section("general", "New words.")),
                Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.PENDING);
        Testimonial after = reload(email);
        assertThat(sectionFor(after, "general").isModified()).isTrue();
        assertThat(sectionFor(after, CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG).isModified()).isFalse();
        assertThat(sectionFor(after, CatalogVisibilityFixture.TOPIC_IN_INACTIVE_GROUP_SLUG).isModified()).isFalse();
    }

    // -- edit: hidden content does not count toward the rules --

    @Test
    void edit_withNoVisibleSectionAnswered_isRejected_hiddenSectionsDoNotCount() {
        String email = "vis-edit-none-visible@example.com";
        createThenHide(email);

        assertThatThrownBy(() -> submissionService.edit(email, request(List.of(), section("general", "  ")), Map.of()))
                .isInstanceOfSatisfying(SubmissionValidationException.class, ex -> assertThat(ex.getViolations())
                        .contains(new FieldViolation(
                                "sections", TestimonialSubmissionRequest.AT_LEAST_ONE_SECTION_MESSAGE)));
        assertThat(reload(email).getSections()).hasSize(3);
    }

    @Test
    void edit_photoLimit_countsOnlyVisiblePhotos_soTheLimitIsReachableBesideHiddenOnes() throws Exception {
        String email = "vis-edit-photo-limit@example.com";
        createThenHide(email);

        submissionService.edit(
                email,
                request(List.of(), section("general", "General words.", "x", "y", "z")),
                Map.of("x", png("x"), "y", png("y"), "z", png("z")));

        Testimonial after = reload(email);
        assertThat(sectionFor(after, "general").getPhotos()).hasSize(MAX_PHOTOS);
        assertThat(after.getSections().stream().mapToInt(s -> s.getPhotos().size()).sum()).isEqualTo(MAX_PHOTOS + 2);
        assertThat(uploadedFiles()).hasSize((MAX_PHOTOS + 2) * 2);
    }

    @Test
    void edit_photoLimit_oneOverTheVisibleLimit_isStillRejected() {
        String email = "vis-edit-photo-limit-over@example.com";
        createThenHide(email);

        assertThatThrownBy(() -> submissionService.edit(
                        email,
                        request(
                                List.of(),
                                section("general", "General words.", "w", "x"),
                                section(CatalogVisibilityFixture.VISIBLE_TOPIC_SLUG, "Visible words.", "y", "z")),
                        Map.of("w", png("w"), "x", png("x"), "y", png("y"), "z", png("z"))))
                .isInstanceOf(SubmissionValidationException.class);
    }

    // -- edit/create: incoming references to invisible catalog entries are rejected --

    @Test
    void edit_incomingSectionOfAnInactiveTopic_isRejected_andNothingChanges() throws Exception {
        String email = "vis-edit-incoming-inactive@example.com";
        createThenHide(email);
        List<String> filesBefore = uploadedFiles();

        assertThatThrownBy(() -> submissionService.edit(
                        email,
                        request(
                                List.of(),
                                section("general", "Edited."),
                                section(CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG, "Rewritten hidden words.")),
                        Map.of()))
                .isInstanceOfSatisfying(SubmissionValidationException.class, ex -> assertThat(violationFields(ex))
                        .containsExactly("sections[1].topicSlug"));
        assertThat(sectionFor(reload(email), CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG).getAnswerText())
                .isEqualTo("Inactive words.");
        assertThat(uploadedFiles()).isEqualTo(filesBefore);
    }

    @Test
    void edit_incomingTickOfAnInactiveAchievement_isRejected() {
        String email = "vis-edit-incoming-achievement@example.com";
        createThenHide(email);

        assertThatThrownBy(() -> submissionService.edit(
                        email,
                        request(
                                List.of(CatalogVisibilityFixture.INACTIVE_ACHIEVEMENT_SLUG),
                                section("general", "General words.")),
                        Map.of()))
                .isInstanceOfSatisfying(SubmissionValidationException.class, ex -> assertThat(violationFields(ex))
                        .containsExactly("achievementSlugs"));
    }

    @Test
    void create_sectionOfAnActiveTopicInAnInactiveGroup_isRejected() {
        assertThatThrownBy(() -> submissionService.create(
                        "vis-create-hidden-group@example.com",
                        request(
                                List.of(),
                                section("general", "General words."),
                                section(CatalogVisibilityFixture.TOPIC_IN_INACTIVE_GROUP_SLUG, "Words.")),
                        Map.of()))
                .isInstanceOfSatisfying(SubmissionValidationException.class, ex -> assertThat(violationFields(ex))
                        .containsExactly("sections[1].topicSlug"));
        assertThat(testimonialRepository.findByEmailLookupHash(
                        emailLookupHashService.hash("vis-create-hidden-group@example.com")))
                .isEmpty();
    }

    @Test
    void create_sectionOfAnInactiveTopic_andAnInactiveAchievement_areRejected() {
        assertThatThrownBy(() -> submissionService.create(
                        "vis-create-inactive@example.com",
                        request(
                                List.of(CatalogVisibilityFixture.INACTIVE_ACHIEVEMENT_SLUG),
                                section(CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG, "Words.")),
                        Map.of()))
                .isInstanceOfSatisfying(SubmissionValidationException.class, ex -> assertThat(violationFields(ex))
                        .containsExactlyInAnyOrder("sections[0].topicSlug", "achievementSlugs"));
    }

    // -- the form's catalog --

    @Test
    void listTopicCatalog_offersNoInvisibleTopic_andListActiveAchievementsNoInactiveOne() {
        List<TopicCatalogEntryDto> entries = submissionService.listTopicCatalog();

        assertThat(entries)
                .filteredOn(e -> "GROUP".equals(e.kind()) && catalog.activeGroup().getId().equals(e.groupId()))
                .singleElement()
                .satisfies(e -> assertThat(e.subtopics())
                        .extracting(TopicPickDto::slug)
                        .containsExactly(CatalogVisibilityFixture.VISIBLE_TOPIC_SLUG));
        assertThat(entries)
                .noneMatch(e -> catalog.inactiveGroup().getId().equals(e.groupId()))
                .noneMatch(e -> catalog.inactiveTopic().getId().equals(e.topicId()));
        assertThat(submissionService.listActiveAchievements())
                .extracting(AchievementView::slug)
                .contains(CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG)
                .doesNotContain(CatalogVisibilityFixture.INACTIVE_ACHIEVEMENT_SLUG);
    }

}
