package com.iitm.beacon.common.error;

/**
 * Thrown when a client exceeds an OTP request rate limit (per-email or
 * per-IP). Mapped to HTTP 429 by {@link GlobalExceptionHandler}.
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
