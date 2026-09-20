package com.iitm.beacon.submission;

/**
 * Public shape for {@code GET /submissions/contact-types} (decision 5) —
 * matches the {@code ContactType} schema in {@code api-spec.yaml}.
 */
public record ContactTypeView(String slug, String label) {
}
