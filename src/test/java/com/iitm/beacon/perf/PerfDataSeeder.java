package com.iitm.beacon.perf;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.contacttype.ContactType;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.perf.PerfCatalog.TopicRef;
import com.iitm.beacon.perf.PerfDataPlan.PlannedContact;
import com.iitm.beacon.perf.PerfDataPlan.PlannedPhoto;
import com.iitm.beacon.perf.PerfDataPlan.PlannedSection;
import com.iitm.beacon.perf.PerfDataPlan.PlannedTestimonial;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saves a {@link PerfDataPlan} through the app's own repositories and
 * entities — so {@code EncryptedValueConverter} encrypts every email and
 * contact value and {@link EmailLookupHashService} hashes every email, as in
 * production — {@value #BATCH} testimonials per transaction. Photo rows only:
 * no file is written (the measured pages render photo URLs, never files).
 */
final class PerfDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(PerfDataSeeder.class);

    private static final int BATCH = 50;

    private final TestimonialRepository testimonials;
    private final CountryRepository countries;
    private final TopicRepository topics;
    private final AchievementRepository achievements;
    private final ContactTypeRepository contactTypes;
    private final EmailLookupHashService emailLookupHashes;
    private final TransactionTemplate transaction;

    PerfDataSeeder(
            TestimonialRepository testimonials,
            CountryRepository countries,
            TopicRepository topics,
            AchievementRepository achievements,
            ContactTypeRepository contactTypes,
            EmailLookupHashService emailLookupHashes,
            PlatformTransactionManager transactionManager) {
        this.testimonials = testimonials;
        this.countries = countries;
        this.topics = topics;
        this.achievements = achievements;
        this.contactTypes = contactTypes;
        this.emailLookupHashes = emailLookupHashes;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The active topics (with their group), achievements and contact types in the database, by slug. */
    PerfCatalog loadCatalog() {
        return transaction.execute(status -> new PerfCatalog(
                topics.findAllByOrderByDisplayOrderAscIdAsc().stream()
                        .filter(Topic::isVisible)
                        .map(topic -> new TopicRef(topic.getSlug(),
                                topic.getTopicGroup() == null ? null : topic.getTopicGroup().getId()))
                        .toList(),
                achievements.findAllByOrderByDisplayOrderAscIdAsc().stream()
                        .filter(Achievement::isVisible).map(Achievement::getSlug).toList(),
                contactTypes.findAll().stream()
                        .filter(ContactType::isActive).map(ContactType::getSlug).toList()));
    }

    /**
     * Saves {@code plan} into a database without testimonials. Seeds once:
     * a database already holding exactly the plan's number of testimonials
     * (this run seeded it before) is left alone.
     *
     * @return whether anything was saved
     * @throws IllegalStateException when the database holds some other number of testimonials
     */
    boolean seedOnce(PerfDataPlan plan) {
        long existing = testimonials.count();
        if (existing == plan.testimonials().size()) {
            log.info("Perf dataset already seeded ({} testimonials), not seeding again", existing);
            return false;
        }
        if (existing != 0) {
            throw new IllegalStateException("The perf database holds " + existing + " testimonials, expected none or "
                    + plan.testimonials().size() + "; use a database of its own");
        }
        List<String> missing = PerfDataPlan.COUNTRY_CODES.stream().filter(code -> !countries.existsById(code)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Countries missing from the database: " + missing);
        }
        List<PlannedTestimonial> all = plan.testimonials();
        for (int from = 0; from < all.size(); from += BATCH) {
            List<PlannedTestimonial> batch = all.subList(from, Math.min(from + BATCH, all.size()));
            transaction.executeWithoutResult(status -> saveBatch(batch));
        }
        return true;
    }

    private void saveBatch(List<PlannedTestimonial> batch) {
        Map<String, Topic> topicsBySlug = bySlug(topics.findAll(), Topic::getSlug);
        Map<String, Achievement> achievementsBySlug = bySlug(achievements.findAll(), Achievement::getSlug);
        Map<String, ContactType> contactTypesBySlug = bySlug(contactTypes.findAll(), ContactType::getSlug);
        testimonials.saveAll(batch.stream()
                .map(planned -> toEntity(planned, topicsBySlug, achievementsBySlug, contactTypesBySlug))
                .toList());
    }

    private Testimonial toEntity(
            PlannedTestimonial planned,
            Map<String, Topic> topicsBySlug,
            Map<String, Achievement> achievementsBySlug,
            Map<String, ContactType> contactTypesBySlug) {
        Country country = countries.getReferenceById(planned.countryCode());
        Testimonial testimonial = Testimonial.builder()
                .firstName(planned.firstName())
                .lastName(planned.lastName())
                .rollNumber(planned.rollNumber())
                .admissionYear(planned.admissionYear())
                .email(planned.email())
                .emailLookupHash(emailLookupHashes.hash(planned.email()))
                .country(country)
                .recommendationScore(planned.score())
                .dataProcessingConsent(true)
                .status(planned.status())
                .createdAt(planned.createdAt())
                .reviewedAt(planned.reviewedAt())
                .rejectedAt(planned.rejectedAt())
                .identityModified(planned.identityModified())
                .scoreModified(planned.scoreModified())
                .build();
        for (PlannedSection plannedSection : planned.sections()) {
            testimonial.getSections().add(toEntity(testimonial, plannedSection, topicsBySlug));
        }
        for (PlannedContact contact : planned.contacts()) {
            testimonial.getContactMethods().add(ContactMethod.builder()
                    .testimonial(testimonial)
                    .contactType(required(contactTypesBySlug, contact.typeSlug()))
                    .value(contact.value())
                    .isPublic(contact.isPublic())
                    .displayOrder(testimonial.getContactMethods().size())
                    .build());
        }
        for (String slug : planned.achievementSlugs()) {
            testimonial.getAchievements().add(TestimonialAchievement.builder()
                    .testimonial(testimonial)
                    .achievement(required(achievementsBySlug, slug))
                    .build());
        }
        return testimonial;
    }

    private static TestimonialSection toEntity(
            Testimonial testimonial, PlannedSection planned, Map<String, Topic> topicsBySlug) {
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(required(topicsBySlug, planned.topicSlug()))
                .answerText(planned.answerText())
                .modified(planned.modified())
                .build();
        List<PlannedPhoto> photos = planned.photos();
        for (int order = 0; order < photos.size(); order++) {
            PlannedPhoto plannedPhoto = photos.get(order);
            Photo photo = Photo.builder()
                    .section(section)
                    .filePath(plannedPhoto.filePath())
                    .thumbnailPath(plannedPhoto.thumbnailPath())
                    .width(plannedPhoto.width())
                    .height(plannedPhoto.height())
                    .displayOrder(order)
                    .build();
            plannedPhoto.tags().forEach(tag ->
                    photo.getTags().add(PhotoTag.builder().photo(photo).tagText(tag).build()));
            section.getPhotos().add(photo);
        }
        return section;
    }

    private static <T> Map<String, T> bySlug(List<T> rows, Function<T, String> slug) {
        return rows.stream().collect(Collectors.toMap(slug, Function.identity()));
    }

    private static <T> T required(Map<String, T> bySlug, String slug) {
        T value = bySlug.get(slug);
        if (value == null) {
            throw new IllegalStateException("No such slug in the database: " + slug);
        }
        return value;
    }
}
