package com.iitm.beacon.common.error;

/**
 * Thrown when a moderation action targets a testimonial that is no longer in
 * {@code PENDING} status. Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class TestimonialNotPendingException extends RuntimeException {

    public TestimonialNotPendingException(String message) {
        super(message);
    }
}
