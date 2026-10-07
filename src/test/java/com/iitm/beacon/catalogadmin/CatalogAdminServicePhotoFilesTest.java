package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Photo files of a catalog delete go only once the delete has committed
 * (decision 28): before the commit they are all still there, and a delete
 * that rolls back leaves every file — and every row — in place.
 *
 * <p>Not {@code @Transactional}: the point is real commits and rollbacks, so
 * the test data is committed and deleted again afterwards. Files live in
 * this test's own uploads root.
 */
@SpringBootTest
class CatalogAdminServicePhotoFilesTest {

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private TestimonialSectionRepository testimonialSectionRepository;

    @Autowired
    private TestimonialAchievementRepository testimonialAchievementRepository;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @TempDir
    Path uploadsRoot;

    private CatalogAdminService service;
    private CatalogFixtures fixtures;
    private TransactionTemplate tx;

    private final List<Long> testimonialIds = new ArrayList<>();
    private final List<Long> topicIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new CatalogAdminService(
                topicGroupRepository,
                topicRepository,
                achievementRepository,
                testimonialRepository,
                testimonialSectionRepository,
                testimonialAchievementRepository,
                new PhotoFileDeleter(TestPhotoStorage.properties(uploadsRoot)));
        fixtures = new CatalogFixtures(
                topicGroupRepository, topicRepository, achievementRepository, testimonialRepository,
                countryRepository);
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void deleteCommittedData() {
        testimonialIds.forEach(id -> testimonialRepository.findById(id).ifPresent(testimonialRepository::delete));
        topicIds.forEach(id -> topicRepository.findById(id).ifPresent(topicRepository::delete));
        groupIds.forEach(id -> topicGroupRepository.findById(id).ifPresent(topicGroupRepository::delete));
    }

    private Topic topic(TopicGroup group, String label) {
        Topic topic = fixtures.topic(group, label, 1, true);
        topicIds.add(topic.getId());
        return topic;
    }

    private TopicGroup group(String label) {
        TopicGroup group = fixtures.group(label, 1, true);
        groupIds.add(group.getId());
        return group;
    }

    /** A committed testimonial whose first section (under {@code topics[0]}) holds these photos, files written. */
    private Testimonial testimonialWithPhotoFiles(List<Topic> topics, Photo... photos) throws IOException {
        for (Photo photo : photos) {
            Files.writeString(uploadsRoot.resolve(photo.getFilePath()), "full");
            if (photo.getThumbnailPath() != null) {
                Files.writeString(uploadsRoot.resolve(photo.getThumbnailPath()), "thumb");
            }
        }
        Testimonial t = tx.execute(status -> fixtures.testimonial(
                TestimonialStatus.APPROVED, topics, List.of(photos), List.of()));
        testimonialIds.add(t.getId());
        return t;
    }

    private boolean exists(String relativePath) {
        return Files.exists(uploadsRoot.resolve(relativePath));
    }

    private static Photo legacyPhoto(String name) {
        return Photo.builder().filePath(name + ".jpg").displayOrder(1).build();
    }

    @Test
    void deleteTopic_filesStayUntilTheCommit_thenAreGone() throws IOException {
        Topic doomed = topic(null, "Doomed");
        testimonialWithPhotoFiles(List.of(doomed), CatalogFixtures.photo("a1"), legacyPhoto("legacy1"));

        tx.executeWithoutResult(status -> {
            service.deleteTopic(doomed.getId());
            testimonialSectionRepository.flush();
            assertThat(exists("a1.webp")).isTrue();
            assertThat(exists("a1-thumb.webp")).isTrue();
            assertThat(exists("legacy1.jpg")).isTrue();
        });

        assertThat(exists("a1.webp")).isFalse();
        assertThat(exists("a1-thumb.webp")).isFalse();
        assertThat(exists("legacy1.jpg")).isFalse();
        assertThat(topicRepository.findById(doomed.getId())).isEmpty();
    }

    @Test
    void deleteTopic_rolledBack_keepsEveryFileAndRow() throws IOException {
        Topic doomed = topic(null, "Doomed");
        Testimonial t = testimonialWithPhotoFiles(List.of(doomed), CatalogFixtures.photo("r1"));

        tx.executeWithoutResult(status -> {
            service.deleteTopic(doomed.getId());
            status.setRollbackOnly();
        });

        assertThat(exists("r1.webp")).isTrue();
        assertThat(exists("r1-thumb.webp")).isTrue();
        assertThat(topicRepository.findById(doomed.getId())).isPresent();
        assertThat(testimonialSectionRepository.countDistinctTestimonialsByTopicIdIn(List.of(doomed.getId())))
                .isEqualTo(1);
        assertThat(testimonialRepository.findById(t.getId())).isPresent();
    }

    @Test
    void deleteTopic_whoseFileIsAlreadyMissing_stillCommits() throws IOException {
        Topic doomed = topic(null, "Doomed");
        testimonialWithPhotoFiles(List.of(doomed), CatalogFixtures.photo("m1"));
        Files.delete(uploadsRoot.resolve("m1.webp"));

        tx.executeWithoutResult(status -> service.deleteTopic(doomed.getId()));

        assertThat(topicRepository.findById(doomed.getId())).isEmpty();
        assertThat(exists("m1-thumb.webp")).isFalse();
    }

    @Test
    void deleteTopicGroup_removesTheFilesOfEveryTopicInIt_andNoOtherFiles() throws IOException {
        TopicGroup doomed = group("Doomed");
        Topic t1 = topic(doomed, "T1");
        Topic t2 = topic(doomed, "T2");
        Topic elsewhere = topic(null, "Elsewhere");
        testimonialWithPhotoFiles(List.of(t1, elsewhere), CatalogFixtures.photo("g1"), CatalogFixtures.photo("g2"));
        testimonialWithPhotoFiles(List.of(t2), CatalogFixtures.photo("g3"));
        testimonialWithPhotoFiles(List.of(elsewhere), CatalogFixtures.photo("keep"));

        tx.executeWithoutResult(status -> service.deleteTopicGroup(doomed.getId()));

        assertThat(List.of("g1.webp", "g1-thumb.webp", "g2.webp", "g2-thumb.webp", "g3.webp", "g3-thumb.webp"))
                .noneMatch(this::exists);
        assertThat(exists("keep.webp")).isTrue();
        assertThat(exists("keep-thumb.webp")).isTrue();
        assertThat(topicGroupRepository.findById(doomed.getId())).isEmpty();
    }

    @Test
    void deleteTopicGroup_rolledBack_keepsEveryFile() throws IOException {
        TopicGroup doomed = group("Doomed");
        Topic t1 = topic(doomed, "T1");
        testimonialWithPhotoFiles(List.of(t1), CatalogFixtures.photo("gr1"));

        tx.executeWithoutResult(status -> {
            service.deleteTopicGroup(doomed.getId());
            status.setRollbackOnly();
        });

        assertThat(exists("gr1.webp")).isTrue();
        assertThat(topicGroupRepository.findById(doomed.getId())).isPresent();
        assertThat(topicRepository.findById(t1.getId())).isPresent();
    }
}
