package com.iitm.beacon.adminauth;

/**
 * Result of {@code OtpService#verify}.
 */
public sealed interface OtpVerifyResult {

    record Verified(String email) implements OtpVerifyResult {
    }

    record Rejected(RejectionReason reason) implements OtpVerifyResult {
    }
}
