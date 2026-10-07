package com.iitm.beacon.catalogadmin;

/**
 * What a catalog delete would take with it, for the confirmation page
 * (decision 28): the entry's label, how many testimonials (of any status)
 * lose a section or a tick, and how many topics go — the group's topics, the
 * one topic itself, or none for an achievement.
 */
public record DeletePreview(String label, long affectedTestimonials, int topicCount) {
}
