package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.TestPhotoStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.AdditionalAnswers;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ModerationService#purgeExpiredRejections()} (UC-PURGE-REJECTED,
 * decision 3): every testimonial rejected more than 30 days before the
 * injected clock's "now" is deleted in one transaction, its photo files only
 * once that transaction has committed.
 *
 * <p>Not {@code @Transactional}: the point is real commits and rollbacks, so
 * each test wraps the call in its own transaction (as the Spring proxy does)
 * and deletes whatever it committed afterwards. Files live in this test's own
 * uploads root. "Now" is far in the past, so no testimonial another test left
 * behind can be due.
 */
@SpringBootTest
class ModerationServicePurgeTest {

    private static final Instant NOW = Instant.parse("2001-03-01T12:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path uploadsRoot;

    private TransactionTemplate tx;
    private final List<Long> committedIds = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        committedIds.forEach(id -> testimonialRepository.findById(id).ifPresent(testimonialRepository::delete));
    }

    private ModerationService service(TestimonialRepository repository) {
        return new ModerationService(
                repository,
                mock(NotificationMailer.class),
                new PhotoUrlResolver(),
                new PhotoFileDeleter(TestPhotoStorage.properties(uploadsRoot)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RejectionPurgeResult purge() {
        return tx.execute(status -> service(testimonialRepository).purgeExpiredRejections());
    }

    // -- fixtures --

    private static Photo photo(String name) {
        return Photo.builder().filePath(name + ".webp").thumbnailPath(name + "-thumb.webp").displayOrder(0).build();
    }

    private static Photo legacyPhoto(String name) {
        return Photo.builder().filePath(name + ".jpg").displayOrder(0).build();
    }

    /**
     * A committed testimonial with one section (under {@code general}) per
     * list of photos, every photo file written to the uploads root.
     */
    @SafeVarargs
    private Testimonial committed(TestimonialStatus status, Instant rejectedAt, List<Photo>... sectionPhotos)
            throws IOException {
        for (List<Photo> photos : sectionPhotos) {
            for (Photo photo : photos) {
                Files.writeString(uploadsRoot.resolve(photo.getFilePath()), "full");
                if (photo.getThumbnailPath() != null) {
                    Files.writeString(uploadsRoot.resolve(photo.getThumbnailPath()), "thumb");
                }
            }
        }
        Testimonial saved = tx.execute(txStatus -> {
            Testimonial t = Testimonial.builder()
                    .firstName("Rita")
                    .lastName("Retention")
                    .rollNumber("GE26Z003")
                    .admissionYear(2000)
                    .email("purge-" + UUID.randomUUID() + "@example.com")
                    .emailLookupHash(UUID.randomUUID().toString())
                    .country(countryRepository.findById("IN").orElseThrow())
                    .recommendationScore(6)
                    .dataProcessingConsent(true)
                    .status(status)
                    .createdAt(Instant.parse("2000-01-01T00:00:00Z"))
                    .rejectedAt(rejectedAt)
                    .build();
            for (List<Photo> photos : sectionPhotos) {
                TestimonialSection section = TestimonialSection.builder()
                        .testimonial(t)
                        .topic(topicRepository.findBySlug("general").orElseThrow())
                        .answerText("Words.")
                        .build();
                for (Photo photo : photos) {
                    photo.setSection(section);
                    section.getPhotos().add(photo);
                }
                t.getSections().add(section);
            }
            return testimonialRepository.saveAndFlush(t);
        });
        committedIds.add(saved.getId());
        return saved;
    }

    private Testimonial rejected(Instant rejectedAt, List<Photo> photos) throws IOException {
        return committed(TestimonialStatus.REJECTED, rejectedAt, photos);
    }

    private static List<Long> photoIdsOf(Testimonial t) {
        return t.getSections().stream().flatMap(s -> s.getPhotos().stream()).map(Photo::getId).toList();
    }

    private List<String> rootContents() throws IOException {
        try (Stream<Path> files = Files.list(uploadsRoot)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private boolean exists(String relativePath) {
        return Files.exists(uploadsRoot.resolve(relativePath));
    }

    private List<ILoggingEvent> appWarnings() {
        return logs.list.stream()
                .filter(e -> e.getLoggerName().startsWith("com.iitm.beacon"))
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
    }

    private void assertKept(Testimonial t, String... files) {
        assertThat(testimonialRepository.existsById(t.getId())).isTrue();
        assertThat(photoIdsOf(t)).allMatch(photoRepository::existsById);
        assertThat(files).allMatch(this::exists);
    }

    // -- the 30-day boundary --

    @Test
    void purge_rejectedMoreThan30DaysAgo_deletesItsRows_andItsFilesOnlyOnceCommitted() throws IOException {
        Testimonial due = committed(TestimonialStatus.REJECTED, NOW.minus(Duration.ofDays(31)),
                List.of(photo("p1"), legacyPhoto("p2")), List.of(photo("p3")));
        List<Long> photoIds = photoIdsOf(due);

        RejectionPurgeResult result = tx.execute(status -> {
            RejectionPurgeResult inside = service(testimonialRepository).purgeExpiredRejections();
            assertThat(List.of("p1.webp", "p1-thumb.webp", "p2.jpg", "p3.webp", "p3-thumb.webp"))
                    .allMatch(this::exists);
            return inside;
        });

        assertThat(result).isEqualTo(new RejectionPurgeResult(1, 3));
        assertThat(testimonialRepository.existsById(due.getId())).isFalse();
        assertThat(photoIds).noneMatch(photoRepository::existsById);
        assertThat(rootContents()).isEmpty();
        assertThat(appWarnings()).isEmpty();
    }

    @Test
    void purge_rejected29DaysAgo_keepsIt() throws IOException {
        Testimonial recent = rejected(NOW.minus(Duration.ofDays(29)), List.of(photo("r29")));

        assertThat(purge()).isEqualTo(new RejectionPurgeResult(0, 0));
        assertKept(recent, "r29.webp", "r29-thumb.webp");
    }

    @Test
    void purge_rejectedExactly30DaysAgo_keepsIt() throws IOException {
        Testimonial atCutoff = rejected(CUTOFF, List.of(photo("r30")));

        assertThat(purge()).isEqualTo(new RejectionPurgeResult(0, 0));
        assertKept(atCutoff, "r30.webp", "r30-thumb.webp");
    }

    @Test
    void purge_rejectedOneMicrosecondMoreThan30DaysAgo_purgesIt() throws IOException {
        Testimonial justDue = rejected(CUTOFF.minus(1, ChronoUnit.MICROS), List.of(photo("r30plus")));

        assertThat(purge()).isEqualTo(new RejectionPurgeResult(1, 1));
        assertThat(testimonialRepository.existsById(justDue.getId())).isFalse();
        assertThat(rootContents()).isEmpty();
    }

    /** Resubmitted (pending) or since approved: a stale old rejection time never makes it due. */
    @ParameterizedTest
    @EnumSource(value = TestimonialStatus.class, names = {"PENDING", "APPROVED"})
    void purge_notRejectedAnyMore_despiteAnOldRejectionTime_keepsIt(TestimonialStatus status) throws IOException {
        Testimonial resubmitted = committed(status, NOW.minus(Duration.ofDays(365)), List.of(photo("stale")));

        assertThat(purge()).isEqualTo(new RejectionPurgeResult(0, 0));
        assertKept(resubmitted, "stale.webp", "stale-thumb.webp");
    }

    @Test
    void purge_nothingDue_returnsZeroCounts_andLeavesEveryFile() throws IOException {
        Files.writeString(uploadsRoot.resolve("unrelated.webp"), "x");

        assertThat(purge()).isEqualTo(new RejectionPurgeResult(0, 0));
        assertThat(rootContents()).containsExactly("unrelated.webp");
    }

    @Test
    void purge_dueTestimonialWithoutAnyPhoto_isPurged_countingNoPhotos() throws IOException {
        Testimonial noPhotos = committed(TestimonialStatus.REJECTED, NOW.minus(Duration.ofDays(40)));

        assertThat(purge()).isEqualTo(new RejectionPurgeResult(1, 0));
        assertThat(testimonialRepository.existsById(noPhotos.getId())).isFalse();
    }

    // -- one transaction: files go only with the commit --

    @Test
    void purge_rolledBack_keepsEveryRowAndFile() throws IOException {
        Testimonial due = rejected(NOW.minus(Duration.ofDays(31)), List.of(photo("rb")));

        tx.executeWithoutResult(status -> {
            service(testimonialRepository).purgeExpiredRejections();
            status.setRollbackOnly();
        });

        assertKept(due, "rb.webp", "rb-thumb.webp");
    }

    @Test
    void purge_failingPartWayThroughTheBatch_deletesNothing_rowsOrFiles() throws IOException {
        Testimonial first = rejected(NOW.minus(Duration.ofDays(31)), List.of(photo("f1")));
        Testimonial second = rejected(NOW.minus(Duration.ofDays(32)), List.of(photo("f2")));
        TestimonialRepository failsOnTheSecondDelete =
                mock(TestimonialRepository.class, AdditionalAnswers.delegatesTo(testimonialRepository));
        AtomicInteger deletes = new AtomicInteger();
        doAnswer(invocation -> {
            if (deletes.incrementAndGet() > 1) {
                throw new IllegalStateException("database connection lost");
            }
            return testimonialRepository.deleteByIdAndStatusAndRejectedAtBefore(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        }).when(failsOnTheSecondDelete).deleteByIdAndStatusAndRejectedAtBefore(any(), any(), any());

        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> service(failsOnTheSecondDelete).purgeExpiredRejections()))
                .isInstanceOf(IllegalStateException.class);

        assertKept(first, "f1.webp", "f1-thumb.webp");
        assertKept(second, "f2.webp", "f2-thumb.webp");
    }

    // -- a missing file: logged, never stops the batch --

    @Test
    void purge_photoFileAlreadyMissing_stillPurgesTheWholeBatch_deletesEveryOtherFile_andWarnsAboutIt()
            throws IOException {
        Testimonial withMissingFile = rejected(NOW.minus(Duration.ofDays(31)), List.of(photo("gone")));
        Testimonial intact = rejected(NOW.minus(Duration.ofDays(31)), List.of(photo("intact")));
        Files.delete(uploadsRoot.resolve("gone.webp"));

        RejectionPurgeResult result = purge();

        assertThat(result).isEqualTo(new RejectionPurgeResult(2, 2));
        assertThat(testimonialRepository.existsById(withMissingFile.getId())).isFalse();
        assertThat(testimonialRepository.existsById(intact.getId())).isFalse();
        assertThat(rootContents()).isEmpty();
        assertThat(appWarnings()).singleElement()
                .satisfies(warning -> assertThat(warning.getFormattedMessage()).contains("gone.webp"));
    }

    /** NFR-CONTACT-CONFIDENTIALITY: nothing the purge logs names the submitter. */
    @Test
    void purge_neverLogsTheSubmittersEmail() throws IOException {
        Testimonial due = rejected(NOW.minus(Duration.ofDays(31)), List.of(photo("quiet")));
        Files.delete(uploadsRoot.resolve("quiet.webp"));

        purge();

        assertThat(logs.list).isNotEmpty()
                .noneMatch(event -> event.getFormattedMessage().contains(due.getEmail()));
    }

    // -- a testimonial resubmitted meanwhile survives --

    @Test
    void purge_testimonialResubmittedAfterItWasFound_isKeptWithItsFiles_whileTheRestIsPurged() throws IOException {
        Testimonial resubmitted = rejected(NOW.minus(Duration.ofDays(31)), List.of(photo("back")));
        Testimonial due = rejected(NOW.minus(Duration.ofDays(31)), List.of(photo("due")));
        TestimonialRepository resubmitsWhileFound =
                mock(TestimonialRepository.class, AdditionalAnswers.delegatesTo(testimonialRepository));
        doAnswer(invocation -> {
            List<Testimonial> found = testimonialRepository.findByStatusAndRejectedAtBeforeOrderByIdAsc(
                    invocation.getArgument(0), invocation.getArgument(1));
            visitorResubmitsConcurrently(resubmitted.getId());
            return found;
        }).when(resubmitsWhileFound).findByStatusAndRejectedAtBeforeOrderByIdAsc(any(), any());

        RejectionPurgeResult result =
                tx.execute(status -> service(resubmitsWhileFound).purgeExpiredRejections());

        assertThat(result).isEqualTo(new RejectionPurgeResult(1, 1));
        assertKept(resubmitted, "back.webp", "back-thumb.webp");
        assertThat(testimonialRepository.findById(resubmitted.getId()).orElseThrow().getStatus())
                .isEqualTo(TestimonialStatus.PENDING);
        assertThat(testimonialRepository.existsById(due.getId())).isFalse();
        assertThat(exists("due.webp")).isFalse();
        assertThat(exists("due-thumb.webp")).isFalse();
    }

    /** The visitor's edit, committed in its own transaction while the purge's is still open. */
    private void visitorResubmitsConcurrently(Long id) {
        TransactionTemplate visitorTx = new TransactionTemplate(transactionManager);
        visitorTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        visitorTx.executeWithoutResult(
                status -> jdbcTemplate.update("UPDATE testimonial SET status = 'PENDING' WHERE id = ?", id));
    }
}
