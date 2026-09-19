package com.iitm.beacon.domain.achievement;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the V15 Flyway seed migration (decision 13) — the 24 achievement
 * labels from docs/use-cases.md's "Achievement checklist" section.
 */
class AchievementSeedDataTest extends AbstractRepositoryTest {

    @Autowired
    private AchievementRepository achievementRepository;

    @Test
    void all24Achievements_areSeeded() {
        assertThat(achievementRepository.count()).isEqualTo(24);
    }

    @Test
    void seededSlugs_areAllUnique() {
        var slugs = achievementRepository.findAll().stream().map(Achievement::getSlug).collect(Collectors.toSet());

        assertThat(slugs).hasSize(24);
    }

    @Test
    void findBySlug_worksForAFewSeededSlugs() {
        assertThat(achievementRepository.findBySlug("made_new_friends")).isPresent();
        assertThat(achievementRepository.findBySlug("overcame_health_challenge")).isPresent();
        assertThat(achievementRepository.findBySlug("learned_to_budget")).isPresent();
    }
}
