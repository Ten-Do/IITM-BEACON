package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.error.FieldViolation;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import com.iitm.beacon.testsupport.TestImages;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import jakarta.validation.Validator;
import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The photo count limits of {@link SubmissionService#create}/{@link
 * SubmissionService#edit}, shared by the JSON API and the HTML form: at most
 * {@code max-photos-per-section} photos per section (topic) and {@code
 * max-photos-per-testimonial} in total — both counting the photos a section
 * keeps plus the new uploads, i.e. the photos the testimonial would end up
 * with. A breach is reported before anything is stored or deleted.
 */
@SpringBootTest
@Transactional
class SubmissionServicePhotoLimitsTest {

    private static final String PHOTOS_NEED_TEXT_MESSAGE =
            "Photos need some text — write something here, or remove the photos.";

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private PhotoStorageProperties configured;

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

    @TempDir
    Path uploadsRoot;

    private final PhotoUrlResolver urls = new PhotoUrlResolver();
    private int partCounter;

    // -- fixtures --

    /** A service with these limits whose photos land in {@link #uploadsRoot}, so stored files can be counted. */
    private SubmissionService service(int maxPhotos, int maxPhotosPerSection) {
        PhotoStorageProperties properties =
                TestPhotoStorage.photoCountLimits(uploadsRoot, maxPhotos, maxPhotosPerSection);
        return new SubmissionService(
                testimonialRepository,
                topicRepository,
                achievementRepository,
                contactTypeRepository,
                countryRepository,
                emailLookupHashService,
                new PhotoStorageService(properties, urls, new PhotoImageProcessor(properties)),
                properties,
                clock,
                topicGroupRepository,
                validator);
    }

    /** {@link #service} with the limits the application is configured with (50 in total, 5 per section). */
    private SubmissionService configuredService() {
        return service(configured.maxPhotosPerTestimonial(), configured.maxPhotosPerSection());
    }

    /** Every active topic slug, in catalog order. */
    private List<String> topicSlugs() {
        List<String> slugs = new ArrayList<>();
        for (TopicCatalogEntryDto entry : submissionService.listTopicCatalog()) {
            if ("GROUP".equals(entry.kind())) {
                entry.subtopics().forEach(pick -> slugs.add(pick.slug()));
            } else {
                slugs.add(entry.slug());
            }
        }
        return slugs;
    }

    /** Builds sections and the matching file parts: {@code newPhotos} new uploads per topic. */
    private final class Draft {

        private final List<SectionInput> sections = new ArrayList<>();
        private final Map<String, MultipartFile> files = new HashMap<>();

        Draft section(String topicSlug, String answer, List<String> keptUrls, int newPhotos) {
            List<PhotoInput> photos = new ArrayList<>();
            keptUrls.forEach(url -> photos.add(new PhotoInput(url, List.of())));
            for (int n = 0; n < newPhotos; n++) {
                String ref = "part-" + partCounter++;
                files.put(ref, new MockMultipartFile(ref, ref + ".png", "image/png", tinyPng()));
                photos.add(new PhotoInput(ref, List.of()));
            }
            sections.add(new SectionInput(topicSlug, answer, photos));
            return this;
        }

        Draft section(String topicSlug, int newPhotos) {
            return section(topicSlug, "Text for " + topicSlug + ".", List.of(), newPhotos);
        }

        TestimonialSubmissionRequest request() {
            return new TestimonialSubmissionRequest(
                    "David", "Jones", "CS21B001", 2024, "IN", 8, sections, List.of(), List.of(), true);
        }
    }

    private static byte[] tinyPng() {
        return TestImages.png(TestImages.solid(2, 2, TestImages.RED));
    }

    private static List<FieldViolation> violationsOf(ThrowingCallable call) {
        SubmissionValidationException ex = catchThrowableOfType(SubmissionValidationException.class, call);
        assertThat(ex).as("expected a SubmissionValidationException").isNotNull();
        return ex.getViolations();
    }

    private static String perTopic(int max) {
        return "At most " + max + " photos per topic.";
    }

    private static String total(int max) {
        return "Too many photos: maximum is " + max + ".";
    }

    private int storedFileCount() {
        File[] files = uploadsRoot.toFile().listFiles();
        return files == null ? 0 : files.length;
    }

    private boolean nothingSavedFor(String email) {
        return testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email)).isEmpty();
    }

    private Testimonial savedFor(String email) {
        return testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email)).orElseThrow();
    }

    private TestimonialSection sectionOf(String email, String topicSlug) {
        return savedFor(email).getSections().stream()
                .filter(s -> topicSlug.equals(s.getTopic().getSlug()))
                .findFirst()
                .orElseThrow();
    }

    private List<String> photoUrlsOf(String email, String topicSlug) {
        return sectionOf(email, topicSlug).getPhotos().stream().map(p -> urls.resolve(p.getFilePath())).toList();
    }

    // -- per section, create --

    @Test
    void create_fivePhotosInOneTopic_isAcceptedAndAllFiveAreStored() {
        String email = "limits-five-stored@example.com";
        Draft draft = new Draft().section("general", 5);

        configuredService().create(email, draft.request(), draft.files);

        assertThat(sectionOf(email, "general").getPhotos()).hasSize(5);
    }

    @Test
    void create_sixPhotosInOneTopic_isOneViolationAtThatSectionsPhotos_andNothingIsStored() {
        String email = "limits-six@example.com";
        Draft draft = new Draft().section("general", 1).section("networking", 6);

        List<FieldViolation> violations =
                violationsOf(() -> configuredService().create(email, draft.request(), draft.files));

        assertThat(violations).containsExactly(new FieldViolation("sections[1].photos", perTopic(5)));
        assertThat(storedFileCount()).isZero();
        assertThat(nothingSavedFor(email)).isTrue();
    }

    @Test
    void create_perTopicMessage_namesTheConfiguredLimit() {
        Draft draft = new Draft().section("general", 3);

        List<FieldViolation> violations =
                violationsOf(() -> service(50, 2).create("limits-two@example.com", draft.request(), draft.files));

        assertThat(violations).containsExactly(new FieldViolation("sections[0].photos", perTopic(2)));
    }

    @Test
    void create_perTopicLimitOfOne_isWordedInTheSingular() {
        Draft draft = new Draft().section("general", 2);

        List<FieldViolation> violations =
                violationsOf(() -> service(50, 1).create("limits-one@example.com", draft.request(), draft.files));

        assertThat(violations).containsExactly(new FieldViolation("sections[0].photos", "At most 1 photo per topic."));
    }

    @Test
    void create_fivePhotosInEachOfTwoTopics_isAccepted_theLimitIsPerTopicNotCumulative() {
        String email = "limits-five-twice@example.com";
        Draft draft = new Draft().section("general", 5).section("networking", 5);

        configuredService().create(email, draft.request(), draft.files);

        assertThat(sectionOf(email, "general").getPhotos()).hasSize(5);
        assertThat(sectionOf(email, "networking").getPhotos()).hasSize(5);
    }

    @Test
    void create_everyTopicOverTheLimit_isReportedAtEachOfThem() {
        Draft draft = new Draft().section("general", 6).section("networking", 2).section("travel_did", 7);

        List<FieldViolation> violations = violationsOf(
                () -> configuredService().create("limits-many-over@example.com", draft.request(), draft.files));

        assertThat(violations).containsExactly(
                new FieldViolation("sections[0].photos", perTopic(5)),
                new FieldViolation("sections[2].photos", perTopic(5)));
    }

    @Test
    void create_blankTopicWithSixPhotos_isOnlyThePhotosNeedTextViolation() {
        Draft draft = new Draft().section("general", 1).section("networking", "  ", List.of(), 6);

        List<FieldViolation> violations = violationsOf(
                () -> configuredService().create("limits-blank-six@example.com", draft.request(), draft.files));

        assertThat(violations).containsExactly(new FieldViolation("sections[1].answer", PHOTOS_NEED_TEXT_MESSAGE));
    }

    // -- in total, create --

    @Test
    void create_exactlyFiftyPhotosAcrossTenTopics_isAccepted() {
        String email = "limits-fifty@example.com";
        List<String> slugs = topicSlugs();
        assertThat(slugs).hasSizeGreaterThanOrEqualTo(11);
        Draft draft = new Draft();
        for (int t = 0; t < 10; t++) {
            draft.section(slugs.get(t), 5);
        }

        configuredService().create(email, draft.request(), draft.files);

        assertThat(savedFor(email).getSections()).flatExtracting(TestimonialSection::getPhotos).hasSize(50);
    }

    @Test
    void create_fiftyOnePhotos_noTopicOverItsLimit_isOnlyTheGlobalTotalViolation_andNothingIsStored() {
        String email = "limits-fifty-one@example.com";
        List<String> slugs = topicSlugs();
        Draft draft = new Draft();
        for (int t = 0; t < 10; t++) {
            draft.section(slugs.get(t), 5);
        }
        draft.section(slugs.get(10), 1);

        List<FieldViolation> violations =
                violationsOf(() -> submissionService.create(email, draft.request(), draft.files));

        assertThat(violations).containsExactly(FieldViolation.global(total(50)));
        assertThat(nothingSavedFor(email)).isTrue();
    }

    @Test
    void create_overBothLimits_reportsBothViolations() {
        Draft draft = new Draft().section("general", 4);

        List<FieldViolation> violations = violationsOf(
                () -> service(3, 2).create("limits-both@example.com", draft.request(), draft.files));

        assertThat(violations).containsExactlyInAnyOrder(
                new FieldViolation("sections[0].photos", perTopic(2)), FieldViolation.global(total(3)));
        assertThat(storedFileCount()).isZero();
    }

    // -- per section, edit: kept photos count too --

    /** Creates a testimonial whose {@code general} section has {@code photos} photos, with room for them. */
    private List<String> createdWithGeneralPhotos(String email, int photos) {
        Draft draft = new Draft().section("general", photos);
        service(50, Math.max(photos, 1)).create(email, draft.request(), draft.files);
        return photoUrlsOf(email, "general");
    }

    @Test
    void edit_threeKeptPlusTwoNew_isFiveAndAccepted() {
        String email = "limits-edit-3-2@example.com";
        List<String> kept = createdWithGeneralPhotos(email, 3);
        Draft edit = new Draft().section("general", "Edited.", kept, 2);

        configuredService().edit(email, edit.request(), edit.files);

        assertThat(sectionOf(email, "general").getPhotos()).hasSize(5);
    }

    @Test
    void edit_threeKeptPlusThreeNew_isRejected_andNeitherStoresNorDeletesAnyFile() {
        String email = "limits-edit-3-3@example.com";
        List<String> kept = createdWithGeneralPhotos(email, 3);
        int filesBefore = storedFileCount();
        Draft edit = new Draft().section("general", "Edited.", kept, 3);

        List<FieldViolation> violations =
                violationsOf(() -> configuredService().edit(email, edit.request(), edit.files));

        assertThat(violations).containsExactly(new FieldViolation("sections[0].photos", perTopic(5)));
        assertThat(storedFileCount()).isEqualTo(filesBefore);
        assertThat(sectionOf(email, "general").getAnswerText()).isEqualTo("Text for general.");
        assertThat(photoUrlsOf(email, "general")).containsExactlyElementsOf(kept);
    }

    @Test
    void edit_fiveSavedPhotos_twoRemovedAndTwoNewAdded_isAccepted_removedPhotosFreeUpTheAllowance() {
        String email = "limits-edit-remove-frees@example.com";
        List<String> saved = createdWithGeneralPhotos(email, 5);
        Draft edit = new Draft().section("general", "Edited.", saved.subList(0, 3), 2);

        configuredService().edit(email, edit.request(), edit.files);

        List<String> after = photoUrlsOf(email, "general");
        assertThat(after).hasSize(5).startsWith(saved.subList(0, 3).toArray(String[]::new));
        assertThat(after).doesNotContain(saved.get(3), saved.get(4));
    }

    @Test
    void edit_fiveSavedPhotos_oneMoreAdded_isRejected() {
        String email = "limits-edit-5-1@example.com";
        List<String> saved = createdWithGeneralPhotos(email, 5);
        Draft edit = new Draft().section("general", "Edited.", saved, 1);

        assertThat(violationsOf(() -> configuredService().edit(email, edit.request(), edit.files)))
                .containsExactly(new FieldViolation("sections[0].photos", perTopic(5)));
    }

    @Test
    void edit_sixPhotosSavedUnderAnOlderLimit_keptAsTheyAre_isRejected() {
        // Saved before the per-topic limit existed (or under a higher one):
        // the limit applies to what the testimonial would end up with.
        String email = "limits-edit-legacy-six@example.com";
        List<String> saved = createdWithGeneralPhotos(email, 6);
        Draft edit = new Draft().section("general", "Edited.", saved, 0);

        assertThat(violationsOf(() -> configuredService().edit(email, edit.request(), edit.files)))
                .containsExactly(new FieldViolation("sections[0].photos", perTopic(5)));
        assertThat(photoUrlsOf(email, "general")).hasSize(6);
    }

    @Test
    void edit_sixPhotosSavedUnderAnOlderLimit_oneRemoved_isAccepted() {
        String email = "limits-edit-legacy-five@example.com";
        List<String> saved = createdWithGeneralPhotos(email, 6);
        Draft edit = new Draft().section("general", "Edited.", saved.subList(1, 6), 0);

        configuredService().edit(email, edit.request(), edit.files);

        assertThat(photoUrlsOf(email, "general")).containsExactlyElementsOf(saved.subList(1, 6));
    }

    // -- in total, edit --

    @Test
    void edit_totalCountsTheKeptPhotosOfEveryTopicPlusTheNewOnes() {
        String email = "limits-edit-total@example.com";
        List<String> kept = createdWithGeneralPhotos(email, 2);
        Draft atLimit = new Draft().section("general", "Edited.", kept, 0).section("networking", 1);
        Draft overLimit = new Draft().section("general", "Edited.", kept, 0).section("networking", 2);

        assertThat(violationsOf(() -> service(3, 5).edit(email, overLimit.request(), overLimit.files)))
                .containsExactly(FieldViolation.global(total(3)));
        service(3, 5).edit(email, atLimit.request(), atLimit.files);

        assertThat(savedFor(email).getSections()).flatExtracting(TestimonialSection::getPhotos)
                .extracting(Photo::getFilePath)
                .filteredOn(Objects::nonNull)
                .hasSize(3);
    }
}
