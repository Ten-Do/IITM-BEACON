package com.iitm.beacon.domain.contacttype;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the V14 Flyway seed migration (decision 5) — the 5 contact-type
 * rows from docs/use-cases.md's "Contact method catalog" table.
 */
class ContactTypeSeedDataTest extends AbstractRepositoryTest {

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Test
    void allFiveContactTypes_areSeeded() {
        assertThat(contactTypeRepository.count()).isEqualTo(5);
    }

    @Test
    void findBySlug_worksForEachSeededSlug() {
        assertThat(contactTypeRepository.findBySlug("email")).isPresent();
        assertThat(contactTypeRepository.findBySlug("whatsapp")).isPresent();
        assertThat(contactTypeRepository.findBySlug("telegram")).isPresent();
        assertThat(contactTypeRepository.findBySlug("instagram")).isPresent();
        assertThat(contactTypeRepository.findBySlug("twitter")).isPresent();
    }
}
