package com.iitm.beacon.common.otp;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Generates 6-character alphanumeric OTP codes, deliberately excluding
 * characters that are easily confused with one another when read aloud or
 * typed ({@code 0}/{@code O}, {@code 1}/{@code I}/{@code L}) — used by both
 * the admin (decision 4) and visitor (decision 17) OTP flows.
 */
@Component
public class OtpCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
