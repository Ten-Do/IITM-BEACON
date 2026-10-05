package com.iitm.beacon.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SubmissionValidationException}'s two shapes (decision 21): a single
 * global message (the pre-existing constructor) and a list of {@link
 * FieldViolation}s collected in one pass.
 */
class SubmissionValidationExceptionTest {

    @Test
    void stringConstructor_keepsTheMessageAndExposesItAsOneGlobalViolation() {
        SubmissionValidationException ex = new SubmissionValidationException("Too many photos: maximum is 20.");

        assertThat(ex.getMessage()).isEqualTo("Too many photos: maximum is 20.");
        assertThat(ex.getViolations()).containsExactly(FieldViolation.global("Too many photos: maximum is 20."));
        assertThat(ex.getViolations().get(0).isGlobal()).isTrue();
    }

    @Test
    void listConstructor_joinsFieldViolationsAsFieldColonMessageInOrder() {
        SubmissionValidationException ex = new SubmissionValidationException(List.of(
                new FieldViolation("rollNumber", "must look like CS21B001"),
                new FieldViolation("contactMethods[1].value", "doesn't look like a valid WhatsApp contact")));

        assertThat(ex.getMessage()).isEqualTo("rollNumber: must look like CS21B001,"
                + " contactMethods[1].value: doesn't look like a valid WhatsApp contact");
        assertThat(ex.getViolations()).extracting(FieldViolation::field)
                .containsExactly("rollNumber", "contactMethods[1].value");
    }

    @Test
    void listConstructor_globalViolationIsJoinedAsItsPlainMessage() {
        SubmissionValidationException ex = new SubmissionValidationException(List.of(
                FieldViolation.global("Too many photos: maximum is 20."),
                new FieldViolation("admissionYear", "must be between 1959 and 2026")));

        assertThat(ex.getMessage())
                .isEqualTo("Too many photos: maximum is 20., admissionYear: must be between 1959 and 2026");
    }

    @Test
    void listConstructor_singleGlobalViolation_messageIsExactlyThatMessage() {
        SubmissionValidationException ex =
                new SubmissionValidationException(List.of(FieldViolation.global("Photo exceeds the maximum.")));

        assertThat(ex.getMessage()).isEqualTo("Photo exceeds the maximum.");
    }

    @Test
    void listConstructor_emptyList_hasABlankMessageAndNoViolations() {
        // A blank message is what GlobalExceptionHandler already falls back
        // from (to its generic "Validation failed"), same as new
        // SubmissionValidationException("").
        SubmissionValidationException ex = new SubmissionValidationException(List.of());

        assertThat(ex.getMessage()).isEmpty();
        assertThat(ex.getViolations()).isEmpty();
    }

    @Test
    void listConstructor_copiesTheListSoLaterChangesToTheCallersListDoNotLeakIn() {
        List<FieldViolation> violations = new ArrayList<>();
        violations.add(new FieldViolation("firstName", "must not be blank"));
        SubmissionValidationException ex = new SubmissionValidationException(violations);

        violations.add(new FieldViolation("lastName", "must not be blank"));

        assertThat(ex.getViolations()).extracting(FieldViolation::field).containsExactly("firstName");
        assertThat(ex.getMessage()).isEqualTo("firstName: must not be blank");
    }

    @Test
    void fieldViolation_withAField_isNotGlobal() {
        assertThat(new FieldViolation("countryCode", "Unknown country code: ZZ").isGlobal()).isFalse();
    }
}
