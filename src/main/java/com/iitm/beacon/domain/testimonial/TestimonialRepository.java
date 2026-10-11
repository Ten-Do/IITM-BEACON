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
        extends JpaRepository<Testimonial, Long>, JpaSpecificationExecutor<Testimonial>, TestimonialSummaryQueries {

    /** The select and from of every {@link TestimonialHeader} query, for its where to follow. */
    String HEADER = "select new com.iitm.beacon.domain.testimonial.TestimonialHeader("
            + " t.id, t.firstName, t.lastName, t.rollNumber, t.admissionYear, t.email, c.code, c.name,"
            + " t.recommendationScore, t.status, t.dataProcessingConsent, t.identityModified, t.scoreModified,"
            + " t.createdAt, t.reviewedAt)"
            + " from Testimonial t join t.country c";

    Optional<Testimonial> findByEmailLookupHash(String emailLookupHash);

    /** The header of testimonial {@code id} — only if it has {@code status}. */
    @Query(HEADER + " where t.id = :id and t.status = :status")
    Optional<TestimonialHeader> findHeaderByIdAndStatus(
            @Param("id") Long id, @Param("status") TestimonialStatus status);

    /** The header of the testimonial with this {@code email_lookup_hash}, whatever its status. */
    @Query(HEADER + " where t.emailLookupHash = :emailLookupHash")
    Optional<TestimonialHeader> findHeaderByEmailLookupHash(@Param("emailLookupHash") String emailLookupHash);

    /**
     * A page of the headers of every testimonial of {@code status}, oldest
     * first ({@code createdAt}, then id for equal times, so a page boundary
     * never repeats or skips one).
     */
    @Query(value = HEADER + " where t.status = :status order by t.createdAt, t.id",
            countQuery = "select count(t) from Testimonial t where t.status = :status")
    Page<TestimonialHeader> findHeadersByStatusOldestFirst(
            @Param("status") TestimonialStatus status, Pageable pageable);

    /**
     * The ids of every testimonial of {@code status} whose {@code rejectedAt}
     * is strictly before {@code cutoff}, in id order — with {@code REJECTED},
     * the ones due for the purge (UC-PURGE-REJECTED, decision 3). A {@code
     * null} {@code rejectedAt} is never before anything.
     */
    @Query("select t.id from Testimonial t where t.status = :status and t.rejectedAt < :cutoff order by t.id")
    List<Long> findIdsByStatusAndRejectedAtBefore(
            @Param("status") TestimonialStatus status, @Param("cutoff") Instant cutoff);

    /**
     * Testimonial {@code id} with its sections, their topics and the topics'
     * groups loaded in the same query — what deciding each section's
     * visibility (decision 28) needs.
     */
    @Query("select t from Testimonial t left join fetch t.sections s left join fetch s.topic tp"
            + " left join fetch tp.topicGroup where t.id = :id")
    Optional<Testimonial> findWithSectionTopicsById(@Param("id") Long id);

    /** {@link #findWithSectionTopicsById}, for the testimonial with this {@code email_lookup_hash}. */
    @Query("select t from Testimonial t left join fetch t.sections s left join fetch s.topic tp"
            + " left join fetch tp.topicGroup where t.emailLookupHash = :emailLookupHash")
    Optional<Testimonial> findWithSectionTopicsByEmailLookupHash(@Param("emailLookupHash") String emailLookupHash);

    /** Testimonial {@code id} with its contact methods loaded in the same query. */
    @Query("select t from Testimonial t left join fetch t.contactMethods where t.id = :id")
    Optional<Testimonial> findWithContactMethodsById(@Param("id") Long id);

    /** Testimonial {@code id} with its achievement ticks, and their achievements, loaded in the same query. */
    @Query("select t from Testimonial t left join fetch t.achievements ta left join fetch ta.achievement"
            + " where t.id = :id")
    Optional<Testimonial> findWithAchievementsById(@Param("id") Long id);

    /** The testimonials of {@code ids}, each with its sections loaded in the same query; unknown ids are ignored. */
    @Query("select t from Testimonial t left join fetch t.sections where t.id in :ids")
    List<Testimonial> findWithSectionsByIdIn(@Param("ids") Collection<Long> ids);

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
