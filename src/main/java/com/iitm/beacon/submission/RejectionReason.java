package com.iitm.beacon.submission;

/**
 * Internal reason a visitor OTP verification was rejected. Never crosses the
 * HTTP boundary — own copy, structurally identical to
 * {@code adminauth.RejectionReason}, deliberately not shared (decision 9:
 * {@code submission} and {@code adminauth} never import each other).
 */
public enum RejectionReason {
    NO_PENDING_OTP,
    EXPIRED,
    WRONG_CODE,
    ATTEMPTS_EXHAUSTED
}
