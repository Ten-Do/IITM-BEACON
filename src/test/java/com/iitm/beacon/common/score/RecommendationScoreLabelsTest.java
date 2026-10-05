package com.iitm.beacon.common.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The fixed 0-10 recommendation score to label table from
 * docs/use-cases.md ("Recommendation score"), shared by the gallery article
 * view and the submission form's slider.
 */
class RecommendationScoreLabelsTest {

    @Test
    void all_hasExactlyOneLabelPerScoreFromZeroToTen() {
        assertThat(RecommendationScoreLabels.all()).hasSize(11);
        assertThat(RecommendationScoreLabels.MIN_SCORE).isZero();
        assertThat(RecommendationScoreLabels.MAX_SCORE).isEqualTo(10);
    }

    @Test
    void all_matchesTheUseCaseTableVerbatimAndInOrder() {
        assertThat(RecommendationScoreLabels.all()).containsExactly(
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
    }

    @Test
    void forScore_lowestAndHighestScores_returnTheEndLabels() {
        assertThat(RecommendationScoreLabels.forScore(0))
                .isEqualTo("Terrible — I regretted my choice a hundred times over");
        assertThat(RecommendationScoreLabels.forScore(10))
                .isEqualTo("Unforgettable! One of the best decisions of this year!");
    }

    @Test
    void forScore_agreesWithAllForEveryValidScore() {
        List<String> all = RecommendationScoreLabels.all();
        for (int score = 0; score <= 10; score++) {
            assertThat(RecommendationScoreLabels.forScore(score)).isEqualTo(all.get(score));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 11, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void forScore_outsideZeroToTen_isRejected(int score) {
        assertThatThrownBy(() -> RecommendationScoreLabels.forScore(score))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(score));
    }

    @Test
    void all_cannotBeModifiedByCallers() {
        List<String> all = RecommendationScoreLabels.all();

        assertThatThrownBy(() -> all.set(0, "tampered")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(RecommendationScoreLabels.forScore(0)).startsWith("Terrible");
    }
}
