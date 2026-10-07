package com.iitm.beacon.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cascading visibility in the public gallery (decision 28): a section whose
 * topic is inactive, or sits in an inactive group, and a tick of an inactive
 * achievement are hidden from the article, the card preview, keyword search
 * and the topic filter — and come back unchanged once reactivated. Also the
 * split topic filter (decision 29): {@code groupIds} and {@code topicIds}.
 */
@SpringBootTest
@Transactional
class GalleryServiceVisibilityTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 100);

    @Autowired
    private GalleryService galleryService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @Autowired
    private EntityManager entityManager;

    private CatalogVisibilityFixture catalog;

    @BeforeEach
    void createCatalog() {
        catalog = CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
    }

    private Testimonial approved(String email) {
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private static TestimonialSection addSection(Testimonial t, Topic topic, String answer, String... photoFiles) {
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topic)
                .answerText(answer)
                .modified(false)
                .build();
        for (int i = 0; i < photoFiles.length; i++) {
            section.getPhotos().add(Photo.builder()
                    .section(section)
                    .filePath(photoFiles[i])
                    .thumbnailPath(photoFiles[i].replace(".webp", "-thumb.webp"))
                    .displayOrder(i)
                    .build());
        }
        t.getSections().add(section);
        return section;
    }

    private static void addAchievement(Testimonial t, Achievement achievement) {
        t.getAchievements().add(TestimonialAchievement.builder().testimonial(t).achievement(achievement).build());
    }

    private Topic general() {
        return topicRepository.findBySlug("general").orElseThrow();
    }

    /** A testimonial with one section of each visibility kind, plus a visible and a hidden achievement tick. */
    private Testimonial mixedTestimonial(String email) {
        Testimonial t = approved(email);
        addSection(t, catalog.inactiveTopic(), "Inactive topic words.", "inactive.webp");
        addSection(t, catalog.topicInInactiveGroup(), "Hidden group words.", "hidden-group.webp");
        addSection(t, catalog.visibleTopic(), "Visible topic words.", "visible.webp");
        addAchievement(t, catalog.visibleAchievement());
        addAchievement(t, catalog.inactiveAchievement());
        return testimonialRepository.saveAndFlush(t);
    }

    private void reactivateAll() {
        catalog.reactivateAll(topicGroupRepository, topicRepository, achievementRepository);
        entityManager.flush();
        entityManager.clear();
    }

    private List<Long> browseIds(List<Long> groupIds, List<Long> topicIds, String q) {
        return galleryService.browse(null, groupIds, topicIds, q, FIRST_PAGE).content().stream()
                .map(TestimonialCardDto::id)
                .toList();
    }

    private TestimonialCardDto card(Long id) {
        return galleryService.browse(null, null, null, null, FIRST_PAGE).content().stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    // -- article (getDetail) --

    @Test
    void getDetail_showsOnlySectionsOfVisibleTopics_andOnlyVisibleAchievements() {
        Testimonial t = mixedTestimonial("vis-detail@example.com");

        TestimonialDetailDto detail = galleryService.getDetail(t.getId());

        assertThat(detail.sections())
                .extracting(TestimonialSectionViewDto::topicSlug)
                .containsExactly(CatalogVisibilityFixture.VISIBLE_TOPIC_SLUG);
        assertThat(detail.sections().get(0).photos())
                .extracting(PhotoRefDto::url)
                .containsExactly("/uploads/visible.webp");
        assertThat(detail.achievements()).containsExactly(CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG);
    }

    @Test
    void getDetail_afterReactivation_showsTheHiddenSectionsAndAchievementAgainUnchanged() {
        Testimonial t = mixedTestimonial("vis-detail-reactivate@example.com");
        reactivateAll();

        TestimonialDetailDto detail = galleryService.getDetail(t.getId());

        assertThat(detail.sections())
                .extracting(TestimonialSectionViewDto::topicSlug, TestimonialSectionViewDto::answer)
                .containsExactlyInAnyOrder(
                        tuple(CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG, "Inactive topic words."),
                        tuple(CatalogVisibilityFixture.TOPIC_IN_INACTIVE_GROUP_SLUG, "Hidden group words."),
                        tuple(CatalogVisibilityFixture.VISIBLE_TOPIC_SLUG, "Visible topic words."));
        assertThat(detail.sections())
                .flatExtracting(TestimonialSectionViewDto::photos)
                .extracting(PhotoRefDto::url)
                .containsExactlyInAnyOrder(
                        "/uploads/inactive.webp", "/uploads/hidden-group.webp", "/uploads/visible.webp");
        assertThat(detail.achievements())
                .containsExactlyInAnyOrder(
                        CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG,
                        CatalogVisibilityFixture.INACTIVE_ACHIEVEMENT_SLUG);
    }

    @Test
    void getDetail_everySectionHidden_stillReturnsTheApprovedArticleWithoutSections() {
        Testimonial t = approved("vis-detail-all-hidden@example.com");
        addSection(t, catalog.inactiveTopic(), "Only hidden words.");
        addSection(t, catalog.topicInInactiveGroup(), "More hidden words.");
        addAchievement(t, catalog.inactiveAchievement());
        testimonialRepository.saveAndFlush(t);

        TestimonialDetailDto detail = galleryService.getDetail(t.getId());

        assertThat(detail.sections()).isEmpty();
        assertThat(detail.achievements()).isEmpty();
        assertThat(detail.status()).isEqualTo("APPROVED");
        assertThat(testimonialRepository.findById(t.getId()).orElseThrow().getSections()).hasSize(2);
    }

    @Test
    void getDetail_topicDeactivatedInAnOtherwiseActiveGroup_hidesOnlyThatTopicsSection() {
        Topic sibling = topicRepository.saveAndFlush(
                CatalogVisibilityFixture.topic("vis_sibling", "Sibling", catalog.activeGroup(), 2, false));
        Testimonial t = approved("vis-detail-sibling@example.com");
        addSection(t, catalog.visibleTopic(), "Visible sibling words.");
        addSection(t, sibling, "Inactive sibling words.");
        testimonialRepository.saveAndFlush(t);

        assertThat(galleryService.getDetail(t.getId()).sections())
                .extracting(TestimonialSectionViewDto::answer)
                .containsExactly("Visible sibling words.");
    }

    // -- card preview (browse) --

    @Test
    void browse_cardPreviewAndCover_comeFromTheFirstVisibleSection_notAHiddenOneOrderedFirst() {
        Testimonial t = approved("vis-card-first-visible@example.com");
        // Both hidden topics sort ahead of "general" (display order 0 vs 17) and carry photos.
        addSection(t, catalog.inactiveTopic(), "Inactive goes first.", "inactive.webp");
        addSection(t, catalog.topicInInactiveGroup(), "Hidden group goes first.", "hidden-group.webp");
        addSection(t, general(), "General is the first visible one.");
        testimonialRepository.saveAndFlush(t);

        TestimonialCardDto card = card(t.getId());

        assertThat(card.previewText()).isEqualTo("General is the first visible one.");
        assertThat(card.thumbnailUrl()).isNull();
    }

    @Test
    void browse_everySectionHidden_cardIsStillListed_withoutExcerptOrCover() {
        Testimonial t = approved("vis-card-all-hidden@example.com");
        addSection(t, catalog.inactiveTopic(), "Hidden words.", "inactive.webp");
        addSection(t, catalog.topicInInactiveGroup(), "Hidden group words.", "hidden-group.webp");
        testimonialRepository.saveAndFlush(t);

        TestimonialCardDto card = card(t.getId());

        assertThat(card.previewText()).isEmpty();
        assertThat(card.thumbnailUrl()).isNull();
    }

    // -- keyword search --

    @Test
    void search_textOnlyInASectionOfAnInactiveTopic_doesNotMatch() {
        Testimonial t = approved("vis-search-inactive@example.com");
        addSection(t, catalog.inactiveTopic(), "A secret quokka sighting.");
        addSection(t, general(), "Ordinary words.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(null, null, "quokka")).doesNotContain(t.getId());
        assertThat(browseIds(null, null, "ordinary")).contains(t.getId());
    }

    @Test
    void search_textOnlyInASectionOfATopicInAnInactiveGroup_doesNotMatch() {
        Testimonial t = approved("vis-search-hidden-group@example.com");
        addSection(t, catalog.topicInInactiveGroup(), "A secret pangolin sighting.");
        addSection(t, general(), "Ordinary words.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(null, null, "pangolin")).doesNotContain(t.getId());
    }

    @Test
    void search_textInASectionOfAVisibleGroupedTopic_matches() {
        Testimonial t = approved("vis-search-visible@example.com");
        addSection(t, catalog.visibleTopic(), "A visible axolotl sighting.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(null, null, "axolotl")).containsExactly(t.getId());
    }

    @Test
    void search_nameMatch_stillFindsATestimonialWhoseSectionsAreAllHidden() {
        Testimonial t = approved("vis-search-name@example.com");
        t.setLastName("Quaternion");
        addSection(t, catalog.inactiveTopic(), "Hidden words.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(null, null, "quaternion")).containsExactly(t.getId());
    }

    // -- topic filter: groupIds / topicIds (decision 29) --

    @Test
    void filter_groupIds_expandsToTheGroupsVisibleMemberTopicsOnly() {
        Topic inactiveMember = topicRepository.saveAndFlush(CatalogVisibilityFixture.topic(
                "vis_inactive_member", "Inactive member", catalog.activeGroup(), 2, false));
        Testimonial viaVisibleMember = approved("vis-filter-group-visible@example.com");
        addSection(viaVisibleMember, catalog.visibleTopic(), "Visible member.");
        testimonialRepository.saveAndFlush(viaVisibleMember);
        Testimonial viaInactiveMember = approved("vis-filter-group-inactive@example.com");
        addSection(viaInactiveMember, inactiveMember, "Inactive member.");
        addSection(viaInactiveMember, general(), "General.");
        testimonialRepository.saveAndFlush(viaInactiveMember);

        assertThat(browseIds(List.of(catalog.activeGroup().getId()), null, null))
                .containsExactly(viaVisibleMember.getId());
    }

    @Test
    void filter_groupIdsOfAnInactiveGroup_matchesNothing_evenWithActiveMemberSections() {
        Testimonial t = approved("vis-filter-inactive-group@example.com");
        addSection(t, catalog.topicInInactiveGroup(), "Active topic, inactive group.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(List.of(catalog.inactiveGroup().getId()), null, null)).isEmpty();
    }

    @Test
    void filter_groupIdsOfAnActiveGroupWhoseTopicsAreAllInactive_matchesNothing() {
        TopicGroup group = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("All inactive").displayOrder(300).active(true).build());
        Topic member = topicRepository.saveAndFlush(
                CatalogVisibilityFixture.topic("vis_all_inactive_member", "Member", group, 1, false));
        Testimonial t = approved("vis-filter-all-inactive@example.com");
        addSection(t, member, "Inactive member words.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(List.of(group.getId()), null, null)).isEmpty();
    }

    @Test
    void filter_topicIdsOfAnInactiveTopic_matchesNothing() {
        Testimonial t = approved("vis-filter-inactive-topic@example.com");
        addSection(t, catalog.inactiveTopic(), "Inactive topic words.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(null, List.of(catalog.inactiveTopic().getId()), null)).isEmpty();
    }

    @Test
    void filter_topicIdsOfAnActiveTopicInAnInactiveGroup_matchesNothing() {
        Testimonial t = approved("vis-filter-topic-hidden-group@example.com");
        addSection(t, catalog.topicInInactiveGroup(), "Words.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(null, List.of(catalog.topicInInactiveGroup().getId()), null)).isEmpty();
    }

    @Test
    void filter_unknownIdsOnly_inEitherParameter_matchNothing() {
        Testimonial t = approved("vis-filter-unknown@example.com");
        addSection(t, general(), "General.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(List.of(999_999L), null, null)).isEmpty();
        assertThat(browseIds(null, List.of(999_999L), null)).isEmpty();
        assertThat(browseIds(List.of(999_999L), List.of(999_998L), null)).isEmpty();
    }

    @Test
    void filter_emptyOrNullOnlyLists_areNoTopicFilterAtAll() {
        Testimonial t = approved("vis-filter-empty-lists@example.com");
        addSection(t, general(), "General.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(List.of(), List.of(), null)).contains(t.getId());
        assertThat(browseIds(Arrays.asList((Long) null), Arrays.asList((Long) null), null)).contains(t.getId());
    }

    @Test
    void filter_groupIdsAndTopicIds_areCombinedWithOr() {
        Testimonial viaGroup = approved("vis-filter-or-group@example.com");
        addSection(viaGroup, catalog.visibleTopic(), "Group member.");
        testimonialRepository.saveAndFlush(viaGroup);
        Testimonial viaTopic = approved("vis-filter-or-topic@example.com");
        addSection(viaTopic, general(), "General.");
        testimonialRepository.saveAndFlush(viaTopic);
        Testimonial neither = approved("vis-filter-or-neither@example.com");
        addSection(neither, topicRepository.findBySlug("networking").orElseThrow(), "Networking.");
        testimonialRepository.saveAndFlush(neither);

        assertThat(browseIds(List.of(catalog.activeGroup().getId()), List.of(general().getId()), null))
                .containsExactlyInAnyOrder(viaGroup.getId(), viaTopic.getId());
    }

    @Test
    void filter_aMatchingIdNextToAnInvisibleOne_stillMatches() {
        Testimonial t = approved("vis-filter-mixed@example.com");
        addSection(t, general(), "General.");
        testimonialRepository.saveAndFlush(t);

        assertThat(browseIds(
                        List.of(catalog.inactiveGroup().getId()),
                        List.of(catalog.inactiveTopic().getId(), general().getId()),
                        null))
                .containsExactly(t.getId());
    }

    @Test
    void filter_groupAndStandaloneTopicSharingOneId_eachParameterFiltersByItsOwnTable() {
        CatalogVisibilityFixture.IdClash clash =
                CatalogVisibilityFixture.forceIdClash(topicGroupRepository, topicRepository);
        Testimonial viaTopic = approved("vis-filter-clash-topic@example.com");
        addSection(viaTopic, clash.standalone(), "Standalone.");
        testimonialRepository.saveAndFlush(viaTopic);
        Testimonial viaGroup = approved("vis-filter-clash-group@example.com");
        addSection(viaGroup, clash.member(), "Group member.");
        testimonialRepository.saveAndFlush(viaGroup);
        Long sharedId = clash.sharedId();

        assertThat(browseIds(null, List.of(sharedId), null)).containsExactly(viaTopic.getId());
        assertThat(browseIds(List.of(sharedId), null, null)).containsExactly(viaGroup.getId());
    }

    // -- filter catalog --

    @Test
    void topicCatalog_offersOnlyVisibleTopicsWithApprovedSections() {
        mixedTestimonial("vis-catalog@example.com");

        List<TopicCatalogEntryDto> catalogEntries = galleryService.listTopicCatalogWithApproved();

        assertThat(catalogEntries)
                .filteredOn(e -> "GROUP".equals(e.kind()) && catalog.activeGroup().getId().equals(e.groupId()))
                .singleElement()
                .satisfies(e -> assertThat(e.subtopics())
                        .extracting(TopicPickDto::slug)
                        .containsExactly(CatalogVisibilityFixture.VISIBLE_TOPIC_SLUG));
        assertThat(catalogEntries)
                .noneMatch(e -> catalog.inactiveGroup().getId().equals(e.groupId()))
                .noneMatch(e -> catalog.inactiveTopic().getId().equals(e.topicId()));
    }

}
