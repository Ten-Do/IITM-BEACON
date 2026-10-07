package com.iitm.beacon.domain.achievement;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AchievementRepository extends JpaRepository<Achievement, Long> {

    Optional<Achievement> findBySlug(String slug);

    /** Exact, case-sensitive slug match, inactive achievements included (decision 28). */
    boolean existsBySlug(String slug);

    /** Whether an achievement other than {@code id} already holds {@code slug} — the uniqueness check on edit. */
    boolean existsBySlugAndIdNot(String slug, Long id);

    /** Every achievement, inactive ones included, in admin catalog order (display order, then id). */
    List<Achievement> findAllByOrderByDisplayOrderAscIdAsc();
}
