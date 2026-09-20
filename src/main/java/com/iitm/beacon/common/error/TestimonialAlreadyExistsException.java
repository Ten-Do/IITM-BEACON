package com.iitm.beacon.common.error;

/**
 * Thrown by {@code POST /submissions} when a testimonial already exists for
 * the visitor's email — they must use {@code PUT /submissions/mine} instead.
 * Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class TestimonialAlreadyExistsException extends RuntimeException {

    public TestimonialAlreadyExistsException(String message) {
        super(message);
    }
}
