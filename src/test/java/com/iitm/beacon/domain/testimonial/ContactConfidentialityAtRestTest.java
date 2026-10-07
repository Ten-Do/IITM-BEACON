package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * NFR-CONTACT-CONFIDENTIALITY at rest: what someone reading the database
 * directly (a stolen backup, a low-privilege credential) gets for a
 * contact-method value and for the login email. Read straight from the
 * columns with native SQL, past the JPA converter: never the plaintext or a
 * recognisable part of it, but base64 of at least a 12-byte IV plus a
 * 16-byte authentication tag (AES-GCM, decision 6), and a different value
 * each time the same plaintext is stored — so equal contacts can't be
 * spotted by comparing rows either.
 */
class ContactConfidentialityAtRestTest extends AbstractRepositoryTest {

    /** 12-byte IV + 16-byte GCM tag: the least an encrypted value can decode to. */
    private static final int MIN_ENCRYPTED_BYTES = 28;

    private static final String LOGIN_LOCAL_PART = "rest-login-quokka";
    private static final String LOGIN_EMAIL = LOGIN_LOCAL_PART + "@example.com";
    private static final String CONTACT_LOCAL_PART = "rest-contact-wombat";
    private static final String CONTACT_EMAIL = CONTACT_LOCAL_PART + "@example.org";
    private static final String PHONE = "+91 98765 43210";

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Testimonial testimonial(String email) {
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private static void addContact(Testimonial t, String typeSlug, String value, boolean isPublic,
            ContactTypeRepository contactTypes) {
        t.getContactMethods().add(ContactMethod.builder()
                .testimonial(t)
                .contactType(contactTypes.findBySlug(typeSlug).orElseThrow())
                .value(value)
                .isPublic(isPublic)
                .displayOrder(t.getContactMethods().size())
                .build());
    }

    private Testimonial saved(Testimonial t) {
        Testimonial saved = testimonialRepository.saveAndFlush(t);
        entityManager.clear();
        return saved;
    }

    private String rawColumn(String sql, Long id) {
        Object raw = entityManager.getEntityManager().createNativeQuery(sql).setParameter("id", id).getSingleResult();
        assertThat(raw).isInstanceOf(String.class);
        return (String) raw;
    }

    private String rawEmail(Testimonial t) {
        return rawColumn("SELECT email FROM testimonial WHERE id = :id", t.getId());
    }

    private String rawContactValue(ContactMethod contact) {
        return rawColumn("SELECT value FROM contact_method WHERE id = :id", contact.getId());
    }

    /** Every column of one row, as text. */
    private List<String> rawRow(String table, Long id) {
        Object row = entityManager.getEntityManager()
                .createNativeQuery("SELECT * FROM " + table + " WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        Object[] columns = row instanceof Object[] array ? array : new Object[] {row};
        return Arrays.stream(columns).filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private static void assertCiphertextOf(String raw, String plaintext, String... recognisableParts) {
        assertThat(raw.toLowerCase(Locale.ROOT)).doesNotContain(plaintext.toLowerCase(Locale.ROOT));
        for (String part : recognisableParts) {
            assertThat(raw.toLowerCase(Locale.ROOT)).doesNotContain(part.toLowerCase(Locale.ROOT));
        }
        byte[] decoded = Base64.getDecoder().decode(raw);
        assertThat(decoded).hasSizeGreaterThanOrEqualTo(MIN_ENCRYPTED_BYTES);
    }

    private ContactMethod onlyContact(Testimonial t) {
        return testimonialRepository.findById(t.getId()).orElseThrow().getContactMethods().get(0);
    }

    // -- the login email --

    @Test
    void loginEmail_isStoredAsCiphertext_neverThePlaintextOrItsLocalPart() {
        Testimonial t = saved(testimonial(LOGIN_EMAIL));

        assertCiphertextOf(rawEmail(t), LOGIN_EMAIL, LOGIN_LOCAL_PART);
    }

    @Test
    void loginEmail_appearsInNoColumnOfTheTestimonialRow() {
        Testimonial t = saved(testimonial(LOGIN_EMAIL));

        assertThat(rawRow("testimonial", t.getId()))
                .isNotEmpty()
                .noneMatch(value -> value.toLowerCase(Locale.ROOT).contains(LOGIN_LOCAL_PART));
    }

    @Test
    void sameLoginEmailStoredTwice_givesTwoDifferentColumnValues() {
        Testimonial first = saved(testimonial(LOGIN_EMAIL));
        Testimonial second = saved(testimonial(LOGIN_EMAIL));

        assertThat(rawEmail(first)).isNotEqualTo(rawEmail(second));
    }

    // -- contact-method values --

    @Test
    void contactValue_isStoredAsCiphertext_neverThePlaintextOrItsLocalPart() {
        Testimonial t = testimonial("contact-owner-1@example.com");
        addContact(t, "email", CONTACT_EMAIL, true, contactTypeRepository);
        Testimonial saved = saved(t);

        assertCiphertextOf(rawContactValue(onlyContact(saved)), CONTACT_EMAIL, CONTACT_LOCAL_PART);
    }

    @Test
    void privateContactValue_isStoredAsCiphertext_neitherWithNorWithoutItsSpaces() {
        Testimonial t = testimonial("contact-owner-2@example.com");
        addContact(t, "whatsapp", PHONE, false, contactTypeRepository);
        Testimonial saved = saved(t);

        assertCiphertextOf(rawContactValue(onlyContact(saved)), PHONE, PHONE.replace(" ", ""), "98765");
    }

    @Test
    void contactValue_appearsInNoColumnOfTheContactMethodRow() {
        Testimonial t = testimonial("contact-owner-3@example.com");
        addContact(t, "email", CONTACT_EMAIL, true, contactTypeRepository);
        Testimonial saved = saved(t);

        assertThat(rawRow("contact_method", onlyContact(saved).getId()))
                .isNotEmpty()
                .noneMatch(value -> value.toLowerCase(Locale.ROOT).contains(CONTACT_LOCAL_PART));
    }

    @Test
    void sameContactValueOnTwoTestimonials_givesTwoDifferentColumnValues() {
        Testimonial first = testimonial("contact-owner-4@example.com");
        addContact(first, "email", CONTACT_EMAIL, true, contactTypeRepository);
        Testimonial second = testimonial("contact-owner-5@example.com");
        addContact(second, "email", CONTACT_EMAIL, true, contactTypeRepository);
        Testimonial savedFirst = saved(first);
        Testimonial savedSecond = saved(second);

        assertThat(rawContactValue(onlyContact(savedFirst))).isNotEqualTo(rawContactValue(onlyContact(savedSecond)));
    }

    @Test
    void loginEmailAlsoGivenAsAContact_isStoredDifferentlyInTheTwoColumns() {
        Testimonial t = testimonial(LOGIN_EMAIL);
        addContact(t, "email", LOGIN_EMAIL, true, contactTypeRepository);
        Testimonial saved = saved(t);

        String rawContact = rawContactValue(onlyContact(saved));
        assertCiphertextOf(rawContact, LOGIN_EMAIL, LOGIN_LOCAL_PART);
        assertThat(rawContact).isNotEqualTo(rawEmail(saved));
    }
}
