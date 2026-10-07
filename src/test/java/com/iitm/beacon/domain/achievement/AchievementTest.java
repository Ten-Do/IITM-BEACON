package com.iitm.beacon.domain.achievement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Achievement visibility (decision 28): achievements have no group, so visible means active. */
class AchievementTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void isVisible_exactlyWhenActive(boolean active) {
        Achievement achievement = Achievement.builder()
                .slug("fixture_visibility")
                .label("Fixture")
                .displayOrder(1)
                .active(active)
                .build();

        assertThat(achievement.isVisible()).isEqualTo(active);
    }
}
