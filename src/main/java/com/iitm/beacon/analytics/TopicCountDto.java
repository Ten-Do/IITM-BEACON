package com.iitm.beacon.analytics;

/**
 * How many approved testimonials have at least one visible section in one
 * visible top-level catalog entry (decisions 11, 28, 30) — an active topic
 * group or a visible standalone topic. {@code id} is the topic-group id for
 * {@link Kind#GROUP} and the topic id for {@link Kind#STANDALONE}: the two
 * id sequences overlap (decision 29), so only the pair identifies an entry.
 * {@code count} is at least 1.
 */
public record TopicCountDto(Kind kind, Long id, String label, long count) {

    /** Which catalog table {@code id} belongs to; serialised as its name. */
    public enum Kind {
        GROUP,
        STANDALONE
    }
}
