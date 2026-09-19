package com.iitm.beacon.submission;

import java.time.Instant;

/**
 * In-memory per-visitor pending OTP state (decision 17). {@code codeHash} is
 * a plain unsalted SHA-256 digest of the code, not the plaintext — the
 * deliberate asymmetry vs the admin's {@code OtpState} documented in
 * docs/architecture.md §6.
 */
public record VisitorOtpState(String codeHash, Instant expiresAt, int attemptsRemaining) {
}
