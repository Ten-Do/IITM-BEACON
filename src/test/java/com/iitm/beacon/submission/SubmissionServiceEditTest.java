package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.ContactMethodInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * Focused tests for {@link SubmissionService#edit}, the largest test surface
 * of M3 (decision 18): each distinct short-circuit path gets its own test,
 * per docs/architecture.md §16's warning that this logic is easy to get
 * subtly wrong.
 */
@SpringBootTest
@Transactional
class SubmissionServiceEditTest {

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

    private TestimonialSubmissionRequest baseRequest(List<SectionInput> sections) {
        return new TestimonialSubmissionRequest(
                "David", "Jones", "GE26Z001", 2024, "IN", 8, sections, List.of(), List.of(), true);
    }

    private SectionInput section(String topicSlug, String answer) {
        return new SectionInput(topicSlug, answer, List.of());
    }

    private Testimonial createAndLoad(String email, TestimonialSubmissionRequest request) {
        SubmissionResultResponse result = submissionService.create(email, request, Map.of());
        return testimonialRepository.findById(result.id()).orElseThrow();
    }

    /** Forces a testimonial straight to APPROVED, bypassing moderation (test setup only). */
    private void approve(Long testimonialId) {
        Testimonial testimonial = testimonialRepository.findById(testimonialId).orElseThrow();
        testimonial.setStatus(TestimonialStatus.APPROVED);
        testimonial.setIdentityModified(false);
        testimonial.setScoreModified(false);
        testimonial.getSections().forEach(s -> s.setModified(false));
        testimonialRepository.saveAndFlush(testimonial);
    }

    @Test
    void edit_noExistingTestimonial_throwsNotFound() {
        TestimonialSubmissionRequest request = baseRequest(List.of(section("general", "Text.")));

        assertThatThrownBy(() -> submissionService.edit("nobody@example.com", request, Map.of()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void edit_textOnlyChangeOnApproved_resetsToPending() {
        String email = "text-change@example.com";
        Testimonial created = createAndLoad(email, baseRequest(List.of(section("general", "Original text."))));
        approve(created.getId());

        SubmissionResultResponse result =
                submissionService.edit(email, baseRequest(List.of(section("general", "Edited text."))), Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.PENDING);
        Testimonial reloaded = testimonialRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getSections().get(0).getAnswerText()).isEqualTo("Edited text.");
        assertThat(reloaded.getSections().get(0).isModified()).isTrue();
    }

    @Test
    void edit_photoAddedOnApproved_resetsToPendingAndMarksSectionModified() throws Exception {
        String email = "photo-add@example.com";
        Testimonial created = createAndLoad(email, baseRequest(List.of(section("general", "Text."))));
        approve(created.getId());

        MockMultipartFile newFile = new MockMultipartFile("photo", "new.png", "image/png", realPngBytes());
        SectionInput withNewPhoto =
                new SectionInput("general", "Text.", List.of(new PhotoInput("new-photo", List.of())));

        SubmissionResultResponse result =
                submissionService.edit(email, baseRequest(List.of(withNewPhoto)), Map.of("new-photo", newFile));

        assertThat(result.status()).isEqualTo(TestimonialStatus.PENDING);
        Testimonial reloaded = testimonialRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getSections().get(0).getPhotos()).hasSize(1);
        assertThat(reloaded.getSections().get(0).isModified()).isTrue();
    }

    @Test
    void edit_photoRemovedOnApproved_resetsToPendingAndDeletesFile() throws Exception {
        String email = "photo-remove@example.com";
        MockMultipartFile file = new MockMultipartFile("photo", "a.png", "image/png", realPngBytes());
        SectionInput withPhoto = new SectionInput("general", "Text.", List.of(new PhotoInput("a", List.of())));
        Testimonial created = createAndLoad(email, baseRequest(List.of(withPhoto)), "a", file);
        String photoUrl = testimonialRepository
                .findById(created.getId())
                .orElseThrow()
                .getSections()
                .get(0)
                .getPhotos()
                .get(0)
                .getFilePath();
        approve(created.getId());

        SectionInput noPhotos = new SectionInput("general", "Text.", List.of());
        SubmissionResultResponse result = submissionService.edit(email, baseRequest(List.of(noPhotos)), Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.PENDING);
        Testimonial reloaded = testimonialRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getSections().get(0).getPhotos()).isEmpty();
        assertThat(photoUrl).isNotBlank();
    }

    @Test
    void edit_tagOnlyChangeOnKeptPhoto_resetsToPending() throws Exception {
        String email = "tag-change@example.com";
        MockMultipartFile file = new MockMultipartFile("photo", "a.png", "image/png", realPngBytes());
        SectionInput withPhoto =
                new SectionInput("general", "Text.", List.of(new PhotoInput("a", List.of("old-tag"))));
        Testimonial created = createAndLoad(email, baseRequest(List.of(withPhoto)), "a", file);
        String photoUrl = testimonialRepository
                .findById(created.getId())
                .orElseThrow()
                .getSections()
                .get(0)
                .getPhotos()
                .get(0)
                .getFilePath();
        approve(created.getId());

        SectionInput retagged = new SectionInput(
                "general", "Text.", List.of(new PhotoInput("/uploads/" + photoUrl, List.of("new-tag"))));
        SubmissionResultResponse result = submissionService.edit(email, baseRequest(List.of(retagged)), Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.PENDING);
        Testimonial reloaded = testimonialRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getSections().get(0).getPhotos()).hasSize(1);
        assertThat(reloaded.getSections().get(0).getPhotos().get(0).getTags())
                .extracting(t -> t.getTagText())
                .containsExactly("new-tag");
    }

    @Test
    void edit_sectionRemovedOnApprovedWithNothingElseChanged_staysApproved() {
        String email = "section-remove@example.com";
        Testimonial created = createAndLoad(
                email,
                baseRequest(List.of(section("general", "General text."), section("academics_teaching", "Good."))));
        approve(created.getId());

        SubmissionResultResponse result =
                submissionService.edit(email, baseRequest(List.of(section("general", "General text."))), Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.APPROVED);
        Testimonial reloaded = testimonialRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getSections()).hasSize(1);
        assertThat(reloaded.getSections().get(0).getTopic().getSlug()).isEqualTo("general");
    }

    @Test
    void edit_countryScoreAchievementsContactsOnlyChangeOnApproved_staysApprovedAndClearsScoreModified() {
        String email = "exempt-fields@example.com";
        Testimonial created = createAndLoad(email, baseRequest(List.of(section("general", "Text."))));
        approve(created.getId());

        TestimonialSubmissionRequest editRequest = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "US",
                3,
                List.of(section("general", "Text.")),
                List.of("made_new_friends"),
                List.of(new ContactMethodInput("email", "new@example.com", true)),
                true);

        SubmissionResultResponse result = submissionService.edit(email, editRequest, Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.APPROVED);
        Testimonial reloaded = testimonialRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getCountry().getCode()).isEqualTo("US");
        assertThat(reloaded.getRecommendationScore()).isEqualTo(3);
        assertThat(reloaded.isScoreModified()).isFalse();
        assertThat(reloaded.isIdentityModified()).isFalse();
        assertThat(reloaded.getAchievements()).hasSize(1);
        assertThat(reloaded.getContactMethods()).hasSize(1);
    }

    @Test
    void edit_identityFieldChangeOnApproved_resetsToPendingAndSetsIdentityModified() {
        String email = "identity-change@example.com";
        Testimonial created = createAndLoad(email, baseRequest(List.of(section("general", "Text."))));
        approve(created.getId());

        TestimonialSubmissionRequest editRequest = new TestimonialSubmissionRequest(
                "Daniel", "Jones", "GE26Z001", 2024, "IN", 8, List.of(section("general", "Text.")), List.of(),
                List.of(), true);

        SubmissionResultResponse result = submissionService.edit(email, editRequest, Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.PENDING);
        Testimonial reloaded = testimonialRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.isIdentityModified()).isTrue();
        assertThat(reloaded.getFirstName()).isEqualTo("Daniel");
    }

    @Test
    void edit_anyEditOnPendingTestimonial_alwaysStaysPendingEvenForExemptFieldsOnly() {
        String email = "pending-edit@example.com";
        Testimonial created = createAndLoad(email, baseRequest(List.of(section("general", "Text."))));
        assertThat(created.getStatus()).isEqualTo(TestimonialStatus.PENDING);

        TestimonialSubmissionRequest editRequest = new TestimonialSubmissionRequest(
                "David", "Jones", "GE26Z001", 2024, "US", 5, List.of(section("general", "Text.")), List.of(),
                List.of(), true);

        SubmissionResultResponse result = submissionService.edit(email, editRequest, Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.PENDING);
    }

    @Test
    void edit_anyEditOnRejectedTestimonial_alwaysGoesToPending() {
        String email = "rejected-edit@example.com";
        Testimonial created = createAndLoad(email, baseRequest(List.of(section("general", "Text."))));
        created.setStatus(TestimonialStatus.REJECTED);
        testimonialRepository.saveAndFlush(created);

        SubmissionResultResponse result =
                submissionService.edit(email, baseRequest(List.of(section("general", "Text."))), Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.PENDING);
    }

    @Test
    void edit_downToZeroSections_throwsSubmissionValidationExceptionAndChangesNothing() {
        String email = "zero-sections@example.com";
        Testimonial created = createAndLoad(email, baseRequest(List.of(section("general", "Text."))));
        approve(created.getId());

        TestimonialSubmissionRequest allBlank = baseRequest(List.of(section("general", "   ")));

        assertThatThrownBy(() -> submissionService.edit(email, allBlank, Map.of()))
                .isInstanceOf(SubmissionValidationException.class);

        Testimonial reloaded = testimonialRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getSections()).hasSize(1);
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
    }

    @Test
    void edit_newSectionAddedOnApproved_resetsToPendingAndMarksItModified() {
        String email = "new-section@example.com";
        Testimonial created = createAndLoad(email, baseRequest(List.of(section("general", "Text."))));
        approve(created.getId());

        SubmissionResultResponse result = submissionService.edit(
                email,
                baseRequest(List.of(section("general", "Text."), section("academics_teaching", "New section."))),
                Map.of());

        assertThat(result.status()).isEqualTo(TestimonialStatus.PENDING);
        Testimonial reloaded = testimonialRepository.findById(created.getId()).orElseThrow();
        TestimonialSection newSection = reloaded.getSections().stream()
                .filter(s -> s.getTopic().getSlug().equals("academics_teaching"))
                .findFirst()
                .orElseThrow();
        assertThat(newSection.isModified()).isTrue();
    }

    @Test
    void edit_photoFileRefMatchingNothing_throwsSubmissionValidationException() {
        String email = "bad-fileref@example.com";
        Testimonial created = createAndLoad(email, baseRequest(List.of(section("general", "Text."))));
        approve(created.getId());

        SectionInput badPhoto =
                new SectionInput("general", "Text.", List.of(new PhotoInput("does-not-exist", List.of())));

        assertThatThrownBy(() -> submissionService.edit(email, baseRequest(List.of(badPhoto)), Map.of()))
                .isInstanceOf(SubmissionValidationException.class);
    }

    @Test
    void edit_newSectionWithFileRefClaimingAnExistingPhotoUrl_isRejected() {
        // Decision 18: a fileRef matching a photo URL only makes sense for a
        // section that already exists — a brand-new section has nothing to
        // "keep", so any fileRef on it must be a genuinely new upload.
        String email = "new-section-bad-ref@example.com";
        Testimonial created = createAndLoad(email, baseRequest(List.of(section("general", "Text."))));
        approve(created.getId());

        SectionInput newSectionClaimingExistingUrl = new SectionInput(
                "academics_teaching", "New.", List.of(new PhotoInput("/uploads/does-not-exist.png", List.of())));

        List<SectionInput> sections = List.of(section("general", "Text."), newSectionClaimingExistingUrl);
        assertThatThrownBy(() -> submissionService.edit(email, baseRequest(sections), Map.of()))
                .isInstanceOf(SubmissionValidationException.class);
    }

    private Testimonial createAndLoad(
            String email, TestimonialSubmissionRequest request, String fileRef, MockMultipartFile file) {
        SubmissionResultResponse result = submissionService.create(email, request, Map.of(fileRef, file));
        return testimonialRepository.findById(result.id()).orElseThrow();
    }
}
