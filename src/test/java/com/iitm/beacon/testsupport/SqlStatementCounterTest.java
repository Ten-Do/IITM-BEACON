package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.SqlStatementCounter.Statements;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link SqlStatementCounter}: what one action costs in SQL statements,
 * counted through Hibernate's statistics, which are on for that action only.
 */
class SqlStatementCounterTest extends AbstractRepositoryTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private TopicRepository topicRepository;

    private SqlStatementCounter counter;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        counter = new SqlStatementCounter(entityManagerFactory);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @AfterEach
    void switchStatisticsOff() {
        statistics.setStatisticsEnabled(false);
    }

    private TopicGroup newGroup() {
        return TopicGroup.builder().label("Counter group").displayOrder(500).build();
    }

    @Test
    void count_ofNothing_isZero() throws Exception {
        Statements statements = counter.count(() -> { });

        assertThat(statements.prepared()).isZero();
        assertThat(statements.withoutEntityWrites()).isZero();
    }

    @Test
    void count_ofOneQuery_isOneStatementAndOneQuery() throws Exception {
        Statements statements = counter.count(topicGroupRepository::count);

        assertThat(statements.prepared()).isEqualTo(1);
        assertThat(statements.queries()).isEqualTo(1);
        assertThat(statements.withoutEntityWrites()).isEqualTo(1);
    }

    @Test
    void count_leavesOutWhateverRanBeforeTheAction() throws Exception {
        topicGroupRepository.count();
        topicGroupRepository.findAll();

        assertThat(counter.count(() -> { }).prepared()).isZero();
    }

    @Test
    void count_ofALazyLoad_isTheQueryPlusTheFetch() throws Exception {
        Long topicId = topicRepository.findBySlug("academics_teaching").orElseThrow().getId();
        entityManager.clear();

        Statements statements = counter.count(() -> {
            Topic topic = topicRepository.findById(topicId).orElseThrow();
            assertThat(topic.getTopicGroup().getLabel()).isEqualTo("Academics");
        });

        assertThat(statements.prepared()).isEqualTo(2);
        assertThat(statements.entityLoads()).isEqualTo(2);
        assertThat(statements.entityFetches()).isEqualTo(1);
        assertThat(statements.collectionFetches()).isZero();
    }

    @Test
    void count_ofEntityWrites_countsEachRowWrittenAndLeavesThemOutOfWithoutEntityWrites() throws Exception {
        Statements inserted = counter.count(() -> topicGroupRepository.saveAndFlush(newGroup()));
        TopicGroup group = topicGroupRepository.findAll().stream()
                .filter(g -> "Counter group".equals(g.getLabel()))
                .findFirst()
                .orElseThrow();
        Statements updated = counter.count(() -> {
            group.setLabel("Renamed counter group");
            topicGroupRepository.flush();
        });
        Statements deleted = counter.count(() -> {
            topicGroupRepository.delete(group);
            topicGroupRepository.flush();
        });

        assertThat(inserted.prepared()).isEqualTo(1);
        assertThat(inserted.entityInserts()).isEqualTo(1);
        assertThat(inserted.withoutEntityWrites()).isZero();
        assertThat(updated.prepared()).isEqualTo(1);
        assertThat(updated.entityUpdates()).isEqualTo(1);
        assertThat(updated.withoutEntityWrites()).isZero();
        assertThat(deleted.prepared()).isEqualTo(1);
        assertThat(deleted.entityDeletes()).isEqualTo(1);
        assertThat(deleted.withoutEntityWrites()).isZero();
    }

    @Test
    void count_switchesStatisticsOffAgain_whenTheyWereOff() throws Exception {
        statistics.setStatisticsEnabled(false);

        counter.count(topicGroupRepository::count);

        assertThat(statistics.isStatisticsEnabled()).isFalse();
    }

    @Test
    void count_leavesStatisticsOn_whenTheyWereOnAlready() throws Exception {
        statistics.setStatisticsEnabled(true);

        counter.count(topicGroupRepository::count);

        assertThat(statistics.isStatisticsEnabled()).isTrue();
    }

    @Test
    void count_actionThrows_rethrowsIt_andSwitchesStatisticsOffAgain() {
        statistics.setStatisticsEnabled(false);
        IllegalStateException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> counter.count(() -> {
            topicGroupRepository.count();
            throw failure;
        })).isSameAs(failure);
        assertThat(statistics.isStatisticsEnabled()).isFalse();
    }
}
