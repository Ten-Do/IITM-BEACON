package com.iitm.beacon.submission;

/**
 * Tells the client whether to render a blank create form or load the
 * existing testimonial for editing, per {@code email_lookup_hash} (decision
 * 6).
 */
public enum SubmissionMode {
    CREATE,
    EDIT
}
