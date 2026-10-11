package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class SubmissionServiceLoadMineTest {

    @Autowired
    private SubmissionService submissionService;

    private TestimonialSubmissionRequest requestWithOneSection() {
        return new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput("general", "Great time overall.", List.of())),
                List.of("made_new_friends"),
                List.of(new TestimonialSubmissionRequest.ContactMethodInput("email", "david@example.com", true)),
                true);
    }

    @Test
    void loadMine_noExistingTestimonial_throwsNotFound() {
        assertThatThrownBy(() -> submissionService.loadMine("nobody@example.com"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void loadMine_existingTestimonial_returnsItsCurrentShape() {
        submissionService.create("mine-visitor@example.com", requestWithOneSection(), Map.of());

        TestimonialSubmissionView view = submissionService.loadMine("mine-visitor@example.com");

        assertThat(view.firstName()).isEqualTo("David");
        assertThat(view.countryCode()).isEqualTo("IN");
        assertThat(view.sections()).hasSize(1);
        assertThat(view.sections().get(0).topicSlug()).isEqualTo("general");
        assertThat(view.sections().get(0).answer()).isEqualTo("Great time overall.");
        assertThat(view.achievementSlugs()).containsExactly("made_new_friends");
        assertThat(view.contactMethods()).hasSize(1);
        assertThat(view.contactMethods().get(0).typeSlug()).isEqualTo("email");
        assertThat(view.contactMethods().get(0).value()).isEqualTo("david@example.com");
        assertThat(view.contactMethods().get(0).isPublic()).isTrue();
        assertThat(view.dataProcessingConsent()).isTrue();
    }

    /** The form's order, whatever order the sections were submitted (and stored) in. */
    @Test
    void loadMine_sectionsComeInDisplayOrder() {
        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest(
                "David",
                "Jones",
                "GE26Z001",
                2024,
                "IN",
                8,
                List.of(new SectionInput("general", "Last in display order.", List.of()),
                        new SectionInput("academics_workload", "Group 1, its 7th topic.", List.of()),
                        new SectionInput("academics_teaching", "Group 1, its 1st topic.", List.of())),
                List.of(),
                List.of(),
                true);
        submissionService.create("mine-order@example.com", request, Map.of());

        TestimonialSubmissionView view = submissionService.loadMine("mine-order@example.com");

        assertThat(view.sections()).extracting(TestimonialSubmissionView.SectionView::topicSlug)
                .containsExactly("academics_teaching", "academics_workload", "general");
    }

    @Test
    void loadMine_emailIsNormalizedForLookup() {
        submissionService.create("case-sensitive@example.com", requestWithOneSection(), Map.of());

        TestimonialSubmissionView view = submissionService.loadMine("  Case-Sensitive@Example.com  ");

        assertThat(view.firstName()).isEqualTo("David");
    }
}
