package com.iitm.beacon.domain.contacttype;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactTypeRepository extends JpaRepository<ContactType, Long> {

    Optional<ContactType> findBySlug(String slug);

    /** The contact types holding one of {@code slugs} — exact matches, inactive ones included — each once. */
    List<ContactType> findBySlugIn(Collection<String> slugs);
}
