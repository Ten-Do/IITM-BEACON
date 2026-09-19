package com.iitm.beacon.common;

import java.util.Locale;

/**
 * Small shared utility to normalize a raw email address (trim + lowercase)
 * before using it as a lookup/cache key or comparing it. Deliberately a
 * separate utility from {@code EmailLookupHashService}'s own inline
 * normalization — not a refactor of that already-tested class.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalArgumentException("email must not be null or blank");
        }
        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }
}
