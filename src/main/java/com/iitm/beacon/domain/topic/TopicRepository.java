package com.iitm.beacon.domain.topic;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopicRepository extends JpaRepository<Topic, Long> {

    Optional<Topic> findBySlug(String slug);

    List<Topic> findByTopicGroupId(Long topicGroupId);

    /** Exact, case-sensitive slug match, inactive topics included (decision 28). */
    boolean existsBySlug(String slug);

    /** Whether a topic other than {@code id} already holds {@code slug} — the uniqueness check on edit. */
    boolean existsBySlugAndIdNot(String slug, Long id);

    /** Every topic, inactive ones included, in admin catalog order (display order, then id). */
    List<Topic> findAllByOrderByDisplayOrderAscIdAsc();

    /**
     * Every topic, inactive ones included, in id order, each with its group
     * (if any) loaded in the same query — so the whole catalog, visibility
     * included, costs one statement.
     */
    @Query("select t from Topic t left join fetch t.topicGroup order by t.id")
    List<Topic> findAllWithTopicGroupOrderById();

    /**
     * The topics holding one of {@code slugs} — exact, case-sensitive
     * matches, inactive topics included — each with its group (if any)
     * loaded in the same query; a slug no topic holds is left out.
     */
    @Query("select t from Topic t left join fetch t.topicGroup where t.slug in :slugs")
    List<Topic> findWithTopicGroupBySlugIn(@Param("slugs") Collection<String> slugs);

    /**
     * The ids of the visible topics (decision 28) that {@code groupIds} and
     * {@code topicIds} name together, each once, unordered: every active
     * topic of an active group in {@code groupIds}, and every topic in
     * {@code topicIds} that is active and standalone or in an active group.
     * Group and topic ids are separate (decision 29): a group id never names
     * a topic. An unknown id names nothing; either list may be empty.
     */
    @Query("select t.id from Topic t left join t.topicGroup g"
            + " where t.active = true and (g is null or g.active = true)"
            + " and (g.id in :groupIds or t.id in :topicIds)")
    List<Long> findVisibleIdsByTopicGroupIdInOrIdIn(
            @Param("groupIds") Collection<Long> groupIds, @Param("topicIds") Collection<Long> topicIds);
}
