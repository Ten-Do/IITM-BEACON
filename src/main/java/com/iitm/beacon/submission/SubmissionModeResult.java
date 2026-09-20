package com.iitm.beacon.submission;

/**
 * Whether a just-verified visitor should be routed to UC-CREATE-TESTIMONIAL
 * or UC-EDIT-TESTIMONIAL, per {@code email_lookup_hash} (decision 6), and the
 * existing testimonial's id when one is found.
 */
public record SubmissionModeResult(SubmissionMode mode, Long testimonialId) {
}
