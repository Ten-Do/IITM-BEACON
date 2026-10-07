package com.iitm.beacon.domain.topic;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicGroupRepository extends JpaRepository<TopicGroup, Long> {

    /** Every group, inactive ones included, in admin catalog order (display order, then id). */
    List<TopicGroup> findAllByOrderByDisplayOrderAscIdAsc();
}
