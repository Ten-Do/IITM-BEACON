package com.iitm.beacon.submission;

/**
 * Public shape for {@code GET /submissions/contact-types} (decision 5) —
 * matches the {@code ContactType} schema in {@code api-spec.yaml}. {@code
 * name} is the network's display name ("WhatsApp"); {@code label} is the
 * placeholder describing what to enter ("phone number, or a wa.me link");
 * {@code valuePattern} is the regular expression (no anchors) a value must
 * fully match, or {@code null} when the type has no format check.
 */
public record ContactTypeView(String slug, String name, String label, String valuePattern) {
}
