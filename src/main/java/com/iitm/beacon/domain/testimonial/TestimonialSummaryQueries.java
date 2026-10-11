package com.iitm.beacon.domain.testimonial;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The page of {@link TestimonialSummary} rows a {@link Specification}
 * selects — a {@link TestimonialRepository} fragment, since Spring Data's own
 * specification queries return entities.
 */
public interface TestimonialSummaryQueries {

    /**
     * The summaries of the testimonials {@code spec} matches, in the order it
     * sets, one {@code pageable} page of them; the entity is never loaded.
     * The total comes from a count query without that order, run only when
     * the page alone doesn't tell it (as Spring Data's own pages do). A
     * {@code spec} predicate of {@code null} matches every testimonial.
     */
    Page<TestimonialSummary> findSummaries(Specification<Testimonial> spec, Pageable pageable);
}
