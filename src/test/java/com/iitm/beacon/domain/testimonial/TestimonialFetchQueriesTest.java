package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.common.crypto.CryptoProperties;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The queries the write paths load what they change with (BL-049): each
 * fetches one level of a testimonial's entity graph for all the parents at
 * once — sections with their topics and groups, contact methods,
 * achievement ticks, the sections' photos, the photos' tags — so walking
 * that level afterwards costs no further statement.
 */
class TestimonialFetchQueriesTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TestimonialSectionRepository testimonialSectionRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private TestimonialAchievementRepository testimonialAchievementRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EntityManager entityManager;

    private final EmailLookupHashService hashService =
            new EmailLookupHashService(new CryptoProperties(TEST_KEY, "dev-only-insecure-pepper-do-not-use-in-prod"));

    private Topic grouped;
    private Topic standalone;
    private Achievement feat;
    private Achievement otherFeat;

    @BeforeEach
    void setUp() {
        TopicGroup group = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Fetch group").displayOrder(450).build());
        grouped = topicRepository.saveAndFlush(Topic.builder().slug("fetch_grouped").label("Fetch grouped")
                .guidingPrompt("?").topicGroup(group).displayOrder(1).build());
        standalone = topicRepository.saveAndFlush(Topic.builder().slug("fetch_standalone").label("Fetch standalone")
                .guidingPrompt("?").displayOrder(451).build());
        feat = achievementRepository.saveAndFlush(
                Achievement.builder().slug("fetch_feat").label("Fetch feat").displayOrder(450).build());
        otherFeat = achievementRepository.saveAndFlush(
                Achievement.builder().slug("fetch_other_feat").label("Other fetch feat").displayOrder(451).build());
    }

    /** A testimonial with a section per topic, two photos of two tags per section, a contact and both ticks. */
    private Testimonial saveFull() {
        String email = "fetch-" + UUID.randomUUID() + "@example.com";
        Testimonial testimonial = Testimonial.builder()
                .firstName("Fetch")
                .lastName("Person")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(hashService.hash(email))
                .country(countryRepository.getReferenceById("IN"))
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        for (Topic topic : List.of(grouped, standalone)) {
            TestimonialSection section = TestimonialSection.builder()
                    .testimonial(testimonial)
                    .topic(topicRepository.getReferenceById(topic.getId()))
                    .answerText("Words.")
                    .build();
            for (int p = 0; p < 2; p++) {
                Photo photo = Photo.builder().section(section).filePath("fetch/" + UUID.randomUUID() + ".webp")
                        .displayOrder(p).build();
                photo.getTags().add(PhotoTag.builder().photo(photo).tagText("first").build());
                photo.getTags().add(PhotoTag.builder().photo(photo).tagText("second").build());
                section.getPhotos().add(photo);
            }
            testimonial.getSections().add(section);
        }
        testimonial.getContactMethods().add(ContactMethod.builder()
                .testimonial(testimonial)
                .contactType(contactTypeRepository.findBySlug("email").orElseThrow())
                .value("fetch@example.com")
                .displayOrder(0)
                .build());
        for (Achievement achievement : List.of(feat, otherFeat)) {
            testimonial.getAchievements().add(TestimonialAchievement.builder()
                    .testimonial(testimonial)
                    .achievement(achievementRepository.getReferenceById(achievement.getId()))
                    .build());
        }
        Testimonial saved = testimonialRepository.saveAndFlush(testimonial);
        entityManager.clear();
        return saved;
    }

    private Testimonial saveBare() {
        String email = "fetch-bare-" + UUID.randomUUID() + "@example.com";
        Testimonial saved = testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("Bare")
                .lastName("Person")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(hashService.hash(email))
                .country(countryRepository.getReferenceById("IN"))
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
        entityManager.clear();
        return saved;
    }

    private static void assertSectionTopicsLoaded(Testimonial testimonial) {
        assertThat(Hibernate.isInitialized(testimonial.getSections())).isTrue();
        assertThat(testimonial.getSections()).hasSize(2).allSatisfy(section -> {
            assertThat(Hibernate.isInitialized(section.getTopic())).isTrue();
            if (section.getTopic().getTopicGroup() != null) {
                assertThat(Hibernate.isInitialized(section.getTopic().getTopicGroup())).isTrue();
            }
        });
    }

    // -- the testimonial's own levels --

    @Test
    void withSectionTopicsById_loadsTheSectionsWithTheirTopicsAndGroups() {
        Testimonial saved = saveFull();

        Testimonial found = testimonialRepository.findWithSectionTopicsById(saved.getId()).orElseThrow();

        assertSectionTopicsLoaded(found);
        assertThat(found.getSections()).extracting(section -> section.getTopic().getSlug())
                .containsExactlyInAnyOrder("fetch_grouped", "fetch_standalone");
    }

    @Test
    void withSectionTopicsById_testimonialWithoutSections_isFound_withNone_andAnUnknownIdIsEmpty() {
        Testimonial bare = saveBare();

        assertThat(testimonialRepository.findWithSectionTopicsById(bare.getId())).get()
                .satisfies(found -> assertThat(found.getSections()).isEmpty());
        assertThat(testimonialRepository.findWithSectionTopicsById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void withSectionTopicsByEmailLookupHash_loadsTheSectionsWithTheirTopicsAndGroups_orIsEmptyForAnUnknownHash() {
        Testimonial saved = saveFull();

        Testimonial found =
                testimonialRepository.findWithSectionTopicsByEmailLookupHash(saved.getEmailLookupHash()).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertSectionTopicsLoaded(found);
        assertThat(testimonialRepository.findWithSectionTopicsByEmailLookupHash("0".repeat(64))).isEmpty();
    }

    @Test
    void withContactMethodsById_loadsTheContactMethods() {
        Testimonial saved = saveFull();

        Testimonial found = testimonialRepository.findWithContactMethodsById(saved.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getContactMethods())).isTrue();
        assertThat(found.getContactMethods()).extracting(ContactMethod::getValue).containsExactly("fetch@example.com");
    }

    @Test
    void withAchievementsById_loadsTheTicksWithTheirAchievements() {
        Testimonial saved = saveFull();

        Testimonial found = testimonialRepository.findWithAchievementsById(saved.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getAchievements())).isTrue();
        assertThat(found.getAchievements()).hasSize(2)
                .allSatisfy(tick -> assertThat(Hibernate.isInitialized(tick.getAchievement())).isTrue());
    }

    @Test
    void withSectionsByIdIn_loadsEachTestimonialsSections_unknownIdsIgnored() {
        Testimonial first = saveFull();
        Testimonial second = saveFull();

        List<Testimonial> found = testimonialRepository.findWithSectionsByIdIn(
                List.of(first.getId(), second.getId(), Long.MAX_VALUE));

        assertThat(found).extracting(Testimonial::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(found).allSatisfy(testimonial -> {
            assertThat(Hibernate.isInitialized(testimonial.getSections())).isTrue();
            assertThat(testimonial.getSections()).hasSize(2);
        });
        assertThat(testimonialRepository.findWithSectionsByIdIn(List.of())).isEmpty();
    }

    // -- sections, photos, ticks of many testimonials --

    @Test
    void sectionsByTopicIdIn_areThoseOfTheTopicsInEveryStatus_withTheirTestimonialAndPhotos() {
        Testimonial first = saveFull();
        Testimonial second = saveFull();

        List<TestimonialSection> found =
                testimonialSectionRepository.findWithTestimonialAndPhotosByTopicIdIn(List.of(grouped.getId()));

        assertThat(found).hasSize(2).allSatisfy(section -> {
            assertThat(section.getTopic().getId()).isEqualTo(grouped.getId());
            assertThat(Hibernate.isInitialized(section.getTestimonial())).isTrue();
            assertThat(Hibernate.isInitialized(section.getPhotos())).isTrue();
            assertThat(section.getPhotos()).hasSize(2);
        });
        assertThat(found).extracting(section -> section.getTestimonial().getId())
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(testimonialSectionRepository.findWithTestimonialAndPhotosByTopicIdIn(List.of())).isEmpty();
    }

    @Test
    void sectionsWithPhotosByIdIn_loadsEachSectionsPhotos() {
        Testimonial saved = saveFull();
        List<Long> sectionIds = saved.getSections().stream().map(TestimonialSection::getId).toList();

        List<TestimonialSection> found = testimonialSectionRepository.findWithPhotosByIdIn(sectionIds);

        assertThat(found).hasSize(2).allSatisfy(section -> {
            assertThat(Hibernate.isInitialized(section.getPhotos())).isTrue();
            assertThat(section.getPhotos()).hasSize(2);
        });
        assertThat(testimonialSectionRepository.findWithPhotosByIdIn(List.of())).isEmpty();
    }

    @Test
    void photosWithTagsBySectionIdIn_loadsEveryPhotoOfThoseSectionsWithItsTags() {
        Testimonial saved = saveFull();
        Long sectionId = saved.getSections().get(0).getId();

        List<Photo> found = photoRepository.findWithTagsBySectionIdIn(List.of(sectionId));

        assertThat(found).hasSize(2).allSatisfy(photo -> {
            assertThat(Hibernate.isInitialized(photo.getTags())).isTrue();
            assertThat(photo.getTags()).extracting(PhotoTag::getTagText).containsExactlyInAnyOrder("first", "second");
        });
        assertThat(photoRepository.findWithTagsBySectionIdIn(List.of())).isEmpty();
    }

    @Test
    void photosWithSectionByTestimonialIdIn_areEveryPhotoOfThoseTestimonials_withItsSection() {
        Testimonial first = saveFull();
        Testimonial second = saveFull();
        Testimonial other = saveFull();

        List<Photo> found = photoRepository.findWithSectionByTestimonialIdIn(List.of(first.getId(), second.getId()));

        assertThat(found).hasSize(8)
                .allSatisfy(photo -> assertThat(Hibernate.isInitialized(photo.getSection())).isTrue());
        assertThat(found).extracting(photo -> photo.getSection().getTestimonial().getId())
                .containsOnly(first.getId(), second.getId())
                .doesNotContain(other.getId());
        assertThat(photoRepository.findWithSectionByTestimonialIdIn(List.of())).isEmpty();
    }

    @Test
    void ticksOfAnAchievement_comeWithTheirTestimonialAndAllOfItsTicks() {
        Testimonial first = saveFull();
        Testimonial second = saveFull();

        List<TestimonialAchievement> found =
                testimonialAchievementRepository.findWithTestimonialTicksByAchievementId(feat.getId());

        assertThat(found).hasSize(2).allSatisfy(tick -> {
            assertThat(tick.getId().getAchievementId()).isEqualTo(feat.getId());
            assertThat(Hibernate.isInitialized(tick.getTestimonial())).isTrue();
            assertThat(Hibernate.isInitialized(tick.getTestimonial().getAchievements())).isTrue();
            assertThat(tick.getTestimonial().getAchievements()).hasSize(2);
        });
        assertThat(found).extracting(tick -> tick.getTestimonial().getId())
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(testimonialAchievementRepository.findWithTestimonialTicksByAchievementId(Long.MAX_VALUE))
                .isEmpty();
    }
}
