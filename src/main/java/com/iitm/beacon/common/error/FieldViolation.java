package com.iitm.beacon.common.error;

import java.io.Serializable;

/**
 * One rule a submission breaks (decision 21). {@code field} is the path of
 * the offending input — request-level ({@code rollNumber}, {@code
 * sections[0].answer}, {@code contactMethods[1].value}) when raised by the
 * core create/edit path, or a form field path ({@code sections[4].answerText})
 * once the HTML form adapter has translated it. A {@code null} field marks a
 * global violation that belongs to the submission as a whole rather than to
 * one input (e.g. too many photos in total, an upload that isn't an image).
 */
public record FieldViolation(String field, String message) implements Serializable {

    /** A violation not tied to any one field. */
    public static FieldViolation global(String message) {
        return new FieldViolation(null, message);
    }

    public boolean isGlobal() {
        return field == null;
    }

    /** {@code field: message}, or just the message for a global violation. */
    public String describe() {
        return isGlobal() ? message : field + ": " + message;
    }
}
