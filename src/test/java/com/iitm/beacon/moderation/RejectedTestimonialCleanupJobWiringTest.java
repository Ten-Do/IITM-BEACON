package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The retention job as the application wires it (UC-PURGE-REJECTED,
 * decision 3): scheduled from the configured cron, and purging through the
 * transactional {@link ModerationService} bean with the real clock.
 */
@SpringBootTest
class RejectedTestimonialCleanupJobWiringTest {

    @Autowired
    private RejectedTestimonialCleanupJob job;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long committedId;

    @AfterEach
    void deleteCommittedData() {
        if (committedId != null) {
            testimonialRepository.findById(committedId).ifPresent(testimonialRepository::delete);
        }
    }

    @Test
    void theApplication_schedulesTheJob_withTheConfiguredCron() {
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .map(ScheduledTask::getTask)
                .filteredOn(task -> task.toString().contains(RejectedTestimonialCleanupJob.class.getName()))
                .singleElement()
                .isInstanceOfSatisfying(CronTask.class,
                        task -> assertThat(task.getExpression()).isEqualTo("0 0 3 * * SUN"));
    }

    /** Its photo's file was never written, so the after-commit file step has a missing file to log. */
    @Test
    void run_purgesALongRejectedTestimonial_inATransaction() {
        committedId = new TransactionTemplate(transactionManager).execute(status -> {
            Testimonial t = Testimonial.builder()
                    .firstName("Old")
                    .lastName("Rejection")
                    .rollNumber("GE26Z004")
                    .admissionYear(1999)
                    .email("wiring-" + UUID.randomUUID() + "@example.com")
                    .emailLookupHash(UUID.randomUUID().toString())
                    .country(countryRepository.findById("IN").orElseThrow())
                    .recommendationScore(5)
                    .dataProcessingConsent(true)
                    .status(TestimonialStatus.REJECTED)
                    .createdAt(Instant.parse("1999-12-01T00:00:00Z"))
                    .rejectedAt(Instant.parse("2000-01-01T00:00:00Z"))
                    .build();
            TestimonialSection section = TestimonialSection.builder()
                    .testimonial(t)
                    .topic(topicRepository.findBySlug("general").orElseThrow())
                    .answerText("Words.")
                    .build();
            section.getPhotos().add(Photo.builder()
                    .section(section)
                    .filePath("never-written-" + UUID.randomUUID() + ".webp")
                    .displayOrder(0)
                    .build());
            t.getSections().add(section);
            return testimonialRepository.saveAndFlush(t).getId();
        });

        job.purgeExpiredRejections();

        assertThat(testimonialRepository.existsById(committedId)).isFalse();
    }
}
