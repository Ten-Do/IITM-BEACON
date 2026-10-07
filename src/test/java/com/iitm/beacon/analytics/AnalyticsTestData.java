package com.iitm.beacon.analytics;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves testimonials for the analytics tests: {@code
 * data.approved().from("DE").score(6).sections("general").ticks("made_new_friends").save()}.
 * A draft starts as an Indian testimonial scoring 8 with no section and no
 * tick; topics and achievements are the seeded ones, looked up by slug, or
 * entities a test created itself.
 */
final class AnalyticsTestData {

    private final TestimonialRepository testimonialRepository;
    private final CountryRepository countryRepository;
    private final TopicRepository topicRepository;
    private final AchievementRepository achievementRepository;
    private final EmailLookupHashService emailLookupHashService;
    private int sequence;

    AnalyticsTestData(
            TestimonialRepository testimonialRepository,
            CountryRepository countryRepository,
            TopicRepository topicRepository,
            AchievementRepository achievementRepository,
            EmailLookupHashService emailLookupHashService) {
        this.testimonialRepository = testimonialRepository;
        this.countryRepository = countryRepository;
        this.topicRepository = topicRepository;
        this.achievementRepository = achievementRepository;
        this.emailLookupHashService = emailLookupHashService;
    }

    Draft approved() {
        return new Draft(TestimonialStatus.APPROVED);
    }

    Draft pending() {
        return new Draft(TestimonialStatus.PENDING);
    }

    Draft rejected() {
        return new Draft(TestimonialStatus.REJECTED);
    }

    Topic topic(String slug) {
        return topicRepository.findBySlug(slug).orElseThrow();
    }

    Achievement achievement(String slug) {
        return achievementRepository.findBySlug(slug).orElseThrow();
    }

    /** One testimonial being described; {@link #save()} writes and flushes it. */
    final class Draft {

        private final TestimonialStatus status;
        private final List<Topic> topics = new ArrayList<>();
        private final List<Achievement> achievements = new ArrayList<>();
        private String countryCode = "IN";
        private int score = 8;

        private Draft(TestimonialStatus status) {
            this.status = status;
        }

        Draft from(String code) {
            this.countryCode = code;
            return this;
        }

        Draft score(int value) {
            this.score = value;
            return this;
        }

        /** One section per slug, in the given order; a repeated slug gives repeated sections. */
        Draft sections(String... topicSlugs) {
            for (String slug : topicSlugs) {
                topics.add(topic(slug));
            }
            return this;
        }

        Draft sections(Topic... sectionTopics) {
            topics.addAll(List.of(sectionTopics));
            return this;
        }

        Draft ticks(String... achievementSlugs) {
            for (String slug : achievementSlugs) {
                achievements.add(achievement(slug));
            }
            return this;
        }

        Draft ticks(Achievement... ticked) {
            achievements.addAll(List.of(ticked));
            return this;
        }

        Testimonial save() {
            sequence++;
            String email = "analytics-" + sequence + "@example.com";
            Testimonial testimonial = Testimonial.builder()
                    .firstName("Ana")
                    .lastName("Lytics")
                    .rollNumber("GE26Z000")
                    .admissionYear(2024)
                    .email(email)
                    .emailLookupHash(emailLookupHashService.hash(email))
                    .country(countryRepository.findById(countryCode).orElseThrow())
                    .recommendationScore(score)
                    .dataProcessingConsent(true)
                    .status(status)
                    .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                    .build();
            for (Topic topic : topics) {
                testimonial.getSections().add(TestimonialSection.builder()
                        .testimonial(testimonial)
                        .topic(topic)
                        .answerText("An answer about " + topic.getSlug() + ".")
                        .modified(false)
                        .build());
            }
            for (Achievement achievement : achievements) {
                testimonial.getAchievements().add(TestimonialAchievement.builder()
                        .testimonial(testimonial)
                        .achievement(achievement)
                        .build());
            }
            return testimonialRepository.saveAndFlush(testimonial);
        }
    }
}
