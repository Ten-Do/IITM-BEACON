package com.iitm.beacon.domain.topic;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicRepository extends JpaRepository<Topic, Long> {

    Optional<Topic> findBySlug(String slug);

    List<Topic> findByTopicGroupId(Long topicGroupId);

    /** Exact, case-sensitive slug match, inactive topics included (decision 28). */
    boolean existsBySlug(String slug);

    /** Whether a topic other than {@code id} already holds {@code slug} — the uniqueness check on edit. */
    boolean existsBySlugAndIdNot(String slug, Long id);

    /** Every topic, inactive ones included, in admin catalog order (display order, then id). */
    List<Topic> findAllByOrderByDisplayOrderAscIdAsc();
}
