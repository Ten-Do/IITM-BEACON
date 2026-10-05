package com.iitm.beacon.e2e;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.moderation.ModerationService;
import com.iitm.beacon.submission.PhotoStorageService;
import com.iitm.beacon.submission.StoredPhoto;
import com.iitm.beacon.testsupport.TestImages;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds and wipes what the e2e tests render, through the app's real
 * repositories, {@link PhotoStorageService} (photos are converted like any
 * upload) and {@link ModerationService} (approval).
 *
 * <p>Deterministic screenshots: {@link #clear()} (run before every test)
 * deletes every testimonial and photo file and restarts testimonial ids at 1
 * (the queue prints the id); each seeded testimonial's {@code createdAt}
 * (the queue prints and orders by it) is 2026-09-01T09:00Z plus one minute
 * per testimonial seeded since. Photos are flat-colour synthetic pictures.
 *
 * <p>Registered only in the e2e tests' context, through {@code @Import} on
 * {@link E2eTestBase}.
 */
public class E2eData {

    static final Color MAROON = new Color(174, 21, 45);
    static final Color SAND = new Color(247, 244, 239);
    static final Color GOLD = new Color(214, 166, 79);
    static final Color OLIVE = new Color(75, 112, 67);
    static final Color NAVY = new Color(34, 52, 94);
    static final Color TEAL = new Color(32, 128, 128);
    static final Color WHITE = new Color(250, 250, 250);

    private static final Instant BASE_TIME = Instant.parse("2026-09-01T09:00:00Z");

    private final TestimonialRepository testimonials;
    private final CountryRepository countries;
    private final TopicRepository topics;
    private final ContactTypeRepository contactTypes;
    private final AchievementRepository achievements;
    private final EmailLookupHashService emailLookupHashes;
    private final PhotoStorageService photoStorage;
    private final ModerationService moderation;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;
    private int seededSinceClear;

    public E2eData(
            TestimonialRepository testimonials,
            CountryRepository countries,
            TopicRepository topics,
            ContactTypeRepository contactTypes,
            AchievementRepository achievements,
            EmailLookupHashService emailLookupHashes,
            PhotoStorageService photoStorage,
            ModerationService moderation,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc) {
        this.testimonials = testimonials;
        this.countries = countries;
        this.topics = topics;
        this.contactTypes = contactTypes;
        this.achievements = achievements;
        this.emailLookupHashes = emailLookupHashes;
        this.photoStorage = photoStorage;
        this.moderation = moderation;
        this.transaction = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    void clear() {
        List<Photo> photos = transaction.execute(status -> {
            List<Testimonial> all = testimonials.findAll();
            List<Photo> stored = all.stream()
                    .flatMap(testimonial -> testimonial.getSections().stream())
                    .flatMap(section -> section.getPhotos().stream())
                    .toList();
            testimonials.deleteAll(all);
            return stored;
        });
        photos.forEach(photoStorage::delete);
        jdbc.execute("ALTER TABLE testimonial ALTER COLUMN id RESTART WITH 1");
        seededSinceClear = 0;
    }

    // -- shared fixtures --

    /**
     * An approved article, photos in viewer order: landscape (tags "campus",
     * "lecture hall") and portrait ("library") under Academics / Teaching
     * Quality, then a square one without tags under General; two public
     * contacts, one private; two achievements. Returns its id.
     */
    long approvedArticle() {
        return testimonial("Ana", "Pereira").country("PT").score(9)
                .section("academics_teaching",
                        "The professors were approachable and expected a lot of independent reading.",
                        photo(1600, 1000, NAVY, GOLD, "campus", "lecture hall"),
                        photo(900, 1350, OLIVE, WHITE, "library"))
                .section("housing_food", "The mess food was spicy at first; by the end of the term I missed it.")
                .section("general", "Come with an open mind and a light suitcase.", photo(1000, 1000, MAROON, SAND))
                .contact("email", "ana.pereira@example.com", true)
                .contact("telegram", "@ana_pereira", true)
                .contact("whatsapp", "+351 912 345 678", false)
                .achievements("made_new_friends", "traveled_within_india")
                .approved();
    }

    /** Two pending testimonials: the first with two tagged photos, the second with three over two topics. */
    void twoPendingTestimonials() {
        testimonial("Jonas", "Weber").score(7)
                .section("travel_recommend", "Take the overnight train to Kerala and spend a day on the backwaters.",
                        photo(1200, 800, TEAL, SAND, "backwaters", "kerala"),
                        photo(800, 1200, GOLD, NAVY, "train"))
                .contact("email", "jonas.weber@example.com", false)
                .contact("telegram", "@jonas_weber", true)
                .achievements("traveled_within_india")
                .pending();
        testimonial("Mei", "Tanaka").country("JP").score(10)
                .section("campus_events", "Shaastra and Saarang were the highlights of my semester.",
                        photo(1200, 900, MAROON, GOLD, "saarang"), photo(900, 900, NAVY, WHITE))
                .section("general", "Everyone was welcoming from the first day.", photo(1000, 700, OLIVE, SAND))
                .pending();
    }

    // -- building blocks --

    Seed testimonial(String firstName, String lastName) {
        return new Seed(firstName, lastName);
    }

    static SeedPhoto photo(int width, int height, Color background, Color shape, String... tags) {
        return new SeedPhoto(scene(width, height, background, shape), List.of(tags));
    }

    /** A {@code background} canvas with a {@code shape}-coloured ellipse over its middle three fifths. */
    static BufferedImage scene(int width, int height, Color background, Color shape) {
        BufferedImage image = TestImages.solid(width, height, background);
        Graphics2D g = image.createGraphics();
        g.setColor(shape);
        g.fillOval(width / 5, height / 5, width * 3 / 5, height * 3 / 5);
        g.dispose();
        return image;
    }

    record SeedPhoto(BufferedImage image, List<String> tags) {
    }

    private record SeedSection(String topicSlug, String answer, List<SeedPhoto> photos) {
    }

    private record SeedContact(String typeSlug, String value, boolean isPublic) {
    }

    /** A testimonial under construction (default: Germany, score 8, email first.last@example.com). */
    final class Seed {

        private final String firstName;
        private final String lastName;
        private String email;
        private String countryCode = "DE";
        private int score = 8;
        private final List<SeedSection> sections = new ArrayList<>();
        private final List<SeedContact> contacts = new ArrayList<>();
        private final List<String> achievementSlugs = new ArrayList<>();

        private Seed(String firstName, String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
            this.email = (firstName + "." + lastName + "@example.com").toLowerCase(Locale.ROOT);
        }

        Seed email(String email) {
            this.email = email;
            return this;
        }

        Seed country(String countryCode) {
            this.countryCode = countryCode;
            return this;
        }

        Seed score(int score) {
            this.score = score;
            return this;
        }

        Seed section(String topicSlug, String answer, SeedPhoto... photos) {
            sections.add(new SeedSection(topicSlug, answer, List.of(photos)));
            return this;
        }

        Seed contact(String typeSlug, String value, boolean isPublic) {
            contacts.add(new SeedContact(typeSlug, value, isPublic));
            return this;
        }

        Seed achievements(String... slugs) {
            achievementSlugs.addAll(List.of(slugs));
            return this;
        }

        long pending() {
            List<List<StoredPhoto>> files = sections.stream()
                    .map(section -> section.photos().stream().map(E2eData.this::store).toList())
                    .toList();
            Instant createdAt = BASE_TIME.plus(Duration.ofMinutes(seededSinceClear++));
            return transaction.execute(status -> {
                Testimonial testimonial = Testimonial.builder()
                        .firstName(firstName).lastName(lastName).rollNumber("CS21B001").admissionYear(2021)
                        .email(email).emailLookupHash(emailLookupHashes.hash(email))
                        .country(countries.getReferenceById(countryCode))
                        .recommendationScore(score).dataProcessingConsent(true)
                        .status(TestimonialStatus.PENDING).createdAt(createdAt)
                        .build();
                for (int s = 0; s < sections.size(); s++) {
                    testimonial.getSections().add(section(testimonial, sections.get(s), files.get(s)));
                }
                for (SeedContact contact : contacts) {
                    testimonial.getContactMethods().add(ContactMethod.builder().testimonial(testimonial)
                            .contactType(contactTypes.findBySlug(contact.typeSlug()).orElseThrow())
                            .value(contact.value()).isPublic(contact.isPublic())
                            .displayOrder(testimonial.getContactMethods().size())
                            .build());
                }
                for (String slug : achievementSlugs) {
                    testimonial.getAchievements().add(TestimonialAchievement.builder().testimonial(testimonial)
                            .achievement(achievements.findBySlug(slug).orElseThrow())
                            .build());
                }
                return testimonials.save(testimonial).getId();
            });
        }

        long approved() {
            long id = pending();
            moderation.approve(id);
            return id;
        }

        private TestimonialSection section(Testimonial testimonial, SeedSection seed, List<StoredPhoto> files) {
            TestimonialSection section = TestimonialSection.builder().testimonial(testimonial)
                    .topic(topics.findBySlug(seed.topicSlug()).orElseThrow())
                    .answerText(seed.answer())
                    .build();
            for (int p = 0; p < files.size(); p++) {
                StoredPhoto file = files.get(p);
                Photo photo = Photo.builder().section(section)
                        .filePath(file.filePath()).thumbnailPath(file.thumbnailPath())
                        .width(file.width()).height(file.height()).displayOrder(p)
                        .build();
                seed.photos().get(p).tags()
                        .forEach(tag -> photo.getTags().add(PhotoTag.builder().photo(photo).tagText(tag).build()));
                section.getPhotos().add(photo);
            }
            return section;
        }
    }

    private StoredPhoto store(SeedPhoto photo) {
        byte[] png = TestImages.png(photo.image());
        return photoStorage.store(new MockMultipartFile("photo", "photo.png", "image/png", png));
    }
}
