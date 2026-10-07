package com.iitm.beacon.domain.topic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Cascaded topic visibility (decision 28): a topic is visible only if it is
 * active itself and — when it belongs to a group — that group is active too.
 */
class TopicTest {

    @ParameterizedTest(name = "topic active={0}, group={1} -> visible={2}")
    @CsvSource({
        "true,  NONE,     true",
        "false, NONE,     false",
        "true,  ACTIVE,   true",
        "true,  INACTIVE, false",
        "false, ACTIVE,   false",
        "false, INACTIVE, false"
    })
    void isVisible_onlyWhenActiveAndStandaloneOrInAnActiveGroup(boolean active, String group, boolean visible) {
        TopicGroup topicGroup = group.equals("NONE")
                ? null
                : TopicGroup.builder().label("Fixture Group").displayOrder(1).active(group.equals("ACTIVE")).build();
        Topic topic = Topic.builder()
                .topicGroup(topicGroup)
                .slug("fixture_visibility")
                .label("Fixture")
                .guidingPrompt("Prompt")
                .displayOrder(1)
                .active(active)
                .build();

        assertThat(topic.isVisible()).isEqualTo(visible);
    }
}
