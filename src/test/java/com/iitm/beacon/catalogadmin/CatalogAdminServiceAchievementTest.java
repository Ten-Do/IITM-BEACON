package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.iitm.beacon.common.error.CatalogConflictException;
import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CatalogAdminService}'s achievement reads and writes
 * (UC-MANAGE-ACHIEVEMENTS, decision 28): list, get, create, and partial
 * update with slug uniqueness (409) on create and on edit. Deletes are in
 * {@code CatalogAdminServiceDeleteTest}.
 */
@SpringBootTest
@Transactional
class CatalogAdminServiceAchievementTest {

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private TestimonialSectionRepository testimonialSectionRepository;

    @Autowired
    private TestimonialAchievementRepository testimonialAchievementRepository;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    private CatalogAdminService service;
    private CatalogFixtures fixtures;

    @BeforeEach
    void setUp() {
        service = new CatalogAdminService(
                topicGroupRepository,
                topicRepository,
                achievementRepository,
                testimonialRepository,
                testimonialSectionRepository,
                testimonialAchievementRepository,
                mock(PhotoFileDeleter.class));
        fixtures = new CatalogFixtures(
                topicGroupRepository, topicRepository, achievementRepository, testimonialRepository,
                countryRepository);
    }

    private static AchievementDto dto(Achievement a) {
        return new AchievementDto(a.getId(), a.getSlug(), a.getLabel(), a.getDisplayOrder(), a.isActive());
    }

    @Test
    void list_returnsEveryAchievementIncludingInactiveOnes_inDisplayOrderThenId() {
        Achievement first = fixtures.achievement("First", 0, false);
        Achievement tieA = fixtures.achievement("Tie A", 9999, true);
        Achievement tieB = fixtures.achievement("Tie B", 9999, false);

        List<AchievementDto> achievements = service.listAchievements();

        assertThat(achievements).hasSize((int) achievementRepository.count());
        assertThat(achievements.get(0)).isEqualTo(dto(first));
        assertThat(achievements).endsWith(dto(tieA), dto(tieB));
        assertThat(achievements).extracting(AchievementDto::displayOrder).isSorted();
    }

    @Test
    void get_returnsTheAchievement_andUnknownIdThrowsNotFound() {
        Achievement a = fixtures.achievement("A", 3, false);

        assertThat(service.getAchievement(a.getId())).isEqualTo(dto(a));
        assertThatThrownBy(() -> service.getAchievement(987_654L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_savesAnActiveAchievement_trimmed() {
        AchievementDto created =
                service.createAchievement(new AchievementCreateRequest(" kayak_x ", " Kayaked ", 9999));

        assertThat(created).isEqualTo(new AchievementDto(created.id(), "kayak_x", "Kayaked", 9999, true));
        assertThat(achievementRepository.findBySlug("kayak_x")).isPresent();
    }

    @Test
    void create_withASlugAlreadyUsed_evenByAnInactiveOne_throwsConflictOnSlug() {
        Achievement inactive = fixtures.achievement("Off", 1, false);
        long before = achievementRepository.count();

        assertThatThrownBy(() -> service.createAchievement(new AchievementCreateRequest(inactive.getSlug(), "L", 1)))
                .isInstanceOf(CatalogConflictException.class)
                .satisfies(ex -> assertThat(((CatalogConflictException) ex).getField()).contains("slug"));
        assertThat(achievementRepository.count()).isEqualTo(before);
    }

    @Test
    void create_withATopicsSlug_isFine_slugsAreUniquePerTable() {
        assertThat(service.createAchievement(new AchievementCreateRequest("general", "General", 1)).slug())
                .isEqualTo("general");
    }

    @Test
    void patch_changesOnlyThePresentFields() {
        Achievement a = fixtures.achievement("Old", 2, true);

        assertThat(service.patchAchievement(a.getId(), new AchievementPatchRequest(null, "New", null, null)))
                .isEqualTo(new AchievementDto(a.getId(), a.getSlug(), "New", 2, true));
        assertThat(service.patchAchievement(a.getId(), new AchievementPatchRequest(null, null, 0, false)))
                .isEqualTo(new AchievementDto(a.getId(), a.getSlug(), "New", 0, false));
        assertThat(service.patchAchievement(a.getId(), new AchievementPatchRequest("new_slug_x", null, null, true)))
                .isEqualTo(new AchievementDto(a.getId(), "new_slug_x", "New", 0, true));
    }

    @Test
    void patch_withNoFields_changesNothing() {
        Achievement a = fixtures.achievement("Same", 2, false);

        assertThat(service.patchAchievement(a.getId(), new AchievementPatchRequest(null, null, null, null)))
                .isEqualTo(dto(a));
    }

    @Test
    void patch_slugToItsOwnCurrentValue_isNotAConflict() {
        Achievement a = fixtures.achievement("A", 2, true);

        assertThat(service.patchAchievement(a.getId(), new AchievementPatchRequest(a.getSlug(), null, null, null))
                .slug()).isEqualTo(a.getSlug());
    }

    @Test
    void patch_slugToAnotherAchievementsSlug_throwsConflictAndChangesNothing() {
        Achievement a = fixtures.achievement("A", 2, true);
        Achievement other = fixtures.achievement("B", 2, true);

        assertThatThrownBy(() -> service.patchAchievement(
                        a.getId(), new AchievementPatchRequest(other.getSlug(), "Changed", null, null)))
                .isInstanceOf(CatalogConflictException.class);
        assertThat(achievementRepository.findById(a.getId()).orElseThrow().getLabel()).isEqualTo("A");
    }

    @Test
    void patch_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> service.patchAchievement(987_654L, new AchievementPatchRequest(null, "X", null, null)))
                .isInstanceOf(NotFoundException.class);
    }
}
