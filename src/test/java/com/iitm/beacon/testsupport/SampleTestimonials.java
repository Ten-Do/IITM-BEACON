package com.iitm.beacon.testsupport;

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
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Saves testimonials of a chosen {@link Size} for the tests that pin how
 * many SQL statements a page or an operation costs (BL-049): the same thing
 * done for one small testimonial and for many large ones must cost the same.
 *
 * <p>Everything a lazily loaded page would fetch one by one differs here:
 * each testimonial has a country of its own; each of its sections is under
 * a different visible topic, taken from the topic groups in turn and from
 * the standalone topics in between, every second one {@code modified};
 * photos alternate between converted ones and legacy ones (no thumbnail, no
 * size); contacts use one contact type each, in display order, with a value
 * that type accepts, the first public and then alternately private and
 * public; achievement ticks are the first visible achievements. {@link
 * #withTopics} and {@link #inCountry} pin the topics or the country instead.
 * Photo files are never written — their paths only have to be unique.
 */
public final class SampleTestimonials {

    /**
     * Where the samples go, and where their reference data is looked up
     * again on every save — by id, so a persistence context cleared since
     * this fixture was built never sees a detached one.
     */
    private record Repositories(
            TestimonialRepository testimonials,
            TopicRepository topics,
            CountryRepository countries,
            ContactTypeRepository contactTypes,
            AchievementRepository achievements) {
    }

    /** How much of everything one testimonial has. */
    public record Size(int sections, int photosPerSection, int tagsPerPhoto, int contacts, int achievements) {

        /** One of everything. */
        public static final Size SMALLEST = new Size(1, 1, 1, 1, 1);

        /** Several of everything. */
        public static final Size LARGE = new Size(5, 3, 3, 4, 4);
    }

    /** When the first sample testimonial was created; each later one a second after the one before. */
    public static final Instant CREATED_AT_REFERENCE = Instant.parse("2025-01-01T00:00:00Z");

    /** When every rejected sample testimonial was rejected. */
    public static final Instant REJECTED_AT_REFERENCE = Instant.parse("2000-01-01T00:00:00Z");

    /** A value each seeded contact type's {@code valuePattern} accepts. */
    private static final Map<String, String> CONTACT_VALUES = Map.of(
            "email", "sample.person@example.com",
            "whatsapp", "+49 1512 3456789",
            "telegram", "@sample_person",
            "instagram", "@sample.person",
            "twitter", "@sample_person");

    private final Repositories repositories;
    private final EmailLookupHashService emailLookupHashService;
    private final List<Topic> topics;
    private final List<Country> countries;
    private final List<ContactType> contactTypes;
    private final List<Achievement> achievements;
    private final AtomicInteger savedSoFar;

    public SampleTestimonials(
            TestimonialRepository testimonialRepository,
            TopicRepository topicRepository,
            CountryRepository countryRepository,
            ContactTypeRepository contactTypeRepository,
            AchievementRepository achievementRepository,
            EmailLookupHashService emailLookupHashService) {
        this(
                new Repositories(testimonialRepository, topicRepository, countryRepository, contactTypeRepository,
                        achievementRepository),
                emailLookupHashService,
                topicsFromEveryGroupInTurn(topicRepository.findAll()),
                countryRepository.findAll().stream().sorted(Comparator.comparing(Country::getCode)).toList(),
                contactTypeRepository.findAll().stream()
                        .filter(ContactType::isActive)
                        .filter(type -> CONTACT_VALUES.containsKey(type.getSlug()))
                        .sorted(Comparator.comparing(ContactType::getDisplayOrder))
                        .toList(),
                achievementRepository.findAll().stream()
                        .filter(Achievement::isVisible)
                        .sorted(Comparator.comparing(Achievement::getDisplayOrder).thenComparing(Achievement::getId))
                        .toList(),
                new AtomicInteger());
    }

    private SampleTestimonials(
            Repositories repositories,
            EmailLookupHashService emailLookupHashService,
            List<Topic> topics,
            List<Country> countries,
            List<ContactType> contactTypes,
            List<Achievement> achievements,
            AtomicInteger savedSoFar) {
        this.repositories = repositories;
        this.emailLookupHashService = emailLookupHashService;
        this.topics = List.copyOf(topics);
        this.countries = List.copyOf(countries);
        this.contactTypes = contactTypes;
        this.achievements = achievements;
        this.savedSoFar = savedSoFar;
    }

    /**
     * The same samples, but with their sections under {@code topics}, in
     * this order: section {@code i} is under {@code topics.get(i)}, so a
     * {@link Size} may have no more sections than there are topics.
     */
    public SampleTestimonials withTopics(List<Topic> topics) {
        return new SampleTestimonials(
                repositories, emailLookupHashService, topics, countries, contactTypes, achievements, savedSoFar);
    }

    /** The same samples, but every one from {@code country}. */
    public SampleTestimonials inCountry(Country country) {
        return new SampleTestimonials(
                repositories, emailLookupHashService, topics, List.of(country), contactTypes, achievements,
                savedSoFar);
    }

    /** The topic every sample's section {@code index} is under. */
    public Topic topic(int index) {
        return topics.get(index);
    }

    /** The slug of the contact type of every sample's contact {@code index}. */
    public String contactTypeSlug(int index) {
        return contactTypes.get(index).getSlug();
    }

    /** The value every sample's contact of type {@code typeSlug} has — one that type accepts. */
    public static String contactValue(String typeSlug) {
        return Objects.requireNonNull(CONTACT_VALUES.get(typeSlug), typeSlug);
    }

    /** The slug of the achievement every sample's tick {@code index} is of. */
    public String achievementSlug(int index) {
        return achievements.get(index).getSlug();
    }

    /**
     * The visible topics, ordered so that consecutive ones come from
     * different topic groups (or are standalone): the first topic of every
     * group and the first standalone topic, then the second of each, and so on.
     */
    private static List<Topic> topicsFromEveryGroupInTurn(List<Topic> all) {
        Map<Long, List<Topic>> byGroup = new LinkedHashMap<>();
        all.stream()
                .filter(Topic::isVisible)
                .sorted(Comparator.comparing(Topic::getId))
                .forEach(topic -> byGroup
                        .computeIfAbsent(topic.getTopicGroup() == null ? 0L : topic.getTopicGroup().getId(),
                                group -> new ArrayList<>())
                        .add(topic));
        List<Topic> inTurn = new ArrayList<>();
        int longest = byGroup.values().stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < longest; i++) {
            for (List<Topic> group : byGroup.values()) {
                if (i < group.size()) {
                    inTurn.add(group.get(i));
                }
            }
        }
        return inTurn;
    }

    /** {@code count} testimonials of {@code status}, each of {@code size}, saved and flushed. */
    public List<Testimonial> save(int count, TestimonialStatus status, Size size) {
        return IntStream.range(0, count).mapToObj(i -> save(status, size)).toList();
    }

    /**
     * One testimonial of {@code status} and {@code size}, saved and
     * flushed. Each one saved is created a second after the one before; an
     * approved one was approved a minute after it was created, and a
     * rejected one was rejected at {@link #REJECTED_AT_REFERENCE}.
     */
    public Testimonial save(TestimonialStatus status, Size size) {
        int n = savedSoFar.getAndIncrement();
        String email = "sample-" + n + "-" + UUID.randomUUID() + "@example.com";
        Instant created = CREATED_AT_REFERENCE.plusSeconds(n);
        Testimonial testimonial = Testimonial.builder()
                .firstName("Sample")
                .lastName("Person")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(repositories.countries().getReferenceById(countries.get(n % countries.size()).getCode()))
                .recommendationScore(n % 11)
                .dataProcessingConsent(true)
                .status(status)
                .createdAt(created)
                .reviewedAt(reviewedAt(status, created))
                .rejectedAt(status == TestimonialStatus.REJECTED ? REJECTED_AT_REFERENCE : null)
                .build();
        for (int s = 0; s < size.sections(); s++) {
            Topic topic = repositories.topics().getReferenceById(topics.get(s).getId());
            testimonial.getSections().add(section(testimonial, topic, n, s, size));
        }
        for (int c = 0; c < size.contacts(); c++) {
            ContactType known = contactTypes.get(c);
            testimonial.getContactMethods().add(ContactMethod.builder()
                    .testimonial(testimonial)
                    .contactType(repositories.contactTypes().getReferenceById(known.getId()))
                    .value(contactValue(known.getSlug()))
                    .isPublic(c % 2 == 0)
                    .displayOrder(c)
                    .build());
        }
        for (int a = 0; a < size.achievements(); a++) {
            testimonial.getAchievements().add(TestimonialAchievement.builder()
                    .testimonial(testimonial)
                    .achievement(repositories.achievements().getReferenceById(achievements.get(a).getId()))
                    .build());
        }
        return repositories.testimonials().saveAndFlush(testimonial);
    }

    private static Instant reviewedAt(TestimonialStatus status, Instant created) {
        return switch (status) {
            case PENDING -> null;
            case APPROVED -> created.plusSeconds(60);
            case REJECTED -> REJECTED_AT_REFERENCE;
        };
    }

    private TestimonialSection section(Testimonial testimonial, Topic topic, int n, int s, Size size) {
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(topic)
                .answerText("Sample answer " + s + " of testimonial " + n + ".")
                .modified(s % 2 == 1)
                .build();
        for (int p = 0; p < size.photosPerSection(); p++) {
            String name = "sample/" + n + "-" + s + "-" + p + "-" + UUID.randomUUID();
            boolean legacy = p % 2 == 1;
            Photo photo = Photo.builder()
                    .section(section)
                    .filePath(name + (legacy ? ".jpg" : ".webp"))
                    .thumbnailPath(legacy ? null : name + "-thumb.webp")
                    .width(legacy ? null : 1200)
                    .height(legacy ? null : 800)
                    .displayOrder(p)
                    .build();
            for (int t = 0; t < size.tagsPerPhoto(); t++) {
                photo.getTags().add(PhotoTag.builder().photo(photo).tagText("tag " + t).build());
            }
            section.getPhotos().add(photo);
        }
        return section;
    }
}
