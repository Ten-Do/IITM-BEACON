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
}
