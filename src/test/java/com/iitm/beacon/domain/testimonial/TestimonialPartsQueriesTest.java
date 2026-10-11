package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.iitm.beacon.common.crypto.CryptoProperties;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.AchievementTick;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The queries that load one level of many testimonials' parts at once,
 * keyed by the parents' ids (BL-049): visible sections in display order and
 * each testimonial's card cover, the sections' photos, the photos' tags, the
 * contact methods (decrypted) and the visible achievement ticks — each a
 * projection, never an entity.
 */
class TestimonialPartsQueriesTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

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

    /** Display order 1 on the top-level range: before every seeded entry but the first group. */
    private TopicGroup earlyGroup;

    /** Display order 0, hidden: its sections must never show, though they would sort first. */
    private TopicGroup hiddenGroup;

    private Topic earlyGroupSecond;
    private Topic earlyGroupFirst;
    private Topic standaloneAtTwo;
    private Topic inactiveStandalone;
    private Topic inHiddenGroup;

    @BeforeEach
    void setUp() {
        earlyGroup = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Parts early group").displayOrder(1).build());
        hiddenGroup = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Parts hidden group").displayOrder(0).active(false).build());
        earlyGroupSecond = topic("parts_early_second", earlyGroup, 2, true);
        earlyGroupFirst = topic("parts_early_first", earlyGroup, 1, true);
        standaloneAtTwo = topic("parts_standalone_two", null, 2, true);
        inactiveStandalone = topic("parts_inactive_standalone", null, 0, false);
        inHiddenGroup = topic("parts_in_hidden_group", hiddenGroup, 0, true);
    }

    private Topic topic(String slug, TopicGroup group, int displayOrder, boolean active) {
        return topicRepository.saveAndFlush(Topic.builder()
                .slug(slug)
                .label("Label of " + slug)
                .guidingPrompt(slug + "?")
                .topicGroup(group)
                .displayOrder(displayOrder)
                .active(active)
                .build());
    }

    private Testimonial testimonial() {
        String email = "parts-" + UUID.randomUUID() + "@example.com";
        return Testimonial.builder()
                .firstName("Parts")
                .lastName("Person")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(hashService.hash(email))
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private TestimonialSection section(Testimonial testimonial, Topic topic, String answer, boolean modified) {
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(topic)
                .answerText(answer)
                .modified(modified)
                .build();
        testimonial.getSections().add(section);
        return section;
    }

    private Photo photo(TestimonialSection section, String name, int displayOrder, boolean legacy, String... tags) {
        Photo photo = Photo.builder()
                .section(section)
                .filePath(name + (legacy ? ".jpg" : ".webp"))
                .thumbnailPath(legacy ? null : name + "-thumb.webp")
                .width(legacy ? null : 640)
                .height(legacy ? null : 480)
                .displayOrder(displayOrder)
                .build();
        for (String tag : tags) {
            photo.getTags().add(PhotoTag.builder().photo(photo).tagText(tag).build());
        }
        section.getPhotos().add(photo);
        return photo;
    }

    private void contact(Testimonial testimonial, String typeSlug, String value, boolean isPublic, int order) {
        testimonial.getContactMethods().add(ContactMethod.builder()
                .testimonial(testimonial)
                .contactType(contactTypeRepository.findBySlug(typeSlug).orElseThrow())
                .value(value)
                .isPublic(isPublic)
                .displayOrder(order)
                .build());
    }

    /** Ticks {@code achievement}, looked up again: {@link #save} clears the persistence context. */
    private void tick(Testimonial testimonial, Achievement achievement) {
        testimonial.getAchievements().add(TestimonialAchievement.builder()
                .testimonial(testimonial)
                .achievement(achievementRepository.getReferenceById(achievement.getId()))
                .build());
    }

    private Achievement achievement(String slug, int displayOrder, boolean active) {
        return achievementRepository.saveAndFlush(Achievement.builder()
                .slug(slug).label("Label of " + slug).displayOrder(displayOrder).active(active).build());
    }

    private Testimonial save(Testimonial testimonial) {
        Testimonial saved = testimonialRepository.saveAndFlush(testimonial);
        entityManager.clear();
        return saved;
    }

    // -- visible sections, in display order --

    @Test
    void visibleSections_leaveHiddenOnesOut_andComeInDisplayOrder_testimonialByTestimonial() {
        Testimonial first = testimonial();
        section(first, standaloneAtTwo, "Standalone at two.", false);
        section(first, inHiddenGroup, "Hidden by its group.", true);
        section(first, earlyGroupSecond, "Group, second topic.", true);
        section(first, inactiveStandalone, "Hidden topic.", false);
        section(first, earlyGroupFirst, "Group, first topic.", false);
        save(first);
        Testimonial second = testimonial();
        section(second, topicRepository.findBySlug("general").orElseThrow(), "General words.", false);
        save(second);

        List<VisibleSection> found =
                testimonialSectionRepository.findVisibleByTestimonialIdIn(List.of(second.getId(), first.getId()));

        assertThat(found).extracting(VisibleSection::testimonialId, VisibleSection::topicSlug).containsExactly(
                tuple(first.getId(), earlyGroupFirst.getSlug()),
                tuple(first.getId(), earlyGroupSecond.getSlug()),
                tuple(first.getId(), standaloneAtTwo.getSlug()),
                tuple(second.getId(), "general"));
        VisibleSection groupSecond = found.get(1);
        assertThat(groupSecond.topicLabel()).isEqualTo("Label of parts_early_second");
        assertThat(groupSecond.answerText()).isEqualTo("Group, second topic.");
        assertThat(groupSecond.modified()).isTrue();
        assertThat(found.get(0).modified()).isFalse();
        assertThat(found).extracting(VisibleSection::id).doesNotContainNull().doesNotHaveDuplicates();
    }

    /** A group and a standalone topic share one top-level range; the group's members keep their own order. */
    @Test
    void visibleSections_standaloneTopicBetweenTwoGroups_sortsByTheTopLevelRange() {
        TopicGroup lateGroup = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Parts late group").displayOrder(3).build());
        Topic lateMember = topic("parts_late_member", lateGroup, 0, true);
        Testimonial testimonial = testimonial();
        section(testimonial, lateMember, "Late group.", false);
        section(testimonial, standaloneAtTwo, "Standalone.", false);
        section(testimonial, earlyGroupSecond, "Early group.", false);
        save(testimonial);

        assertThat(testimonialSectionRepository.findVisibleByTestimonialIdIn(List.of(testimonial.getId())))
                .extracting(VisibleSection::topicSlug)
                .containsExactly(earlyGroupSecond.getSlug(), standaloneAtTwo.getSlug(), lateMember.getSlug());
    }

    @Test
    void visibleSections_ofTestimonialsWithNoneVisible_orUnknownIds_orNoIds_areNone() {
        Testimonial onlyHidden = testimonial();
        section(onlyHidden, inactiveStandalone, "Hidden.", false);
        save(onlyHidden);
        Testimonial noSections = save(testimonial());

        assertThat(testimonialSectionRepository.findVisibleByTestimonialIdIn(
                List.of(onlyHidden.getId(), noSections.getId(), Long.MAX_VALUE))).isEmpty();
        assertThat(testimonialSectionRepository.findVisibleByTestimonialIdIn(List.of())).isEmpty();
    }

    // -- card covers --

    @Test
    void covers_areTheFirstVisibleSectionsStart_withItsFirstPhotoByDisplayOrder() {
        Testimonial testimonial = testimonial();
        TestimonialSection hidden = section(testimonial, inHiddenGroup, "Hidden comes first in order.", false);
        photo(hidden, "parts/hidden", 0, false);
        section(testimonial, standaloneAtTwo, "Second visible.", false);
        TestimonialSection first = section(testimonial, earlyGroupFirst, "First visible section.", false);
        photo(first, "parts/second", 1, false);
        photo(first, "parts/first", 0, false);
        save(testimonial);

        List<TestimonialCover> covers =
                testimonialSectionRepository.findCoversByTestimonialIdIn(List.of(testimonial.getId()), 100);

        assertThat(covers).singleElement().satisfies(cover -> {
            assertThat(cover.testimonialId()).isEqualTo(testimonial.getId());
            assertThat(cover.answerStart()).isEqualTo("First visible section.");
            assertThat(cover.photoFilePath()).isEqualTo("parts/first.webp");
            assertThat(cover.photoThumbnailPath()).isEqualTo("parts/first-thumb.webp");
        });
    }

    @Test
    void covers_legacyFirstPhoto_hasItsFilePathAndNoThumbnail() {
        Testimonial testimonial = testimonial();
        photo(section(testimonial, earlyGroupFirst, "Legacy.", false), "parts/legacy", 0, true);
        save(testimonial);

        assertThat(testimonialSectionRepository.findCoversByTestimonialIdIn(List.of(testimonial.getId()), 100))
                .singleElement().satisfies(cover -> {
                    assertThat(cover.photoFilePath()).isEqualTo("parts/legacy.jpg");
                    assertThat(cover.photoThumbnailPath()).isNull();
                });
    }

    /** The cover is the first section's own first photo — never one of a later section. */
    @Test
    void covers_firstSectionWithoutPhotos_hasNoPhoto_evenWhenALaterSectionHasOne() {
        Testimonial testimonial = testimonial();
        section(testimonial, earlyGroupFirst, "No photos here.", false);
        photo(section(testimonial, standaloneAtTwo, "Photos here.", false), "parts/later", 0, false);
        save(testimonial);

        assertThat(testimonialSectionRepository.findCoversByTestimonialIdIn(List.of(testimonial.getId()), 100))
                .singleElement().satisfies(cover -> {
                    assertThat(cover.answerStart()).isEqualTo("No photos here.");
                    assertThat(cover.photoFilePath()).isNull();
                    assertThat(cover.photoThumbnailPath()).isNull();
                });
    }

    @Test
    void covers_answerStart_isTheAnswersFirstCharacters_upToTheLengthAsked() {
        Testimonial longAnswer = testimonial();
        section(longAnswer, earlyGroupFirst, "abcdefghij", false);
        save(longAnswer);
        Testimonial exactAnswer = testimonial();
        section(exactAnswer, earlyGroupFirst, "abcde", false);
        save(exactAnswer);
        Testimonial emojiAnswer = testimonial();
        section(emojiAnswer, earlyGroupFirst, "😀😀😀😀😀😀", false);
        save(emojiAnswer);

        List<TestimonialCover> covers = testimonialSectionRepository.findCoversByTestimonialIdIn(
                List.of(longAnswer.getId(), exactAnswer.getId(), emojiAnswer.getId()), 5);

        assertThat(covers).extracting(TestimonialCover::testimonialId, TestimonialCover::answerStart)
                .containsExactlyInAnyOrder(
                        tuple(longAnswer.getId(), "abcde"),
                        tuple(exactAnswer.getId(), "abcde"),
                        tuple(emojiAnswer.getId(), "😀😀😀😀😀"));
    }

    @Test
    void covers_testimonialWithNoVisibleSection_hasNone_andNoIdsGiveNone() {
        Testimonial onlyHidden = testimonial();
        photo(section(onlyHidden, inactiveStandalone, "Hidden.", false), "parts/hidden-only", 0, false);
        save(onlyHidden);

        assertThat(testimonialSectionRepository.findCoversByTestimonialIdIn(List.of(onlyHidden.getId()), 100))
                .isEmpty();
        assertThat(testimonialSectionRepository.findCoversByTestimonialIdIn(List.of(), 100)).isEmpty();
    }

    // -- photos, tags --

    @Test
    void sectionPhotos_comeSectionBySectionInDisplayOrder_withTheirFilesAndSize() {
        Testimonial testimonial = testimonial();
        TestimonialSection first = section(testimonial, earlyGroupFirst, "First.", false);
        TestimonialSection second = section(testimonial, standaloneAtTwo, "Second.", false);
        photo(second, "parts/s2-only", 0, true);
        photo(first, "parts/s1-b", 1, false);
        photo(first, "parts/s1-a", 0, false);
        save(testimonial);
        List<Long> sectionIds = sectionIdsOf(testimonial);

        List<SectionPhoto> photos = photoRepository.findSectionPhotosBySectionIdIn(sectionIds);

        assertThat(photos).extracting(SectionPhoto::filePath)
                .containsExactly("parts/s1-a.webp", "parts/s1-b.webp", "parts/s2-only.jpg");
        assertThat(photos.get(0).sectionId()).isEqualTo(sectionIds.get(0));
        assertThat(photos.get(0).thumbnailPath()).isEqualTo("parts/s1-a-thumb.webp");
        assertThat(photos.get(0).width()).isEqualTo(640);
        assertThat(photos.get(0).height()).isEqualTo(480);
        assertThat(photos.get(2).sectionId()).isEqualTo(sectionIds.get(1));
        assertThat(photos.get(2).thumbnailPath()).isNull();
        assertThat(photos.get(2).width()).isNull();
        assertThat(photos.get(2).height()).isNull();
        assertThat(photoRepository.findSectionPhotosBySectionIdIn(List.of())).isEmpty();
    }

    private List<Long> sectionIdsOf(Testimonial testimonial) {
        return testimonial.getSections().stream().map(TestimonialSection::getId).toList();
    }

    @Test
    void tagTexts_comePhotoByPhotoInTheOrderTheyWereAdded() {
        Testimonial testimonial = testimonial();
        TestimonialSection section = section(testimonial, earlyGroupFirst, "Tagged.", false);
        Photo first = photo(section, "parts/tagged-1", 0, false, "zebra", "apple");
        Photo second = photo(section, "parts/tagged-2", 1, false, "mango");
        photo(section, "parts/untagged", 2, false);
        save(testimonial);

        List<PhotoTagText> tags = photoTagRepository.findTextsByPhotoIdIn(List.of(second.getId(), first.getId()));

        assertThat(tags).extracting(PhotoTagText::photoId, PhotoTagText::tagText).containsExactly(
                tuple(first.getId(), "zebra"),
                tuple(first.getId(), "apple"),
                tuple(second.getId(), "mango"));
        assertThat(photoTagRepository.findTextsByPhotoIdIn(List.of())).isEmpty();
    }

    // -- contact methods --

    @Test
    void contactValues_areDecrypted_inDisplayOrder_testimonialByTestimonial_privateOnesIncluded() {
        Testimonial first = testimonial();
        contact(first, "telegram", "@second_one", false, 1);
        contact(first, "email", "first@example.com", true, 0);
        save(first);
        Testimonial second = testimonial();
        contact(second, "instagram", "@other.person", true, 0);
        save(second);
        save(testimonial());

        List<ContactMethodValue> found =
                contactMethodRepository.findValuesByTestimonialIdIn(List.of(second.getId(), first.getId()));

        assertThat(found).containsExactly(
                new ContactMethodValue(first.getId(), "email", "first@example.com", true),
                new ContactMethodValue(first.getId(), "telegram", "@second_one", false),
                new ContactMethodValue(second.getId(), "instagram", "@other.person", true));
        assertThat(contactMethodRepository.findValuesByTestimonialIdIn(List.of())).isEmpty();
    }

    @Test
    void publicContactValues_ofATestimonialInThatStatus_areItsPublicOnes_inDisplayOrder() {
        Testimonial testimonial = testimonial();
        contact(testimonial, "twitter", "@public_two", true, 2);
        contact(testimonial, "telegram", "@private_one", false, 1);
        contact(testimonial, "email", "public@example.com", true, 0);
        save(testimonial);

        assertThat(contactMethodRepository.findPublicValuesByTestimonialIdAndStatus(
                testimonial.getId(), TestimonialStatus.APPROVED)).containsExactly(
                new ContactMethodValue(testimonial.getId(), "email", "public@example.com", true),
                new ContactMethodValue(testimonial.getId(), "twitter", "@public_two", true));
    }

    @Test
    void publicContactValues_ofATestimonialInAnotherStatus_orUnknown_orWithOnlyPrivateOnes_areNone() {
        Testimonial pending = testimonial();
        pending.setStatus(TestimonialStatus.PENDING);
        contact(pending, "email", "pending@example.com", true, 0);
        save(pending);
        Testimonial onlyPrivate = testimonial();
        contact(onlyPrivate, "email", "private@example.com", false, 0);
        save(onlyPrivate);

        assertThat(contactMethodRepository.findPublicValuesByTestimonialIdAndStatus(
                pending.getId(), TestimonialStatus.APPROVED)).isEmpty();
        assertThat(contactMethodRepository.findPublicValuesByTestimonialIdAndStatus(
                onlyPrivate.getId(), TestimonialStatus.APPROVED)).isEmpty();
        assertThat(contactMethodRepository.findPublicValuesByTestimonialIdAndStatus(
                Long.MAX_VALUE, TestimonialStatus.APPROVED)).isEmpty();
    }

    @Test
    void existsPublicContact_isTrueOnlyWithAtLeastOnePublicContact() {
        Testimonial withPublic = testimonial();
        contact(withPublic, "email", "a@example.com", false, 0);
        contact(withPublic, "telegram", "@public_one", true, 1);
        save(withPublic);
        Testimonial onlyPrivate = testimonial();
        contact(onlyPrivate, "email", "b@example.com", false, 0);
        save(onlyPrivate);
        Testimonial none = save(testimonial());

        assertThat(contactMethodRepository.existsByTestimonialIdAndIsPublicTrue(withPublic.getId())).isTrue();
        assertThat(contactMethodRepository.existsByTestimonialIdAndIsPublicTrue(onlyPrivate.getId())).isFalse();
        assertThat(contactMethodRepository.existsByTestimonialIdAndIsPublicTrue(none.getId())).isFalse();
    }

    // -- achievement ticks --

    @Test
    void visibleTicks_leaveInactiveAchievementsOut_inCatalogOrder_testimonialByTestimonial() {
        Achievement late = achievement("parts_late_feat", 900, true);
        Achievement earlyTie = achievement("parts_early_tie_feat", 0, true);
        Achievement inactive = achievement("parts_inactive_feat", 0, false);
        Testimonial first = testimonial();
        tick(first, late);
        tick(first, inactive);
        tick(first, earlyTie);
        save(first);
        Testimonial second = testimonial();
        tick(second, late);
        save(second);

        List<AchievementTick> ticks = testimonialAchievementRepository.findVisibleTicksByTestimonialIdIn(
                List.of(second.getId(), first.getId()));

        assertThat(ticks).containsExactly(
                new AchievementTick(first.getId(), "parts_early_tie_feat", "Label of parts_early_tie_feat"),
                new AchievementTick(first.getId(), "parts_late_feat", "Label of parts_late_feat"),
                new AchievementTick(second.getId(), "parts_late_feat", "Label of parts_late_feat"));
        assertThat(testimonialAchievementRepository.findVisibleTicksByTestimonialIdIn(List.of())).isEmpty();
    }
}
