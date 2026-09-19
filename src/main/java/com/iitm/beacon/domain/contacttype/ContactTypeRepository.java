package com.iitm.beacon.domain.contacttype;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactTypeRepository extends JpaRepository<ContactType, Long> {

    Optional<ContactType> findBySlug(String slug);
}
