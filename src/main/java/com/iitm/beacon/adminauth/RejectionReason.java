package com.iitm.beacon.adminauth;

/**
 * Internal reason an OTP verification was rejected. Never crosses the HTTP
 * boundary — {@code AdminAuthController} maps every {@code Rejected} result
 * to one undifferentiated {@code OtpVerificationFailedException} (401),
 * matching {@code api-spec.yaml}.
 */
public enum RejectionReason {
    NO_PENDING_OTP,
    EXPIRED,
    WRONG_CODE,
    ATTEMPTS_EXHAUSTED
}
