package com.iitm.beacon.domain.achievement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestimonialAchievementRepository
        extends JpaRepository<TestimonialAchievement, TestimonialAchievementId> {

    /** Every testimonial's tick of this achievement — what deleting it removes (decision 28). */
    List<TestimonialAchievement> findByAchievementId(Long achievementId);

    /** How many testimonials ticked this achievement (one row per testimonial, by the composite key). */
    long countByAchievementId(Long achievementId);
}
