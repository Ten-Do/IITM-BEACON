package com.iitm.beacon.common.error;

/**
 * Thrown when an OTP verification attempt is rejected — wrong code, expired
 * code, exhausted attempts, or no pending OTP at all. Deliberately one
 * undifferentiated exception: the finer-grained {@code RejectionReason} stays
 * internal to each OTP service and never crosses the HTTP boundary, matching
 * {@code api-spec.yaml}'s single undifferentiated 401 for each verify
 * endpoint. Mapped to HTTP 401 by {@link GlobalExceptionHandler}.
 */
public class OtpVerificationFailedException extends RuntimeException {

    public OtpVerificationFailedException(String message) {
        super(message);
    }
}
