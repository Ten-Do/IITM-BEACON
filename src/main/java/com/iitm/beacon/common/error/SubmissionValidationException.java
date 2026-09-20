package com.iitm.beacon.common.error;

/**
 * Thrown for every submission business-rule validation failure not
 * expressible as a plain Bean Validation annotation: an unknown/inactive
 * topic, achievement, contact type, or country; a photo whose {@code
 * fileRef} doesn't resolve; too many photos; a non-image or oversized photo
 * upload; or zero sections left after dropping blank-answer entries. Mapped
 * to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class SubmissionValidationException extends RuntimeException {

    public SubmissionValidationException(String message) {
        super(message);
    }
}
