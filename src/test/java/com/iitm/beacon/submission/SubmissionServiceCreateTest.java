package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.common.error.TestimonialAlreadyExistsException;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.ContactMethodInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import jakarta.validation.Validator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@SpringBootTest
@Transactional
class SubmissionServiceCreateTest {

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @Autowired
    private PhotoStorageService photoStorageService;

    @Autowired
    private Clock clock;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private Validator validator;

    @TempDir
    Path uploadsRoot;

    private static byte[] realPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private TestimonialSubmissionRequest.SectionInput section(String topicSlug, String answer) {
        return new SectionInput(topicSlug, answer, List.of());
    }

    private TestimonialSubmissionRequest validRequest(String countryCode, List<SectionInput> sections) {
        return new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                countryCode,
                8,
                sections,
                List.of(),
                List.of(),
                true);
    }

    @Test
    void create_validRequest_persistsPendingTestimonialAndReturnsItsId() {
        TestimonialSubmissionRequest request =
                validRequest("IN", List.of(section("general", "Great time overall.")));

        SubmissionResultResponse response = submissionService.create("new-visitor@example.com", request, Map.of());

        assertThat(response.status()).isEqualTo(TestimonialStatus.PENDING);
        Testimonial saved = testimonialRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getSections()).hasSize(1);
        assertThat(saved.getSections().get(0).getAnswerText()).isEqualTo("Great time overall.");
        assertThat(saved.getCountry().getCode()).isEqualTo("IN");
    }

    @Test
    void create_withPhotoAndTags_storesPhotoAndDedupedTags() throws Exception {
        MockMultipartFile file = new MockMultipartFile("photo", "cat.png", "image/png", realPngBytes());
        SectionInput sectionWithPhoto = new SectionInput(
                "general",
                "Loved it here.",
                List.of(new PhotoInput("photo-0", List.of("sunset", "sunset", "beach"))));
        TestimonialSubmissionRequest request = validRequest("IN", List.of(sectionWithPhoto));

        SubmissionResultResponse response =
                submissionService.create("photo-visitor@example.com", request, Map.of("photo-0", file));

        Testimonial saved = testimonialRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getSections().get(0).getPhotos()).hasSize(1);
        assertThat(saved.getSections().get(0).getPhotos().get(0).getTags()).hasSize(2);
    }

    @Test
    void create_secondSubmissionForSameEmail_throwsTestimonialAlreadyExists() {
        TestimonialSubmissionRequest request = validRequest("IN", List.of(section("general", "First.")));
        submissionService.create("duplicate@example.com", request, Map.of());

        assertThatThrownBy(() -> submissionService.create("duplicate@example.com", request, Map.of()))
                .isInstanceOf(TestimonialAlreadyExistsException.class);
    }

    @Test
    void create_unknownCountryCode_throwsSubmissionValidationException() {
        TestimonialSubmissionRequest request =
                validRequest("ZZ", List.of(section("general", "Text.")));

        assertThatThrownBy(() -> submissionService.create("bad-country@example.com", request, Map.of()))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void create_allSectionsBlank_throwsSubmissionValidationException() {
        TestimonialSubmissionRequest request = validRequest(
                "IN", List.of(section("general", ""), section("academics_teaching", "   ")));

        assertThatThrownBy(() -> submissionService.create("blank-sections@example.com", request, Map.of()))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void create_groupPickedButNoSubtopicFilled_isRejected() {
        // Picking a topic-group alone (no individual subtopic with a
        // non-blank answer) doesn't count per UC-CREATE-TESTIMONIAL's alt
        // flow: the write-side request has no notion of "group" on its own,
        // only individual topic slugs, so a request whose only section under
        // a group has a blank answer must be rejected exactly like any other
        // all-blank submission.
        TestimonialSubmissionRequest request =
                validRequest("IN", List.of(section("academics_teaching", "")));

        assertThatThrownBy(() -> submissionService.create("group-only@example.com", request, Map.of()))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void create_unknownTopicSlug_throwsSubmissionValidationException() {
        TestimonialSubmissionRequest request =
                validRequest("IN", List.of(section("does_not_exist", "Text.")));

        assertThatThrownBy(() -> submissionService.create("bad-topic@example.com", request, Map.of()))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void create_unknownAchievementSlug_throwsSubmissionValidationException() {
        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(section("general", "Text.")),
                List.of("not_a_real_achievement"),
                List.of(),
                true);

        assertThatThrownBy(() -> submissionService.create("bad-achievement@example.com", request, Map.of()))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void create_unknownContactTypeSlug_throwsSubmissionValidationException() {
        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(section("general", "Text.")),
                List.of(),
                List.of(new ContactMethodInput("not_a_real_type", "value", false)),
                true);

        assertThatThrownBy(() -> submissionService.create("bad-contact@example.com", request, Map.of()))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void create_photoFileRefNotAMultipartPart_throwsSubmissionValidationException() {
        SectionInput sectionWithMissingPhoto =
                new SectionInput("general", "Text.", List.of(new PhotoInput("does-not-exist", List.of())));
        TestimonialSubmissionRequest request = validRequest("IN", List.of(sectionWithMissingPhoto));

        assertThatThrownBy(() -> submissionService.create("missing-file-part@example.com", request, Map.of()))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void create_tooManyPhotos_throwsSubmissionValidationExceptionBeforeStoringAnyFile() throws Exception {
        PhotoStorageProperties restrictiveProperties =
                new PhotoStorageProperties(uploadsRoot.toString(), 1, 5_242_880L);
        PhotoStorageService restrictivePhotoStorage =
                new PhotoStorageService(restrictiveProperties, new PhotoUrlResolver());
        SubmissionService restrictedService = new SubmissionService(
                testimonialRepository,
                topicRepository,
                achievementRepository,
                contactTypeRepository,
                countryRepository,
                emailLookupHashService,
                restrictivePhotoStorage,
                restrictiveProperties,
                clock,
                topicGroupRepository,
                validator);

        MockMultipartFile fileA = new MockMultipartFile("a", "a.png", "image/png", realPngBytes());
        MockMultipartFile fileB = new MockMultipartFile("b", "b.png", "image/png", realPngBytes());
        SectionInput sectionWithTwoPhotos = new SectionInput(
                "general",
                "Text.",
                List.of(new PhotoInput("a", List.of()), new PhotoInput("b", List.of())));
        TestimonialSubmissionRequest request = validRequest("IN", List.of(sectionWithTwoPhotos));

        assertThatThrownBy(() -> restrictedService.create(
                        "too-many-photos@example.com", request, Map.<String, MultipartFile>of("a", fileA, "b", fileB)))
                .isInstanceOf(SubmissionValidationException.class);
        assertThat(uploadsRoot.toFile().listFiles()).isNullOrEmpty();
    }

    @Test
    void create_countryCodeIsCaseInsensitive() {
        TestimonialSubmissionRequest request =
                validRequest("in", List.of(section("general", "Text.")));

        SubmissionResultResponse response =
                submissionService.create("lowercase-country@example.com", request, Map.of());

        Testimonial saved = testimonialRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getCountry().getCode()).isEqualTo("IN");
    }
}
