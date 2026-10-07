package com.iitm.beacon.common.error;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when a catalog change (topic group, topic, achievement) breaks a
 * field rule the service checks itself, beyond Bean Validation on the
 * request — e.g. a {@code topicGroupId} naming no existing group (decision
 * 28). Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 *
 * <p>Carries every violation as a {@link FieldViolation}, so a page can show
 * each next to its own field; {@link #getMessage()} joins them as {@code
 * field: message, field2: message2}, exactly like {@link
 * SubmissionValidationException}, which is what REST callers receive.
 */
public class CatalogValidationException extends RuntimeException {

    private final ArrayList<FieldViolation> violations;

    public CatalogValidationException(List<FieldViolation> violations) {
        super(violations.stream().map(FieldViolation::describe).collect(Collectors.joining(", ")));
        this.violations = new ArrayList<>(violations);
    }

    /** Defensive copy (SpotBugs EI_EXPOSE_REP). */
    public List<FieldViolation> getViolations() {
        return List.copyOf(violations);
    }
}
