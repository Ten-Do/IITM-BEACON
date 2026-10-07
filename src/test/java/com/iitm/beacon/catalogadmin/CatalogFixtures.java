package com.iitm.beacon.catalogadmin;

import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds catalog entries and testimonials that use them, for the catalog
 * admin tests. Every slug and email is unique per call, so fixtures never
 * collide with the seed data or with each other.
 */
final class CatalogFixtures {

    private final TopicGroupRepository topicGroupRepository;
    private final TopicRepository topicRepository;
    private final AchievementRepository achievementRepository;
    private final TestimonialRepository testimonialRepository;
    private final CountryRepository countryRepository;

    CatalogFixtures(
            TopicGroupRepository topicGroupRepository,
            TopicRepository topicRepository,
            AchievementRepository achievementRepository,
            TestimonialRepository testimonialRepository,
            CountryRepository countryRepository) {
        this.topicGroupRepository = topicGroupRepository;
        this.topicRepository = topicRepository;
        this.achievementRepository = achievementRepository;
        this.testimonialRepository = testimonialRepository;
        this.countryRepository = countryRepository;
    }

    static String uniqueSlug(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    TopicGroup group(String label, int displayOrder, boolean active) {
        return topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label(label).displayOrder(displayOrder).active(active).build());
    }

    Topic topic(TopicGroup group, String label, int displayOrder, boolean active) {
        return topicRepository.saveAndFlush(Topic.builder()
                .topicGroup(group)
                .slug(uniqueSlug("t"))
                .label(label)
                .guidingPrompt(label + "?")
                .displayOrder(displayOrder)
                .active(active)
                .build());
    }

    Achievement achievement(String label, int displayOrder, boolean active) {
        return achievementRepository.saveAndFlush(Achievement.builder()
                .slug(uniqueSlug("a"))
                .label(label)
                .displayOrder(displayOrder)
                .active(active)
                .build());
    }

    Topic general() {
        return topicRepository.findBySlug(Topic.GENERAL_SLUG).orElseThrow();
    }

    /** A new photo (not yet attached), with these tags. */
    static Photo photo(String name, String... tags) {
        Photo photo = Photo.builder()
                .filePath(name + ".webp")
                .thumbnailPath(name + "-thumb.webp")
                .width(800)
                .height(600)
                .displayOrder(0)
                .build();
        for (String tag : tags) {
            photo.getTags().add(PhotoTag.builder().photo(photo).tagText(tag).build());
        }
        return photo;
    }

    /** A testimonial of {@code status} with one section per topic (photos only on the first) and these ticks. */
    Testimonial testimonial(
            TestimonialStatus status, List<Topic> topics, List<Photo> firstSectionPhotos,
            List<Achievement> achievements) {
        Testimonial t = Testimonial.builder()
                .firstName("Ada")
                .lastName("Lovelace")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email("catalog-" + UUID.randomUUID() + "@example.com")
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(status)
                .createdAt(Instant.parse("2026-03-01T10:00:00Z"))
                .build();
        boolean first = true;
        for (Topic topic : topics) {
            TestimonialSection section = TestimonialSection.builder()
                    .testimonial(t)
                    .topic(topic)
                    .answerText("About " + topic.getLabel())
                    .build();
            if (first) {
                for (Photo photo : firstSectionPhotos) {
                    photo.setSection(section);
                    section.getPhotos().add(photo);
                }
                first = false;
            }
            t.getSections().add(section);
        }
        for (Achievement achievement : achievements) {
            t.getAchievements().add(TestimonialAchievement.builder().testimonial(t).achievement(achievement).build());
        }
        return testimonialRepository.saveAndFlush(t);
    }

    Testimonial testimonial(TestimonialStatus status, List<Topic> topics) {
        return testimonial(status, topics, List.of(), List.of());
    }
}
