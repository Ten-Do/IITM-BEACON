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
     * (decision 28) — each with its testimonial and its photos loaded in the
     * same query. An empty collection yields an empty list.
     */
    @Query("select s from TestimonialSection s join fetch s.testimonial left join fetch s.photos"
            + " where s.topic.id in :topicIds")
    List<TestimonialSection> findWithTestimonialAndPhotosByTopicIdIn(@Param("topicIds") Collection<Long> topicIds);

    /** The sections of {@code ids}, each with its photos loaded in the same query. */
    @Query("select s from TestimonialSection s left join fetch s.photos where s.id in :ids")
    List<TestimonialSection> findWithPhotosByIdIn(@Param("ids") Collection<Long> ids);

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

    /**
     * The sections of visible topics (decision 28) of every testimonial in
     * {@code testimonialIds}, testimonial by testimonial in id order, and
     * each testimonial's in display order: by its topic's group's display
     * order — a standalone topic's own, on the top-level range it shares
     * with groups — then by the topic's display order within its group, then
     * by id. A testimonial without such a section has no row; an unknown id
     * is ignored.
     */
    @Query("select new com.iitm.beacon.domain.testimonial.VisibleSection("
            + " s.id, s.testimonial.id, tp.slug, tp.label, s.answerText, s.modified)"
            + " from TestimonialSection s join s.topic tp left join tp.topicGroup g"
            + " where s.testimonial.id in :testimonialIds and tp.active = true and (g is null or g.active = true)"
            + " order by s.testimonial.id, coalesce(g.displayOrder, tp.displayOrder),"
            + " case when g is null then 0 else tp.displayOrder end, s.id")
    List<VisibleSection> findVisibleByTestimonialIdIn(@Param("testimonialIds") Collection<Long> testimonialIds);

    /**
     * Each testimonial's card cover, in one query for all of {@code
     * testimonialIds}: its first section in the display order of {@link
     * #findVisibleByTestimonialIdIn} — the first of a visible topic — with
     * the first {@code answerLength} characters of its answer and its own
     * first photo by display order (then id), if it has any. A testimonial
     * without a visible section has no row; unordered. Native for {@code
     * DISTINCT ON}; it selects no encrypted column.
     */
    @Query(nativeQuery = true, value = "SELECT DISTINCT ON (s.testimonial_id)"
            + " s.testimonial_id AS testimonialId, LEFT(s.answer_text, :answerLength) AS answerStart,"
            + " p.file_path AS photoFilePath, p.thumbnail_path AS photoThumbnailPath"
            + " FROM testimonial_section s"
            + " JOIN topic tp ON tp.id = s.topic_id"
            + " LEFT JOIN topic_group g ON g.id = tp.topic_group_id"
            + " LEFT JOIN photo p ON p.testimonial_section_id = s.id"
            + " WHERE s.testimonial_id IN (:testimonialIds) AND tp.active AND (g.id IS NULL OR g.active)"
            + " ORDER BY s.testimonial_id, COALESCE(g.display_order, tp.display_order),"
            + " CASE WHEN g.id IS NULL THEN 0 ELSE tp.display_order END, s.id, p.display_order, p.id")
    List<TestimonialCover> findCoversByTestimonialIdIn(
            @Param("testimonialIds") Collection<Long> testimonialIds, @Param("answerLength") int answerLength);
}
