package com.iitm.beacon.config;

/**
 * Masks an email address for a log line (NFR-CONTACT-CONFIDENTIALITY: an
 * email is never logged in plaintext, in any environment): the local part's
 * first character, {@code ***}, then {@code @} and the domain —
 * {@code j***@example.com}. A local part of a single character is masked
 * completely ({@code ***@example.com}), and the mask never shows how long
 * the local part is. Anything without an {@code @} is just {@code ***}.
 * Control characters (a line break, say) become {@code ?}, so a crafted
 * address can't forge a log line.
 */
final class LogMask {

    private static final String MASK = "***";

    private LogMask() {
    }

    static String email(String email) {
        if (email == null) {
            return MASK;
        }
        String value = email.strip();
        int at = value.lastIndexOf('@');
        if (at < 0) {
            return MASK;
        }
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        String kept = local.codePointCount(0, local.length()) > 1
                ? local.substring(0, Character.charCount(local.codePointAt(0)))
                : "";
        return withoutControlCharacters(kept + MASK + "@" + domain);
    }

    private static String withoutControlCharacters(String text) {
        StringBuilder safe = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            safe.append(Character.isISOControl(c) ? '?' : c);
        }
        return safe.toString();
    }
}
