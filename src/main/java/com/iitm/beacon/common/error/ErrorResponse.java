package com.iitm.beacon.common.error;

import java.time.Instant;

/**
 * Uniform error shape returned by {@link GlobalExceptionHandler}, matching the
 * {@code ErrorResponse} schema locked in {@code docs/api-spec.yaml}.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
