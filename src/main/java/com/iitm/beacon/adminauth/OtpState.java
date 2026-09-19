package com.iitm.beacon.adminauth;

import java.time.Instant;

/**
 * In-memory state for the single admin's pending OTP (decision 4).
 */
public record OtpState(String code, Instant expiresAt, int attemptsRemaining) {
}
