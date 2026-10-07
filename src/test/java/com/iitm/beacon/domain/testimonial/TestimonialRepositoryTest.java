package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.crypto.CryptoProperties;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

class TestimonialRepositoryTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TestEntityManager entityManager;

    private final EmailLookupHashService hashService =
            new EmailLookupHashService(new CryptoProperties(TEST_KEY, "dev-only-insecure-pepper-do-not-use-in-prod"));

    private Country india;

    @BeforeEach
    void setUp() {
        // "IN" is already seeded by V12 — look it up rather than inserting a
        // fresh row, which would now violate the PK.
        india = countryRepository.findById("IN").orElseThrow();
    }

    private Testimonial.TestimonialBuilder validTestimonialBuilder(String email) {
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(hashService.hash(email))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void savedTestimonial_emailIsEncryptedAtRest_notPlaintextInRawColumn() {
        Testimonial saved = testimonialRepository.saveAndFlush(validTestimonialBuilder("visitor@example.com").build());

        Object rawEmail = entityManager
                .getEntityManager()
                .createNativeQuery("SELECT email FROM testimonial WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(rawEmail).isNotEqualTo("visitor@example.com");
    }

    @Test
    void findByEmailLookupHash_resolvesCorrectRow_forNormalizedEmail() {
        testimonialRepository.saveAndFlush(validTestimonialBuilder("someone@example.com").build());

        var found = testimonialRepository.findByEmailLookupHash(hashService.hash("Someone@Example.com "));

        assertThat(found).isPresent();
    }

    @Test
    void findByEmailLookupHash_unknownHash_returnsEmpty() {
        assertThat(testimonialRepository.findByEmailLookupHash("0".repeat(64))).isEmpty();
    }

    @Test
    void duplicateEmailLookupHash_violatesUniqueConstraint() {
        testimonialRepository.saveAndFlush(validTestimonialBuilder("first@example.com").build());

        Testimonial duplicate = validTestimonialBuilder("first@example.com").build();

        assertThatThrownBy(() -> testimonialRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recommendationScore_zeroAndTen_bothPersistSuccessfully() {
        Testimonial zero = testimonialRepository.saveAndFlush(
                validTestimonialBuilder("zero@example.com").recommendationScore(0).build());
        Testimonial ten = testimonialRepository.saveAndFlush(
                validTestimonialBuilder("ten@example.com").recommendationScore(10).build());

        assertThat(testimonialRepository.findById(zero.getId())).isPresent();
        assertThat(testimonialRepository.findById(ten.getId())).isPresent();
    }

    @Test
    void recommendationScore_belowZero_violatesCheckConstraint() {
        Testimonial invalid = validTestimonialBuilder("below@example.com").recommendationScore(-1).build();

        assertThatThrownBy(() -> testimonialRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recommendationScore_aboveTen_violatesCheckConstraint() {
        Testimonial invalid = validTestimonialBuilder("above@example.com").recommendationScore(11).build();

        assertThatThrownBy(() -> testimonialRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void status_defaultsToPending_whenNotExplicitlySet() {
        Testimonial saved = testimonialRepository.saveAndFlush(validTestimonialBuilder("pending@example.com").build());

        assertThat(saved.getStatus()).isEqualTo(TestimonialStatus.PENDING);
    }

    @Test
    void booleanFlags_defaultToFalse_whenNotExplicitlySet() {
        Testimonial saved = testimonialRepository.saveAndFlush(validTestimonialBuilder("flags@example.com").build());

        assertThat(saved.isIdentityModified()).isFalse();
        assertThat(saved.isScoreModified()).isFalse();
    }

    @Test
    void findByStatusOrderByCreatedAtAsc_returnsOnlyMatchingStatus_orderedOldestFirst() {
        testimonialRepository.saveAndFlush(validTestimonialBuilder("newer-pending@example.com")
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build());
        testimonialRepository.saveAndFlush(validTestimonialBuilder("older-pending@example.com")
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
        testimonialRepository.saveAndFlush(validTestimonialBuilder("approved@example.com")
                .status(TestimonialStatus.APPROVED)
                .createdAt(Instant.parse("2025-12-01T00:00:00Z"))
                .build());

        Page<Testimonial> page = testimonialRepository.findByStatusOrderByCreatedAtAsc(
                TestimonialStatus.PENDING, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(page.getContent().get(1).getCreatedAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void findByStatusOrderByCreatedAtAsc_noMatchingStatus_returnsEmptyPage() {
        testimonialRepository.saveAndFlush(validTestimonialBuilder("only-pending@example.com")
                .status(TestimonialStatus.PENDING)
                .build());

        Page<Testimonial> page = testimonialRepository.findByStatusOrderByCreatedAtAsc(
                TestimonialStatus.REJECTED, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void findByStatusOrderByCreatedAtAsc_pageSizeSmallerThanResultSet_returnsFirstPageWithCorrectTotals() {
        testimonialRepository.saveAndFlush(validTestimonialBuilder("first@example.com")
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
        testimonialRepository.saveAndFlush(validTestimonialBuilder("second@example.com")
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.parse("2026-01-02T00:00:00Z"))
                .build());

        Page<Testimonial> page = testimonialRepository.findByStatusOrderByCreatedAtAsc(
                TestimonialStatus.PENDING, PageRequest.of(0, 1));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void findDistinctCountriesByStatus_multipleApprovedTestimonials_returnsDistinctCountriesOrderedByName() {
        Country unitedStates = countryRepository.findById("US").orElseThrow();
        testimonialRepository.saveAndFlush(validTestimonialBuilder("approved-in-1@example.com")
                .status(TestimonialStatus.APPROVED)
                .country(india)
                .build());
        testimonialRepository.saveAndFlush(validTestimonialBuilder("approved-in-2@example.com")
                .status(TestimonialStatus.APPROVED)
                .country(india)
                .build());
        testimonialRepository.saveAndFlush(validTestimonialBuilder("approved-us@example.com")
                .status(TestimonialStatus.APPROVED)
                .country(unitedStates)
                .build());
        testimonialRepository.saveAndFlush(validTestimonialBuilder("pending-in@example.com")
                .status(TestimonialStatus.PENDING)
                .country(india)
                .build());

        var countries = testimonialRepository.findDistinctCountriesByStatus(TestimonialStatus.APPROVED);

        assertThat(countries).extracting(Country::getCode).containsExactly("IN", "US");
    }

    @Test
    void findDistinctCountriesByStatus_noMatchingTestimonials_returnsEmptyList() {
        testimonialRepository.saveAndFlush(validTestimonialBuilder("pending-only@example.com")
                .status(TestimonialStatus.PENDING)
                .build());

        var countries = testimonialRepository.findDistinctCountriesByStatus(TestimonialStatus.APPROVED);

        assertThat(countries).isEmpty();
    }

    // -- findDistinctByStatusAndModifiedSectionTopicIdIn (decision 28: re-pend on reactivation) --

    private Topic topic(String slug) {
        return topicRepository.saveAndFlush(Topic.builder()
                .slug(slug)
                .label(slug)
                .guidingPrompt(slug + "?")
                .displayOrder(1)
                .active(true)
                .build());
    }

    /** A testimonial of {@code status} with one section per topic, flagged {@code modified} as mapped. */
    private Testimonial withSections(String email, TestimonialStatus status, Map<Topic, Boolean> modifiedByTopic) {
        Testimonial t = validTestimonialBuilder(email).status(status).build();
        modifiedByTopic.forEach((topic, modified) -> t.getSections().add(TestimonialSection.builder()
                .testimonial(t)
                .topic(topic)
                .answerText("Words.")
                .modified(modified)
                .build()));
        return testimonialRepository.saveAndFlush(t);
    }

    @Test
    void findDistinctByStatusAndModifiedSectionTopicIdIn_matchesOnlyThatStatusWithAModifiedSectionAmongTheTopics() {
        Topic reactivated = topic("repend_reactivated");
        Topic alsoReactivated = topic("repend_also_reactivated");
        Topic other = topic("repend_other");
        Testimonial match = withSections("repend-match@example.com", TestimonialStatus.APPROVED,
                Map.of(reactivated, true, other, false));
        Testimonial twoMatchingSections = withSections("repend-two@example.com", TestimonialStatus.APPROVED,
                Map.of(reactivated, true, alsoReactivated, true));
        withSections("repend-unmodified@example.com", TestimonialStatus.APPROVED,
                Map.of(reactivated, false, other, true));
        withSections("repend-pending@example.com", TestimonialStatus.PENDING, Map.of(reactivated, true));
        withSections("repend-rejected@example.com", TestimonialStatus.REJECTED, Map.of(reactivated, true));
        withSections("repend-other-topic@example.com", TestimonialStatus.APPROVED, Map.of(other, true));
        entityManager.clear();

        List<Testimonial> found = testimonialRepository.findDistinctByStatusAndModifiedSectionTopicIdIn(
                TestimonialStatus.APPROVED, List.of(reactivated.getId(), alsoReactivated.getId()));

        assertThat(found).extracting(Testimonial::getId)
                .containsExactlyInAnyOrder(match.getId(), twoMatchingSections.getId());
    }
}
