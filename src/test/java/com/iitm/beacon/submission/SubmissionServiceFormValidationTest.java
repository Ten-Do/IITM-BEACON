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
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import jakarta.validation.Validator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubmissionService#createFromForm}/{@link
 * SubmissionService#editFromForm} validation (decision 21): the core's
 * violations come back translated to FORM field paths and merged with the
 * form-only rules (tags without a photo, a public contact without a value),
 * all in one exception, and nothing — no row, no photo file — is saved when
 * any rule is broken. The pure path bookkeeping is unit-tested in {@code
 * FormSubmissionTest}.
 */
@SpringBootTest
@Transactional
class SubmissionServiceFormValidationTest {

    @Autowired
    private SubmissionService submissionService;

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

    private static MockMultipartFile png(String name) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile(name, name + ".png", "image/png", out.toByteArray());
    }

    private static SectionFormEntry section(String slug, String answer) {
        SectionFormEntry entry = new SectionFormEntry();
        entry.setTopicSlug(slug);
        entry.setAnswerText(answer);
        return entry;
    }

    private static ContactFormEntry contact(String typeSlug, String value, boolean publicContact) {
        ContactFormEntry entry = new ContactFormEntry();
        entry.setTypeSlug(typeSlug);
        entry.setValue(value);
        entry.setPublicContact(publicContact);
        return entry;
    }

    /** A valid command whose only filled section, "general", sits at form index {@code generalIndex}. */
    private static SubmissionFormCommand command(int generalIndex) {
        SubmissionFormCommand command = new SubmissionFormCommand();
        command.setFirstName("David");
        command.setLastName("Jones");
        command.setRollNumber("CS21B001");
        command.setAdmissionYear(2024);
        command.setCountryCode("IN");
        command.setRecommendationScore(8);
        command.setDataProcessingConsent(true);
        List<SectionFormEntry> sections = new ArrayList<>();
        for (int i = 0; i <= generalIndex; i++) {
            sections.add(new SectionFormEntry());
        }
        sections.set(generalIndex, section("general", "Great time overall."));
        command.setSections(sections);
        return command;
    }

    private static List<FieldViolation> violationsOf(ThrowingCallable call) {
        SubmissionValidationException ex = catchThrowableOfType(SubmissionValidationException.class, call);
        assertThat(ex).as("expected a SubmissionValidationException").isNotNull();
        return ex.getViolations();
    }

    private boolean nothingSavedFor(String email) {
        return testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email)).isEmpty();
    }

    private Testimonial savedFor(String email) {
        return testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email)).orElseThrow();
    }

    private SubmissionService serviceStoringPhotosIn(Path root) {
        PhotoStorageProperties properties = TestPhotoStorage.properties(root);
        return new SubmissionService(
                testimonialRepository,
                topicRepository,
                achievementRepository,
                contactTypeRepository,
                countryRepository,
                emailLookupHashService,
                new PhotoStorageService(properties, new PhotoUrlResolver(), new PhotoImageProcessor(properties)),
                properties,
                clock,
                topicGroupRepository,
                validator);
    }

    // -- create --

    @Test
    void createFromForm_lowercaseRollNumberAndPaddedContact_areStoredNormalized() {
        String email = "form-normalized@example.com";
        SubmissionFormCommand command = command(0);
        command.setRollNumber(" cs21b001 ");
        command.setContactMethods(new ArrayList<>(List.of(contact("telegram", "  @david_jones  ", true))));

        submissionService.createFromForm(email, command);

        Testimonial saved = savedFor(email);
        assertThat(saved.getRollNumber()).isEqualTo("CS21B001");
        assertThat(saved.getContactMethods()).singleElement()
                .extracting(ContactMethod::getValue).isEqualTo("@david_jones");
    }

    @Test
    void createFromForm_onlyAFormOnlyRuleBroken_savesNothingAndStoresNoPhoto() throws Exception {
        SubmissionService service = serviceStoringPhotosIn(uploadsRoot);
        String email = "form-only-violation@example.com";
        SubmissionFormCommand command = command(4);
        command.getSections().get(4).setPhotos(new ArrayList<>(List.of(png("a"))));
        command.getSections().get(4).setPhotoTags(new ArrayList<>(List.of("sunset", "no photo here")));

        List<FieldViolation> violations = violationsOf(() -> service.createFromForm(email, command));

        assertThat(violations).containsExactly(new FieldViolation(
                "sections[4].photoTags[1]", "Tags need a photo — attach one or clear the tags."));
        assertThat(nothingSavedFor(email)).isTrue();
        assertThat(uploadsRoot.toFile().listFiles()).isNullOrEmpty();
    }

    @Test
    void createFromForm_photoOnABlankTopicAtAHighCatalogIndex_isAnchoredAtThatTopicsTextarea() throws Exception {
        SubmissionService service = serviceStoringPhotosIn(uploadsRoot);
        String email = "form-high-index@example.com";
        SubmissionFormCommand command = command(2);
        while (command.getSections().size() <= 41) {
            command.getSections().add(new SectionFormEntry());
        }
        SectionFormEntry blankWithPhoto = section("networking", "   ");
        blankWithPhoto.setPhotos(new ArrayList<>(List.of(png("n"))));
        command.getSections().set(41, blankWithPhoto);

        List<FieldViolation> violations = violationsOf(() -> service.createFromForm(email, command));

        assertThat(violations).containsExactly(new FieldViolation(
                "sections[41].answerText", "Photos need some text — write something here, or remove the photos."));
        assertThat(uploadsRoot.toFile().listFiles()).isNullOrEmpty();
    }

    @Test
    void createFromForm_coreAndFormOnlyViolations_comeBackTogetherAtFormPaths() {
        String email = "form-many-violations@example.com";
        SubmissionFormCommand command = command(3);
        command.setRollNumber("21CS");
        command.setAdmissionYear(1900);
        command.getSections().get(3).setPhotoTags(new ArrayList<>(List.of("orphan tag")));
        command.setContactMethods(new ArrayList<>(List.of(
                contact("email", "", true),
                contact("whatsapp", "   ", false),
                contact("telegram", "", false),
                contact("instagram", "john!doe", true))));

        List<FieldViolation> violations = violationsOf(() -> submissionService.createFromForm(email, command));

        assertThat(violations).extracting(FieldViolation::field).containsExactlyInAnyOrder(
                "rollNumber",
                "admissionYear",
                "sections[3].photoTags[0]",
                "contactMethods[0].publicContact",
                "contactMethods[3].value");
        assertThat(nothingSavedFor(email)).isTrue();
    }

    @Test
    void createFromForm_allTopicsUnpicked_isReportedAtSections() {
        SubmissionFormCommand command = command(0);
        command.setSections(new ArrayList<>());

        assertThat(violationsOf(() -> submissionService.createFromForm("form-no-topic@example.com", command)))
                .containsExactly(new FieldViolation("sections", "At least one section must be filled in."));
    }

    @Test
    void createFromForm_contactRowOfAnUnknownType_isAGlobalViolation() {
        SubmissionFormCommand command = command(0);
        command.setContactMethods(new ArrayList<>(List.of(contact("myspace", "tom", false))));

        assertThat(violationsOf(() -> submissionService.createFromForm("form-bad-type@example.com", command)))
                .containsExactly(FieldViolation.global("Unknown or inactive contact type: myspace"));
    }

    // -- edit --

    private String createWithPhoto(String email) throws Exception {
        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest("David", "Jones", "CS21B001", 2024,
                "IN", 8, List.of(new SectionInput("general", "Original.", List.of(new PhotoInput("a", List.of())))),
                List.of(), List.of(), true);
        submissionService.create(email, request, Map.of("a", png("a")));
        return "/uploads/" + savedFor(email).getSections().get(0).getPhotos().get(0).getFilePath();
    }

    @Test
    void editFromForm_keptPhotoWithTheTextCleared_isAnchoredAtTheTextareaAndNothingChanges() throws Exception {
        String email = "form-edit-cleared@example.com";
        String keptUrl = createWithPhoto(email);
        SubmissionFormCommand command = command(7);
        command.getSections().get(7).setAnswerText("");
        command.getSections().get(7).setExistingPhotoUrls(new ArrayList<>(List.of(keptUrl)));
        command.getSections().get(7).setExistingPhotoTags(new ArrayList<>(List.of("")));

        List<FieldViolation> violations = violationsOf(() -> submissionService.editFromForm(email, command));

        assertThat(violations).contains(new FieldViolation(
                "sections[7].answerText", "Photos need some text — write something here, or remove the photos."));
        assertThat(savedFor(email).getSections()).singleElement().satisfies(s -> {
            assertThat(s.getAnswerText()).isEqualTo("Original.");
            assertThat(s.getPhotos()).hasSize(1);
        });
    }

    @Test
    void editFromForm_keptPhotoRemovedAndTextCleared_isFineTheSectionIsDropped() throws Exception {
        // Ticking "Remove" on every photo leaves a blank section with no
        // photos, which is dropped as before — here that leaves no section,
        // so the only violation is the at-least-one rule.
        String email = "form-edit-removed@example.com";
        String keptUrl = createWithPhoto(email);
        SubmissionFormCommand command = command(0);
        command.getSections().get(0).setAnswerText(" ");
        command.getSections().get(0).setExistingPhotoUrls(new ArrayList<>(List.of(keptUrl)));
        command.getSections().get(0).setRemovedPhotoUrls(new ArrayList<>(List.of(keptUrl)));

        assertThat(violationsOf(() -> submissionService.editFromForm(email, command)))
                .containsExactly(new FieldViolation("sections", "At least one section must be filled in."));
    }

    @Test
    void editFromForm_publicContactWithoutAValue_isRejectedAndTheStoredContactsAreKept() {
        String email = "form-edit-public-blank@example.com";
        SubmissionFormCommand created = command(0);
        created.setContactMethods(new ArrayList<>(List.of(contact("email", "d@example.com", true))));
        submissionService.createFromForm(email, created);

        SubmissionFormCommand command = command(0);
        command.setContactMethods(new ArrayList<>(List.of(contact("email", "  ", true))));

        assertThat(violationsOf(() -> submissionService.editFromForm(email, command)))
                .containsExactly(new FieldViolation(
                        "contactMethods[0].publicContact", "Enter a contact before making it public."));
        assertThat(savedFor(email).getContactMethods()).singleElement()
                .extracting(ContactMethod::getValue).isEqualTo("d@example.com");
    }
}
