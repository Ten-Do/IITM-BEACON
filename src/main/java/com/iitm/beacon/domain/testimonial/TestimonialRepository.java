package com.iitm.beacon.domain.testimonial;

import com.iitm.beacon.domain.country.Country;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestimonialRepository
        extends JpaRepository<Testimonial, Long>, JpaSpecificationExecutor<Testimonial> {

    Optional<Testimonial> findByEmailLookupHash(String emailLookupHash);

    Page<Testimonial> findByStatusOrderByCreatedAtAsc(TestimonialStatus status, Pageable pageable);

    /**
     * Every testimonial of {@code status} whose {@code rejectedAt} is strictly
     * before {@code cutoff}, in id order — with {@code REJECTED}, the ones due
     * for the purge (UC-PURGE-REJECTED, decision 3). A {@code null}
     * {@code rejectedAt} is never before anything.
     */
    List<Testimonial> findByStatusAndRejectedAtBeforeOrderByIdAsc(TestimonialStatus status, Instant cutoff);

    /**
     * Deletes testimonial {@code id} only if it still has {@code status} and a
     * {@code rejectedAt} strictly before {@code cutoff} — so a testimonial
     * resubmitted (or rejected again) after it was found survives the purge
     * (UC-PURGE-REJECTED, decision 3). A bulk delete: its sections, photos,
     * photo tags, contact methods and achievement ticks go through the
     * database's {@code ON DELETE CASCADE} foreign keys, not JPA cascades.
     * Flushes pending changes first and clears the persistence context
     * afterwards, so no stale entity of a deleted row is left behind.
     *
     * @return 1 if the testimonial was deleted, 0 if it was gone or no longer due
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Testimonial t"
            + " where t.id = :id and t.status = :status and t.rejectedAt < :cutoff")
    int deleteByIdAndStatusAndRejectedAtBefore(
            @Param("id") Long id, @Param("status") TestimonialStatus status, @Param("cutoff") Instant cutoff);

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
