package com.iitm.beacon.domain.achievement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.crypto.CryptoProperties;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

class TestimonialAchievementRepositoryTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    @Autowired
    private TestimonialAchievementRepository testimonialAchievementRepository;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private TestEntityManager entityManager;

    private final EmailLookupHashService hashService =
            new EmailLookupHashService(new CryptoProperties(TEST_KEY, "dev-only-insecure-pepper-do-not-use-in-prod"));

    private Testimonial testimonial;
    private Achievement achievement;

    @BeforeEach
    void setUp() {
        Country india = countryRepository.findById("IN").orElseThrow();
        testimonial = testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email("ta-test@example.com")
                .emailLookupHash(hashService.hash("ta-test@example.com"))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
        achievement = achievementRepository.findBySlug("made_new_friends").orElseThrow();
    }

    @Test
    void savedJoinRow_roundTrips_withCompositeIdDerivedFromAssociations() {
        TestimonialAchievement saved = testimonialAchievementRepository.saveAndFlush(
                TestimonialAchievement.builder().testimonial(testimonial).achievement(achievement).build());

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getId().getTestimonialId()).isEqualTo(testimonial.getId());
        assertThat(saved.getId().getAchievementId()).isEqualTo(achievement.getId());
    }

    @Test
    void duplicateCompositePair_violatesPrimaryKeyUniqueness() {
        testimonialAchievementRepository.saveAndFlush(
                TestimonialAchievement.builder().testimonial(testimonial).achievement(achievement).build());

        TestimonialAchievement duplicate =
                TestimonialAchievement.builder().testimonial(testimonial).achievement(achievement).build();

        assertThatThrownBy(() -> testimonialAchievementRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTestimonial_removesJoinRow_butNeverTheReferencedAchievement() {
        testimonialAchievementRepository.saveAndFlush(
                TestimonialAchievement.builder().testimonial(testimonial).achievement(achievement).build());

        Long testimonialId = testimonial.getId();
        Long achievementId = achievement.getId();
        TestimonialAchievementId joinId =
                TestimonialAchievementId.builder().testimonialId(testimonialId).achievementId(achievementId).build();

        entityManager.clear();
        Testimonial reloaded = testimonialRepository.findById(testimonialId).orElseThrow();

        testimonialRepository.delete(reloaded);
        testimonialRepository.flush();

        assertThat(testimonialAchievementRepository.findById(joinId)).isEmpty();
        assertThat(achievementRepository.findById(achievementId)).isPresent();
    }
}
