package com.iitm.beacon.submission;

/**
 * Result of {@code VisitorOtpService#verify}.
 */
public sealed interface VisitorOtpVerifyResult {

    record Verified(String email) implements VisitorOtpVerifyResult {
    }

    record Rejected(RejectionReason reason) implements VisitorOtpVerifyResult {
    }
}
