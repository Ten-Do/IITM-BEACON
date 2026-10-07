package com.iitm.beacon.moderation;

/**
 * What one run of the rejected-testimonial purge deleted (UC-PURGE-REJECTED,
 * decision 3): how many testimonials, and how many photos they held. Counts
 * only — never anything that identifies a submitter.
 */
public record RejectionPurgeResult(int testimonialsPurged, int photosPurged) {
}
