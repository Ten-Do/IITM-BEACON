package com.iitm.beacon.domain.achievement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Generic repository CRUD/constraint behavior, exercised with
 * {@code fixture_}-prefixed slugs so these tests are independent of the
 * seeded achievement checklist verified separately in {@link
 * AchievementSeedDataTest}.
 */
class AchievementRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private AchievementRepository achievementRepository;

    @Test
    void savedAchievement_roundTrips() {
        Achievement saved = achievementRepository.saveAndFlush(Achievement.builder()
                .slug("fixture_made_new_friends")
                .label("Made new friends here")
                .displayOrder(1)
                .build());

        var found = achievementRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getLabel()).isEqualTo("Made new friends here");
    }

    @Test
    void findBySlug_existingSlug_returnsAchievement() {
        achievementRepository.saveAndFlush(Achievement.builder()
                .slug("fixture_found_love")
                .label("Found love / started a relationship")
                .displayOrder(2)
                .build());

        assertThat(achievementRepository.findBySlug("fixture_found_love")).isPresent();
    }

    @Test
    void findBySlug_nonExistentSlug_returnsEmpty() {
        assertThat(achievementRepository.findBySlug("does-not-exist")).isEmpty();
    }

    @Test
    void duplicateSlug_violatesUniqueConstraint() {
        achievementRepository.saveAndFlush(Achievement.builder()
                .slug("fixture_traveled_within_india")
                .label("Traveled within India")
                .displayOrder(3)
                .build());

        assertThatThrownBy(() -> achievementRepository.saveAndFlush(Achievement.builder()
                        .slug("fixture_traveled_within_india")
                        .label("duplicate")
                        .displayOrder(4)
                        .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void active_defaultsToTrue_whenNotExplicitlySet() {
        Achievement saved = achievementRepository.saveAndFlush(Achievement.builder()
                .slug("fixture_learned_local_dish")
                .label("Learned to cook a local dish")
                .displayOrder(5)
                .build());

        assertThat(saved.isActive()).isTrue();
    }

    // -- findAllByOrderByDisplayOrderAscIdAsc: the admin catalog list (display order, then id) --

    @Test
    void findAllOrdered_sortsByDisplayOrder_thenBreaksTiesById() {
        Achievement late = achievementRepository.saveAndFlush(achievement("fixture_order_late", 9999, true));
        Achievement tieFirst = achievementRepository.saveAndFlush(achievement("fixture_order_tie_a", 0, true));
        Achievement tieSecond = achievementRepository.saveAndFlush(achievement("fixture_order_tie_b", 0, true));
        Set<Long> fixtureIds = Set.of(late.getId(), tieFirst.getId(), tieSecond.getId());

        List<Achievement> all = achievementRepository.findAllByOrderByDisplayOrderAscIdAsc();

        assertThat(all).isSortedAccordingTo(
                Comparator.comparing(Achievement::getDisplayOrder).thenComparing(Achievement::getId));
        assertThat(all.stream().map(Achievement::getId).filter(fixtureIds::contains))
                .containsExactly(tieFirst.getId(), tieSecond.getId(), late.getId());
    }

    private static Achievement achievement(String slug, int displayOrder, boolean active) {
        return Achievement.builder()
                .slug(slug)
                .label("Fixture " + slug)
                .displayOrder(displayOrder)
                .active(active)
                .build();
    }
}
