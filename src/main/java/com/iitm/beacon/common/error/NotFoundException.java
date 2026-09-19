package com.iitm.beacon.common.error;

/**
 * Thrown when a requested resource does not exist. Mapped to HTTP 404 by
 * {@link GlobalExceptionHandler}.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
