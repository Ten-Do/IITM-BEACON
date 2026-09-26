package com.iitm.beacon.domain.testimonial;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestimonialSectionRepository extends JpaRepository<TestimonialSection, Long> {

    @Query("select distinct s.topic.id from TestimonialSection s where s.testimonial.status = :status")
    List<Long> findDistinctTopicIdsByTestimonialStatus(@Param("status") TestimonialStatus status);
}
