package com.iitm.beacon.domain.contacttype;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the V14 Flyway seed migration (decision 5) — the 5 contact-type
 * rows from docs/use-cases.md's "Contact method catalog" table — the
 * display names V16 adds to them, and the validation patterns V17 adds.
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

    @Test
    void everySeededContactType_hasItsDisplayName() {
        assertThat(contactTypeRepository.findBySlug("email").orElseThrow().getName()).isEqualTo("Email");
        assertThat(contactTypeRepository.findBySlug("whatsapp").orElseThrow().getName()).isEqualTo("WhatsApp");
        assertThat(contactTypeRepository.findBySlug("telegram").orElseThrow().getName()).isEqualTo("Telegram");
        assertThat(contactTypeRepository.findBySlug("instagram").orElseThrow().getName()).isEqualTo("Instagram");
        assertThat(contactTypeRepository.findBySlug("twitter").orElseThrow().getName()).isEqualTo("X (Twitter)");
    }

    @Test
    void everySeededContactType_hasAValuePatternMappedOntoTheEntity() {
        // The exact patterns and their accepted/rejected samples are pinned
        // down in ContactTypeValuePatternMigrationTest.
        assertThat(contactTypeRepository.findAll())
                .allSatisfy(type -> assertThat(type.getValuePattern()).as(type.getSlug()).isNotBlank());
        assertThat(contactTypeRepository.findBySlug("email").orElseThrow().getValuePattern())
                .isEqualTo("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    }

    @Test
    void seededLabels_stayThePlaceholderTextNotTheName() {
        assertThat(contactTypeRepository.findBySlug("whatsapp").orElseThrow().getLabel())
                .isEqualTo("phone number, or a wa.me link");
    }
}
