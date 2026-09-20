package com.iitm.beacon.submission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Write-side shape for {@code POST /submissions} and {@code PUT
 * /submissions/mine}, matching the {@code TestimonialSubmission} schema in
 * {@code api-spec.yaml}. Topics are database-driven (decision 11), so
 * sections are a list keyed by {@code topicSlug} rather than one field per
 * topic.
 */
public record TestimonialSubmissionRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank String rollNumber,
        @NotNull Integer admissionYear,
        @NotBlank @Size(min = 2, max = 2) String countryCode,
        @NotNull @Min(0) @Max(10) Integer recommendationScore,
        @NotEmpty List<@Valid SectionInput> sections,
        List<String> achievementSlugs,
        List<@Valid ContactMethodInput> contactMethods,
        @AssertTrue boolean dataProcessingConsent) {

    public TestimonialSubmissionRequest {
        // Defensive copies (SpotBugs EI_EXPOSE_REP2): a null `sections` is
        // left as-is so @NotEmpty produces its own clean 400, rather than an
        // NPE from copying a null list.
        sections = sections == null ? null : List.copyOf(sections);
        achievementSlugs = achievementSlugs == null ? List.of() : List.copyOf(achievementSlugs);
        contactMethods = contactMethods == null ? List.of() : List.copyOf(contactMethods);
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
     * Bean Validation.
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

    public record ContactMethodInput(@NotBlank String typeSlug, @NotBlank String value, boolean isPublic) {
    }
}
