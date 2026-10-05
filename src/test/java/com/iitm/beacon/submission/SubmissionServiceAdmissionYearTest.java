package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.iitm.beacon.common.error.FieldViolation;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admission year's range (decision 10): 1959 (IIT Madras's founding
 * year) up to the current year, the ceiling taken from the application
 * {@code Clock} (UTC) rather than hardcoded. Own top-level class for the
 * same reason as {@code VisitorAuthControllerExpiredOtpTest}: its {@link
 * MutableClock} override must not leak into other tests' contexts.
 */
@SpringBootTest
@Transactional
@Import(SubmissionServiceAdmissionYearTest.MutableClockTestConfig.class)
class SubmissionServiceAdmissionYearTest {

    private static final Instant MID_2026 = Instant.parse("2026-06-15T12:00:00Z");

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private MutableClock mutableClock;

    @BeforeEach
    void resetClock() {
        mutableClock.advanceTo(MID_2026);
    }

    private static TestimonialSubmissionRequest withAdmissionYear(int year) {
        return new TestimonialSubmissionRequest("David", "Jones", "CS21B001", year, "IN", 8,
                List.of(new SectionInput("general", "Text.", List.of())), List.of(), List.of(), true);
    }

    private List<FieldViolation> violationsCreating(String email, int year) {
        SubmissionValidationException ex = catchThrowableOfType(
                SubmissionValidationException.class,
                () -> submissionService.create(email, withAdmissionYear(year), Map.of()));
        return ex == null ? List.of() : ex.getViolations();
    }

    @Test
    void year1958_isRejected() {
        assertThat(violationsCreating("year-1958@example.com", 1958))
                .containsExactly(new FieldViolation("admissionYear", "must be between 1959 and the current year"));
    }

    @Test
    void year1959_isAccepted() {
        assertThat(violationsCreating("year-1959@example.com", 1959)).isEmpty();
    }

    @Test
    void currentYear_isAccepted() {
        assertThat(violationsCreating("year-2026@example.com", 2026)).isEmpty();
    }

    @Test
    void nextYear_isRejectedNamingTheCurrentYear() {
        assertThat(violationsCreating("year-2027@example.com", 2027))
                .containsExactly(new FieldViolation("admissionYear", "must be between 1959 and 2026"));
    }

    @Test
    void newYearBoundary_theCeilingMovesWithTheClock() {
        mutableClock.advanceTo(Instant.parse("2026-12-31T23:59:59Z"));
        assertThat(violationsCreating("new-year-before@example.com", 2027)).isNotEmpty();

        mutableClock.advanceTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(violationsCreating("new-year-after@example.com", 2027)).isEmpty();
    }

    @Test
    void edit_futureYear_isRejectedToo() {
        String email = "year-edit@example.com";
        submissionService.create(email, withAdmissionYear(2024), Map.of());

        SubmissionValidationException ex = catchThrowableOfType(
                SubmissionValidationException.class,
                () -> submissionService.edit(email, withAdmissionYear(2030), Map.of()));

        assertThat(ex).isNotNull();
        assertThat(ex.getViolations()).extracting(FieldViolation::field).containsExactly("admissionYear");
    }

    @Test
    void latestAdmissionYear_isTheClocksCurrentYear() {
        assertThat(submissionService.latestAdmissionYear()).isEqualTo(2026);

        mutableClock.advanceTo(Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(submissionService.latestAdmissionYear()).isEqualTo(2027);
    }

    @TestConfiguration
    static class MutableClockTestConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(MID_2026);
        }
    }
}
