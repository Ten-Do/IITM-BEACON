package com.iitm.beacon.domain.achievement;

import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestimonialAchievementRepository
        extends JpaRepository<TestimonialAchievement, TestimonialAchievementId> {

    /** Every testimonial's tick of this achievement — what deleting it removes (decision 28). */
    List<TestimonialAchievement> findByAchievementId(Long achievementId);

    /** How many testimonials ticked this achievement (one row per testimonial, by the composite key). */
    long countByAchievementId(Long achievementId);

    /**
     * For each active achievement — the JPQL form of {@code
     * Achievement.isVisible()} (decision 28) — how many testimonials of
     * {@code status} ticked it (decision 30). Achievements with no such tick
     * are absent; unordered.
     */
    @Query("select new com.iitm.beacon.domain.achievement.AchievementTickCount("
            + " a.id, a.slug, a.label, a.displayOrder, a.active, count(t.id))"
            + " from TestimonialAchievement ta join ta.testimonial t join ta.achievement a"
            + " where t.status = :status and a.active = true"
            + " group by a.id, a.slug, a.label, a.displayOrder, a.active")
    List<AchievementTickCount> countTicksPerActiveAchievementByStatus(@Param("status") TestimonialStatus status);
}
