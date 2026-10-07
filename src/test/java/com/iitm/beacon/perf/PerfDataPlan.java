package com.iitm.beacon.perf;

import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.perf.PerfCatalog.TopicRef;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A production-sized, deterministic dataset for the performance pass
 * (docs/nfr.md: "hundreds of approved testimonials"), planned in memory from
 * a {@link PerfCatalog} and a seed — the same seed always plans the same
 * data — before {@link PerfDataSeeder} saves it.
 *
 * <p>{@value #APPROVED} approved testimonials from {@value #COUNTRIES}
 * countries — every country has at least one, the rest follow a Zipf-like
 * spread, so one country is clearly the most populous — plus {@value
 * #PENDING} pending ones (every one with contacts; every third an edit of an
 * approved one, carrying modified flags) and {@value #REJECTED} rejected
 * ones, in mixed insertion order. Each has {@value #MIN_SECTIONS}-{@value
 * #MAX_SECTIONS} sections of distinct topics, each answer {@value
 * #MIN_ANSWER}-{@value #MAX_ANSWER} characters of realistic sentences, a
 * quarter of which contain {@value #COMMON_WORD} — so searching for it hits
 * nearly every row — while {@value #RARE_WORD} is in exactly {@value
 * #RARE_WORD_TESTIMONIALS} approved testimonials; 0-{@value
 * #MAX_PHOTOS_PER_SECTION} photo rows per section (no files: pages only
 * render their URLs), 0-{@value #MAX_ACHIEVEMENTS} achievement ticks, a
 * score of 0-10 and, when approved, a review time no other testimonial has.
 */
final class PerfDataPlan {

    static final long SEED = 20_261_007L;

    static final int APPROVED = 500;
    static final int PENDING = 60;
    static final int REJECTED = 40;
    static final int COUNTRIES = 40;
    static final int MIN_SECTIONS = 3;
    static final int MAX_SECTIONS = 5;
    static final int MIN_ANSWER = 300;
    static final int MAX_ANSWER = 1500;
    static final int MAX_PHOTOS_PER_SECTION = 3;
    static final int MAX_TAGS_PER_PHOTO = 2;
    static final int MAX_ACHIEVEMENTS = 5;
    static final int MAX_CONTACTS = 3;

    /** In a quarter of the answer sentences: a search for it matches nearly every approved testimonial. */
    static final String COMMON_WORD = "campus";

    /** In exactly {@link #RARE_WORD_TESTIMONIALS} approved testimonials, nowhere else. */
    static final String RARE_WORD = "kolam";
    static final int RARE_WORD_TESTIMONIALS = 3;

    /** Where exchange students come from, most frequent first (the Zipf rank). */
    static final List<String> COUNTRY_CODES = List.of(
            "DE", "FR", "US", "IT", "ES", "NL", "SE", "CH", "AT", "GB",
            "JP", "KR", "CN", "SG", "AU", "CA", "BE", "DK", "NO", "FI",
            "PL", "CZ", "PT", "IE", "IL", "TW", "BR", "MX", "AR", "CL",
            "ZA", "NZ", "HU", "RO", "GR", "TR", "EE", "LT", "SK", "SI");

    private static final List<String> SENTENCES = List.of(
            "The campus is huge and green, and I often saw deer and monkeys on my way to lectures.",
            "Most professors were approachable and happy to discuss assignments after class.",
            "Cycling across campus at night was one of my favourite routines.",
            "The mess food was spicy at first, but by the end of the semester I missed it every day.",
            "Chennai is hot and humid for most of the year, so pack light cotton clothes.",
            "The international office helped me register my residential permit within the first two weeks.",
            "I joined the campus football team and played every evening after the heat went down.",
            "Weekend trips to Pondicherry and Mahabalipuram were cheap and easy to organise by bus.",
            "Lectures were more theory-heavy than at my home university, with frequent quizzes.",
            "The library stays open late, which made exam preparation much easier.",
            "Getting a local SIM card took some patience, so bring several passport photos.",
            "Festivals like Saarang and Shaastra bring the whole campus together for several days.",
            "My hostel room was basic but clean, with a fan, a desk and a small cupboard.",
            "Auto-rickshaws are the easiest way to get around the city, but agree on the price first.",
            "I made friends from all over India and from a dozen other countries.",
            "The workload was manageable if you kept up with the weekly assignments.",
            "Campus Wi-Fi was reliable in the academic zone and a bit slower in the hostels.",
            "Learning a few words of Tamil made shopkeepers and drivers much friendlier.",
            "The research lab I worked in treated me like a regular member of the team.",
            "Healthcare was free for minor issues, and the hospital staff spoke English.",
            "I travelled to Kerala during the mid-semester break and loved the backwaters.",
            "Living on campus meant everything I needed was within a short walk or bike ride.",
            "Budgeting was easy because food, transport and accommodation are inexpensive.",
            "The first weeks were overwhelming, but the buddy programme helped a lot.",
            "Classes start early in the morning, so the campus is quiet and pleasant at sunrise.",
            "I would definitely do this exchange again and recommend it to anyone curious about India.",
            "Credit transfer back home required some paperwork, but my coordinator handled most of it.",
            "There are many student clubs, from music and dance to robotics and entrepreneurship.",
            "The canteens on campus serve dosas, parottas and fresh juice until late at night.",
            "Monsoon season brings heavy rain, so a good umbrella and quick-dry shoes are essential.",
            "Group projects were a great way to get to know local students.",
            "I picked up cooking a few South Indian dishes from my hostel neighbours.",
            "Markets in the city have everything you might have forgotten to pack.",
            "Some of my best memories are the late conversations on the hostel terrace.",
            "Exams were demanding, but professors were fair and transparent about grading.",
            "Walking around campus between classes, you pass lakes, temples and old banyan trees.");

    private static final String RARE_SENTENCE =
            "On Pongal morning my neighbour showed me how to draw a kolam with rice flour in front of the hostel.";

    private static final List<String> FIRST_NAMES = List.of(
            "Anna", "Lukas", "Sophie", "Jonas", "Marie", "Felix", "Emma", "Paul", "Lea", "Noah",
            "Chloe", "Hugo", "Camille", "Louis", "Ines", "Mateo", "Lucia", "Pablo", "Sofia", "Diego",
            "Giulia", "Marco", "Yuki", "Haruto", "Mei", "Jiwoo", "Minjun", "Olivia", "Liam", "Ava",
            "Ethan", "Freya", "Oskar", "Astrid", "Mikkel", "Elif", "Can", "Zofia", "Jakub", "Tereza");

    private static final List<String> LAST_NAMES = List.of(
            "Schmidt", "Dubois", "Martin", "Garcia", "Rossi", "Tanaka", "Kim", "Smith", "Jensen", "Nowak",
            "Silva", "Costa", "Yilmaz", "Andersson", "Virtanen", "Kowalski", "Fischer", "Weber", "Moreau",
            "Laurent", "Fernandez", "Romano", "Sato", "Park", "Brown", "Larsen", "Horvath", "Popescu",
            "Ivanova", "Dvorak", "Bakker", "De Vries", "Lindqvist", "Murphy", "Novotna", "Peeters", "Janssen");

    private static final List<String> PHOTO_TAGS = List.of(
            "hostel", "food", "friends", "festival", "travel", "beach", "lab", "library", "temple",
            "sunset", "monsoon", "market", "train", "lecture hall");

    /** Full-size WebP dimensions as the photo pipeline stores them (long edge 2560 at most). */
    private static final int[][] PHOTO_SIZES = {{2560, 1920}, {1920, 2560}, {2560, 1440}, {1600, 1600}, {1440, 2560}};

    private static final Instant APPROVED_BASE = Instant.parse("2025-01-06T08:00:00Z");
    private static final Instant PENDING_BASE = Instant.parse("2026-08-01T09:00:00Z");
    private static final Instant REJECTED_BASE = Instant.parse("2026-09-08T09:00:00Z");

    private final List<PlannedTestimonial> testimonials;

    private PerfDataPlan(List<PlannedTestimonial> testimonials) {
        this.testimonials = List.copyOf(testimonials);
    }

    /**
     * Plans the dataset from {@code catalog} and {@code seed}.
     *
     * @throws IllegalArgumentException when the catalog can't supply it: fewer
     *     than {@value #MAX_SECTIONS} topics, no standalone topic, no grouped
     *     topic, fewer than {@value #MAX_ACHIEVEMENTS} achievements, no contact
     *     type, or a slug listed twice
     */
    static PerfDataPlan generate(PerfCatalog catalog, long seed) {
        Objects.requireNonNull(catalog, "catalog");
        check(catalog);
        return new Generator(catalog, seed).generate();
    }

    private static void check(PerfCatalog catalog) {
        List<String> topicSlugs = catalog.topics().stream().map(TopicRef::slug).toList();
        if (topicSlugs.size() < MAX_SECTIONS) {
            throw new IllegalArgumentException("The catalog needs at least " + MAX_SECTIONS + " topics, has "
                    + topicSlugs.size());
        }
        if (catalog.topics().stream().noneMatch(topic -> topic.groupId() == null)) {
            throw new IllegalArgumentException("The catalog needs at least one standalone topic");
        }
        if (catalog.topics().stream().allMatch(topic -> topic.groupId() == null)) {
            throw new IllegalArgumentException("The catalog needs at least one topic in a group");
        }
        if (catalog.achievementSlugs().size() < MAX_ACHIEVEMENTS) {
            throw new IllegalArgumentException("The catalog needs at least " + MAX_ACHIEVEMENTS + " achievements, has "
                    + catalog.achievementSlugs().size());
        }
        if (catalog.contactTypeSlugs().isEmpty()) {
            throw new IllegalArgumentException("The catalog needs contact types, has none");
        }
        requireNoDuplicates("topic", topicSlugs);
        requireNoDuplicates("achievement", catalog.achievementSlugs());
        requireNoDuplicates("contact type", catalog.contactTypeSlugs());
    }

    private static void requireNoDuplicates(String kind, Collection<String> slugs) {
        if (new HashSet<>(slugs).size() != slugs.size()) {
            throw new IllegalArgumentException("The catalog lists a duplicate " + kind + " slug: " + slugs);
        }
    }

    /** Every planned testimonial, in the order they are to be saved. */
    List<PlannedTestimonial> testimonials() {
        return testimonials;
    }

    List<PlannedTestimonial> withStatus(TestimonialStatus status) {
        return testimonials.stream().filter(t -> t.status() == status).toList();
    }

    /** The country with the most approved testimonials (on a tie, the first code alphabetically). */
    String mostPopulousCountry() {
        Map<String, Long> perCountry = withStatus(TestimonialStatus.APPROVED).stream()
                .collect(Collectors.groupingBy(PlannedTestimonial::countryCode, Collectors.counting()));
        return perCountry.entrySet().stream()
                .min(Comparator.comparing(Map.Entry<String, Long>::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .orElseThrow()
                .getKey();
    }

    record PlannedTestimonial(
            TestimonialStatus status,
            String firstName,
            String lastName,
            String rollNumber,
            int admissionYear,
            String email,
            String countryCode,
            int score,
            Instant createdAt,
            Instant reviewedAt,
            Instant rejectedAt,
            boolean identityModified,
            boolean scoreModified,
            List<PlannedSection> sections,
            List<PlannedContact> contacts,
            List<String> achievementSlugs) {

        PlannedTestimonial {
            sections = List.copyOf(sections);
            contacts = List.copyOf(contacts);
            achievementSlugs = List.copyOf(achievementSlugs);
        }
    }

    /** A section; its photos are listed in display order. */
    record PlannedSection(String topicSlug, String answerText, boolean modified, List<PlannedPhoto> photos) {

        PlannedSection {
            photos = List.copyOf(photos);
        }
    }

    record PlannedPhoto(String filePath, String thumbnailPath, int width, int height, List<String> tags) {

        PlannedPhoto {
            tags = List.copyOf(tags);
        }
    }

    record PlannedContact(String typeSlug, String value, boolean isPublic) {
    }

    /** One generation run: all randomness comes from one {@link Random} seeded once. */
    private static final class Generator {

        private final PerfCatalog catalog;
        private final long seed;
        private final Random random;
        private final double[] countryWeights;
        private int photoCounter;
        private int contactCounter;

        Generator(PerfCatalog catalog, long seed) {
            this.catalog = catalog;
            this.seed = seed;
            this.random = new Random(seed);
            this.countryWeights = IntStream.rangeClosed(1, COUNTRIES).mapToDouble(rank -> 1.0 / rank).toArray();
        }

        PerfDataPlan generate() {
            List<PlannedTestimonial> all = new ArrayList<>();
            List<Integer> reviewSlots = new ArrayList<>(IntStream.range(0, APPROVED).boxed().toList());
            Collections.shuffle(reviewSlots, random);
            for (int i = 0; i < APPROVED; i++) {
                all.add(approved(i, reviewSlots.get(i)));
            }
            for (int i = 0; i < PENDING; i++) {
                all.add(pending(i));
            }
            for (int i = 0; i < REJECTED; i++) {
                all.add(rejected(i));
            }
            Collections.shuffle(all, random);
            return new PerfDataPlan(all);
        }

        /** The first {@value #COUNTRIES} cover every country once; the rest follow the Zipf spread. */
        private PlannedTestimonial approved(int index, int reviewSlot) {
            String country = index < COUNTRIES ? COUNTRY_CODES.get(index) : weightedCountry();
            Instant reviewedAt = APPROVED_BASE.plus(Duration.ofHours(7L * reviewSlot))
                    .plus(Duration.ofMinutes(random.nextInt(60)));
            Instant createdAt = reviewedAt.minus(Duration.ofHours(1 + random.nextInt(72)));
            List<PlannedSection> sections = sections(index < RARE_WORD_TESTIMONIALS, false);
            return testimonial(TestimonialStatus.APPROVED, "approved", index, country, createdAt, reviewedAt, null,
                    false, false, sections, contacts(0));
        }

        /** Every third is an edit of an approved testimonial: reviewed before, now with modified flags. */
        private PlannedTestimonial pending(int index) {
            boolean edit = index % 3 == 0;
            Instant createdAt = PENDING_BASE.plus(Duration.ofHours(11L * index));
            Instant reviewedAt = null;
            if (edit) {
                createdAt = createdAt.minus(Duration.ofDays(200));
                reviewedAt = createdAt.plus(Duration.ofDays(2));
            }
            List<PlannedSection> sections = sections(false, edit);
            return testimonial(TestimonialStatus.PENDING, "pending", index, weightedCountry(), createdAt, reviewedAt,
                    null, edit && random.nextBoolean(), edit && random.nextBoolean(), sections, contacts(1));
        }

        private PlannedTestimonial rejected(int index) {
            Instant createdAt = REJECTED_BASE.plus(Duration.ofHours(13L * index));
            Instant rejectedAt = createdAt.plus(Duration.ofDays(1));
            return testimonial(TestimonialStatus.REJECTED, "rejected", index, weightedCountry(), createdAt,
                    rejectedAt, rejectedAt, false, false, sections(false, false), contacts(0));
        }

        private PlannedTestimonial testimonial(
                TestimonialStatus status,
                String emailPrefix,
                int index,
                String country,
                Instant createdAt,
                Instant reviewedAt,
                Instant rejectedAt,
                boolean identityModified,
                boolean scoreModified,
                List<PlannedSection> sections,
                List<PlannedContact> contacts) {
            int admissionYear = 2018 + random.nextInt(8);
            return new PlannedTestimonial(
                    status,
                    pick(FIRST_NAMES),
                    pick(LAST_NAMES),
                    String.format("GE%02dZ%03d", admissionYear % 100, random.nextInt(1000)),
                    admissionYear,
                    "perf." + emailPrefix + "." + index + "@example.com",
                    country,
                    random.nextInt(11),
                    createdAt,
                    reviewedAt,
                    rejectedAt,
                    identityModified,
                    scoreModified,
                    sections,
                    contacts,
                    sample(catalog.achievementSlugs(), random.nextInt(MAX_ACHIEVEMENTS + 1)));
        }

        /** An edit has at least one modified section; a new or approved testimonial none. */
        private List<PlannedSection> sections(boolean withRareWord, boolean edit) {
            int count = MIN_SECTIONS + random.nextInt(MAX_SECTIONS - MIN_SECTIONS + 1);
            List<TopicRef> topics = sample(catalog.topics(), count);
            int alwaysModified = edit ? random.nextInt(count) : -1;
            List<PlannedSection> sections = new ArrayList<>();
            for (int s = 0; s < count; s++) {
                boolean modified = edit && (s == alwaysModified || random.nextBoolean());
                sections.add(new PlannedSection(
                        topics.get(s).slug(), answer(withRareWord && s == 0), modified, photos()));
            }
            return sections;
        }

        /** Whole sentences up to a random length in [MIN_ANSWER, MAX_ANSWER]; no sentence is cut. */
        private String answer(boolean withRareWord) {
            int target = MIN_ANSWER + random.nextInt(MAX_ANSWER - MIN_ANSWER + 1);
            StringBuilder answer = new StringBuilder(withRareWord ? RARE_SENTENCE : "");
            while (answer.length() < target) {
                String sentence = pick(SENTENCES);
                int separator = answer.isEmpty() ? 0 : 1;
                if (answer.length() + separator + sentence.length() > MAX_ANSWER) {
                    break;
                }
                answer.append(separator == 0 ? "" : " ").append(sentence);
            }
            return answer.toString();
        }

        private List<PlannedPhoto> photos() {
            int count = random.nextInt(MAX_PHOTOS_PER_SECTION + 1);
            List<PlannedPhoto> photos = new ArrayList<>();
            for (int p = 0; p < count; p++) {
                String id = UUID.nameUUIDFromBytes(
                        ("perf-photo-" + seed + "-" + photoCounter++).getBytes(StandardCharsets.UTF_8)).toString();
                int[] size = PHOTO_SIZES[random.nextInt(PHOTO_SIZES.length)];
                photos.add(new PlannedPhoto(id + ".webp", id + "-thumb.webp", size[0], size[1],
                        sample(PHOTO_TAGS, random.nextInt(MAX_TAGS_PER_PHOTO + 1))));
            }
            return photos;
        }

        private List<PlannedContact> contacts(int atLeast) {
            int most = Math.min(MAX_CONTACTS, catalog.contactTypeSlugs().size());
            int count = atLeast + random.nextInt(most - atLeast + 1);
            return sample(catalog.contactTypeSlugs(), count).stream()
                    .map(type -> new PlannedContact(type, contactValue(type, contactCounter++), random.nextBoolean()))
                    .toList();
        }

        private static String contactValue(String typeSlug, int n) {
            return switch (typeSlug) {
                case "email" -> "contact" + n + "@example.org";
                case "whatsapp" -> String.format("+49 151 %07d", n);
                case "telegram" -> "@perf_user_" + n;
                case "instagram" -> "@perf.user." + n;
                case "twitter" -> "@perfuser" + n;
                default -> "perf-contact-" + n;
            };
        }

        private String weightedCountry() {
            double total = 0;
            for (double weight : countryWeights) {
                total += weight;
            }
            double point = random.nextDouble() * total;
            for (int i = 0; i < countryWeights.length; i++) {
                point -= countryWeights[i];
                if (point < 0) {
                    return COUNTRY_CODES.get(i);
                }
            }
            return COUNTRY_CODES.get(COUNTRY_CODES.size() - 1);
        }

        private <T> T pick(List<T> values) {
            return values.get(random.nextInt(values.size()));
        }

        /** {@code count} distinct elements of {@code values}, in random order. */
        private <T> List<T> sample(List<T> values, int count) {
            List<T> shuffled = new ArrayList<>(values);
            Collections.shuffle(shuffled, random);
            return List.copyOf(shuffled.subList(0, count));
        }
    }
}
