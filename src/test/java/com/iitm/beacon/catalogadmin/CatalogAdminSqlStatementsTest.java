package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.SampleTestimonials;
import com.iitm.beacon.testsupport.SampleTestimonials.Size;
import com.iitm.beacon.testsupport.SqlStatementCounter;
import com.iitm.beacon.testsupport.SqlStatementCounter.Statements;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * How many SQL statements the catalog's cascading writes cost (BL-049,
 * decision 28): deleting a topic, a group or an achievement, and making a
 * topic or a group visible again, load what they change in the same
 * statements for one small testimonial as for many large ones — only the
 * row writes themselves (a delete per section, photo, tag or tick; an
 * update per testimonial sent back to pending) grow with them.
 */
@SpringBootTest
@Transactional
class CatalogAdminSqlStatementsTest {

    @Autowired
    private CatalogAdminService service;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TestimonialRepository testimonialRepository;

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
    private EmailLookupHashService emailLookupHashService;

    private SqlStatementCounter counter;
    private SampleTestimonials samples;
    private CatalogFixtures fixtures;

    @BeforeEach
    void setUp() {
        counter = new SqlStatementCounter(entityManagerFactory);
        samples = new SampleTestimonials(testimonialRepository, topicRepository, countryRepository,
                contactTypeRepository, achievementRepository, emailLookupHashService);
        fixtures = new CatalogFixtures(
                topicGroupRepository, topicRepository, achievementRepository, testimonialRepository,
                countryRepository);
    }

    /**
     * What {@code action} costs, starting from an empty persistence context
     * and ending with a flush, as a request's transaction does.
     */
    private Statements count(SqlStatementCounter.Action action) throws Exception {
        entityManager.flush();
        entityManager.clear();
        return counter.count(() -> {
            action.run();
            entityManager.flush();
        });
    }

    private static void assertSameLoading(Statements many, Statements one, long bound) {
        assertThat(many.withoutEntityWrites())
                .as("everything but the row writes, many large testimonials vs one small one (%s)",
                        one.withoutEntityWrites())
                .isEqualTo(one.withoutEntityWrites());
        assertThat(many.withoutEntityWrites()).isLessThanOrEqualTo(bound);
    }

    /** {@code topic} followed by four sample topics: sections for a large sample, the first under {@code topic}. */
    private List<Topic> firstUnder(Topic topic) {
        return List.of(topic, samples.topic(1), samples.topic(2), samples.topic(3), samples.topic(4));
    }

    // -- deletes --

    @Test
    void deleteTopic_loadsOneSmallSectionAndManyLargeOnesInTheSameStatements() throws Exception {
        Topic one = fixtures.topic(null, "Topic of one", 500, true);
        Topic many = fixtures.topic(null, "Topic of many", 501, true);
        samples.withTopics(List.of(one)).save(TestimonialStatus.PENDING, Size.SMALLEST);
        samples.withTopics(firstUnder(many)).save(5, TestimonialStatus.APPROVED, Size.LARGE);

        Statements oneDeleted = count(() -> service.deleteTopic(one.getId()));
        Statements manyDeleted = count(() -> service.deleteTopic(many.getId()));

        assertThat(manyDeleted.entityDeletes()).isGreaterThan(oneDeleted.entityDeletes());
        assertSameLoading(manyDeleted, oneDeleted, 4);
    }

    @Test
    void deleteTopicGroup_loadsOneSmallSectionAndManyLargeOnesInTheSameStatements() throws Exception {
        TopicGroup groupOfOne = fixtures.group("Group of one", 500, true);
        Topic one = fixtures.topic(groupOfOne, "Topic of one", 1, true);
        fixtures.topic(groupOfOne, "Empty topic of one", 2, true);
        TopicGroup groupOfMany = fixtures.group("Group of many", 501, true);
        Topic many = fixtures.topic(groupOfMany, "Topic of many", 1, true);
        Topic alsoMany = fixtures.topic(groupOfMany, "Another topic of many", 2, true);
        samples.withTopics(List.of(one)).save(TestimonialStatus.PENDING, Size.SMALLEST);
        samples.withTopics(List.of(many, alsoMany, samples.topic(2), samples.topic(3), samples.topic(4)))
                .save(5, TestimonialStatus.APPROVED, Size.LARGE);

        Statements oneDeleted = count(() -> service.deleteTopicGroup(groupOfOne.getId()));
        Statements manyDeleted = count(() -> service.deleteTopicGroup(groupOfMany.getId()));

        assertThat(manyDeleted.entityDeletes()).isGreaterThan(oneDeleted.entityDeletes());
        assertSameLoading(manyDeleted, oneDeleted, 5);
    }

    @Test
    void deleteAchievement_loadsOneTickAndManyInTheSameStatements() throws Exception {
        Achievement one = fixtures.achievement("Achievement of one", 500, true);
        Achievement many = fixtures.achievement("Achievement of many", 501, true);
        tick(samples.save(TestimonialStatus.PENDING, Size.SMALLEST), one);
        samples.save(5, TestimonialStatus.APPROVED, Size.LARGE).forEach(testimonial -> tick(testimonial, many));

        Statements oneDeleted = count(() -> service.deleteAchievement(one.getId()));
        Statements manyDeleted = count(() -> service.deleteAchievement(many.getId()));

        assertThat(manyDeleted.entityDeletes()).isGreaterThan(oneDeleted.entityDeletes());
        assertSameLoading(manyDeleted, oneDeleted, 2);
    }

    private void tick(Testimonial testimonial, Achievement achievement) {
        testimonial.getAchievements().add(
                TestimonialAchievement.builder().testimonial(testimonial).achievement(achievement).build());
        testimonialRepository.saveAndFlush(testimonial);
    }

    // -- visible again: hidden edits go back to pending --

    @Test
    void patchTopic_madeVisibleAgain_loadsOneHiddenEditAndManyInTheSameStatements() throws Exception {
        Topic one = fixtures.topic(null, "Hidden topic of one", 500, false);
        Topic many = fixtures.topic(null, "Hidden topic of many", 501, false);
        hiddenEditUnderItsFirstTopic(samples.withTopics(List.of(one)).save(TestimonialStatus.APPROVED, Size.SMALLEST));
        samples.withTopics(firstUnder(many)).save(5, TestimonialStatus.APPROVED, Size.LARGE)
                .forEach(this::hiddenEditUnderItsFirstTopic);
        TopicPatchRequest visibleAgain = new TopicPatchRequest(null, null, null, null, true, null);

        Statements oneSentBack = count(() -> service.patchTopic(one.getId(), visibleAgain));
        Statements manySentBack = count(() -> service.patchTopic(many.getId(), visibleAgain));

        assertThat(manySentBack.entityUpdates()).isGreaterThan(oneSentBack.entityUpdates());
        assertSameLoading(manySentBack, oneSentBack, 2);
    }

    @Test
    void patchTopicGroup_madeVisibleAgain_loadsOneHiddenEditAndManyInTheSameStatements() throws Exception {
        TopicGroup groupOfOne = fixtures.group("Hidden group of one", 500, false);
        Topic one = fixtures.topic(groupOfOne, "Topic of one", 1, true);
        TopicGroup groupOfMany = fixtures.group("Hidden group of many", 501, false);
        Topic many = fixtures.topic(groupOfMany, "Topic of many", 1, true);
        fixtures.topic(groupOfMany, "Another topic of many", 2, true);
        hiddenEditUnderItsFirstTopic(samples.withTopics(List.of(one)).save(TestimonialStatus.APPROVED, Size.SMALLEST));
        samples.withTopics(firstUnder(many)).save(5, TestimonialStatus.APPROVED, Size.LARGE)
                .forEach(this::hiddenEditUnderItsFirstTopic);
        TopicGroupPatchRequest visibleAgain = new TopicGroupPatchRequest(null, null, true);

        Statements oneSentBack = count(() -> service.patchTopicGroup(groupOfOne.getId(), visibleAgain));
        Statements manySentBack = count(() -> service.patchTopicGroup(groupOfMany.getId(), visibleAgain));

        assertThat(manySentBack.entityUpdates()).isGreaterThan(oneSentBack.entityUpdates());
        assertSameLoading(manySentBack, oneSentBack, 3);
    }

    /** Marks the testimonial's first section — the one under the hidden topic — as edited while hidden. */
    private void hiddenEditUnderItsFirstTopic(Testimonial testimonial) {
        testimonial.getSections().get(0).setModified(true);
        testimonialRepository.saveAndFlush(testimonial);
    }
}
