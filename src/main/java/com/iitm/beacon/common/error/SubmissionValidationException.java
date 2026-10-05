package com.iitm.beacon.common.error;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when a testimonial submission breaks one or more validation rules
 * (decision 21): Bean Validation on the request, the identity-field formats
 * (decision 10), a contact value not matching its type's pattern (decision
 * 5), photos on a section with a blank answer, an unknown/inactive topic,
 * achievement, contact type, or country, a photo whose {@code fileRef}
 * doesn't resolve, too many photos, a non-image or oversized photo upload,
 * or no section left filled in. Mapped to HTTP 400 by {@link
 * GlobalExceptionHandler}.
 *
 * <p>Carries every violation of one submission, collected in a single pass,
 * as {@link FieldViolation}s — each tied to the input it concerns, or
 * {@linkplain FieldViolation#global global}. The single-message constructor
 * is the one-global-violation shortcut. {@link #getMessage()} joins them all
 * as {@code field: message, field2: message2} (a global one contributes just
 * its message), which is exactly what REST callers receive.
 */
public class SubmissionValidationException extends RuntimeException {

    private final ArrayList<FieldViolation> violations;

    /** A single global violation (not tied to one field). */
    public SubmissionValidationException(String message) {
        super(message);
        this.violations = new ArrayList<>(List.of(FieldViolation.global(message)));
    }

    /** Every violation of one submission, in the order they were found. */
    public SubmissionValidationException(List<FieldViolation> violations) {
        super(describe(violations));
        this.violations = new ArrayList<>(violations);
    }

    /** Defensive copy (SpotBugs EI_EXPOSE_REP). */
    public List<FieldViolation> getViolations() {
        return List.copyOf(violations);
    }

    private static String describe(List<FieldViolation> violations) {
        return violations.stream().map(FieldViolation::describe).collect(Collectors.joining(", "));
    }
}
