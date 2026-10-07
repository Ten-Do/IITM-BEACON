package com.iitm.beacon.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.iitm.beacon.analytics.TopicCountDto.Kind;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AnalyticsService#summary()} — testimonials per top-level topic entry
 * and ticks per achievement (decision 30): what counts, what the catalog's
 * visibility rule (decision 28) leaves out, and the order of the lists.
 */
@SpringBootTest
@Transactional
class AnalyticsServiceCatalogCountsTest {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private AnalyticsTestData data;

    @BeforeEach
    void setUp() {
        data = new AnalyticsTestData(
                testimonialRepository,
                countryRepository,
                topicRepository,
                achievementRepository,
                emailLookupHashService);
    }

    private List<TopicCountDto> topicCounts() {
        return analyticsService.summary().testimonialCountsByTopic();
    }

    private List<AchievementCountDto> achievementCounts() {
        return analyticsService.summary().achievementCounts();
    }

    private TopicCountDto group(String label, long count) {
        TopicGroup group = topicGroupRepository.findAll().stream()
                .filter(g -> g.getLabel().equals(label))
                .findFirst()
                .orElseThrow();
        return new TopicCountDto(Kind.GROUP, group.getId(), label, count);
    }

    private TopicCountDto standalone(String slug, long count) {
        Topic topic = data.topic(slug);
        return new TopicCountDto(Kind.STANDALONE, topic.getId(), topic.getLabel(), count);
    }

    private static TopicCountDto entry(TopicGroup group, long count) {
        return new TopicCountDto(Kind.GROUP, group.getId(), group.getLabel(), count);
    }

    private static TopicCountDto entry(Topic standaloneTopic, long count) {
        return new TopicCountDto(Kind.STANDALONE, standaloneTopic.getId(), standaloneTopic.getLabel(), count);
    }

    private Topic standaloneTopic(String slug, String label, int displayOrder) {
        return topicRepository.saveAndFlush(CatalogVisibilityFixture.topic(slug, label, null, displayOrder, true));
    }

    private TopicGroup groupWithOneTopic(String label, int displayOrder, String memberSlug) {
        TopicGroup group = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label(label).displayOrder(displayOrder).active(true).build());
        topicRepository.saveAndFlush(CatalogVisibilityFixture.topic(memberSlug, "Member of " + label, group, 1, true));
        return group;
    }

    private Achievement newAchievement(String slug, String label, int displayOrder) {
        return achievementRepository.saveAndFlush(
                Achievement.builder().slug(slug).label(label).displayOrder(displayOrder).build());
    }

    private static AchievementCountDto ticks(Achievement achievement, long count) {
        return new AchievementCountDto(
                new AchievementDto(
                        achievement.getId(),
                        achievement.getSlug(),
                        achievement.getLabel(),
                        achievement.getDisplayOrder(),
                        true),
                count);
    }

    // ---- what a topic entry counts ----

    @Test
    void group_countsATestimonialOnce_howeverManyOfItsSubtopicsItFills() {
        data.approved().sections("academics_teaching", "academics_difficulty", "academics_style").save();
        data.approved().sections("academics_teaching").save();

        assertThat(topicCounts()).containsExactly(group("Academics", 2));
    }

    @Test
    void standaloneTopic_countsTheTestimonialsWithASectionInIt() {
        data.approved().sections("romance").save();
        data.approved().sections("romance").save();
        data.approved().sections("general").save();

        assertThat(topicCounts()).containsExactly(standalone("romance", 2), standalone("general", 1));
    }

    @Test
    void standaloneTopic_twoSectionsOfItInOneTestimonial_countOnce() {
        data.approved().sections("romance", "romance").save();

        assertThat(topicCounts()).containsExactly(standalone("romance", 1));
    }

    @Test
    void oneTestimonial_countsOnceInEveryEntryItHasASectionIn() {
        data.approved().sections("academics_teaching", "travel_did", "travel_ease", "general").save();

        assertThat(topicCounts()).containsExactly(group("Academics", 1), group("Travel", 1), standalone("general", 1));
    }

    @Test
    void groupAndStandaloneTopicSharingAnId_areSeparateEntries_toldApartByKind() {
        CatalogVisibilityFixture.IdClash clash =
                CatalogVisibilityFixture.forceIdClash(topicGroupRepository, topicRepository);
        data.approved().sections(clash.member()).save();
        data.approved().sections(clash.member()).save();
        data.approved().sections(clash.standalone()).save();

        assertThat(topicCounts()).containsExactly(entry(clash.group(), 2), entry(clash.standalone(), 1));
    }

    // ---- visibility (decision 28) ----

    @Test
    void inactiveStandaloneTopic_isLeftOut() {
        CatalogVisibilityFixture catalog =
                CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
        data.approved().sections(catalog.inactiveTopic()).save();

        assertThat(topicCounts()).isEmpty();
    }

    @Test
    void activeTopicInAnInactiveGroup_leavesOutTheGroup_andIsNoStandaloneEntryEither() {
        CatalogVisibilityFixture catalog =
                CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
        data.approved().sections(catalog.topicInInactiveGroup()).save();

        assertThat(topicCounts()).isEmpty();
    }

    @Test
    void activeGroup_whoseOnlyApprovedSectionsAreInInactiveTopics_isLeftOut() {
        Topic teaching = data.topic("academics_teaching");
        teaching.setActive(false);
        topicRepository.saveAndFlush(teaching);
        data.approved().sections("academics_teaching").save();

        assertThat(topicCounts()).isEmpty();
    }

    @Test
    void activeGroup_countsOnlyTestimonialsWithASectionInOneOfItsActiveTopics() {
        Topic teaching = data.topic("academics_teaching");
        teaching.setActive(false);
        topicRepository.saveAndFlush(teaching);
        data.approved().sections("academics_teaching").save();
        data.approved().sections("academics_difficulty").save();
        data.approved().sections("academics_teaching", "academics_difficulty").save();

        assertThat(topicCounts()).containsExactly(group("Academics", 2));
    }

    @Test
    void activeTopicInAnActiveGroup_countsOnlyForItsGroup_neverAsAStandaloneEntry() {
        CatalogVisibilityFixture catalog =
                CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
        data.approved().sections(catalog.visibleTopic()).save();

        assertThat(topicCounts()).containsExactly(entry(catalog.activeGroup(), 1));
    }

    // ---- topic order ----

    @Test
    void topicEntries_higherCountFirst_whateverTheDisplayOrder() {
        data.approved().sections("general").save();
        data.approved().sections("general").save();
        data.approved().sections("academics_teaching").save();

        assertThat(topicCounts()).containsExactly(standalone("general", 2), group("Academics", 1));
    }

    @Test
    void topicEntries_tiedCounts_goByTheGroupsAndStandaloneTopicsSharedTopLevelDisplayOrder() {
        // Top-level display orders: Academics 1, Travel 3, new_friendships 7, romance 8,
        // Practicalities 14 (its practical_cost is 1 within the group), general 17.
        data.approved().sections("general").save();
        data.approved().sections("practical_cost").save();
        data.approved().sections("romance").save();
        data.approved().sections("travel_did").save();
        data.approved().sections("new_friendships").save();
        data.approved().sections("academics_library").save();

        assertThat(topicCounts()).containsExactly(
                group("Academics", 1),
                group("Travel", 1),
                standalone("new_friendships", 1),
                standalone("romance", 1),
                group("Practicalities", 1),
                standalone("general", 1));
    }

    @Test
    void topicEntries_tiedCountAndDisplayOrder_putTheGroupBeforeTheStandaloneTopic() {
        CatalogVisibilityFixture.IdClash clash =
                CatalogVisibilityFixture.forceIdClash(topicGroupRepository, topicRepository);
        Topic standalone = clash.standalone();
        standalone.setDisplayOrder(clash.group().getDisplayOrder());
        topicRepository.saveAndFlush(standalone);
        data.approved().sections(standalone).save();
        data.approved().sections(clash.member()).save();

        assertThat(topicCounts()).containsExactly(entry(clash.group(), 1), entry(standalone, 1));
    }

    @Test
    void topicEntries_tiedCountDisplayOrderAndKind_goByIdAscending_notByLabel() {
        Topic olderStandalone = standaloneTopic("tie_standalone_older", "Zeta standalone", 60);
        Topic newerStandalone = standaloneTopic("tie_standalone_newer", "Alpha standalone", 60);
        TopicGroup olderGroup = groupWithOneTopic("Zeta group", 61, "tie_group_older_member");
        TopicGroup newerGroup = groupWithOneTopic("Alpha group", 61, "tie_group_newer_member");
        data.approved().sections(newerStandalone).save();
        data.approved().sections(olderStandalone).save();
        data.approved().sections("tie_group_newer_member").save();
        data.approved().sections("tie_group_older_member").save();

        assertThat(topicCounts()).containsExactly(
                entry(olderStandalone, 1), entry(newerStandalone, 1), entry(olderGroup, 1), entry(newerGroup, 1));
    }

    // ---- achievements ----

    @Test
    void achievements_countTheTicksOnApprovedTestimonials_withTheAchievementsFields() {
        data.approved().ticks("made_new_friends", "traveled_within_india").save();
        data.approved().ticks("made_new_friends").save();

        assertThat(achievementCounts()).containsExactly(
                ticks(data.achievement("made_new_friends"), 2), ticks(data.achievement("traveled_within_india"), 1));
    }

    @Test
    void achievements_ticksOfAnInactiveAchievement_areLeftOut() {
        CatalogVisibilityFixture catalog =
                CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
        data.approved().ticks(catalog.inactiveAchievement(), catalog.visibleAchievement()).save();
        data.approved().ticks(catalog.inactiveAchievement()).save();

        assertThat(achievementCounts()).containsExactly(ticks(catalog.visibleAchievement(), 1));
    }

    @Test
    void achievements_noTickOnAnyApprovedTestimonial_leavesTheListEmpty() {
        data.approved().sections("general").save();
        data.pending().ticks("made_new_friends").save();

        assertThat(achievementCounts()).isEmpty();
    }

    @Test
    void achievements_byCountDescending_thenByDisplayOrder_thenById_notByLabel() {
        // Seeded display orders: made_new_friends 1, found_love_or_relationship 2, missed_home 16.
        Achievement firstInOrder = newAchievement("tie_order_zero", "Zulu feat", 0);
        Achievement sameOrderAsMadeNewFriends = newAchievement("tie_order_one", "Aardvark feat", 1);
        data.approved().ticks("missed_home", "found_love_or_relationship").save();
        data.approved().ticks("missed_home", "made_new_friends").save();
        data.approved().ticks(sameOrderAsMadeNewFriends, firstInOrder).save();

        assertThat(achievementCounts())
                .extracting(entry -> entry.achievement().slug(), AchievementCountDto::count)
                .containsExactly(
                        tuple("missed_home", 2L),
                        tuple("tie_order_zero", 1L),
                        tuple("made_new_friends", 1L),
                        tuple("tie_order_one", 1L),
                        tuple("found_love_or_relationship", 1L));
    }
}
