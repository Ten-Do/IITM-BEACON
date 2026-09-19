package com.iitm.beacon.domain.contacttype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Generic repository CRUD/constraint behavior, exercised with
 * {@code fixture_}-prefixed slugs so these tests are independent of the
 * seeded contact-type catalog verified separately in {@link
 * ContactTypeSeedDataTest}.
 */
class ContactTypeRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Test
    void savedContactType_roundTrips() {
        ContactType saved = contactTypeRepository.saveAndFlush(
                ContactType.builder().slug("fixture_email").label("email address").displayOrder(1).build());

        var found = contactTypeRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getSlug()).isEqualTo("fixture_email");
    }

    @Test
    void findBySlug_existingSlug_returnsContactType() {
        contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug("fixture_whatsapp")
                .label("phone number, or a wa.me link")
                .displayOrder(2)
                .build());

        assertThat(contactTypeRepository.findBySlug("fixture_whatsapp")).isPresent();
    }

    @Test
    void findBySlug_nonExistentSlug_returnsEmpty() {
        assertThat(contactTypeRepository.findBySlug("does-not-exist")).isEmpty();
    }

    @Test
    void duplicateSlug_violatesUniqueConstraint() {
        contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug("fixture_telegram")
                .label("phone number, @username, or a t.me link")
                .displayOrder(3)
                .build());

        assertThatThrownBy(() -> contactTypeRepository.saveAndFlush(
                        ContactType.builder().slug("fixture_telegram").label("duplicate").displayOrder(4).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void active_defaultsToTrue_whenNotExplicitlySet() {
        ContactType saved = contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug("fixture_instagram")
                .label("@username or a profile link")
                .displayOrder(4)
                .build());

        assertThat(saved.isActive()).isTrue();
    }
}
