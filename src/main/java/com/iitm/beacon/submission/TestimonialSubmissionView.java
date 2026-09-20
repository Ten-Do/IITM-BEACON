package com.iitm.beacon.submission;

import java.util.List;

/**
 * Read-side shape for {@code GET /submissions/mine} — the visitor's own
 * testimonial, pre-filled into the same logical shape used to submit it
 * ({@code TestimonialSubmission} in {@code api-spec.yaml}), with photos
 * represented as {@code PhotoRef} (url + tags) rather than a write-side
 * {@code fileRef}.
 */
public record TestimonialSubmissionView(
        String firstName,
        String lastName,
        String rollNumber,
        Integer admissionYear,
        String countryCode,
        Integer recommendationScore,
        List<SectionView> sections,
        List<String> achievementSlugs,
        List<ContactMethodView> contactMethods,
        boolean dataProcessingConsent) {

    public TestimonialSubmissionView {
        // Defensive copies (SpotBugs EI_EXPOSE_REP2).
        sections = List.copyOf(sections);
        achievementSlugs = List.copyOf(achievementSlugs);
        contactMethods = List.copyOf(contactMethods);
    }

    /** Defensive copy on the getter side too (SpotBugs EI_EXPOSE_REP). */
    public List<SectionView> sections() {
        return List.copyOf(sections);
    }

    /** Defensive copy on the getter side too (SpotBugs EI_EXPOSE_REP). */
    public List<String> achievementSlugs() {
        return List.copyOf(achievementSlugs);
    }

    /** Defensive copy on the getter side too (SpotBugs EI_EXPOSE_REP). */
    public List<ContactMethodView> contactMethods() {
        return List.copyOf(contactMethods);
    }

    public record SectionView(String topicSlug, String answer, List<PhotoRef> photos) {

        public SectionView {
            photos = List.copyOf(photos);
        }

        /** Defensive copy on the getter side too (SpotBugs EI_EXPOSE_REP). */
        public List<PhotoRef> photos() {
            return List.copyOf(photos);
        }
    }

    public record PhotoRef(String url, List<String> tags) {

        public PhotoRef {
            tags = List.copyOf(tags);
        }

        /** Defensive copy on the getter side too (SpotBugs EI_EXPOSE_REP). */
        public List<String> tags() {
            return List.copyOf(tags);
        }
    }

    public record ContactMethodView(String typeSlug, String value, boolean isPublic) {
    }
}
