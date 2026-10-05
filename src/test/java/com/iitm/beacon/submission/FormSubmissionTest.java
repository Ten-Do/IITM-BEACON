package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.iitm.beacon.common.error.FieldViolation;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.ContactMethodInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link FormSubmission}: the HTML form command -> request translation, and
 * the reverse mapping of request-level violation paths back onto the form
 * fields they came from (decision 21). The request's {@code sections} skip
 * form entries without a topic slug and its {@code contactMethods} skip
 * blank rows, so request index != form index in general — every case below
 * is built so the two differ.
 */
class FormSubmissionTest {

    private static final String TAGS_NEED_A_PHOTO = "Tags need a photo — attach one or clear the tags.";
    private static final String PUBLIC_NEEDS_A_VALUE = "Enter a contact before making it public.";

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

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photo", "cat.png", "image/png", new byte[] {1, 2, 3});
    }

    private static MockMultipartFile emptyFileInput() {
        return new MockMultipartFile("photo", "", "application/octet-stream", new byte[0]);
    }

    /**
     * 18 form sections: only index 3 ("general") and index 17 ("networking")
     * carry a topic slug (a sparse post, as the topic picker produces), so
     * they become request sections 0 and 1.
     */
    private static SubmissionFormCommand sparseCommand() {
        SubmissionFormCommand command = new SubmissionFormCommand();
        command.setFirstName("David");
        command.setLastName("Jones");
        command.setRollNumber("CS21B001");
        command.setAdmissionYear(2024);
        command.setCountryCode("IN");
        command.setRecommendationScore(8);
        command.setDataProcessingConsent(true);
        List<SectionFormEntry> sections = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            sections.add(new SectionFormEntry());
        }
        sections.set(3, section("general", "Great time."));
        sections.set(17, section("networking", "Met people."));
        command.setSections(sections);
        // Rows 1 and 2 are left blank, so row 3 is request contact 1.
        command.setContactMethods(new ArrayList<>(List.of(
                contact("email", "d@example.com", true),
                contact("whatsapp", "", false),
                contact("telegram", null, false),
                contact("instagram", "@david.jones", true))));
        return command;
    }

    private static List<FieldViolation> translate(FormSubmission form, FieldViolation... requestViolations) {
        return form.toFormViolations(List.of(requestViolations));
    }

    // -- the translated request itself --

    @Test
    void request_skipsSectionsWithoutASlugAndBlankContactRows() {
        FormSubmission form = FormSubmission.from(sparseCommand());

        assertThat(form.request().sections()).extracting(SectionInput::topicSlug)
                .containsExactly("general", "networking");
        assertThat(form.request().contactMethods()).extracting(ContactMethodInput::typeSlug)
                .containsExactly("email", "instagram");
        assertThat(form.toFormViolations(List.of())).isEmpty();
    }

    @Test
    void request_newUploadIsKeyedByItsFormSectionAndItsIndexInThePostedList() {
        SubmissionFormCommand command = sparseCommand();
        command.getSections().get(17).setPhotos(new ArrayList<>(List.of(emptyFileInput(), photo())));

        FormSubmission form = FormSubmission.from(command);

        assertThat(form.fileMap()).containsOnlyKeys("section-17-photo-1");
        assertThat(form.request().sections().get(1).photos()).singleElement()
                .satisfies(p -> assertThat(p.fileRef()).isEqualTo("section-17-photo-1"));
    }

    // -- new photos: one multiple file input per section, tags paired by index --

    private static MockMultipartFile photo(String filename) {
        return new MockMultipartFile("sections[3].photos", filename, "image/png", new byte[] {1, 2, 3});
    }

    @Test
    void request_everyFileOfAMultipleInput_isKeptInOrderWithTheTagsAtItsOwnIndex() {
        SubmissionFormCommand command = sparseCommand();
        MockMultipartFile a = photo("a.png");
        MockMultipartFile b = photo("b.png");
        MockMultipartFile c = photo("c.png");
        command.getSections().get(3).setPhotos(new ArrayList<>(List.of(a, b, c)));
        command.getSections().get(3).setPhotoTags(new ArrayList<>(List.of("beach", "", " sunset , dunes ")));

        FormSubmission form = FormSubmission.from(command);

        assertThat(form.request().sections().get(0).photos()).extracting(PhotoInput::fileRef, PhotoInput::tags)
                .containsExactly(
                        tuple("section-3-photo-0", List.of("beach")),
                        tuple("section-3-photo-1", List.of()),
                        tuple("section-3-photo-2", List.of("sunset", "dunes")));
        assertThat(form.fileMap()).containsEntry("section-3-photo-0", a)
                .containsEntry("section-3-photo-1", b)
                .containsEntry("section-3-photo-2", c);
        assertThat(form.toFormViolations(List.of())).isEmpty();
    }

    @Test
    void request_emptyAndMissingPartsBetweenFiles_areSkippedWithoutShiftingTheOthersTags() {
        SubmissionFormCommand command = sparseCommand();
        List<MultipartFile> files = new ArrayList<>();
        files.add(photo("a.png"));
        files.add(emptyFileInput());
        files.add(null);
        files.add(photo("d.png"));
        command.getSections().get(3).setPhotos(files);
        command.getSections().get(3).setPhotoTags(new ArrayList<>(List.of("first", "", " ", "last")));

        FormSubmission form = FormSubmission.from(command);

        assertThat(form.request().sections().get(0).photos()).extracting(PhotoInput::fileRef, PhotoInput::tags)
                .containsExactly(
                        tuple("section-3-photo-0", List.of("first")), tuple("section-3-photo-3", List.of("last")));
        assertThat(form.toFormViolations(List.of())).isEmpty();
    }

    @Test
    void request_fewerTagFieldsThanFiles_leavesTheRestUntagged() {
        SubmissionFormCommand command = sparseCommand();
        command.getSections().get(3).setPhotos(new ArrayList<>(List.of(photo("a.png"), photo("b.png"))));
        command.getSections().get(3).setPhotoTags(new ArrayList<>(List.of("only-the-first")));

        FormSubmission form = FormSubmission.from(command);

        assertThat(form.request().sections().get(0).photos()).extracting(PhotoInput::tags)
                .containsExactly(List.of("only-the-first"), List.of());
    }

    @Test
    void request_untouchedFileInput_isNoPhotoAndNoViolation() {
        // Without JS an empty multiple file input still posts one empty part.
        SubmissionFormCommand command = sparseCommand();
        command.getSections().get(3).setPhotos(new ArrayList<>(List.of(emptyFileInput())));

        FormSubmission form = FormSubmission.from(command);

        assertThat(form.request().sections().get(0).photos()).isEmpty();
        assertThat(form.fileMap()).isEmpty();
        assertThat(form.toFormViolations(List.of())).isEmpty();
    }

    @Test
    void request_tagsPastTheLastFile_areAViolationAtThatIndexsTags() {
        SubmissionFormCommand command = sparseCommand();
        command.getSections().get(3).setPhotos(new ArrayList<>(List.of(photo("a.png"))));
        command.getSections().get(3).setPhotoTags(new ArrayList<>(List.of("kept", "", "orphan")));

        FormSubmission form = FormSubmission.from(command);

        assertThat(form.toFormViolations(List.of()))
                .containsExactly(new FieldViolation("sections[3].photoTags[2]", TAGS_NEED_A_PHOTO));
        assertThat(form.request().sections().get(0).photos()).extracting(PhotoInput::tags)
                .containsExactly(List.of("kept"));
    }

    // -- request path -> form path --

    @Test
    void sectionAnswer_mapsToThatSectionsFormTextarea() {
        FormSubmission form = FormSubmission.from(sparseCommand());

        assertThat(translate(form, new FieldViolation("sections[1].answer", "Photos need some text.")))
                .containsExactly(new FieldViolation("sections[17].answerText", "Photos need some text."));
    }

    @Test
    void anyPhotoViolationOfASection_mapsToThatSectionsPhotosAnchor() {
        FormSubmission form = FormSubmission.from(sparseCommand());

        assertThat(translate(form,
                        new FieldViolation("sections[0].photos[2].tags", "size must be between 0 and 10"),
                        new FieldViolation("sections[1].photos[0].fileRef", "does not match")))
                .containsExactly(
                        new FieldViolation("sections[3].photos", "size must be between 0 and 10"),
                        new FieldViolation("sections[17].photos", "does not match"));
    }

    @Test
    void sectionTopicSlug_isAHiddenInputSoItBecomesGlobal() {
        FormSubmission form = FormSubmission.from(sparseCommand());

        assertThat(translate(form, new FieldViolation("sections[0].topicSlug", "Unknown or inactive topic: x")))
                .containsExactly(FieldViolation.global("Unknown or inactive topic: x"));
    }

    @Test
    void contactValue_mapsToTheFormRowItCameFromSkippingBlankRows() {
        FormSubmission form = FormSubmission.from(sparseCommand());

        assertThat(translate(form, new FieldViolation("contactMethods[1].value", "doesn't look like a valid one")))
                .containsExactly(new FieldViolation("contactMethods[3].value", "doesn't look like a valid one"));
    }

    @Test
    void contactTypeSlug_isAHiddenInputSoItBecomesGlobal() {
        FormSubmission form = FormSubmission.from(sparseCommand());

        assertThat(translate(form, new FieldViolation("contactMethods[0].typeSlug", "Unknown contact type: x")))
                .containsExactly(FieldViolation.global("Unknown contact type: x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "firstName", "lastName", "rollNumber", "admissionYear", "countryCode", "recommendationScore",
        "dataProcessingConsent", "achievementSlugs", "sections"
    })
    void topLevelField_mapsOneToOne(String field) {
        FormSubmission form = FormSubmission.from(sparseCommand());

        assertThat(translate(form, new FieldViolation(field, "message")))
                .containsExactly(new FieldViolation(field, "message"));
    }

    @Test
    void globalViolation_staysGlobal() {
        FormSubmission form = FormSubmission.from(sparseCommand());

        assertThat(translate(form, FieldViolation.global("Too many photos: maximum is 20.")))
                .containsExactly(FieldViolation.global("Too many photos: maximum is 20."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sections[2].answer", "contactMethods[2].value", "sections[-1].answer", "somethingElse"})
    void pathWithNoFormCounterpart_becomesGlobalRatherThanPointingAtTheWrongField(String requestField) {
        FormSubmission form = FormSubmission.from(sparseCommand());

        assertThat(translate(form, new FieldViolation(requestField, "message")))
                .containsExactly(FieldViolation.global("message"));
    }

    // -- form-only rules --

    @Test
    void tagsWithoutAnyFilePart_areAViolationAtThatIndexsTags() {
        SubmissionFormCommand command = sparseCommand();
        command.getSections().get(17).setPhotoTags(new ArrayList<>(List.of("", "", "sunset, beach")));

        FormSubmission form = FormSubmission.from(command);

        assertThat(form.toFormViolations(List.of()))
                .containsExactly(new FieldViolation("sections[17].photoTags[2]", TAGS_NEED_A_PHOTO));
        assertThat(form.request().sections().get(1).photos()).isEmpty();
    }

    @Test
    void tagsAtTheIndexOfAnEmptyFilePart_areAViolationToo() {
        SubmissionFormCommand command = sparseCommand();
        command.getSections().get(3).setPhotos(new ArrayList<>(List.of(photo(), emptyFileInput())));
        command.getSections().get(3).setPhotoTags(new ArrayList<>(List.of("kept", "orphan")));

        FormSubmission form = FormSubmission.from(command);

        assertThat(form.toFormViolations(List.of()))
                .containsExactly(new FieldViolation("sections[3].photoTags[1]", TAGS_NEED_A_PHOTO));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", " , ,"})
    void blankTagsWithoutAFile_areNotAViolation(String tags) {
        SubmissionFormCommand command = sparseCommand();
        command.getSections().get(3).setPhotoTags(new ArrayList<>(List.of(tags)));

        assertThat(FormSubmission.from(command).toFormViolations(List.of())).isEmpty();
    }

    @Test
    void tagsWithTheirFile_areNotAViolation() {
        SubmissionFormCommand command = sparseCommand();
        command.getSections().get(3).setPhotos(new ArrayList<>(List.of(photo())));
        command.getSections().get(3).setPhotoTags(new ArrayList<>(List.of("sunset")));

        assertThat(FormSubmission.from(command).toFormViolations(List.of())).isEmpty();
    }

    @Test
    void publicContactRowWithoutAValue_isAViolationAtItsPublicCheckbox() {
        SubmissionFormCommand command = sparseCommand();
        command.getContactMethods().get(1).setPublicContact(true); // whatsapp, value ""
        command.getContactMethods().get(2).setPublicContact(true); // telegram, value null

        FormSubmission form = FormSubmission.from(command);

        assertThat(form.toFormViolations(List.of())).containsExactly(
                new FieldViolation("contactMethods[1].publicContact", PUBLIC_NEEDS_A_VALUE),
                new FieldViolation("contactMethods[2].publicContact", PUBLIC_NEEDS_A_VALUE));
        assertThat(form.request().contactMethods()).extracting(ContactMethodInput::typeSlug)
                .containsExactly("email", "instagram");
    }

    @Test
    void privateContactRowWithoutAValue_isStillSilentlyDropped() {
        FormSubmission form = FormSubmission.from(sparseCommand());

        assertThat(form.toFormViolations(List.of())).isEmpty();
    }

    @Test
    void formOnlyAndTranslatedViolations_areReportedTogether() {
        SubmissionFormCommand command = sparseCommand();
        command.getContactMethods().get(1).setPublicContact(true);
        command.getSections().get(3).setPhotoTags(new ArrayList<>(List.of("orphan")));

        FormSubmission form = FormSubmission.from(command);

        assertThat(translate(form, new FieldViolation("rollNumber", "must look like CS21B001")))
                .containsExactlyInAnyOrder(
                        new FieldViolation("rollNumber", "must look like CS21B001"),
                        new FieldViolation("sections[3].photoTags[0]", TAGS_NEED_A_PHOTO),
                        new FieldViolation("contactMethods[1].publicContact", PUBLIC_NEEDS_A_VALUE));
    }

    @Test
    void nullListsInTheCommand_translateToAnEmptyRequestWithoutCrashing() {
        SubmissionFormCommand command = sparseCommand();
        command.setSections(null);
        command.setContactMethods(null);
        command.setAchievementSlugs(null);
        List<MultipartFile> noFiles = null;
        SectionFormEntry entryWithNullLists = section("general", "Text.");
        entryWithNullLists.setPhotos(noFiles);
        entryWithNullLists.setPhotoTags(null);

        FormSubmission form = FormSubmission.from(command);

        assertThat(form.request().sections()).isEmpty();
        assertThat(form.request().contactMethods()).isEmpty();
        assertThat(form.request().achievementSlugs()).isEmpty();
        assertThat(FormSubmission.from(commandWith(entryWithNullLists)).toFormViolations(List.of())).isEmpty();
    }

    private static SubmissionFormCommand commandWith(SectionFormEntry entry) {
        SubmissionFormCommand command = sparseCommand();
        command.setSections(new ArrayList<>(List.of(entry)));
        return command;
    }
}
