package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;

/**
 * NFR-CATALOG-CONFIGURABILITY, in the running context: nothing is set up
 * that could serve catalog rows from memory instead of the database — no
 * Spring {@link CacheManager}, no Hibernate second-level or query cache —
 * and a topic, topic group or achievement that was just read is not held in
 * any shared cache. {@code architecture.NoApplicationCachingTest} guards the
 * code; this guards the wiring.
 */
@SpringBootTest
class NoCatalogCacheContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Test
    void noCacheManagerBeanExists() {
        assertThat(context.getBeanNamesForType(CacheManager.class)).isEmpty();
    }

    @Test
    void hibernatesSecondLevelAndQueryCaches_areOff() {
        var options = entityManagerFactory.unwrap(SessionFactoryImplementor.class).getSessionFactoryOptions();

        assertThat(options.isSecondLevelCacheEnabled()).isFalse();
        assertThat(options.isQueryCacheEnabled()).isFalse();
    }

    @Test
    void catalogRowsJustRead_areNotHeldInTheSharedEntityCache() {
        Topic topic = topicRepository.findBySlug(Topic.GENERAL_SLUG).orElseThrow();
        TopicGroup group = topicGroupRepository.findAll().get(0);
        Achievement achievement = achievementRepository.findAll().get(0);

        assertThat(entityManagerFactory.getCache().contains(Topic.class, topic.getId())).isFalse();
        assertThat(entityManagerFactory.getCache().contains(TopicGroup.class, group.getId())).isFalse();
        assertThat(entityManagerFactory.getCache().contains(Achievement.class, achievement.getId())).isFalse();
    }
}
