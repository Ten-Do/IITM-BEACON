package com.iitm.beacon.gallery;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.AchievementTick;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.testimonial.ContactMethodRepository;
import com.iitm.beacon.domain.testimonial.PhotoRepository;
import com.iitm.beacon.domain.testimonial.PhotoTagRepository;
import com.iitm.beacon.domain.testimonial.PhotoTagText;
import com.iitm.beacon.domain.testimonial.SectionPhoto;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialCover;
import com.iitm.beacon.domain.testimonial.TestimonialHeader;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.testimonial.TestimonialSummary;
import com.iitm.beacon.domain.testimonial.VisibleSection;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service backing the public {@code gallery} slice
 * (UC-BROWSE-APPROVED, UC-FILTER-COUNTRY, UC-FILTER-TOPIC,
 * UC-SEARCH-KEYWORD, UC-EXPAND-TESTIMONIAL, UC-REVEAL-CONTACT). Only ever
 * exposes {@code TestimonialStatus.APPROVED} testimonials (CLAUDE.md: "a
 * testimonial is never publicly visible until an admin approves it").
 *
 * <p>Every read costs a fixed number of queries (BL-049): projections of
 * just what is shown, one query per level — a page's cards, then their
 * covers; an article, then its sections, their photos, the photos' tags —
 * never one more per card, section or photo.
 */
@Service
@Transactional(readOnly = true)
public class GalleryService {

    /**
     * Shared 404 message for {@link #getDetail(Long)} and
     * {@link #revealContact(Long)} — deliberately identical across "doesn't
     * exist", "not approved", and (for {@code revealContact} only) "zero
     * public contact methods", so none of those states is distinguishable
     * from another to an unauthenticated caller.
     */
    static final String TESTIMONIAL_NOT_FOUND_MESSAGE = "Testimonial not found.";

    private static final int PREVIEW_MAX_LENGTH = 200;

    private final TestimonialRepository testimonialRepository;
    private final TestimonialSectionRepository testimonialSectionRepository;
    private final PhotoRepository photoRepository;
    private final PhotoTagRepository photoTagRepository;
    private final ContactMethodRepository contactMethodRepository;
    private final TestimonialAchievementRepository testimonialAchievementRepository;
    private final TopicRepository topicRepository;
    private final PhotoUrlResolver photoUrlResolver;

    public GalleryService(
            TestimonialRepository testimonialRepository,
            TestimonialSectionRepository testimonialSectionRepository,
            PhotoRepository photoRepository,
            PhotoTagRepository photoTagRepository,
            ContactMethodRepository contactMethodRepository,
            TestimonialAchievementRepository testimonialAchievementRepository,
            TopicRepository topicRepository,
            PhotoUrlResolver photoUrlResolver) {
        this.testimonialRepository = testimonialRepository;
        this.testimonialSectionRepository = testimonialSectionRepository;
        this.photoRepository = photoRepository;
        this.photoTagRepository = photoTagRepository;
        this.contactMethodRepository = contactMethodRepository;
        this.testimonialAchievementRepository = testimonialAchievementRepository;
        this.topicRepository = topicRepository;
        this.photoUrlResolver = photoUrlResolver;
    }

    /**
     * Lists approved testimonials matching every supplied filter (decision
     * 8), combined with AND semantics. The topic filter takes topic-group
     * ids and topic ids as separate lists (decision 29), combined with OR;
     * each group id expands server-side to its visible member topics
     * (decisions 11, 28). Search matches only visible sections' text.
     *
     * <p>Always newest approval first ({@link
     * TestimonialSpecifications#newestApprovalFirst()}): only {@code
     * pageable}'s page number and size are used — any sort it carries is
     * ignored, so no caller can change the order.
     */
    public PageResponse<TestimonialCardDto> browse(
            String country, List<Long> groupIds, List<Long> topicIds, String q, Pageable pageable) {
        Specification<Testimonial> spec = Specification.allOf(
                TestimonialSpecifications.statusIs(TestimonialStatus.APPROVED),
                TestimonialSpecifications.countryIs(country),
                topicFilter(groupIds, topicIds),
                TestimonialSpecifications.matchesQuery(q),
                TestimonialSpecifications.newestApprovalFirst());

        Page<TestimonialSummary> page = testimonialRepository.findSummaries(spec, withoutSort(pageable));
        Map<Long, TestimonialCover> covers = coversOf(page.getContent());
        List<TestimonialCardDto> content = page.getContent().stream()
                .map(summary -> toCard(summary, covers.get(summary.id())))
                .toList();
        return PageResponse.of(content, page);
    }

    /**
     * The topic filter, or {@code null} (no filter) when neither list names
     * any id. Once any id is given, a testimonial must have a section of one
     * of the visible topics they resolve to — an unknown or invisible id
     * resolves to nothing, so a filter of only such ids matches nothing
     * (api-spec.yaml). All ids are resolved in one query.
     */
    private Specification<Testimonial> topicFilter(List<Long> groupIds, List<Long> topicIds) {
        Set<Long> requestedGroupIds = nonNullIds(groupIds);
        Set<Long> requestedTopicIds = nonNullIds(topicIds);
        if (requestedGroupIds.isEmpty() && requestedTopicIds.isEmpty()) {
            return null;
        }
        return TestimonialSpecifications.hasAnyTopic(
                topicRepository.findVisibleIdsByTopicGroupIdInOrIdIn(requestedGroupIds, requestedTopicIds));
    }

    /** The same page, unsorted: a sort would replace the gallery's own order. */
    private static Pageable withoutSort(Pageable pageable) {
        return pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())
                : Pageable.unpaged();
    }

    private static Set<Long> nonNullIds(List<Long> ids) {
        if (ids == null) {
            return Set.of();
        }
        return ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * Every card's cover, by testimonial id, in one query — none for an
     * empty page. The answer is fetched one character longer than a preview,
     * so {@link #truncate} can still tell a longer one.
     */
    private Map<Long, TestimonialCover> coversOf(List<TestimonialSummary> summaries) {
        if (summaries.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = summaries.stream().map(TestimonialSummary::id).toList();
        return testimonialSectionRepository.findCoversByTestimonialIdIn(ids, PREVIEW_MAX_LENGTH + 1).stream()
                .collect(Collectors.toMap(TestimonialCover::testimonialId, Function.identity()));
    }

    /**
     * A card: the preview and cover photo come from the testimonial's first
     * visible section in display order ({@code null} {@code cover} when it
     * has none) — the cover is that section's first photo's thumbnail, or
     * its full-size file for a legacy photo.
     */
    private TestimonialCardDto toCard(TestimonialSummary summary, TestimonialCover cover) {
        String previewText = cover == null ? "" : truncate(cover.answerStart());
        String thumbnailUrl = cover == null || cover.photoFilePath() == null
                ? null
                : thumbnailUrl(cover.photoThumbnailPath(), cover.photoFilePath());
        return new TestimonialCardDto(
                summary.id(),
                new CountryDto(summary.countryCode(), summary.countryName()),
                summary.recommendationScore(),
                previewText,
                thumbnailUrl);
    }

    /** The thumbnail's URL, or the full-size file's for a legacy photo stored before thumbnails. */
    private String thumbnailUrl(String thumbnailPath, String filePath) {
        return photoUrlResolver.resolve(thumbnailPath != null ? thumbnailPath : filePath);
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= PREVIEW_MAX_LENGTH) {
            return text;
        }
        return text.substring(0, PREVIEW_MAX_LENGTH) + "...";
    }

    private CountryDto toCountryDto(Country country) {
        return new CountryDto(country.getCode(), country.getName());
    }

    /**
     * Full public detail for one testimonial (UC-EXPAND-TESTIMONIAL). A
     * PENDING/REJECTED testimonial must be indistinguishable from a
     * nonexistent one to an unauthenticated caller (CLAUDE.md), so both
     * "no such id" and "not approved" throw the exact same {@link
     * NotFoundException}. Only sections of visible topics, in display order
     * — by their group's display order (a standalone topic's own, on the
     * same top-level range), then their topic's within the group — and only
     * ticks of visible achievements show (decision 28).
     */
    public TestimonialDetailDto getDetail(Long id) {
        TestimonialHeader header = testimonialRepository
                .findHeaderByIdAndStatus(id, TestimonialStatus.APPROVED)
                .orElseThrow(() -> new NotFoundException(TESTIMONIAL_NOT_FOUND_MESSAGE));
        List<TestimonialSectionViewDto> sections =
                sectionViews(testimonialSectionRepository.findVisibleByTestimonialIdIn(List.of(id)));
        List<String> achievementSlugs = testimonialAchievementRepository
                .findVisibleTicksByTestimonialIdIn(List.of(id)).stream()
                .map(AchievementTick::slug)
                .toList();
        return new TestimonialDetailDto(
                header.id(),
                displayName(header),
                new CountryDto(header.countryCode(), header.countryName()),
                header.recommendationScore(),
                header.status().name(),
                sections,
                achievementSlugs,
                contactMethodRepository.existsByTestimonialIdAndIsPublicTrue(id));
    }

    /** The sections as shown, each with its photos (in display order) and their tags — one query per level. */
    private List<TestimonialSectionViewDto> sectionViews(List<VisibleSection> sections) {
        Map<Long, List<PhotoRefDto>> photosBySectionId = photosBySectionId(sections);
        return sections.stream()
                .map(section -> new TestimonialSectionViewDto(
                        section.topicSlug(),
                        section.topicLabel(),
                        section.answerText(),
                        photosBySectionId.getOrDefault(section.id(), List.of()),
                        section.modified()))
                .toList();
    }

    private Map<Long, List<PhotoRefDto>> photosBySectionId(List<VisibleSection> sections) {
        if (sections.isEmpty()) {
            return Map.of();
        }
        List<SectionPhoto> photos =
                photoRepository.findSectionPhotosBySectionIdIn(sections.stream().map(VisibleSection::id).toList());
        Map<Long, List<String>> tagsByPhotoId = tagsByPhotoId(photos);
        return photos.stream().collect(Collectors.groupingBy(
                SectionPhoto::sectionId,
                Collectors.mapping(photo -> toPhotoRef(photo, tagsByPhotoId.getOrDefault(photo.id(), List.of())),
                        Collectors.toList())));
    }

    private Map<Long, List<String>> tagsByPhotoId(List<SectionPhoto> photos) {
        if (photos.isEmpty()) {
            return Map.of();
        }
        return photoTagRepository.findTextsByPhotoIdIn(photos.stream().map(SectionPhoto::id).toList()).stream()
                .collect(Collectors.groupingBy(
                        PhotoTagText::photoId, Collectors.mapping(PhotoTagText::tagText, Collectors.toList())));
    }

    private PhotoRefDto toPhotoRef(SectionPhoto photo, List<String> tags) {
        return new PhotoRefDto(
                photoUrlResolver.resolve(photo.filePath()),
                thumbnailUrl(photo.thumbnailPath(), photo.filePath()),
                photo.width(),
                photo.height(),
                tags);
    }

    /** "First name + last-initial." (decision 10), e.g. "David J." */
    private String displayName(TestimonialHeader header) {
        String lastInitial = header.lastName().substring(0, 1).toUpperCase(Locale.ROOT);
        return header.firstName() + " " + lastInitial + ".";
    }

    /**
     * Reveals a testimonial's public contact methods on demand
     * (UC-REVEAL-CONTACT, decisions 5, 6), in display order. Unknown id,
     * not-approved, and approved-but-zero-public-contact-methods all throw
     * the exact same {@link NotFoundException} as {@link #getDetail(Long)} —
     * deliberately indistinguishable to the caller (api-spec.yaml).
     */
    public List<ContactMethodViewDto> revealContact(Long id) {
        List<ContactMethodViewDto> publicContacts = contactMethodRepository
                .findPublicValuesByTestimonialIdAndStatus(id, TestimonialStatus.APPROVED).stream()
                .map(contact -> new ContactMethodViewDto(contact.typeSlug(), contact.value()))
                .toList();
        if (publicContacts.isEmpty()) {
            throw new NotFoundException(TESTIMONIAL_NOT_FOUND_MESSAGE);
        }
        return publicContacts;
    }

    /**
     * Countries usable in the gallery's filter dropdown, in {@link
     * Country#DISPLAY_ORDER}: "Other" first if it has an approved
     * testimonial, then the countries by name.
     */
    public List<CountryDto> listCountriesWithApproved() {
        return testimonialRepository.findDistinctCountriesByStatus(TestimonialStatus.APPROVED).stream()
                .sorted(Country.DISPLAY_ORDER)
                .map(this::toCountryDto)
                .toList();
    }

    /**
     * Visible top-level topic-catalog entries (decisions 11, 28) with at
     * least one APPROVED-testimonial section — groups nest only their
     * qualifying visible subtopics; a group left with zero qualifying
     * subtopics (or an inactive group) is dropped entirely; a standalone
     * topic appears only if it directly qualifies and is visible. The
     * topics come with their groups in one query; entries of equal top-level
     * display order keep the groups first, by id, then the standalone
     * topics, by id.
     */
    public List<TopicCatalogEntryDto> listTopicCatalogWithApproved() {
        Set<Long> qualifyingTopicIds = new HashSet<>(
                testimonialSectionRepository.findDistinctTopicIdsByTestimonialStatus(TestimonialStatus.APPROVED));

        List<Topic> allTopics = topicRepository.findAllWithTopicGroupOrderById();
        Map<Long, TopicGroup> groupsById = new TreeMap<>();
        Map<Long, List<Topic>> topicsByGroupId = new TreeMap<>();
        for (Topic topic : allTopics) {
            TopicGroup group = topic.getTopicGroup();
            if (group != null) {
                groupsById.put(group.getId(), group);
                topicsByGroupId.computeIfAbsent(group.getId(), groupId -> new ArrayList<>()).add(topic);
            }
        }

        List<OrderedEntry> entries = new ArrayList<>();

        for (TopicGroup group : groupsById.values()) {
            if (!group.isActive()) {
                continue;
            }
            List<TopicPickDto> subtopics = topicsByGroupId.get(group.getId()).stream()
                    .filter(Topic::isVisible)
                    .filter(t -> qualifyingTopicIds.contains(t.getId()))
                    .sorted(Comparator.comparing(Topic::getDisplayOrder))
                    .map(t -> new TopicPickDto(t.getId(), t.getSlug(), t.getLabel(), t.getGuidingPrompt()))
                    .toList();
            if (!subtopics.isEmpty()) {
                entries.add(new OrderedEntry(group.getDisplayOrder(), TopicCatalogEntryDto.group(group, subtopics)));
            }
        }

        for (Topic topic : allTopics) {
            if (topic.getTopicGroup() != null || !topic.isVisible() || !qualifyingTopicIds.contains(topic.getId())) {
                continue;
            }
            entries.add(new OrderedEntry(topic.getDisplayOrder(), TopicCatalogEntryDto.standalone(topic)));
        }

        return entries.stream()
                .sorted(Comparator.comparingInt(OrderedEntry::topLevelDisplayOrder))
                .map(OrderedEntry::dto)
                .toList();
    }

    /** Pairs a catalog entry with its top-level sort key ahead of the final combined sort. */
    private record OrderedEntry(int topLevelDisplayOrder, TopicCatalogEntryDto dto) {
    }
}
