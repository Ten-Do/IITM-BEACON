package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.iitm.beacon.common.crypto.CryptoProperties;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.country.CountryRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * {@link TestimonialRepository}'s projections (BL-049): the page of {@link
 * TestimonialSummary} rows any {@link Specification} selects and orders,
 * and the {@link TestimonialHeader} of one testimonial or of a page of
 * them — never the entity, the email decrypted where it is selected.
 */
class TestimonialRepositoryProjectionTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EntityManager entityManager;

    private final EmailLookupHashService hashService =
            new EmailLookupHashService(new CryptoProperties(TEST_KEY, "dev-only-insecure-pepper-do-not-use-in-prod"));

    /** Every testimonial of one test carries this last name, so a specification can pick only those. */
    private final String marker = "Marker" + UUID.randomUUID().toString().replace("-", "");

    private Testimonial save(String countryCode, int score, TestimonialStatus status, Instant createdAt) {
        String email = "projection-" + UUID.randomUUID() + "@example.com";
        Testimonial saved = testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("Projected")
                .lastName(marker)
                .rollNumber("GE26Z123")
                .admissionYear(2023)
                .email(email)
                .emailLookupHash(hashService.hash(email))
                .country(countryRepository.findById(countryCode).orElseThrow())
                .recommendationScore(score)
                .dataProcessingConsent(true)
                .status(status)
                .createdAt(createdAt)
                .build());
        entityManager.clear();
        return saved;
    }

    private Testimonial save(String countryCode, int score) {
        return save(countryCode, score, TestimonialStatus.APPROVED, CREATED);
    }

    private Specification<Testimonial> marked() {
        return (root, query, cb) -> cb.equal(root.get("lastName"), marker);
    }

    /** Highest score first, then highest id: an order no index or insertion order would give by chance. */
    private static Specification<Testimonial> highestScoreFirst() {
        return (root, query, cb) -> {
            query.orderBy(cb.desc(root.get("recommendationScore")), cb.desc(root.get("id")));
            return null;
        };
    }

    // -- findSummaries --

    @Test
    void summaries_areTheIdCountryAndScoreOfEveryTestimonialTheSpecificationMatches() {
        Testimonial india = save("IN", 3);
        Testimonial germany = save("DE", 9);

        Page<TestimonialSummary> page =
                testimonialRepository.findSummaries(Specification.allOf(marked(), highestScoreFirst()),
                        PageRequest.of(0, 10));

        assertThat(page.getContent()).containsExactly(
                new TestimonialSummary(germany.getId(), "DE", "Germany", 9),
                new TestimonialSummary(india.getId(), "IN", "India", 3));
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void summaries_comeInTheSpecificationsOrder() {
        Testimonial low = save("IN", 1);
        Testimonial high = save("IN", 10);
        Testimonial tieOlder = save("IN", 5);
        Testimonial tieNewer = save("IN", 5);

        Page<TestimonialSummary> page = testimonialRepository.findSummaries(
                Specification.allOf(marked(), highestScoreFirst()), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TestimonialSummary::id)
                .containsExactly(high.getId(), tieNewer.getId(), tieOlder.getId(), low.getId());
    }

    /** The total comes from a count query the specification's order is dropped from — PostgreSQL refuses one. */
    @Test
    void summaries_aFullPage_hasTheTotalOfEveryMatch_thoughTheSpecificationOrders() {
        save("IN", 1);
        Testimonial second = save("IN", 2);
        Testimonial third = save("IN", 3);

        Page<TestimonialSummary> page = testimonialRepository.findSummaries(
                Specification.allOf(marked(), highestScoreFirst()), PageRequest.of(0, 2));

        assertThat(page.getContent()).extracting(TestimonialSummary::id).containsExactly(third.getId(), second.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void summaries_theLastPage_isTheRest() {
        Testimonial first = save("IN", 1);
        save("IN", 2);
        save("IN", 3);

        Page<TestimonialSummary> page = testimonialRepository.findSummaries(
                Specification.allOf(marked(), highestScoreFirst()), PageRequest.of(1, 2));

        assertThat(page.getContent()).extracting(TestimonialSummary::id).containsExactly(first.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void summaries_aPageBeyondTheLast_isEmpty_withTheTotal() {
        save("IN", 1);
        save("IN", 2);

        Page<TestimonialSummary> page = testimonialRepository.findSummaries(
                Specification.allOf(marked(), highestScoreFirst()), PageRequest.of(5, 2));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void summaries_noMatch_isAnEmptyPage_withTotalZero() {
        Page<TestimonialSummary> page = testimonialRepository.findSummaries(marked(), PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void summaries_unpaged_isEveryMatch() {
        save("IN", 1);
        save("IN", 2);
        save("IN", 3);

        Page<TestimonialSummary> page = testimonialRepository.findSummaries(marked(), Pageable.unpaged());

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void summaries_specificationRestrictingNothing_matchesEveryTestimonial() {
        Testimonial mine = save("IN", 4);

        Page<TestimonialSummary> page =
                testimonialRepository.findSummaries((root, query, cb) -> null, Pageable.unpaged());

        assertThat(page.getContent()).extracting(TestimonialSummary::id).contains(mine.getId());
        assertThat(page.getTotalElements()).isEqualTo(testimonialRepository.count());
    }

    // -- headers --

    @Test
    void headerByIdAndStatus_isEveryShownColumn_withTheCountry_andTheEmailDecrypted() {
        String email = "header-" + UUID.randomUUID() + "@example.com";
        Testimonial saved = testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("Ana")
                .lastName("Pereira")
                .rollNumber("CS21B001")
                .admissionYear(2021)
                .email(email)
                .emailLookupHash(hashService.hash(email))
                .country(countryRepository.findById("PT").orElseThrow())
                .recommendationScore(7)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(CREATED)
                .reviewedAt(CREATED.plusSeconds(3600))
                .identityModified(true)
                .scoreModified(false)
                .build());
        entityManager.clear();

        assertThat(testimonialRepository.findHeaderByIdAndStatus(saved.getId(), TestimonialStatus.APPROVED))
                .contains(new TestimonialHeader(saved.getId(), "Ana", "Pereira", "CS21B001", 2021, email, "PT",
                        "Portugal", 7, TestimonialStatus.APPROVED, true, true, false, CREATED,
                        CREATED.plusSeconds(3600)));
    }

    @Test
    void headerByIdAndStatus_ofAnotherStatus_orAnUnknownId_isEmpty() {
        Testimonial pending = save("IN", 5, TestimonialStatus.PENDING, CREATED);

        assertThat(testimonialRepository.findHeaderByIdAndStatus(pending.getId(), TestimonialStatus.APPROVED))
                .isEmpty();
        assertThat(testimonialRepository.findHeaderByIdAndStatus(Long.MAX_VALUE, TestimonialStatus.APPROVED))
                .isEmpty();
    }

    @Test
    void headerByEmailLookupHash_isThatTestimonialsHeader_whateverItsStatus_orEmptyForAnUnknownHash() {
        Testimonial rejected = save("IN", 5, TestimonialStatus.REJECTED, CREATED);

        assertThat(testimonialRepository.findHeaderByEmailLookupHash(rejected.getEmailLookupHash()))
                .get().satisfies(header -> {
                    assertThat(header.id()).isEqualTo(rejected.getId());
                    assertThat(header.email()).isEqualTo(rejected.getEmail());
                    assertThat(header.status()).isEqualTo(TestimonialStatus.REJECTED);
                    assertThat(header.reviewedAt()).isNull();
                });
        assertThat(testimonialRepository.findHeaderByEmailLookupHash("0".repeat(64))).isEmpty();
    }

    // -- findHeadersByStatusOldestFirst --

    @Test
    void headersOldestFirst_areThatStatusOnly_oldestFirst_equalTimesInIdOrder() {
        Testimonial newest = save("IN", 1, TestimonialStatus.PENDING, CREATED.plusSeconds(20));
        Testimonial tieFirst = save("IN", 1, TestimonialStatus.PENDING, CREATED.plusSeconds(10));
        Testimonial tieSecond = save("IN", 1, TestimonialStatus.PENDING, CREATED.plusSeconds(10));
        Testimonial oldest = save("IN", 1, TestimonialStatus.PENDING, CREATED);
        save("IN", 1, TestimonialStatus.APPROVED, CREATED.minusSeconds(10));

        Page<TestimonialHeader> page =
                testimonialRepository.findHeadersByStatusOldestFirst(TestimonialStatus.PENDING, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TestimonialHeader::id, TestimonialHeader::status)
                .containsExactly(
                        tuple(oldest.getId(), TestimonialStatus.PENDING),
                        tuple(tieFirst.getId(), TestimonialStatus.PENDING),
                        tuple(tieSecond.getId(), TestimonialStatus.PENDING),
                        tuple(newest.getId(), TestimonialStatus.PENDING));
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    void headersOldestFirst_aFullPage_hasTheTotalOfAll() {
        Testimonial oldest = save("IN", 1, TestimonialStatus.PENDING, CREATED);
        save("IN", 1, TestimonialStatus.PENDING, CREATED.plusSeconds(1));

        Page<TestimonialHeader> page =
                testimonialRepository.findHeadersByStatusOldestFirst(TestimonialStatus.PENDING, PageRequest.of(0, 1));

        assertThat(page.getContent()).extracting(TestimonialHeader::id).containsExactly(oldest.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void headersOldestFirst_noneOfThatStatus_isAnEmptyPage_withTotalZero() {
        save("IN", 1, TestimonialStatus.PENDING, CREATED);

        Page<TestimonialHeader> page = testimonialRepository.findHeadersByStatusOldestFirst(
                TestimonialStatus.REJECTED, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }
}
