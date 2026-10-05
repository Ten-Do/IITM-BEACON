package com.iitm.beacon.common.score;

import java.util.List;

/**
 * Fixed recommendation score to label table (docs/use-cases.md
 * "Recommendation score"), indexed directly by the 0-10 score. Lives in
 * {@code common} because both the {@code gallery} article view and the
 * {@code submission} form's slider show it, and feature slices never import
 * each other (decision 9).
 */
public final class RecommendationScoreLabels {

    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 10;

    private static final List<String> LABELS = List.of(
            "Terrible — I regretted my choice a hundred times over",
            "A very rough experience, almost nothing positive",
            "It was very hard",
            "Lots of serious problems",
            "More disappointed than satisfied",
            "Mixed — real upsides, but real downsides too",
            "Solid overall, would work for a lot of people",
            "A good experience, happy with the choice",
            "A great experience, a lot to remember",
            "Excellent! I'm taking a wealth of memories with me!",
            "Unforgettable! One of the best decisions of this year!");

    private RecommendationScoreLabels() {
    }

    /** Every label, element {@code i} belonging to score {@code i}; unmodifiable. */
    public static List<String> all() {
        return LABELS;
    }

    /**
     * The label for one score. A stored score is always in range (the
     * {@code testimonial.recommendation_score} check constraint), so anything
     * else is a programming error.
     *
     * @throws IllegalArgumentException if {@code score} is outside 0-10
     */
    public static String forScore(int score) {
        if (score < MIN_SCORE || score > MAX_SCORE) {
            throw new IllegalArgumentException(
                    "Recommendation score must be between " + MIN_SCORE + " and " + MAX_SCORE + ", was " + score);
        }
        return LABELS.get(score);
    }
}
