package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.error.FieldViolation;
import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.common.error.TestimonialAlreadyExistsException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.contacttype.ContactType;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.ContactMethodInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import jakarta.validation.Validator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Validation of the core {@link SubmissionService#create}/{@link
 * SubmissionService#edit} path shared by the JSON API and the HTML form
 * (decisions 5, 10, 21): every rule a request breaks is collected into ONE
 * {@link SubmissionValidationException}, each violation anchored at its
 * request-level field path ({@code rollNumber}, {@code sections[i].answer},
 * {@code contactMethods[j].value}, ...), indexed over the request's own lists.
 * The admission year's clock-based ceiling is in {@code
 * SubmissionServiceAdmissionYearTest}.
 */
@SpringBootTest
@Transactional
class SubmissionServiceValidationTest {

    private static final String ROLL_NUMBER_FORMAT_MESSAGE =
            "must look like CS21B001 (two letters, two digits, a letter, three digits)";
    private static final String PHOTOS_NEED_TEXT_MESSAGE =
            "Photos need some text — write something here, or remove the photos.";
    private static final String AT_LEAST_ONE_SECTION_MESSAGE = "At least one section must be filled in.";

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

    private static byte[] realPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static MockMultipartFile png(String name) throws Exception {
        return new MockMultipartFile(name, name + ".png", "image/png", realPngBytes());
    }

    private static SectionInput section(String topicSlug, String answer) {
        return new SectionInput(topicSlug, answer, List.of());
    }

    private static TestimonialSubmissionRequest request(
            String rollNumber, List<SectionInput> sections, List<ContactMethodInput> contacts) {
        return new TestimonialSubmissionRequest(
                "David", "Jones", rollNumber, 2024, "IN", 8, sections, List.of(), contacts, true);
    }

    private static TestimonialSubmissionRequest validRequest() {
        return request("CS21B001", List.of(section("general", "Great time overall.")), List.of());
    }

    private static TestimonialSubmissionRequest withContacts(ContactMethodInput... contacts) {
        return request("CS21B001", List.of(section("general", "Great time overall.")), List.of(contacts));
    }

    private static List<FieldViolation> violationsOf(ThrowingCallable call) {
        SubmissionValidationException ex = catchThrowableOfType(SubmissionValidationException.class, call);
        assertThat(ex).as("expected a SubmissionValidationException").isNotNull();
        return ex.getViolations();
    }

    private Testimonial savedFor(SubmissionResultResponse response) {
        return testimonialRepository.findById(response.id()).orElseThrow();
    }

    private boolean nothingSavedFor(String email) {
        return testimonialRepository.findByEmailLookupHash(emailLookupHashService.hash(email)).isEmpty();
    }

    /** A service whose photos land in this test's own {@link #uploadsRoot}, so stored files can be counted. */
    private SubmissionService serviceStoringPhotosIn(Path root, int maxPhotos) {
        PhotoStorageProperties properties =
                TestPhotoStorage.properties(root, maxPhotos, TestPhotoStorage.DEFAULT_MAX_PHOTO_SIZE_BYTES);
        return new SubmissionService(
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
    }

    // -- roll number (decision 10) --

    @Test
    void create_lowercaseRollNumber_isStoredUppercased() {
        SubmissionResultResponse response = submissionService.create(
                "roll-lower@example.com",
                request("cs21b001", List.of(section("general", "Text.")), List.of()),
                Map.of());

        assertThat(savedFor(response).getRollNumber()).isEqualTo("CS21B001");
    }

    @Test
    void create_rollNumberWithSurroundingWhitespace_isStoredTrimmed() {
        SubmissionResultResponse response = submissionService.create(
                "roll-padded@example.com",
                request("  CS21B001 ", List.of(section("general", "Text.")), List.of()),
                Map.of());

        assertThat(savedFor(response).getRollNumber()).isEqualTo("CS21B001");
    }

    @ParameterizedTest(name = "\"{0}\" is rejected at rollNumber")
    @ValueSource(strings = {"CS21B00", "CS21B0012", "C521B001", "СS21B001"})
    void create_wrongRollNumberShape_isOneViolationAtRollNumberAndNothingIsSaved(String rollNumber) {
        String email = "roll-wrong@example.com";

        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                email, request(rollNumber, List.of(section("general", "Text.")), List.of()), Map.of()));

        assertThat(violations).containsExactly(new FieldViolation("rollNumber", ROLL_NUMBER_FORMAT_MESSAGE));
        assertThat(nothingSavedFor(email)).isTrue();
    }

    @Test
    void create_blankRollNumber_isOneViolationAtRollNumber() {
        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "roll-blank@example.com", request("   ", List.of(section("general", "Text.")), List.of()), Map.of()));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.field()).isEqualTo("rollNumber");
            assertThat(v.message()).isNotEqualTo(ROLL_NUMBER_FORMAT_MESSAGE);
        });
    }

    @Test
    void edit_wrongRollNumber_isRejectedAndTheStoredOneIsKept() {
        String email = "roll-edit@example.com";
        SubmissionResultResponse created = submissionService.create(email, validRequest(), Map.of());

        List<FieldViolation> violations = violationsOf(() -> submissionService.edit(
                email, request("21CS001B", List.of(section("general", "Text.")), List.of()), Map.of()));

        assertThat(violations).extracting(FieldViolation::field).containsExactly("rollNumber");
        assertThat(savedFor(created).getRollNumber()).isEqualTo("CS21B001");
    }

    // -- photos on a blank section --

    @Test
    void create_newPhotoOnASectionWithBlankAnswer_isAViolationAtThatAnswerAndNoFileIsStored() throws Exception {
        SubmissionService service = serviceStoringPhotosIn(uploadsRoot, 20);
        String email = "photo-blank-create@example.com";
        SectionInput blankWithPhoto =
                new SectionInput("academics_teaching", "   ", List.of(new PhotoInput("p", List.of())));
        TestimonialSubmissionRequest req =
                request("CS21B001", List.of(section("general", "Text."), blankWithPhoto), List.of());

        List<FieldViolation> violations = violationsOf(
                () -> service.create(email, req, Map.<String, MultipartFile>of("p", png("p"))));

        assertThat(violations).containsExactly(new FieldViolation("sections[1].answer", PHOTOS_NEED_TEXT_MESSAGE));
        assertThat(uploadsRoot.toFile().listFiles()).isNullOrEmpty();
        assertThat(nothingSavedFor(email)).isTrue();
    }

    @Test
    void create_onlySectionIsBlankWithAPhoto_alsoReportsThatNoSectionIsFilledIn() throws Exception {
        SectionInput blankWithPhoto = new SectionInput("general", "", List.of(new PhotoInput("p", List.of())));

        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "photo-blank-only@example.com",
                request("CS21B001", List.of(blankWithPhoto), List.of()),
                Map.<String, MultipartFile>of("p", png("p"))));

        assertThat(violations).containsExactlyInAnyOrder(
                new FieldViolation("sections[0].answer", PHOTOS_NEED_TEXT_MESSAGE),
                new FieldViolation("sections", AT_LEAST_ONE_SECTION_MESSAGE));
    }

    @Test
    void create_blankSectionWithoutPhotos_isStillSilentlyDropped() {
        SubmissionResultResponse response = submissionService.create(
                "blank-dropped@example.com",
                request("CS21B001", List.of(section("general", "Text."), section("academics_teaching", "  ")),
                        List.of()),
                Map.of());

        assertThat(savedFor(response).getSections()).extracting(s -> s.getTopic().getSlug())
                .containsExactly("general");
    }

    @Test
    void edit_keptPhotoWithItsSectionTextCleared_isAViolationAndNothingChanges() throws Exception {
        String email = "photo-blank-edit@example.com";
        SectionInput withPhoto = new SectionInput("general", "Original.", List.of(new PhotoInput("a", List.of("x"))));
        SubmissionResultResponse created = submissionService.create(
                email, request("CS21B001", List.of(withPhoto), List.of()), Map.of("a", png("a")));
        Testimonial before = savedFor(created);
        before.setStatus(TestimonialStatus.APPROVED);
        testimonialRepository.saveAndFlush(before);
        String keptUrl = "/uploads/" + before.getSections().get(0).getPhotos().get(0).getFilePath();

        SectionInput clearedButKept = new SectionInput("general", "  ", List.of(new PhotoInput(keptUrl, List.of("x"))));
        List<FieldViolation> violations = violationsOf(() -> submissionService.edit(
                email, request("CS21B001", List.of(clearedButKept), List.of()), Map.of()));

        assertThat(violations).contains(new FieldViolation("sections[0].answer", PHOTOS_NEED_TEXT_MESSAGE));
        Testimonial after = savedFor(created);
        assertThat(after.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
        assertThat(after.getSections()).singleElement().satisfies(s -> {
            assertThat(s.getAnswerText()).isEqualTo("Original.");
            assertThat(s.getPhotos()).hasSize(1);
        });
    }

    // -- sections --

    @Test
    void create_allSectionsBlank_isOneViolationAnchoredAtSections() {
        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "all-blank@example.com",
                request("CS21B001", List.of(section("general", ""), section("networking", " ")), List.of()),
                Map.of()));

        assertThat(violations).containsExactly(new FieldViolation("sections", AT_LEAST_ONE_SECTION_MESSAGE));
    }

    @Test
    void create_emptySectionList_isTheSameSingleViolationNotTwo() {
        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "no-sections@example.com", request("CS21B001", List.of(), List.of()), Map.of()));

        assertThat(violations).containsExactly(new FieldViolation("sections", AT_LEAST_ONE_SECTION_MESSAGE));
    }

    @Test
    void create_nullSectionList_isReportedAtSectionsWithoutCrashing() {
        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "null-sections@example.com", request("CS21B001", null, List.of()), Map.of()));

        assertThat(violations).containsExactly(new FieldViolation("sections", AT_LEAST_ONE_SECTION_MESSAGE));
    }

    @Test
    void create_unknownTopic_isAnchoredAtThatSectionsTopicSlugOnly() {
        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "unknown-topic@example.com",
                request("CS21B001", List.of(section("general", "Text."), section("does_not_exist", "Text.")),
                        List.of()),
                Map.of()));

        assertThat(violations).containsExactly(
                new FieldViolation("sections[1].topicSlug", "Unknown or inactive topic: does_not_exist"));
    }

    @Test
    void create_photoFileRefNotAnUploadedPart_isAnchoredAtThatPhoto() {
        SectionInput withMissing = new SectionInput(
                "general", "Text.", List.of(new PhotoInput("missing", List.of())));

        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "missing-part@example.com", request("CS21B001", List.of(withMissing), List.of()), Map.of()));

        assertThat(violations).extracting(FieldViolation::field).containsExactly("sections[0].photos[0].fileRef");
    }

    @Test
    void create_elevenTagsOnOnePhoto_isAnchoredAtThatPhotosTags() throws Exception {
        List<String> elevenTags = Stream.iterate(1, i -> i + 1).limit(11).map(i -> "tag" + i).toList();
        SectionInput tooManyTags = new SectionInput("general", "Text.", List.of(new PhotoInput("p", elevenTags)));

        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "eleven-tags@example.com",
                request("CS21B001", List.of(tooManyTags), List.of()),
                Map.<String, MultipartFile>of("p", png("p"))));

        assertThat(violations).extracting(FieldViolation::field).containsExactly("sections[0].photos[0].tags");
    }

    @Test
    void create_tooManyPhotosInTotal_isAGlobalViolationWordedAsBefore() throws Exception {
        SubmissionService service = serviceStoringPhotosIn(uploadsRoot, 1);
        SectionInput twoPhotos = new SectionInput(
                "general", "Text.", List.of(new PhotoInput("a", List.of()), new PhotoInput("b", List.of())));

        List<FieldViolation> violations = violationsOf(() -> service.create(
                "too-many@example.com",
                request("CS21B001", List.of(twoPhotos), List.of()),
                Map.<String, MultipartFile>of("a", png("a"), "b", png("b"))));

        assertThat(violations).containsExactly(FieldViolation.global("Too many photos: maximum is 1."));
    }

    // -- reference checks --

    @Test
    void create_unknownCountry_isAnchoredAtCountryCode() {
        TestimonialSubmissionRequest req = new TestimonialSubmissionRequest("David", "Jones", "CS21B001", 2024,
                "ZZ", 8, List.of(section("general", "Text.")), List.of(), List.of(), true);

        List<FieldViolation> violations =
                violationsOf(() -> submissionService.create("zz@example.com", req, Map.of()));

        assertThat(violations).containsExactly(new FieldViolation("countryCode", "Unknown country code: ZZ"));
    }

    @Test
    void create_nullCountry_isOnlyTheBlankViolationNotAlsoAnUnknownCountry() {
        TestimonialSubmissionRequest req = new TestimonialSubmissionRequest("David", "Jones", "CS21B001", 2024,
                null, 8, List.of(section("general", "Text.")), List.of(), List.of(), true);

        List<FieldViolation> violations =
                violationsOf(() -> submissionService.create("null-country@example.com", req, Map.of()));

        assertThat(violations).extracting(FieldViolation::field).containsExactly("countryCode");
    }

    @ParameterizedTest(name = "\"{0}\" is one \"select your country\" violation")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void create_blankCountry_isExactlyOneViolationAskingToSelectACountry(String countryCode) {
        TestimonialSubmissionRequest req = new TestimonialSubmissionRequest("David", "Jones", "CS21B001", 2024,
                countryCode, 8, List.of(section("general", "Text.")), List.of(), List.of(), true);

        List<FieldViolation> violations =
                violationsOf(() -> submissionService.create("blank-country@example.com", req, Map.of()));

        assertThat(violations).containsExactly(new FieldViolation("countryCode", "Please select your country."));
        assertThat(nothingSavedFor("blank-country@example.com")).isTrue();
    }

    @Test
    void edit_blankCountry_isExactlyOneViolationAskingToSelectACountry() {
        submissionService.create("edit-blank-country@example.com", validRequest(), Map.of());
        TestimonialSubmissionRequest req = new TestimonialSubmissionRequest("David", "Jones", "CS21B001", 2024,
                "", 8, List.of(section("general", "Edited.")), List.of(), List.of(), true);

        List<FieldViolation> violations =
                violationsOf(() -> submissionService.edit("edit-blank-country@example.com", req, Map.of()));

        assertThat(violations).containsExactly(new FieldViolation("countryCode", "Please select your country."));
    }

    @Test
    void create_unknownLowercaseCountry_isReportedAsTyped() {
        TestimonialSubmissionRequest req = new TestimonialSubmissionRequest("David", "Jones", "CS21B001", 2024,
                "zz", 8, List.of(section("general", "Text.")), List.of(), List.of(), true);

        List<FieldViolation> violations =
                violationsOf(() -> submissionService.create("zz-lower@example.com", req, Map.of()));

        assertThat(violations).containsExactly(new FieldViolation("countryCode", "Unknown country code: zz"));
    }

    @Test
    void create_unknownAchievement_isAnchoredAtAchievementSlugs() {
        TestimonialSubmissionRequest req = new TestimonialSubmissionRequest("David", "Jones", "CS21B001", 2024,
                "IN", 8, List.of(section("general", "Text.")), List.of("made_new_friends", "nope"), List.of(), true);

        List<FieldViolation> violations =
                violationsOf(() -> submissionService.create("bad-achievement@example.com", req, Map.of()));

        assertThat(violations).containsExactly(
                new FieldViolation("achievementSlugs", "Unknown or inactive achievement: nope"));
    }

    @Test
    void create_unknownContactType_isAnchoredAtThatContactsTypeSlug() {
        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "bad-type@example.com",
                withContacts(new ContactMethodInput("email", "d@example.com", true),
                        new ContactMethodInput("myspace", "tom", false)),
                Map.of()));

        assertThat(violations).containsExactly(
                new FieldViolation("contactMethods[1].typeSlug", "Unknown or inactive contact type: myspace"));
    }

    // -- contact values (decision 5) --

    static Stream<Arguments> validContactValues() {
        return Stream.of(
                Arguments.of("email", "david@example.com"),
                Arguments.of("whatsapp", "+91 98765 43210"),
                Arguments.of("whatsapp", "wa.me/919876543210"),
                Arguments.of("telegram", "@david_jones"),
                Arguments.of("telegram", "t.me/david_jones"),
                Arguments.of("instagram", "@david.jones"),
                Arguments.of("instagram", "instagram.com/david_jones"),
                Arguments.of("twitter", "@davidj"),
                Arguments.of("twitter", "x.com/davidj"));
    }

    @ParameterizedTest(name = "{0}: \"{1}\" is saved")
    @MethodSource("validContactValues")
    void create_contactValueMatchingItsTypesPattern_isSaved(String typeSlug, String value) {
        SubmissionResultResponse response = submissionService.create(
                "contact-ok-" + typeSlug + "@example.com",
                withContacts(new ContactMethodInput(typeSlug, value, true)),
                Map.of());

        assertThat(savedFor(response).getContactMethods()).singleElement()
                .extracting(ContactMethod::getValue).isEqualTo(value);
    }

    static Stream<Arguments> invalidContactValues() {
        return Stream.of(
                Arguments.of("email", "david@example", "Email", "email address"),
                Arguments.of("whatsapp", "abc", "WhatsApp", "phone number, or a wa.me link"),
                Arguments.of("whatsapp", "12", "WhatsApp", "phone number, or a wa.me link"),
                Arguments.of("telegram", "@ab", "Telegram", "phone number, @username, or a t.me link"),
                Arguments.of("instagram", "john!doe", "Instagram", "@username or a profile link"),
                Arguments.of("twitter", "@" + "a".repeat(16), "X (Twitter)", "@username or a profile link"));
    }

    @ParameterizedTest(name = "{0}: \"{1}\" is rejected")
    @MethodSource("invalidContactValues")
    void create_contactValueNotMatchingItsTypesPattern_namesTheTypeAndWhatItExpects(
            String typeSlug, String value, String typeName, String expected) {
        String email = "contact-bad-" + typeSlug + "@example.com";

        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                email, withContacts(new ContactMethodInput(typeSlug, value, true)), Map.of()));

        assertThat(violations).containsExactly(new FieldViolation(
                "contactMethods[0].value",
                "doesn't look like a valid " + typeName + " contact — expected: " + expected));
        assertThat(nothingSavedFor(email)).isTrue();
    }

    @Test
    void create_contactValue_isStoredTrimmed() {
        SubmissionResultResponse response = submissionService.create(
                "contact-trimmed@example.com",
                withContacts(new ContactMethodInput("telegram", "  @david_jones \t", true)),
                Map.of());

        assertThat(savedFor(response).getContactMethods()).singleElement()
                .extracting(ContactMethod::getValue).isEqualTo("@david_jones");
    }

    @Test
    void create_blankContactValue_isOnlyTheBlankViolationNotAlsoAPatternMismatch() {
        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "contact-blank@example.com", withContacts(new ContactMethodInput("email", "   ", false)), Map.of()));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.field()).isEqualTo("contactMethods[0].value");
            assertThat(v.message()).doesNotStartWith("doesn't look like");
        });
    }

    @Test
    void create_invalidContactIsAnchoredAtItsOwnIndexInTheRequestList() {
        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "contact-index@example.com",
                withContacts(new ContactMethodInput("email", "d@example.com", false),
                        new ContactMethodInput("telegram", "@david_jones", true),
                        new ContactMethodInput("whatsapp", "call me", true)),
                Map.of()));

        assertThat(violations).extracting(FieldViolation::field).containsExactly("contactMethods[2].value");
    }

    @Test
    void create_contactTypeWithoutAPattern_acceptsAnyNonBlankValue() {
        contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug("fixture_signal").name("Signal").label("anything").displayOrder(90).build());

        SubmissionResultResponse response = submissionService.create(
                "contact-no-pattern@example.com",
                withContacts(new ContactMethodInput("fixture_signal", "  whatever works!! ", false)),
                Map.of());

        assertThat(savedFor(response).getContactMethods()).singleElement()
                .extracting(ContactMethod::getValue).isEqualTo("whatever works!!");
    }

    @Test
    void create_contactTypeWhosePatternDoesNotCompile_isTreatedLikeOneWithoutAPattern() {
        // Browsers ignore an HTML pattern that doesn't compile; the server
        // does the same instead of failing every submission with a 500.
        contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug("fixture_broken").name("Broken").label("anything").valuePattern("([a-z")
                .displayOrder(91).build());

        SubmissionResultResponse response = submissionService.create(
                "contact-broken-pattern@example.com",
                withContacts(new ContactMethodInput("fixture_broken", "abc", false)),
                Map.of());

        assertThat(savedFor(response).getContactMethods()).hasSize(1);
    }

    @Test
    void edit_invalidContactValue_isRejectedAndTheStoredContactsAreKept() {
        String email = "contact-edit@example.com";
        SubmissionResultResponse created = submissionService.create(
                email, withContacts(new ContactMethodInput("email", "d@example.com", true)), Map.of());

        List<FieldViolation> violations = violationsOf(() -> submissionService.edit(
                email, withContacts(new ContactMethodInput("email", "not an email", true)), Map.of()));

        assertThat(violations).extracting(FieldViolation::field).containsExactly("contactMethods[0].value");
        assertThat(savedFor(created).getContactMethods()).singleElement()
                .extracting(ContactMethod::getValue).isEqualTo("d@example.com");
    }

    // -- one pass --

    @Test
    void create_everyBrokenRuleIsReportedAtOnceInOneException() throws Exception {
        TestimonialSubmissionRequest req = new TestimonialSubmissionRequest(
                "",
                "Jones",
                "cs21b0x1",
                1958,
                "ZZ",
                11,
                List.of(new SectionInput("general", " ", List.of(new PhotoInput("p", List.of())))),
                List.of(),
                List.of(new ContactMethodInput("whatsapp", "call me", true)),
                false);

        List<FieldViolation> violations = violationsOf(() -> submissionService.create(
                "everything-wrong@example.com", req, Map.<String, MultipartFile>of("p", png("p"))));

        assertThat(violations).extracting(FieldViolation::field).containsExactlyInAnyOrder(
                "firstName",
                "rollNumber",
                "admissionYear",
                "countryCode",
                "recommendationScore",
                "dataProcessingConsent",
                "sections[0].answer",
                "sections",
                "contactMethods[0].value");
    }

    @Test
    void edit_everyBrokenRuleIsReportedAtOnceInOneException() {
        String email = "everything-wrong-edit@example.com";
        submissionService.create(email, validRequest(), Map.of());

        List<FieldViolation> violations = violationsOf(() -> submissionService.edit(
                email,
                request("nope", List.of(section("general", "Still here.")),
                        List.of(new ContactMethodInput("twitter", "not a handle", false))),
                Map.of()));

        assertThat(violations).extracting(FieldViolation::field)
                .containsExactlyInAnyOrder("rollNumber", "contactMethods[0].value");
    }

    // -- precedence over the not-found / already-exists checks --

    @Test
    void create_whenATestimonialAlreadyExists_conflictWinsOverValidation() {
        String email = "exists-and-invalid@example.com";
        submissionService.create(email, validRequest(), Map.of());

        assertThatThrownBy(() -> submissionService.create(
                        email, request("bad", List.of(section("general", "Text.")), List.of()), Map.of()))
                .isInstanceOf(TestimonialAlreadyExistsException.class);
    }

    @Test
    void edit_whenNoTestimonialExists_notFoundWinsOverValidation() {
        assertThatThrownBy(() -> submissionService.edit(
                        "missing-and-invalid@example.com",
                        request("bad", List.of(section("general", "Text.")), List.of()),
                        Map.of()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void edit_blankSectionWithoutPhotos_isStillDroppedWhichRemovesThatSavedSection() {
        String email = "edit-blank-dropped@example.com";
        SubmissionResultResponse created = submissionService.create(
                email,
                request("CS21B001", List.of(section("general", "Text."), section("networking", "People.")),
                        List.of()),
                Map.of());

        submissionService.edit(
                email,
                request("CS21B001", List.of(section("general", "Text."), section("networking", "")), List.of()),
                Map.of());

        assertThat(savedFor(created).getSections()).extracting(TestimonialSection::getTopic)
                .extracting(t -> t.getSlug()).containsExactly("general");
    }
}
