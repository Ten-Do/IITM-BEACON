package com.iitm.beacon.submission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;

/**
 * Write-side shape for {@code POST /submissions} and {@code PUT
 * /submissions/mine}, matching the {@code TestimonialSubmission} schema in
 * {@code api-spec.yaml}. Topics are database-driven (decision 11), so
 * sections are a list keyed by {@code topicSlug} rather than one field per
 * topic.
 *
 * <p>Identity fields are format-checked, not verified (decision 10): the roll
 * number is trimmed and uppercased here, before validation, so {@code
 * cs21b001} is accepted and stored as {@code CS21B001}; the admission year's
 * 1959 floor is Bean Validation, its current-year ceiling is checked by
 * {@link SubmissionService} against the application clock. A blank country
 * code is reported only as missing, and whether a two-letter code names a
 * real country is checked by {@link SubmissionService}. These annotations
 * are applied by {@link SubmissionService} itself, together with its own
 * business rules, so every violation is reported in one pass (decision 21).
 */
public record TestimonialSubmissionRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank @Pattern(regexp = ROLL_NUMBER_PATTERN, message = ROLL_NUMBER_MESSAGE) String rollNumber,
        @NotNull @Min(value = EARLIEST_ADMISSION_YEAR, message = ADMISSION_YEAR_FLOOR_MESSAGE) Integer admissionYear,
        @NotBlank(message = SELECT_COUNTRY_MESSAGE) @Size(min = 2, max = 2, message = COUNTRY_CODE_LENGTH_MESSAGE)
                String countryCode,
        @NotNull @Min(0) @Max(10) Integer recommendationScore,
        @NotEmpty(message = AT_LEAST_ONE_SECTION_MESSAGE) List<@Valid SectionInput> sections,
        List<String> achievementSlugs,
        List<@Valid ContactMethodInput> contactMethods,
        @AssertTrue boolean dataProcessingConsent) {

    /** IITM's {@code AA00A000} roll-number shape, matched after uppercasing (decision 10). */
    public static final String ROLL_NUMBER_PATTERN = "[A-Z]{2}[0-9]{2}[A-Z][0-9]{3}";

    public static final String ROLL_NUMBER_MESSAGE =
            "must look like CS21B001 (two letters, two digits, a letter, three digits)";

    /** IIT Madras's founding year — no earlier admission is possible (decision 10). */
    public static final int EARLIEST_ADMISSION_YEAR = 1959;

    static final String ADMISSION_YEAR_FLOOR_MESSAGE =
            "must be between " + EARLIEST_ADMISSION_YEAR + " and the current year";

    /** Shared by an empty {@code sections} list and one whose sections are all blank. */
    public static final String AT_LEAST_ONE_SECTION_MESSAGE = "At least one section must be filled in.";

    /** A missing or blank country — on the form, the select's empty placeholder left chosen. */
    static final String SELECT_COUNTRY_MESSAGE = "Please select your country.";

    static final String COUNTRY_CODE_LENGTH_MESSAGE = "must be a two-letter country code";

    public TestimonialSubmissionRequest {
        rollNumber = normalizeRollNumber(rollNumber);
        countryCode = blankToNull(countryCode);
        // Defensive copies (SpotBugs EI_EXPOSE_REP2): a null `sections` is
        // left as-is so @NotEmpty produces its own clean 400, rather than an
        // NPE from copying a null list.
        sections = sections == null ? null : List.copyOf(sections);
        achievementSlugs = achievementSlugs == null ? List.of() : List.copyOf(achievementSlugs);
        contactMethods = contactMethods == null ? List.of() : List.copyOf(contactMethods);
    }

    /**
     * Trimmed and uppercased; blank becomes {@code null}, so a blank roll
     * number is reported once as blank, not also as a format mismatch.
     */
    private static String normalizeRollNumber(String rollNumber) {
        if (rollNumber == null || rollNumber.isBlank()) {
            return null;
        }
        return rollNumber.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * Blank becomes {@code null} (anything else is kept as sent), so a
     * blank country code is reported once, as missing — {@code @Size}
     * ignores {@code null} — not also as the wrong length.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Defensive copy on the getter side too (SpotBugs EI_EXPOSE_REP). */
    public List<SectionInput> sections() {
        return sections == null ? null : List.copyOf(sections);
    }

    /** Defensive copy on the getter side too (SpotBugs EI_EXPOSE_REP). */
    public List<String> achievementSlugs() {
        return List.copyOf(achievementSlugs);
    }

    /** Defensive copy on the getter side too (SpotBugs EI_EXPOSE_REP). */
    public List<ContactMethodInput> contactMethods() {
        return List.copyOf(contactMethods);
    }

    /**
     * One topic/subtopic block. {@code answer} may be blank — an entry with
     * a blank answer doesn't count as filled in (UC-CREATE-TESTIMONIAL alt
     * flow) and is dropped by {@link SubmissionService}, not rejected by
     * Bean Validation — unless it carries photos, which {@link
     * SubmissionService} rejects.
     */
    public record SectionInput(@NotBlank String topicSlug, String answer, List<@Valid PhotoInput> photos) {

        public SectionInput {
            photos = photos == null ? List.of() : List.copyOf(photos);
        }

        /** Defensive copy on the getter side too (SpotBugs EI_EXPOSE_REP). */
        public List<PhotoInput> photos() {
            return List.copyOf(photos);
        }
    }

    /**
     * {@code fileRef} names a multipart file part on create; on edit it is
     * dual-purpose — it may instead match an existing photo's own {@code
     * url} to keep that photo (decision 18).
     */
    public record PhotoInput(@NotBlank String fileRef, @Size(max = 10) List<String> tags) {

        public PhotoInput {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        /** Defensive copy on the getter side too (SpotBugs EI_EXPOSE_REP). */
        public List<String> tags() {
            return List.copyOf(tags);
        }
    }

    /**
     * {@code value} is trimmed here, so the trimmed value is both what's
     * matched against the type's {@code valuePattern} and what's stored
     * (decision 5).
     */
    public record ContactMethodInput(@NotBlank String typeSlug, @NotBlank String value, boolean isPublic) {

        public ContactMethodInput {
            value = value == null ? null : value.strip();
        }
    }
}
