package com.iitm.beacon.domain.contacttype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import java.util.List;
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
                ContactType.builder()
                        .slug("fixture_email")
                        .name("Email")
                        .label("email address")
                        .displayOrder(1)
                        .build());

        var found = contactTypeRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getSlug()).isEqualTo("fixture_email");
        assertThat(found.get().getName()).isEqualTo("Email");
    }

    @Test
    void findBySlug_existingSlug_returnsContactType() {
        contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug("fixture_whatsapp")
                .name("WhatsApp")
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
                .name("Telegram")
                .label("phone number, @username, or a t.me link")
                .displayOrder(3)
                .build());

        assertThatThrownBy(() -> contactTypeRepository.saveAndFlush(
                        ContactType.builder()
                                .slug("fixture_telegram")
                                .name("Duplicate")
                                .label("duplicate")
                                .displayOrder(4)
                                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void active_defaultsToTrue_whenNotExplicitlySet() {
        ContactType saved = contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug("fixture_instagram")
                .name("Instagram")
                .label("@username or a profile link")
                .displayOrder(4)
                .build());

        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void valuePattern_roundTrips() {
        ContactType saved = contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug("fixture_signal")
                .name("Signal")
                .label("phone number")
                .valuePattern("\\+?[0-9]{7,15}")
                .displayOrder(7)
                .build());

        assertThat(contactTypeRepository.findById(saved.getId()).orElseThrow().getValuePattern())
                .isEqualTo("\\+?[0-9]{7,15}");
    }

    @Test
    void valuePattern_isOptional() {
        ContactType saved = contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug("fixture_matrix")
                .name("Matrix")
                .label("@user:server")
                .displayOrder(8)
                .build());

        assertThat(contactTypeRepository.findById(saved.getId()).orElseThrow().getValuePattern()).isNull();
    }

    @Test
    void missingName_violatesNotNullConstraint() {
        assertThatThrownBy(() -> contactTypeRepository.saveAndFlush(ContactType.builder()
                        .slug("fixture_nameless")
                        .label("@username")
                        .displayOrder(6)
                        .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    // -- findBySlugIn: every contact type a submission names, in one query --

    private ContactType contactType(String slug, boolean active) {
        return contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug(slug)
                .name("Fixture " + slug)
                .label("fixture")
                .displayOrder(9)
                .active(active)
                .build());
    }

    @Test
    void findBySlugIn_findsExactSlugsOnly_inactiveOnesIncluded() {
        ContactType active = contactType("fixture_in_active", true);
        ContactType inactive = contactType("fixture_in_inactive", false);

        List<ContactType> found = contactTypeRepository.findBySlugIn(
                List.of(active.getSlug(), inactive.getSlug(), "FIXTURE_IN_ACTIVE", "fixture_in_unknown"));

        assertThat(found).extracting(ContactType::getSlug)
                .containsExactlyInAnyOrder(active.getSlug(), inactive.getSlug());
    }

    @Test
    void findBySlugIn_sameSlugTwice_findsItOnce() {
        ContactType active = contactType("fixture_in_twice", true);

        assertThat(contactTypeRepository.findBySlugIn(List.of(active.getSlug(), active.getSlug())))
                .extracting(ContactType::getId).containsExactly(active.getId());
    }

    @Test
    void findBySlugIn_noSlugs_findsNothing() {
        assertThat(contactTypeRepository.findBySlugIn(List.of())).isEmpty();
    }
}
