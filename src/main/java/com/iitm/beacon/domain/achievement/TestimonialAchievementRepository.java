package com.iitm.beacon.domain.achievement;

import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestimonialAchievementRepository
        extends JpaRepository<TestimonialAchievement, TestimonialAchievementId> {

    /**
     * Every testimonial's tick of this achievement — what deleting it removes
     * (decision 28) — each with its testimonial, and all of that
     * testimonial's ticks, loaded in the same query.
     */
    @Query("select ta from TestimonialAchievement ta join fetch ta.testimonial t left join fetch t.achievements"
            + " where ta.achievement.id = :achievementId")
    List<TestimonialAchievement> findWithTestimonialTicksByAchievementId(@Param("achievementId") Long achievementId);

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

    /**
     * The ticks of active achievements — the JPQL form of {@code
     * Achievement.isVisible()} (decision 28) — of every testimonial in {@code
     * testimonialIds}, testimonial by testimonial in id order, each
     * testimonial's in catalog order (display order, then id).
     */
    @Query("select new com.iitm.beacon.domain.achievement.AchievementTick(ta.testimonial.id, a.slug, a.label)"
            + " from TestimonialAchievement ta join ta.achievement a"
            + " where ta.testimonial.id in :testimonialIds and a.active = true"
            + " order by ta.testimonial.id, a.displayOrder, a.id")
    List<AchievementTick> findVisibleTicksByTestimonialIdIn(@Param("testimonialIds") Collection<Long> testimonialIds);
}
