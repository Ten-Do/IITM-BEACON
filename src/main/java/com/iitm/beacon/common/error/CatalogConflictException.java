package com.iitm.beacon.common.error;

import java.util.Optional;

/**
 * Thrown when a catalog change conflicts with the existing catalog (decision
 * 28): a slug already used by another entry of the same kind, or an attempt
 * to deactivate, delete, move into a group, or re-slug the protected {@code
 * general} topic. Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 *
 * <p>{@link #getField()} names the input that caused it (e.g. {@code slug}),
 * so a page can show the message next to that field; it is empty for a
 * conflict of the change as a whole.
 */
public class CatalogConflictException extends RuntimeException {

    private final String field;

    /** A conflict of the change as a whole, not tied to one field. */
    public CatalogConflictException(String message) {
        this(null, message);
    }

    /** A conflict caused by one field's value; a {@code null} field makes it global. */
    public CatalogConflictException(String field, String message) {
        super(message);
        this.field = field;
    }

    public Optional<String> getField() {
        return Optional.ofNullable(field);
    }
}
