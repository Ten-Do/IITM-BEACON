package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.common.error.TestimonialAlreadyExistsException;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubmissionService#createFromForm}/{@link
 * SubmissionService#editFromForm} — the {@link SubmissionFormCommand} ->
 * {@code TestimonialSubmissionRequest} + file-map translation, verified by
 * comparing the persisted result against an equivalent direct {@link
 * SubmissionService#create}/{@link SubmissionService#edit} call. Business
 * rules already covered by {@code SubmissionServiceCreateTest}/{@code
 * SubmissionServiceEditTest} (unknown topic/country/achievement, photo
 * limits, decision-18 diffing) are deliberately not re-tested here — only
 * this adapter's own translation logic.
 */
@SpringBootTest
@Transactional
class SubmissionServiceFormAdapterTest {

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    private static byte[] realPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private SubmissionFormCommand baseCommand() {
        SubmissionFormCommand command = new SubmissionFormCommand();
        command.setFirstName("David");
        command.setLastName("Jones");
        command.setRollNumber("GE26Z001");
        command.setAdmissionYear(2024);
        command.setCountryCode("IN");
        command.setRecommendationScore(8);
        command.setDataProcessingConsent(true);
        SectionFormEntry section = new SectionFormEntry();
        section.setTopicSlug("general");
        section.setAnswerText("Great time overall.");
        command.setSections(new ArrayList<>(List.of(section)));
        return command;
    }

    // -- createFromForm --

    @Test
    void createFromForm_translatesFlatCommandToPersistedTestimonial() {
        SubmissionFormCommand command = baseCommand();

        SubmissionResultResponse response = submissionService.createFromForm("form-create@example.com", command);

        Testimonial saved = testimonialRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getFirstName()).isEqualTo("David");
        assertThat(saved.getCountry().getCode()).isEqualTo("IN");
        assertThat(saved.getSections()).hasSize(1);
        assertThat(saved.getSections().get(0).getAnswerText()).isEqualTo("Great time overall.");
    }

    @Test
    void createFromForm_matchesEquivalentDirectCreateCall() {
        SubmissionFormCommand command = baseCommand();
        SubmissionFormCommand equivalentCommand = baseCommand();

        SubmissionResultResponse viaForm = submissionService.createFromForm("form-equiv-a@example.com", command);
        TestimonialSubmissionRequest directRequest = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput("general", "Great time overall.", List.of())),
                List.of(),
                List.of(),
                true);
        SubmissionResultResponse viaDirect =
                submissionService.create("form-equiv-b@example.com", directRequest, Map.of());

        Testimonial formResult = testimonialRepository.findById(viaForm.id()).orElseThrow();
        Testimonial directResult = testimonialRepository.findById(viaDirect.id()).orElseThrow();
        assertThat(formResult.getStatus()).isEqualTo(directResult.getStatus());
        assertThat(formResult.getSections().get(0).getAnswerText())
                .isEqualTo(directResult.getSections().get(0).getAnswerText());
        assertThat(equivalentCommand).isNotNull(); // built for symmetry/readability with the direct-call branch above
    }

    @Test
    void createFromForm_withNewPhotoAndCommaSeparatedTags_storesPhotoWithParsedTags() throws Exception {
        SubmissionFormCommand command = baseCommand();
        MockMultipartFile photo = new MockMultipartFile("photo", "cat.png", "image/png", realPngBytes());
        command.getSections().get(0).setPhotos(new ArrayList<>(List.of(photo)));
        command.getSections().get(0).setPhotoTags(new ArrayList<>(List.of(" sunset , sunset ,beach ")));

        SubmissionResultResponse response = submissionService.createFromForm("form-photo@example.com", command);

        Testimonial saved = testimonialRepository.findById(response.id()).orElseThrow();
        List<Photo> photos = saved.getSections().get(0).getPhotos();
        assertThat(photos).hasSize(1);
        assertThat(photos.get(0).getTags()).extracting(PhotoTag::getTagText).containsExactlyInAnyOrder(
                "sunset", "beach");
    }

    @Test
    void createFromForm_emptyUploadSlotIsSkipped_onlyRealFileIsStored() throws Exception {
        SubmissionFormCommand command = baseCommand();
        MockMultipartFile emptySlot = new MockMultipartFile("photo", "", "image/png", new byte[0]);
        MockMultipartFile realFile = new MockMultipartFile("photo", "cat.png", "image/png", realPngBytes());
        command.getSections().get(0).setPhotos(new ArrayList<>(List.of(emptySlot, realFile)));
        command.getSections().get(0).setPhotoTags(new ArrayList<>(List.of("", "kept")));

        SubmissionResultResponse response = submissionService.createFromForm("form-empty-slot@example.com", command);

        Testimonial saved = testimonialRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getSections().get(0).getPhotos()).hasSize(1);
        assertThat(saved.getSections().get(0).getPhotos().get(0).getTags())
                .extracting(PhotoTag::getTagText)
                .containsExactly("kept");
    }

    @Test
    void createFromForm_contactEntryWithBlankValueIsSkipped() {
        // The form renders one contact row per active contact type so the
        // visitor never has to "add" one — most rows are left blank, and a
        // blank row must be dropped rather than rejected as an invalid
        // ContactMethodInput (its value would fail @NotBlank otherwise).
        SubmissionFormCommand command = baseCommand();
        ContactFormEntry blank = new ContactFormEntry();
        blank.setTypeSlug("whatsapp");
        blank.setValue("   ");
        ContactFormEntry filled = new ContactFormEntry();
        filled.setTypeSlug("email");
        filled.setValue("david@example.com");
        filled.setPublicContact(true);
        command.setContactMethods(new ArrayList<>(List.of(blank, filled)));

        SubmissionResultResponse response = submissionService.createFromForm("form-blank-contact@example.com", command);

        Testimonial saved = testimonialRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getContactMethods()).hasSize(1);
        assertThat(saved.getContactMethods().get(0).getContactType().getSlug()).isEqualTo("email");
    }

    @Test
    void createFromForm_nullAchievementsAndContactMethods_defaultsToEmptyWithoutError() {
        SubmissionFormCommand command = baseCommand();
        command.setAchievementSlugs(null);
        command.setContactMethods(null);

        SubmissionResultResponse response = submissionService.createFromForm("form-null-optional@example.com", command);

        Testimonial saved = testimonialRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getAchievements()).isEmpty();
        assertThat(saved.getContactMethods()).isEmpty();
    }

    @Test
    void createFromForm_sectionWithBlankTopicSlugIsSkipped() {
        SubmissionFormCommand command = baseCommand();
        SectionFormEntry blank = new SectionFormEntry();
        blank.setTopicSlug("");
        blank.setAnswerText("Should be ignored.");
        command.getSections().add(blank);

        SubmissionResultResponse response = submissionService.createFromForm("form-blank-slug@example.com", command);

        Testimonial saved = testimonialRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getSections()).hasSize(1);
        assertThat(saved.getSections().get(0).getTopic().getSlug()).isEqualTo("general");
    }

    @Test
    void createFromForm_noSectionsAtAll_throwsSubmissionValidationException() {
        SubmissionFormCommand command = baseCommand();
        command.setSections(new ArrayList<>());

        assertThatThrownBy(() -> submissionService.createFromForm("form-no-sections@example.com", command))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void createFromForm_blankFirstName_throwsSubmissionValidationException() {
        SubmissionFormCommand command = baseCommand();
        command.setFirstName("   ");

        assertThatThrownBy(() -> submissionService.createFromForm("form-blank-first-name@example.com", command))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void createFromForm_recommendationScoreAboveMax_throwsSubmissionValidationException() {
        SubmissionFormCommand command = baseCommand();
        command.setRecommendationScore(11);

        assertThatThrownBy(() -> submissionService.createFromForm("form-score-too-high@example.com", command))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void createFromForm_missingConsent_throwsSubmissionValidationException() {
        SubmissionFormCommand command = baseCommand();
        command.setDataProcessingConsent(false);

        assertThatThrownBy(() -> submissionService.createFromForm("form-no-consent@example.com", command))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void createFromForm_secondSubmissionForSameVisitor_throwsTestimonialAlreadyExists() {
        SubmissionFormCommand command = baseCommand();
        submissionService.createFromForm("form-duplicate@example.com", command);

        assertThatThrownBy(() -> submissionService.createFromForm("form-duplicate@example.com", baseCommand()))
                .isInstanceOf(TestimonialAlreadyExistsException.class);
    }

    // -- editFromForm --

    @Test
    void editFromForm_noExistingTestimonial_throwsNotFound() {
        SubmissionFormCommand command = baseCommand();

        assertThatThrownBy(() -> submissionService.editFromForm("form-edit-missing@example.com", command))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void editFromForm_keepsExistingPhotoAndAddsNewOne() throws Exception {
        String email = "form-edit-keep-photo@example.com";
        MockMultipartFile firstPhoto = new MockMultipartFile("photo", "a.png", "image/png", realPngBytes());
        TestimonialSubmissionRequest createRequest = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput(
                        "general", "Text.", List.of(new TestimonialSubmissionRequest.PhotoInput("a", List.of("old"))))),
                List.of(),
                List.of(),
                true);
        SubmissionResultResponse created = submissionService.create(email, createRequest, Map.of("a", firstPhoto));
        String existingUrl = testimonialRepository
                .findById(created.id())
                .orElseThrow()
                .getSections()
                .get(0)
                .getPhotos()
                .get(0)
                .getFilePath();
        String existingPhotoUrl = "/uploads/" + existingUrl;

        SubmissionFormCommand editCommand = baseCommand();
        SectionFormEntry section = editCommand.getSections().get(0);
        section.setExistingPhotoUrls(new ArrayList<>(List.of(existingPhotoUrl)));
        section.setExistingPhotoTags(new ArrayList<>(List.of("old")));
        MockMultipartFile secondPhoto = new MockMultipartFile("photo", "b.png", "image/png", realPngBytes());
        section.setPhotos(new ArrayList<>(List.of(secondPhoto)));
        section.setPhotoTags(new ArrayList<>(List.of("new")));

        submissionService.editFromForm(email, editCommand);

        List<Photo> photos = testimonialRepository
                .findById(created.id())
                .orElseThrow()
                .getSections()
                .get(0)
                .getPhotos();
        assertThat(photos).hasSize(2);
    }

    @Test
    void editFromForm_removedPhotoUrlDropsThatPhoto() throws Exception {
        String email = "form-edit-remove-photo@example.com";
        MockMultipartFile firstPhoto = new MockMultipartFile("photo", "a.png", "image/png", realPngBytes());
        TestimonialSubmissionRequest createRequest = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput(
                        "general", "Text.", List.of(new TestimonialSubmissionRequest.PhotoInput("a", List.of())))),
                List.of(),
                List.of(),
                true);
        SubmissionResultResponse created = submissionService.create(email, createRequest, Map.of("a", firstPhoto));
        String existingUrl = "/uploads/"
                + testimonialRepository
                        .findById(created.id())
                        .orElseThrow()
                        .getSections()
                        .get(0)
                        .getPhotos()
                        .get(0)
                        .getFilePath();

        SubmissionFormCommand editCommand = baseCommand();
        SectionFormEntry section = editCommand.getSections().get(0);
        section.setExistingPhotoUrls(new ArrayList<>(List.of(existingUrl)));
        section.setExistingPhotoTags(new ArrayList<>(List.of("")));
        section.setRemovedPhotoUrls(new ArrayList<>(List.of(existingUrl)));

        submissionService.editFromForm(email, editCommand);

        TestimonialSection reloaded = testimonialRepository
                .findById(created.id())
                .orElseThrow()
                .getSections()
                .get(0);
        assertThat(reloaded.getPhotos()).isEmpty();
    }

    @Test
    void editFromForm_matchesEquivalentDirectEditCall() {
        String emailForm = "form-edit-equiv-a@example.com";
        String emailDirect = "form-edit-equiv-b@example.com";
        submissionService.createFromForm(emailForm, baseCommand());
        submissionService.create(
                emailDirect,
                new TestimonialSubmissionRequest(
                        "David",
                        "Jones",
                        "GE26Z001",
                        2024,
                        "IN",
                        8,
                        List.of(new SectionInput("general", "Great time overall.", List.of())),
                        List.of(),
                        List.of(),
                        true),
                Map.of());

        SubmissionFormCommand editCommand = baseCommand();
        editCommand.getSections().get(0).setAnswerText("Updated via form.");
        SubmissionResultResponse viaForm = submissionService.editFromForm(emailForm, editCommand);

        TestimonialSubmissionRequest directEdit = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput("general", "Updated via form.", List.of())),
                List.of(),
                List.of(),
                true);
        SubmissionResultResponse viaDirect = submissionService.edit(emailDirect, directEdit, Map.of());

        assertThat(viaForm.status()).isEqualTo(viaDirect.status());
        Testimonial formResult = testimonialRepository.findById(viaForm.id()).orElseThrow();
        assertThat(formResult.getSections().get(0).getAnswerText()).isEqualTo("Updated via form.");
    }
}
