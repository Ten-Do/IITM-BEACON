package com.iitm.beacon.domain.topic;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture.IdClash;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The topic lookups that load a whole set at once (BL-049): the gallery's
 * topic filter resolved to visible topic ids in one query (decisions 28,
 * 29), every topic with its group, and the topics a submission names by
 * slug, with their groups.
 */
class TopicRepositoryLookupTest extends AbstractRepositoryTest {

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private EntityManager entityManager;

    private TopicGroup activeGroup;
    private Topic visibleMember;
    private Topic inactiveMember;
    private TopicGroup inactiveGroup;
    private Topic memberOfInactiveGroup;
    private Topic visibleStandalone;
    private Topic inactiveStandalone;

    @BeforeEach
    void setUp() {
        activeGroup = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Lookup group").displayOrder(400).active(true).build());
        inactiveGroup = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Hidden lookup group").displayOrder(401).active(false).build());
        visibleMember = save("lookup_visible_member", activeGroup, true);
        inactiveMember = save("lookup_inactive_member", activeGroup, false);
        memberOfInactiveGroup = save("lookup_member_of_hidden_group", inactiveGroup, true);
        visibleStandalone = save("lookup_visible_standalone", null, true);
        inactiveStandalone = save("lookup_inactive_standalone", null, false);
    }

    private Topic save(String slug, TopicGroup group, boolean active) {
        return topicRepository.saveAndFlush(CatalogVisibilityFixture.topic(slug, slug, group, 1, active));
    }

    private List<Long> visibleIds(List<Long> groupIds, List<Long> topicIds) {
        return topicRepository.findVisibleIdsByTopicGroupIdInOrIdIn(groupIds, topicIds);
    }

    // -- findVisibleIdsByTopicGroupIdInOrIdIn --

    @Test
    void visibleIds_ofAnActiveGroup_areItsActiveMembersOnly() {
        assertThat(visibleIds(List.of(activeGroup.getId()), List.of())).containsExactly(visibleMember.getId());
    }

    @Test
    void visibleIds_ofAnInactiveGroup_areNone_evenItsActiveMembers() {
        assertThat(visibleIds(List.of(inactiveGroup.getId()), List.of())).isEmpty();
    }

    @Test
    void visibleIds_ofTopics_keepOnlyVisibleOnes() {
        List<Long> found = visibleIds(List.of(), List.of(
                visibleMember.getId(), inactiveMember.getId(), memberOfInactiveGroup.getId(),
                visibleStandalone.getId(), inactiveStandalone.getId()));

        assertThat(found).containsExactlyInAnyOrder(visibleMember.getId(), visibleStandalone.getId());
    }

    @Test
    void visibleIds_ofGroupsAndTopicsTogether_areTheUnion_eachIdOnce() {
        List<Long> found = visibleIds(
                List.of(activeGroup.getId(), inactiveGroup.getId()),
                List.of(visibleMember.getId(), visibleStandalone.getId()));

        assertThat(found).containsExactlyInAnyOrder(visibleMember.getId(), visibleStandalone.getId());
    }

    @Test
    void visibleIds_ofUnknownIds_areNone() {
        assertThat(visibleIds(List.of(Long.MAX_VALUE), List.of(Long.MAX_VALUE, -1L))).isEmpty();
    }

    @Test
    void visibleIds_ofNoIdsAtAll_areNone() {
        assertThat(visibleIds(List.of(), List.of())).isEmpty();
    }

    /** Group and topic ids come from separate sequences (decision 29): a group id never names a topic. */
    @Test
    void visibleIds_groupIdEqualToAStandaloneTopicsId_namesTheGroupOnly() {
        IdClash clash = CatalogVisibilityFixture.forceIdClash(topicGroupRepository, topicRepository);

        assertThat(visibleIds(List.of(clash.sharedId()), List.of())).containsExactly(clash.member().getId());
        assertThat(visibleIds(List.of(), List.of(clash.sharedId()))).containsExactly(clash.standalone().getId());
    }

    // -- findAllWithTopicGroupOrderById --

    @Test
    void allWithTopicGroup_isEveryTopicInIdOrder_inactiveOnesIncluded_withItsGroupLoaded() {
        entityManager.clear();

        List<Topic> all = topicRepository.findAllWithTopicGroupOrderById();

        assertThat(all).hasSize((int) topicRepository.count());
        assertThat(all).isSortedAccordingTo(Comparator.comparing(Topic::getId));
        assertThat(all).extracting(Topic::getSlug).contains(
                inactiveMember.getSlug(), memberOfInactiveGroup.getSlug(), inactiveStandalone.getSlug());
        assertThat(all).filteredOn(topic -> topic.getTopicGroup() != null)
                .isNotEmpty()
                .allMatch(topic -> Hibernate.isInitialized(topic.getTopicGroup()));
        Topic member = all.stream().filter(topic -> topic.getId().equals(memberOfInactiveGroup.getId()))
                .findFirst().orElseThrow();
        assertThat(member.getTopicGroup().isActive()).isFalse();
    }

    // -- findWithTopicGroupBySlugIn --

    @Test
    void withTopicGroupBySlugIn_findsExactSlugsOnly_inactiveOnesIncluded_withTheirGroupsLoaded() {
        entityManager.clear();

        List<Topic> found = topicRepository.findWithTopicGroupBySlugIn(List.of(
                visibleMember.getSlug(), memberOfInactiveGroup.getSlug(), inactiveStandalone.getSlug(),
                "LOOKUP_VISIBLE_STANDALONE", "no_such_topic"));

        assertThat(found).extracting(Topic::getSlug).containsExactlyInAnyOrder(
                visibleMember.getSlug(), memberOfInactiveGroup.getSlug(), inactiveStandalone.getSlug());
        assertThat(found).filteredOn(topic -> topic.getTopicGroup() != null)
                .hasSize(2)
                .allMatch(topic -> Hibernate.isInitialized(topic.getTopicGroup()));
    }

    @Test
    void withTopicGroupBySlugIn_noSlugs_findsNothing() {
        assertThat(topicRepository.findWithTopicGroupBySlugIn(List.of())).isEmpty();
    }
}
