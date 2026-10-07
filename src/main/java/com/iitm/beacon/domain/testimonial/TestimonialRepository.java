package com.iitm.beacon.domain.testimonial;

import com.iitm.beacon.domain.country.Country;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestimonialRepository
        extends JpaRepository<Testimonial, Long>, JpaSpecificationExecutor<Testimonial> {

    Optional<Testimonial> findByEmailLookupHash(String emailLookupHash);

    Page<Testimonial> findByStatusOrderByCreatedAtAsc(TestimonialStatus status, Pageable pageable);

    @Query("select distinct t.country from Testimonial t where t.status = :status order by t.country.name")
    List<Country> findDistinctCountriesByStatus(@Param("status") TestimonialStatus status);

    /**
     * Every testimonial of {@code status} with at least one {@code modified}
     * section filed under one of {@code topicIds}, each listed once — the
     * approved testimonials that go back to {@code PENDING} when those topics
     * become visible again (decision 28). An empty collection yields an
     * empty list.
     */
    @Query("select distinct t from Testimonial t join t.sections s"
            + " where t.status = :status and s.modified = true and s.topic.id in :topicIds")
    List<Testimonial> findDistinctByStatusAndModifiedSectionTopicIdIn(
            @Param("status") TestimonialStatus status, @Param("topicIds") Collection<Long> topicIds);

    /**
     * Every testimonial of {@code status} in one row (decision 30): how many
     * there are, their mean score ({@code null} when there are none) and how
     * many score {@code threshold} or more (0 when there are none). Loads no
     * entity.
     */
    @Query("select new com.iitm.beacon.domain.testimonial.RecommendationScoreSummary("
            + " count(t), avg(t.recommendationScore),"
            + " coalesce(sum(case when t.recommendationScore >= :threshold then 1 else 0 end), 0))"
            + " from Testimonial t where t.status = :status")
    RecommendationScoreSummary summarizeScoresByStatus(
            @Param("status") TestimonialStatus status, @Param("threshold") int threshold);

    /**
     * How many testimonials of {@code status} come from each country, one row
     * per country that has any (decision 30); unordered.
     */
    @Query("select new com.iitm.beacon.domain.testimonial.CountryTestimonialCount(c.code, c.name, count(t))"
            + " from Testimonial t join t.country c where t.status = :status group by c.code, c.name")
    List<CountryTestimonialCount> countPerCountryByStatus(@Param("status") TestimonialStatus status);
}
