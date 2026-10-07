package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.achievement.TestimonialAchievementId;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * The two queries behind the rejected-testimonial purge (UC-PURGE-REJECTED,
 * decision 3): finding the testimonials due for it, and deleting one only if
 * it is still due — a testimonial the visitor resubmitted (or that was
 * rejected again) after it was found must survive. The delete relies on the
 * database's {@code ON DELETE CASCADE} foreign keys for everything the
 * testimonial owns.
 *
 * <p>The cutoff is far in the past, so no testimonial another test left
 * behind can ever be due.
 */
class TestimonialRepositoryRetentionTest extends AbstractRepositoryTest {

    private static final Instant CUTOFF = Instant.parse("2001-01-01T00:00:00Z");
    private static final Instant LONG_BEFORE = Instant.parse("2000-06-01T00:00:00Z");

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TestimonialSectionRepository testimonialSectionRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private PhotoTagRepository photoTagRepository;

    @Autowired
    private ContactMethodRepository contactMethodRepository;

    @Autowired
    private TestimonialAchievementRepository testimonialAchievementRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Testimonial testimonial(TestimonialStatus status, Instant rejectedAt) {
        return testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("Rita")
                .lastName("Retention")
                .rollNumber("GE26Z002")
                .admissionYear(2024)
                .email("retention-" + UUID.randomUUID() + "@example.com")
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(7)
                .dataProcessingConsent(true)
                .status(status)
                .createdAt(Instant.parse("2000-01-01T00:00:00Z"))
                .rejectedAt(rejectedAt)
                .build());
    }

    /** A testimonial owning one of everything: a section with a tagged photo, a contact method, a tick. */
    private Testimonial fullTestimonial(TestimonialStatus status, Instant rejectedAt) {
        Testimonial t = testimonial(status, rejectedAt);
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("It was fine.")
                .build();
        Photo photo = Photo.builder().section(section).filePath(UUID.randomUUID() + ".webp").displayOrder(0).build();
        photo.getTags().add(PhotoTag.builder().photo(photo).tagText("campus").build());
        section.getPhotos().add(photo);
        t.getSections().add(section);
        t.getContactMethods().add(ContactMethod.builder()
                .testimonial(t)
                .contactType(contactTypeRepository.findBySlug("telegram").orElseThrow())
                .value("@rita")
                .displayOrder(0)
                .build());
        t.getAchievements().add(TestimonialAchievement.builder()
                .testimonial(t)
                .achievement(achievementRepository.findBySlug("made_new_friends").orElseThrow())
                .build());
        return testimonialRepository.saveAndFlush(t);
    }

    // -- findByStatusAndRejectedAtBeforeOrderByIdAsc --

    @Test
    void findDue_returnsOnlyRejectedTestimonialsRejectedStrictlyBeforeTheCutoff_inIdOrder() {
        Testimonial longAgo = testimonial(TestimonialStatus.REJECTED, LONG_BEFORE);
        Testimonial justBefore = testimonial(TestimonialStatus.REJECTED, CUTOFF.minus(1, ChronoUnit.MICROS));
        testimonial(TestimonialStatus.REJECTED, CUTOFF);
        testimonial(TestimonialStatus.REJECTED, CUTOFF.plus(1, ChronoUnit.MICROS));
        testimonial(TestimonialStatus.PENDING, LONG_BEFORE);
        testimonial(TestimonialStatus.APPROVED, LONG_BEFORE);
        entityManager.clear();

        List<Testimonial> due = testimonialRepository.findByStatusAndRejectedAtBeforeOrderByIdAsc(
                TestimonialStatus.REJECTED, CUTOFF);

        assertThat(due).extracting(Testimonial::getId).containsExactly(longAgo.getId(), justBefore.getId());
    }

    /** No rejection time is no evidence of an old rejection: such a row is never due. */
    @Test
    void findDue_rejectedWithoutARejectionTime_isNotDue() {
        testimonial(TestimonialStatus.REJECTED, null);

        assertThat(testimonialRepository.findByStatusAndRejectedAtBeforeOrderByIdAsc(
                TestimonialStatus.REJECTED, CUTOFF)).isEmpty();
    }

    @Test
    void findDue_nothingDue_returnsAnEmptyList() {
        testimonial(TestimonialStatus.REJECTED, CUTOFF.plus(1, ChronoUnit.DAYS));

        assertThat(testimonialRepository.findByStatusAndRejectedAtBeforeOrderByIdAsc(
                TestimonialStatus.REJECTED, CUTOFF)).isEmpty();
    }

    // -- deleteByIdAndStatusAndRejectedAtBefore --

    @Test
    void deleteIfDue_dueTestimonial_returnsOne_andRemovesEverythingItOwns_butNothingOfAnotherTestimonial() {
        Testimonial doomed = fullTestimonial(TestimonialStatus.REJECTED, LONG_BEFORE);
        Testimonial other = fullTestimonial(TestimonialStatus.REJECTED, LONG_BEFORE);
        Owned doomedRows = Owned.of(doomed);
        Owned otherRows = Owned.of(other);
        entityManager.clear();

        int deleted = testimonialRepository.deleteByIdAndStatusAndRejectedAtBefore(
                doomed.getId(), TestimonialStatus.REJECTED, CUTOFF);
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(testimonialRepository.existsById(doomed.getId())).isFalse();
        assertThat(testimonialSectionRepository.existsById(doomedRows.sectionId())).isFalse();
        assertThat(photoRepository.existsById(doomedRows.photoId())).isFalse();
        assertThat(photoTagRepository.existsById(doomedRows.tagId())).isFalse();
        assertThat(contactMethodRepository.existsById(doomedRows.contactMethodId())).isFalse();
        assertThat(testimonialAchievementRepository.existsById(doomedRows.tick())).isFalse();

        assertThat(testimonialRepository.existsById(other.getId())).isTrue();
        assertThat(testimonialSectionRepository.existsById(otherRows.sectionId())).isTrue();
        assertThat(photoRepository.existsById(otherRows.photoId())).isTrue();
        assertThat(photoTagRepository.existsById(otherRows.tagId())).isTrue();
        assertThat(contactMethodRepository.existsById(otherRows.contactMethodId())).isTrue();
        assertThat(testimonialAchievementRepository.existsById(otherRows.tick())).isTrue();
    }

    /** Resubmitted (now pending) or since approved: the stale old rejection time doesn't make it due. */
    @ParameterizedTest
    @EnumSource(value = TestimonialStatus.class, names = {"PENDING", "APPROVED"})
    void deleteIfDue_noLongerRejected_returnsZero_andKeepsIt(TestimonialStatus status) {
        Testimonial resubmitted = fullTestimonial(status, LONG_BEFORE);
        Owned rows = Owned.of(resubmitted);
        entityManager.clear();

        int deleted = testimonialRepository.deleteByIdAndStatusAndRejectedAtBefore(
                resubmitted.getId(), TestimonialStatus.REJECTED, CUTOFF);
        entityManager.clear();

        assertThat(deleted).isZero();
        assertThat(testimonialRepository.existsById(resubmitted.getId())).isTrue();
        assertThat(photoRepository.existsById(rows.photoId())).isTrue();
    }

    /** Rejected again since it was found: the new rejection restarts the 30 days. */
    @Test
    void deleteIfDue_rejectedAgainAtTheCutoff_returnsZero_andKeepsIt() {
        Testimonial rejectedAgain = testimonial(TestimonialStatus.REJECTED, CUTOFF);

        int deleted = testimonialRepository.deleteByIdAndStatusAndRejectedAtBefore(
                rejectedAgain.getId(), TestimonialStatus.REJECTED, CUTOFF);
        entityManager.clear();

        assertThat(deleted).isZero();
        assertThat(testimonialRepository.existsById(rejectedAgain.getId())).isTrue();
    }

    @Test
    void deleteIfDue_unknownId_returnsZero() {
        assertThat(testimonialRepository.deleteByIdAndStatusAndRejectedAtBefore(
                -1L, TestimonialStatus.REJECTED, CUTOFF)).isZero();
    }

    /** Ids of the rows a {@link #fullTestimonial} owns. */
    private record Owned(Long sectionId, Long photoId, Long tagId, Long contactMethodId,
            TestimonialAchievementId tick) {

        static Owned of(Testimonial t) {
            TestimonialSection section = t.getSections().get(0);
            Photo photo = section.getPhotos().get(0);
            return new Owned(
                    section.getId(),
                    photo.getId(),
                    photo.getTags().get(0).getId(),
                    t.getContactMethods().get(0).getId(),
                    t.getAchievements().get(0).getId());
        }
    }
}
