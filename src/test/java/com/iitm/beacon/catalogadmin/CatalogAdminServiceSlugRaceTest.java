package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitm.beacon.common.error.CatalogConflictException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Two admins saving the same new slug at once: both pass the "is the slug
 * free" check, and the database's unique constraint stops the second save.
 * That second save must still answer 409 (a {@link
 * CatalogConflictException} on {@code slug}), not a 500. The race itself
 * can't be staged against a real database inside one test, so the
 * repositories are mocks that report the slug free and then fail the save.
 */
class CatalogAdminServiceSlugRaceTest {

    private TopicRepository topicRepository;
    private AchievementRepository achievementRepository;
    private CatalogAdminService service;

    @BeforeEach
    void setUp() {
        topicRepository = mock(TopicRepository.class);
        achievementRepository = mock(AchievementRepository.class);
        service = new CatalogAdminService(
                mock(TopicGroupRepository.class),
                topicRepository,
                achievementRepository,
                mock(TestimonialRepository.class),
                mock(TestimonialSectionRepository.class),
                mock(TestimonialAchievementRepository.class),
                mock(PhotoFileDeleter.class));
        DataIntegrityViolationException uniqueViolation =
                new DataIntegrityViolationException("could not execute statement; constraint [uk_topic_slug]");
        when(topicRepository.existsBySlug(anyString())).thenReturn(false);
        when(topicRepository.existsBySlugAndIdNot(anyString(), anyLong())).thenReturn(false);
        when(topicRepository.saveAndFlush(any(Topic.class))).thenThrow(uniqueViolation);
        when(achievementRepository.existsBySlug(anyString())).thenReturn(false);
        when(achievementRepository.existsBySlugAndIdNot(anyString(), anyLong())).thenReturn(false);
        when(achievementRepository.saveAndFlush(any(Achievement.class))).thenThrow(uniqueViolation);
    }

    private static void assertSlugConflictWithoutInternals(Throwable ex) {
        assertThat(ex).isInstanceOf(CatalogConflictException.class);
        assertThat(((CatalogConflictException) ex).getField()).contains("slug");
        assertThat(ex.getMessage()).doesNotContain("constraint").doesNotContain("statement");
    }

    @Test
    void createTopic_losingTheRace_isASlugConflict() {
        assertThatThrownBy(() -> service.createTopic(new TopicCreateRequest("race", "L", "P", 1, null)))
                .satisfies(CatalogAdminServiceSlugRaceTest::assertSlugConflictWithoutInternals);
    }

    @Test
    void patchTopic_losingTheRace_isASlugConflict() {
        Topic topic = Topic.builder().id(5L).slug("old").label("L").guidingPrompt("P").displayOrder(1).build();
        when(topicRepository.findById(5L)).thenReturn(Optional.of(topic));

        assertThatThrownBy(() -> service.patchTopic(5L, new TopicPatchRequest("race", null, null, null, null, null)))
                .satisfies(CatalogAdminServiceSlugRaceTest::assertSlugConflictWithoutInternals);
    }

    @Test
    void createAchievement_losingTheRace_isASlugConflict() {
        assertThatThrownBy(() -> service.createAchievement(new AchievementCreateRequest("race", "L", 1)))
                .satisfies(CatalogAdminServiceSlugRaceTest::assertSlugConflictWithoutInternals);
    }

    @Test
    void patchAchievement_losingTheRace_isASlugConflict() {
        Achievement achievement = Achievement.builder().id(7L).slug("old").label("L").displayOrder(1).build();
        when(achievementRepository.findById(7L)).thenReturn(Optional.of(achievement));

        assertThatThrownBy(() -> service.patchAchievement(7L, new AchievementPatchRequest("race", null, null, null)))
                .satisfies(CatalogAdminServiceSlugRaceTest::assertSlugConflictWithoutInternals);
    }
}
