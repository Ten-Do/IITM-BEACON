package com.iitm.beacon.domain.achievement;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Composite PK for {@link TestimonialAchievement} — has no relationship
 * fields of its own, so it's safe to derive equals/hashCode over both parts.
 */
@Embeddable
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestimonialAchievementId implements Serializable {

    @Column(name = "testimonial_id")
    private Long testimonialId;

    @Column(name = "achievement_id")
    private Long achievementId;
}
