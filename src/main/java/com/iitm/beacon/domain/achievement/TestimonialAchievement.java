package com.iitm.beacon.domain.achievement;

import com.iitm.beacon.domain.testimonial.Testimonial;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Pure many-to-many join row between {@link Testimonial} and {@link
 * Achievement} (decision 13) — composite PK, no extra columns. Deleting a
 * {@code Testimonial} cascades to its join rows, but never to the
 * referenced {@code Achievement} itself (reference data, never cascaded
 * away).
 */
@Entity
@Table(name = "testimonial_achievement")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class TestimonialAchievement {

    // Hibernate's @MapsId generator populates this object's sub-fields via
    // reflection at persist time — it does not instantiate it itself, so it
    // must never be left null (unlike a @GeneratedValue surrogate id).
    @Builder.Default
    @EmbeddedId
    private TestimonialAchievementId id = new TestimonialAchievementId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("testimonialId")
    @JoinColumn(name = "testimonial_id")
    private Testimonial testimonial;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("achievementId")
    @JoinColumn(name = "achievement_id")
    private Achievement achievement;
}
