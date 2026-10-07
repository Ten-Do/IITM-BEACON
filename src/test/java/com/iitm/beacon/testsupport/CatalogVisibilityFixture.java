package com.iitm.beacon.testsupport;

import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;

/**
 * Catalog rows for decision 28's cascading-visibility tests, one of each
 * kind: a visible topic (active, in an active group), an inactive
 * standalone topic, an active topic inside an inactive group, and a visible
 * and an inactive achievement. Both hidden topics sort ahead of every
 * seeded entry (display order 0), so a test can tell "the first section
 * shown" from "the first section stored".
 */
public record CatalogVisibilityFixture(
        TopicGroup activeGroup,
        Topic visibleTopic,
        Topic inactiveTopic,
        TopicGroup inactiveGroup,
        Topic topicInInactiveGroup,
        Achievement visibleAchievement,
        Achievement inactiveAchievement) {

    public static final String VISIBLE_TOPIC_SLUG = "vis_visible_topic";
    public static final String INACTIVE_TOPIC_SLUG = "vis_inactive_topic";
    public static final String TOPIC_IN_INACTIVE_GROUP_SLUG = "vis_topic_in_inactive_group";
    public static final String VISIBLE_ACHIEVEMENT_SLUG = "vis_visible_feat";
    public static final String INACTIVE_ACHIEVEMENT_SLUG = "vis_inactive_feat";

    public static CatalogVisibilityFixture create(
            TopicGroupRepository topicGroupRepository,
            TopicRepository topicRepository,
            AchievementRepository achievementRepository) {
        TopicGroup activeGroup = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Visible group").displayOrder(200).active(true).build());
        TopicGroup inactiveGroup = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("Hidden group").displayOrder(0).active(false).build());
        Topic visibleTopic =
                topicRepository.saveAndFlush(topic(VISIBLE_TOPIC_SLUG, "Visible topic", activeGroup, 1, true));
        Topic inactiveTopic =
                topicRepository.saveAndFlush(topic(INACTIVE_TOPIC_SLUG, "Inactive topic", null, 0, false));
        Topic topicInInactiveGroup = topicRepository.saveAndFlush(
                topic(TOPIC_IN_INACTIVE_GROUP_SLUG, "Topic in hidden group", inactiveGroup, 1, true));
        Achievement visibleAchievement = achievementRepository.saveAndFlush(
                Achievement.builder().slug(VISIBLE_ACHIEVEMENT_SLUG).label("Visible feat").displayOrder(200).build());
        Achievement inactiveAchievement = achievementRepository.saveAndFlush(Achievement.builder()
                .slug(INACTIVE_ACHIEVEMENT_SLUG)
                .label("Hidden feat")
                .displayOrder(201)
                .active(false)
                .build());
        return new CatalogVisibilityFixture(
                activeGroup,
                visibleTopic,
                inactiveTopic,
                inactiveGroup,
                topicInInactiveGroup,
                visibleAchievement,
                inactiveAchievement);
    }

    /** A further topic, standalone when {@code group} is null. */
    public static Topic topic(String slug, String label, TopicGroup group, int displayOrder, boolean active) {
        return Topic.builder()
                .slug(slug)
                .label(label)
                .guidingPrompt("Tell us about " + label + ".")
                .topicGroup(group)
                .displayOrder(displayOrder)
                .active(active)
                .build();
    }

    /**
     * An active topic group and an active standalone topic that share one
     * id — possible because groups and topics have separate id sequences
     * (decision 29). Inserts rows into whichever table is behind until the
     * two sequences meet.
     */
    public static IdClash forceIdClash(TopicGroupRepository topicGroupRepository, TopicRepository topicRepository) {
        int suffix = 0;
        Topic standalone = topicRepository.saveAndFlush(topic("clash_topic_0", "Clash topic", null, 50, true));
        TopicGroup group = topicGroupRepository.saveAndFlush(clashGroup(0));
        while (!group.getId().equals(standalone.getId())) {
            suffix++;
            if (suffix > 1000) {
                throw new IllegalStateException("Topic and topic-group ids never met");
            }
            if (group.getId() < standalone.getId()) {
                group = topicGroupRepository.saveAndFlush(clashGroup(suffix));
            } else {
                standalone = topicRepository.saveAndFlush(
                        topic("clash_topic_" + suffix, "Clash topic", null, 50, true));
            }
        }
        Topic member = topicRepository.saveAndFlush(topic("clash_member", "Clash member", group, 1, true));
        return new IdClash(group, member, standalone);
    }

    private static TopicGroup clashGroup(int suffix) {
        return TopicGroup.builder().label("Clash group " + suffix).displayOrder(51).active(true).build();
    }

    /** A group (with one visible {@code member} topic) and a standalone topic whose ids are equal. */
    public record IdClash(TopicGroup group, Topic member, Topic standalone) {

        public Long sharedId() {
            return group.getId();
        }
    }

    /** Reactivates the inactive topic, the inactive group and the inactive achievement. */
    public void reactivateAll(
            TopicGroupRepository topicGroupRepository,
            TopicRepository topicRepository,
            AchievementRepository achievementRepository) {
        setHiddenOnesActive(true, topicGroupRepository, topicRepository, achievementRepository);
    }

    /** Deactivates the inactive topic, the inactive group and the inactive achievement again. */
    public void deactivateAgain(
            TopicGroupRepository topicGroupRepository,
            TopicRepository topicRepository,
            AchievementRepository achievementRepository) {
        setHiddenOnesActive(false, topicGroupRepository, topicRepository, achievementRepository);
    }

    private void setHiddenOnesActive(
            boolean active,
            TopicGroupRepository topicGroupRepository,
            TopicRepository topicRepository,
            AchievementRepository achievementRepository) {
        inactiveTopic.setActive(active);
        inactiveGroup.setActive(active);
        inactiveAchievement.setActive(active);
        topicRepository.saveAndFlush(inactiveTopic);
        topicGroupRepository.saveAndFlush(inactiveGroup);
        achievementRepository.saveAndFlush(inactiveAchievement);
    }
}
