package com.iitm.beacon.domain.achievement;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AchievementRepository extends JpaRepository<Achievement, Long> {

    Optional<Achievement> findBySlug(String slug);
}
