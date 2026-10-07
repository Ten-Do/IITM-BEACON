package com.iitm.beacon.domain.testimonial;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestimonialSectionRepository extends JpaRepository<TestimonialSection, Long> {

    @Query("select distinct s.topic.id from TestimonialSection s where s.testimonial.status = :status")
    List<Long> findDistinctTopicIdsByTestimonialStatus(@Param("status") TestimonialStatus status);

    /**
     * Every section filed under one of {@code topicIds}, across all
     * testimonials and statuses — what deleting those topics removes
     * (decision 28). An empty collection yields an empty list.
     */
    List<TestimonialSection> findByTopicIdIn(Collection<Long> topicIds);

    /**
     * How many distinct testimonials, of any status, have at least one
     * section under one of {@code topicIds} — the count shown on the catalog
     * delete confirmation page (decision 28). An empty collection yields 0.
     */
    @Query("select count(distinct s.testimonial.id) from TestimonialSection s where s.topic.id in :topicIds")
    long countDistinctTestimonialsByTopicIdIn(@Param("topicIds") Collection<Long> topicIds);

    /**
     * For each active topic group, how many testimonials of {@code status}
     * have at least one section in one of its active topics — each
     * testimonial counted once per group however many subtopics it fills
     * (decision 30). The topic and group conditions are the JPQL form of
     * {@code Topic.isVisible()} for a grouped topic (decision 28). Groups
     * with no such testimonial are absent; unordered.
     */
    @Query("select new com.iitm.beacon.domain.testimonial.TopicGroupTestimonialCount("
            + " g.id, g.label, g.displayOrder, count(distinct t.id))"
            + " from TestimonialSection s join s.testimonial t join s.topic tp join tp.topicGroup g"
            + " where t.status = :status and tp.active = true and g.active = true"
            + " group by g.id, g.label, g.displayOrder")
    List<TopicGroupTestimonialCount> countTestimonialsPerActiveTopicGroup(@Param("status") TestimonialStatus status);

    /**
     * For each visible standalone topic — active, in no group, the JPQL form
     * of {@code Topic.isVisible()} for a standalone topic (decision 28) — how
     * many testimonials of {@code status} have at least one section in it,
     * each counted once (decision 30). Topics with no such testimonial are
     * absent; unordered.
     */
    @Query("select new com.iitm.beacon.domain.testimonial.StandaloneTopicTestimonialCount("
            + " tp.id, tp.label, tp.displayOrder, count(distinct t.id))"
            + " from TestimonialSection s join s.testimonial t join s.topic tp"
            + " where t.status = :status and tp.active = true and tp.topicGroup is null"
            + " group by tp.id, tp.label, tp.displayOrder")
    List<StandaloneTopicTestimonialCount> countTestimonialsPerVisibleStandaloneTopic(
            @Param("status") TestimonialStatus status);
}
