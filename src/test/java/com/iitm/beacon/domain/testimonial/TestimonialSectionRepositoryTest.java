package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.crypto.CryptoProperties;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.AbstractRepositoryTest;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class TestimonialSectionRepositoryTest extends AbstractRepositoryTest {

    private static final String TEST_KEY = "aunkIRNayPvNjoN2fPob8307AX7znDryq0XhVWMZoaM=";

    @Autowired
    private TestimonialSectionRepository testimonialSectionRepository;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    private final EmailLookupHashService hashService =
            new EmailLookupHashService(new CryptoProperties(TEST_KEY, "dev-only-insecure-pepper-do-not-use-in-prod"));

    private Testimonial testimonial;
    private Topic topic;

    @BeforeEach
    void setUp() {
        // "IN" (V12) and the "general" topic (V13) are already seeded — look
        // them up rather than inserting fresh rows, which would now violate
        // their unique constraints.
        Country india = countryRepository.findById("IN").orElseThrow();
        testimonial = testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email("section-test@example.com")
                .emailLookupHash(hashService.hash("section-test@example.com"))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
        topic = topicRepository.findBySlug("general").orElseThrow();
    }

    @Test
    void savedSection_roundTrips() {
        TestimonialSection section = testimonialSectionRepository.saveAndFlush(TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(topic)
                .answerText("It was a great experience overall.")
                .build());

        var found = testimonialSectionRepository.findById(section.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getAnswerText()).isEqualTo("It was a great experience overall.");
        assertThat(found.get().getTestimonial().getId()).isEqualTo(testimonial.getId());
        assertThat(found.get().getTopic().getId()).isEqualTo(topic.getId());
    }

    @Test
    void modified_defaultsToFalse_whenNotExplicitlySet() {
        TestimonialSection saved = testimonialSectionRepository.saveAndFlush(TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(topic)
                .answerText("Some answer.")
                .build());

        assertThat(saved.isModified()).isFalse();
    }

    @Test
    void topic_isRequired_nullTopicViolatesNotNullConstraint() {
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(null)
                .answerText("Missing topic.")
                .build();

        assertThatThrownBy(() -> testimonialSectionRepository.saveAndFlush(section))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findDistinctTopicIdsByTestimonialStatus_pendingTestimonialWithSections_returnsItsTopicIds() {
        Topic otherTopic = topicRepository.findBySlug("adapt_weather").orElseThrow();
        testimonialSectionRepository.saveAndFlush(TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(topic)
                .answerText("First section.")
                .build());
        testimonialSectionRepository.saveAndFlush(TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(otherTopic)
                .answerText("Second section.")
                .build());

        var found =
                testimonialSectionRepository.findDistinctTopicIdsByTestimonialStatus(TestimonialStatus.PENDING);

        assertThat(found).containsExactlyInAnyOrder(topic.getId(), otherTopic.getId());
    }

    @Test
    void findDistinctTopicIdsByTestimonialStatus_noMatchingTestimonials_returnsEmptyList() {
        testimonialSectionRepository.saveAndFlush(TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(topic)
                .answerText("Only a pending section exists.")
                .build());

        var found =
                testimonialSectionRepository.findDistinctTopicIdsByTestimonialStatus(TestimonialStatus.APPROVED);

        assertThat(found).isEmpty();
    }

    // -- countDistinctTestimonialsByTopicIdIn: the catalog delete confirmation page --

    @Test
    void countDistinctTestimonials_oneTestimonialWithSeveralMatchingSections_countsOnce() {
        Topic first = fixtureTopic("fixture_count_first");
        Topic second = fixtureTopic("fixture_count_second");
        section(testimonial, first);
        section(testimonial, second);

        long count = testimonialSectionRepository.countDistinctTestimonialsByTopicIdIn(
                Set.of(first.getId(), second.getId()));

        assertThat(count).isEqualTo(1);
    }

    @Test
    void countDistinctTestimonials_countsEveryStatus_andIgnoresOtherTopics() {
        Topic target = fixtureTopic("fixture_count_target");
        Topic other = fixtureTopic("fixture_count_other");
        Testimonial approved = fixtureTestimonial("section-count-approved@example.com", TestimonialStatus.APPROVED);
        Testimonial rejected = fixtureTestimonial("section-count-rejected@example.com", TestimonialStatus.REJECTED);
        Testimonial unrelated = fixtureTestimonial("section-count-unrelated@example.com", TestimonialStatus.APPROVED);
        section(testimonial, target);
        section(approved, target);
        section(rejected, target);
        section(unrelated, other);

        long count = testimonialSectionRepository.countDistinctTestimonialsByTopicIdIn(Set.of(target.getId()));

        assertThat(count).isEqualTo(3);
    }

    @Test
    void countDistinctTestimonials_topicsWithoutSections_isZero() {
        Topic unused = fixtureTopic("fixture_count_unused");

        assertThat(testimonialSectionRepository.countDistinctTestimonialsByTopicIdIn(Set.of(unused.getId())))
                .isZero();
    }

    private Topic fixtureTopic(String slug) {
        return topicRepository.saveAndFlush(Topic.builder()
                .slug(slug)
                .label("Fixture " + slug)
                .guidingPrompt("A fixture guiding prompt?")
                .displayOrder(1)
                .build());
    }

    private Testimonial fixtureTestimonial(String email, TestimonialStatus status) {
        return testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("Fixture")
                .lastName("Author")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(hashService.hash(email))
                .country(testimonial.getCountry())
                .recommendationScore(7)
                .dataProcessingConsent(true)
                .status(status)
                .createdAt(Instant.parse("2026-01-02T00:00:00Z"))
                .build());
    }

    private TestimonialSection section(Testimonial owner, Topic sectionTopic) {
        return testimonialSectionRepository.saveAndFlush(TestimonialSection.builder()
                .testimonial(owner)
                .topic(sectionTopic)
                .answerText("Fixture answer.")
                .build());
    }
}
