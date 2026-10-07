package com.iitm.beacon.catalogadmin;

/**
 * Catalog input rules shared by the REST request records and the page forms
 * (decision 28): the trimming applied before any rule is checked, and the
 * Bean Validation patterns and messages, so both callers report the same
 * text for the same broken rule.
 */
final class CatalogText {

    /** Lowercase ASCII letters, digits and underscores. Emptiness is the blank check's job. */
    static final String SLUG_PATTERN = "[a-z0-9_]*";

    static final String SLUG_PATTERN_MESSAGE = "may contain only lowercase letters a-z, digits and underscores";
    static final String BLANK_MESSAGE = "must not be blank";
    static final String REQUIRED_MESSAGE = "is required";
    static final String SLUG_TOO_LONG_MESSAGE = "must be at most 64 characters";
    static final String LABEL_TOO_LONG_MESSAGE = "must be at most 120 characters";
    static final String PROMPT_TOO_LONG_MESSAGE = "must be at most 500 characters";
    static final String DISPLAY_ORDER_MESSAGE = "must be between 0 and 9999";

    static final int SLUG_MAX = 64;
    static final int LABEL_MAX = 120;
    static final int PROMPT_MAX = 500;
    static final int DISPLAY_ORDER_MAX = 9999;

    private CatalogText() {
    }

    /** {@code value} without leading and trailing whitespace; {@code null} stays {@code null} (absent). */
    static String trim(String value) {
        return value == null ? null : value.strip();
    }
}
