package com.iitm.beacon.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.perf.PerfCatalog.TopicRef;
import com.iitm.beacon.perf.PerfDataPlan.PlannedContact;
import com.iitm.beacon.perf.PerfDataPlan.PlannedPhoto;
import com.iitm.beacon.perf.PerfDataPlan.PlannedSection;
import com.iitm.beacon.perf.PerfDataPlan.PlannedTestimonial;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The production-sized dataset the performance pass seeds (docs/nfr.md:
 * "hundreds of approved testimonials"), checked as a plan before it touches
 * a database: its sizes, its spread over countries and topics, and every
 * invariant the schema or the app's own rules put on a real testimonial.
 */
class PerfDataPlanTest {

    /** The shape of the seeded catalog (V13, V15, V14): 9 groups of 2-8 topics, 8 standalone topics. */
    private static final PerfCatalog CATALOG = catalog(new int[] {8, 4, 3, 4, 3, 4, 6, 2, 4}, 8, 24, 5);

    private static final PerfDataPlan PLAN = PerfDataPlan.generate(CATALOG, PerfDataPlan.SEED);

    private static PerfCatalog catalog(int[] topicsPerGroup, int standaloneTopics, int achievements, int contactTypes) {
        List<TopicRef> topics = new ArrayList<>();
        for (int g = 0; g < topicsPerGroup.length; g++) {
            for (int t = 0; t < topicsPerGroup[g]; t++) {
                topics.add(new TopicRef("group" + (g + 1) + "_topic" + (t + 1), (long) g + 1));
            }
        }
        for (int s = 0; s < standaloneTopics; s++) {
            topics.add(new TopicRef("standalone" + (s + 1), null));
        }
        return new PerfCatalog(
                topics,
                IntStream.rangeClosed(1, achievements).mapToObj(i -> "achievement" + i).toList(),
                IntStream.rangeClosed(1, contactTypes).mapToObj(i -> List.of(
                        "email", "whatsapp", "telegram", "instagram", "twitter", "other").get((i - 1) % 6)).toList());
    }

    private static List<PlannedTestimonial> approved() {
        return PLAN.withStatus(TestimonialStatus.APPROVED);
    }

    private static List<PlannedTestimonial> pending() {
        return PLAN.withStatus(TestimonialStatus.PENDING);
    }

    private static List<PlannedTestimonial> rejected() {
        return PLAN.withStatus(TestimonialStatus.REJECTED);
    }

    private static Stream<PlannedSection> sections(List<PlannedTestimonial> testimonials) {
        return testimonials.stream().flatMap(t -> t.sections().stream());
    }

    private static boolean mentions(PlannedTestimonial testimonial, String word) {
        return testimonial.sections().stream()
                .anyMatch(s -> s.answerText().toLowerCase(Locale.ROOT).contains(word));
    }

    // -- sizes --

    @Test
    void sizes_fiveHundredApproved_sixtyPending_fortyRejected() {
        assertThat(approved()).hasSize(500);
        assertThat(pending()).hasSize(60);
        assertThat(rejected()).hasSize(40);
        assertThat(PLAN.testimonials()).hasSize(600);
    }

    @Test
    void theSameSeed_plansTheSameData_anotherSeedOtherData() {
        assertThat(PerfDataPlan.generate(CATALOG, PerfDataPlan.SEED).testimonials())
                .isEqualTo(PLAN.testimonials());
        assertThat(PerfDataPlan.generate(CATALOG, PerfDataPlan.SEED + 1).testimonials())
                .isNotEqualTo(PLAN.testimonials());
    }

    // -- countries --

    @Test
    void countryCodes_areFortyDistinctAlpha2Codes() {
        assertThat(PerfDataPlan.COUNTRY_CODES).hasSize(40).doesNotHaveDuplicates()
                .allSatisfy(code -> assertThat(code).matches("[A-Z]{2}"));
    }

    @Test
    void approved_comeFromAllFortyCountries() {
        assertThat(approved().stream().map(PlannedTestimonial::countryCode).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(PerfDataPlan.COUNTRY_CODES);
        assertThat(PLAN.testimonials()).allSatisfy(t ->
                assertThat(PerfDataPlan.COUNTRY_CODES).contains(t.countryCode()));
    }

    @Test
    void theMostPopulousCountry_hasTheMostApproved_atLeastATenthOfThem() {
        Map<String, Long> perCountry = approved().stream()
                .collect(Collectors.groupingBy(PlannedTestimonial::countryCode, Collectors.counting()));
        long most = perCountry.values().stream().mapToLong(Long::longValue).max().orElseThrow();

        assertThat(perCountry.get(PLAN.mostPopulousCountry())).isEqualTo(most).isGreaterThanOrEqualTo(50);
    }

    // -- sections, topics and answers --

    @Test
    void everyTestimonial_hasThreeToFiveSections_ofDistinctCatalogTopics_bothBoundsOccurring() {
        Set<String> catalogSlugs = CATALOG.topics().stream().map(TopicRef::slug).collect(Collectors.toSet());

        assertThat(PLAN.testimonials()).allSatisfy(t -> {
            assertThat(t.sections()).hasSizeBetween(3, 5);
            List<String> slugs = t.sections().stream().map(PlannedSection::topicSlug).toList();
            assertThat(slugs).doesNotHaveDuplicates();
            assertThat(catalogSlugs).containsAll(slugs);
        });
        assertThat(PLAN.testimonials()).anySatisfy(t -> assertThat(t.sections()).hasSize(3));
        assertThat(PLAN.testimonials()).anySatisfy(t -> assertThat(t.sections()).hasSize(5));
    }

    @Test
    void approvedSections_coverEveryTopicGroupAndEveryStandaloneTopic() {
        Set<String> used = sections(approved()).map(PlannedSection::topicSlug).collect(Collectors.toSet());
        Set<Long> groupsUsed = CATALOG.topics().stream()
                .filter(topic -> topic.groupId() != null && used.contains(topic.slug()))
                .map(TopicRef::groupId)
                .collect(Collectors.toSet());

        assertThat(groupsUsed).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(used).containsAll(CATALOG.topics().stream()
                .filter(topic -> topic.groupId() == null).map(TopicRef::slug).toList());
    }

    @Test
    void everyAnswer_isThreeHundredToFifteenHundredCharacters_spreadOverTheWholeRange() {
        List<Integer> lengths = sections(PLAN.testimonials()).map(s -> s.answerText().length()).toList();

        assertThat(lengths).allSatisfy(length -> assertThat(length).isBetween(300, 1500));
        assertThat(lengths).anySatisfy(length -> assertThat(length).isLessThan(400));
        assertThat(lengths).anySatisfy(length -> assertThat(length).isGreaterThan(1400));
    }

    @Test
    void theCommonWord_isInMostApprovedTestimonials() {
        long hits = approved().stream().filter(t -> mentions(t, PerfDataPlan.COMMON_WORD)).count();

        assertThat(hits).isGreaterThanOrEqualTo(250);
    }

    @Test
    void theRareWord_isInExactlyItsFewApprovedTestimonials_andNowhereElse() {
        assertThat(approved().stream().filter(t -> mentions(t, PerfDataPlan.RARE_WORD)))
                .hasSize(PerfDataPlan.RARE_WORD_TESTIMONIALS);
        assertThat(Stream.concat(pending().stream(), rejected().stream()))
                .noneMatch(t -> mentions(t, PerfDataPlan.RARE_WORD));
        assertThat(PLAN.testimonials()).noneMatch(t ->
                (t.firstName() + " " + t.lastName()).toLowerCase(Locale.ROOT).contains(PerfDataPlan.RARE_WORD));
        assertThat(PerfDataPlan.RARE_WORD_TESTIMONIALS).isBetween(1, 5);
    }

    // -- photos, achievements, scores --

    @Test
    void photos_zeroToThreePerSection_bothBoundsOccurring() {
        assertThat(sections(PLAN.testimonials()))
                .allSatisfy(s -> assertThat(s.photos()).hasSizeBetween(0, 3))
                .anySatisfy(s -> assertThat(s.photos()).isEmpty())
                .anySatisfy(s -> assertThat(s.photos()).hasSize(3));
    }

    @Test
    void photos_haveUniqueFilesAThumbnailASize_andNoTagTwice() {
        List<PlannedPhoto> photos = sections(PLAN.testimonials()).flatMap(s -> s.photos().stream()).toList();

        assertThat(photos).allSatisfy(photo -> {
            assertThat(photo.filePath()).matches("[0-9a-f-]{36}\\.webp");
            assertThat(photo.thumbnailPath()).isEqualTo(photo.filePath().replace(".webp", "-thumb.webp"));
            assertThat(photo.width()).isPositive();
            assertThat(photo.height()).isPositive();
            assertThat(photo.tags()).hasSizeBetween(0, 2).doesNotHaveDuplicates();
        });
        assertThat(photos.stream().map(PlannedPhoto::filePath)).doesNotHaveDuplicates();
        assertThat(photos).anySatisfy(photo -> assertThat(photo.tags()).hasSize(2));
    }

    @Test
    void achievements_zeroToFiveDistinctCatalogTicks_bothBoundsOccurring() {
        assertThat(PLAN.testimonials())
                .allSatisfy(t -> assertThat(t.achievementSlugs()).hasSizeBetween(0, 5).doesNotHaveDuplicates()
                        .isSubsetOf(CATALOG.achievementSlugs()))
                .anySatisfy(t -> assertThat(t.achievementSlugs()).isEmpty())
                .anySatisfy(t -> assertThat(t.achievementSlugs()).hasSize(5));
    }

    @Test
    void scores_zeroToTen_bothBoundsOccurring() {
        assertThat(PLAN.testimonials().stream().map(PlannedTestimonial::score))
                .allSatisfy(score -> assertThat(score).isBetween(0, 10))
                .contains(0, 10);
    }

    // -- per status --

    @Test
    void approved_haveDistinctReviewTimes_noEarlierThanCreation_andNoOpenFlags() {
        assertThat(approved().stream().map(PlannedTestimonial::reviewedAt))
                .doesNotContainNull().doesNotHaveDuplicates();
        assertThat(approved()).allSatisfy(t -> {
            assertThat(t.createdAt()).isBeforeOrEqualTo(t.reviewedAt());
            assertThat(t.rejectedAt()).isNull();
            assertThat(t.identityModified()).isFalse();
            assertThat(t.scoreModified()).isFalse();
            assertThat(t.sections()).noneMatch(PlannedSection::modified);
        });
    }

    @Test
    void pending_allHaveContacts_distinctCreationTimes_andNoRejection() {
        assertThat(pending()).allSatisfy(t -> {
            assertThat(t.contacts()).isNotEmpty();
            assertThat(t.rejectedAt()).isNull();
        });
        assertThat(pending().stream().map(PlannedTestimonial::createdAt)).doesNotHaveDuplicates();
    }

    @Test
    void pending_editsOfApprovedOnes_carryModifiedFlags_newSubmissionsNone() {
        List<PlannedTestimonial> edits = pending().stream().filter(t -> t.reviewedAt() != null).toList();
        List<PlannedTestimonial> fresh = pending().stream().filter(t -> t.reviewedAt() == null).toList();

        assertThat(edits).isNotEmpty()
                .anyMatch(PlannedTestimonial::identityModified)
                .anyMatch(PlannedTestimonial::scoreModified)
                .anyMatch(t -> t.sections().stream().anyMatch(PlannedSection::modified))
                .allSatisfy(t -> assertThat(t.createdAt()).isBefore(t.reviewedAt()));
        assertThat(fresh).isNotEmpty().allSatisfy(t -> {
            assertThat(t.identityModified()).isFalse();
            assertThat(t.scoreModified()).isFalse();
            assertThat(t.sections()).noneMatch(PlannedSection::modified);
        });
    }

    @Test
    void rejected_wereReviewedAndRejectedAtTheSameTime() {
        assertThat(rejected()).allSatisfy(t -> {
            assertThat(t.rejectedAt()).isNotNull().isEqualTo(t.reviewedAt());
            assertThat(t.createdAt()).isBefore(t.rejectedAt());
        });
    }

    // -- people and contacts --

    @Test
    void emails_areUniqueIgnoringCase_soTheirLookupHashesAreToo() {
        assertThat(PLAN.testimonials().stream().map(t -> t.email().toLowerCase(Locale.ROOT)))
                .doesNotHaveDuplicates()
                .allSatisfy(email -> assertThat(email).matches("[a-z0-9.]+@example\\.com"));
    }

    @Test
    void identities_areComplete() {
        assertThat(PLAN.testimonials()).allSatisfy(t -> {
            assertThat(t.firstName()).isNotBlank();
            assertThat(t.lastName()).isNotBlank();
            assertThat(t.rollNumber()).matches("GE\\d{2}Z\\d{3}");
            assertThat(t.admissionYear()).isBetween(2018, 2025);
            assertThat(t.createdAt()).isAfter(Instant.parse("2024-01-01T00:00:00Z"));
        });
    }

    @Test
    void contacts_zeroToThreePerTestimonial_ofDistinctCatalogTypes_withValues_somePublicSomeNot() {
        List<PlannedContact> all = PLAN.testimonials().stream().flatMap(t -> t.contacts().stream()).toList();

        assertThat(PLAN.testimonials()).allSatisfy(t -> {
            assertThat(t.contacts()).hasSizeBetween(0, 3);
            assertThat(t.contacts().stream().map(PlannedContact::typeSlug))
                    .doesNotHaveDuplicates().isSubsetOf(CATALOG.contactTypeSlugs());
        });
        assertThat(all).allSatisfy(c -> assertThat(c.value()).isNotBlank())
                .anyMatch(PlannedContact::isPublic)
                .anyMatch(c -> !c.isPublic());
    }

    // -- the catalog it is planned from --

    @Test
    void aNullCatalog_isRejected() {
        assertThatThrownBy(() -> PerfDataPlan.generate(null, 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void exactlyFiveTopics_oneOfThemStandalone_isEnough_everyFiveSectionTestimonialUsingAll() {
        PerfDataPlan plan = PerfDataPlan.generate(catalog(new int[] {4}, 1, 5, 1), 3);

        assertThat(plan.testimonials()).hasSize(600).allSatisfy(t ->
                assertThat(t.sections().stream().map(PlannedSection::topicSlug)).doesNotHaveDuplicates());
    }

    @Test
    void fewerThanFiveTopics_cannotFillFiveDistinctSections() {
        assertThatThrownBy(() -> PerfDataPlan.generate(catalog(new int[] {3}, 1, 5, 1), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("topics");
    }

    @Test
    void aCatalogWithoutStandaloneTopics_orWithoutGroupedOnes_isRejected() {
        assertThatThrownBy(() -> PerfDataPlan.generate(catalog(new int[] {5}, 0, 5, 1), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("standalone");
        assertThatThrownBy(() -> PerfDataPlan.generate(catalog(new int[0], 5, 5, 1), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("group");
    }

    @Test
    void fewerThanFiveAchievements_cannotFillFiveDistinctTicks() {
        assertThatThrownBy(() -> PerfDataPlan.generate(catalog(new int[] {4}, 1, 4, 1), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("achievements");
    }

    @Test
    void noContactTypes_isRejected() {
        assertThatThrownBy(() -> PerfDataPlan.generate(catalog(new int[] {4}, 1, 5, 0), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contact types");
    }

    @Test
    void duplicateSlugsInTheCatalog_areRejected() {
        PerfCatalog withDuplicateTopic = new PerfCatalog(
                Stream.concat(CATALOG.topics().stream(), Stream.of(CATALOG.topics().get(0))).toList(),
                CATALOG.achievementSlugs(), CATALOG.contactTypeSlugs());
        PerfCatalog withDuplicateAchievement = new PerfCatalog(
                CATALOG.topics(),
                Stream.concat(CATALOG.achievementSlugs().stream(), Stream.of("achievement1")).toList(),
                CATALOG.contactTypeSlugs());

        assertThatThrownBy(() -> PerfDataPlan.generate(withDuplicateTopic, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> PerfDataPlan.generate(withDuplicateAchievement, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
    }

    @Test
    void thePlannedTestimonialsCanNotBeChangedFromOutside() {
        List<PlannedTestimonial> testimonials = PLAN.testimonials();
        PlannedTestimonial first = testimonials.get(0);

        assertThatThrownBy(() -> testimonials.remove(0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.sections().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.sections().get(0).photos().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.achievementSlugs().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
