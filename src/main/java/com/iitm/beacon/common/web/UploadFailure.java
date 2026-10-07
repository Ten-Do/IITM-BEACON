package com.iitm.beacon.common.web;

/**
 * What the submission form says after an upload the servlet container
 * couldn't read — too large, or too many parts. Shared by {@code
 * submission.SubmissionViewController}, which meets the failure when its
 * handler reads the form, and {@code config.UnreadableFormUploadHandler},
 * which meets it first when the CSRF check can't read the form's token
 * from that same body (BL-004).
 */
public final class UploadFailure {

    public static final String MESSAGE =
            "Your upload was too large or contained too many files. Please try again with fewer or smaller photos.";

    private UploadFailure() {
    }
}
