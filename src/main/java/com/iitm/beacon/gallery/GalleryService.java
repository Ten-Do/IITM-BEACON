package com.iitm.beacon.gallery;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
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
    private final TopicRepository topicRepository;
    private final TopicGroupRepository topicGroupRepository;
    private final PhotoUrlResolver photoUrlResolver;

    public GalleryService(
            TestimonialRepository testimonialRepository,
            TestimonialSectionRepository testimonialSectionRepository,
            TopicRepository topicRepository,
            TopicGroupRepository topicGroupRepository,
            PhotoUrlResolver photoUrlResolver) {
        this.testimonialRepository = testimonialRepository;
        this.testimonialSectionRepository = testimonialSectionRepository;
        this.topicRepository = topicRepository;
        this.topicGroupRepository = topicGroupRepository;
        this.photoUrlResolver = photoUrlResolver;
    }

    /**
     * Lists approved testimonials matching every supplied filter (decision
     * 8), combined with AND semantics. A requested topic-group id expands
     * server-side to all of its member topic ids (decision 11).
     */
    public PageResponse<TestimonialCardDto> browse(String country, List<Long> topicIds, String q, Pageable pageable) {
        List<Long> expandedTopicIds = expandTopicIds(topicIds);
        Specification<Testimonial> spec = Specification.allOf(
                TestimonialSpecifications.statusIs(TestimonialStatus.APPROVED),
                TestimonialSpecifications.countryIs(country),
                TestimonialSpecifications.hasAnyTopic(expandedTopicIds),
                TestimonialSpecifications.matchesQuery(q));

        Page<Testimonial> page = testimonialRepository.findAll(spec, pageable);
        List<TestimonialCardDto> content =
                page.getContent().stream().map(this::toCard).toList();
        return PageResponse.of(content, page);
    }

    /**
     * Expands each requested top-level pick (decision 11) into concrete
     * topic ids: a topic-group id expands to all of its member topics, a
     * plain topic id is kept as-is, and any id matching neither is silently
     * dropped (not an error — an unmatched filter value simply contributes
     * nothing).
     */
    private List<Long> expandTopicIds(List<Long> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<Long> expanded = new ArrayList<>();
        for (Long id : requested) {
            if (id == null) {
                continue;
            }
            if (topicGroupRepository.existsById(id)) {
                topicRepository.findByTopicGroupId(id).forEach(topic -> expanded.add(topic.getId()));
            } else if (topicRepository.existsById(id)) {
                expanded.add(id);
            }
        }
        return expanded;
    }

    private TestimonialCardDto toCard(Testimonial testimonial) {
        List<TestimonialSection> ordered = sectionsInDisplayOrder(testimonial);
        TestimonialSection first = ordered.isEmpty() ? null : ordered.get(0);
        String previewText = first == null ? "" : truncate(first.getAnswerText());
        String thumbnailUrl = first == null ? null : firstPhotoUrl(first);
        return new TestimonialCardDto(
                testimonial.getId(),
                toCountryDto(testimonial.getCountry()),
                testimonial.getRecommendationScore(),
                previewText,
                thumbnailUrl);
    }

    private String firstPhotoUrl(TestimonialSection section) {
        return section.getPhotos().stream()
                .sorted(Comparator.comparing(Photo::getDisplayOrder))
                .findFirst()
                .map(photo -> photoUrlResolver.resolve(photo.getFilePath()))
                .orElse(null);
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
     * Orders a testimonial's sections for both display and "first section"
     * selection: primarily by the owning topic-group's display order (or
     * the topic's own display order for a standalone topic), then by the
     * topic's own display order within its group (0 for a standalone
     * topic) — groups and standalone topics share one flat top-level
     * ordering range (see {@code V13__seed_topic_groups_and_topics.sql}).
     */
    private List<TestimonialSection> sectionsInDisplayOrder(Testimonial testimonial) {
        return testimonial.getSections().stream()
                .sorted(Comparator.comparingInt(this::topLevelOrder).thenComparingInt(this::withinGroupOrder))
                .toList();
    }

    private int topLevelOrder(TestimonialSection section) {
        Topic topic = section.getTopic();
        return topic.getTopicGroup() != null ? topic.getTopicGroup().getDisplayOrder() : topic.getDisplayOrder();
    }

    private int withinGroupOrder(TestimonialSection section) {
        Topic topic = section.getTopic();
        return topic.getTopicGroup() != null ? topic.getDisplayOrder() : 0;
    }

    /**
     * Full public detail for one testimonial (UC-EXPAND-TESTIMONIAL). A
     * PENDING/REJECTED testimonial must be indistinguishable from a
     * nonexistent one to an unauthenticated caller (CLAUDE.md), so both
     * "no such id" and "not approved" throw the exact same {@link
     * NotFoundException}.
     */
    public TestimonialDetailDto getDetail(Long id) {
        Testimonial testimonial = testimonialRepository
                .findById(id)
                .filter(t -> t.getStatus() == TestimonialStatus.APPROVED)
                .orElseThrow(() -> new NotFoundException(TESTIMONIAL_NOT_FOUND_MESSAGE));
        return toDetail(testimonial);
    }

    private TestimonialDetailDto toDetail(Testimonial testimonial) {
        List<TestimonialSectionViewDto> sections =
                sectionsInDisplayOrder(testimonial).stream().map(this::toSectionView).toList();
        List<String> achievementSlugs = testimonial.getAchievements().stream()
                .map(ta -> ta.getAchievement().getSlug())
                .toList();
        boolean hasRevealableContact =
                testimonial.getContactMethods().stream().anyMatch(ContactMethod::isPublic);
        return new TestimonialDetailDto(
                testimonial.getId(),
                displayName(testimonial),
                toCountryDto(testimonial.getCountry()),
                testimonial.getRecommendationScore(),
                testimonial.getStatus().name(),
                sections,
                achievementSlugs,
                hasRevealableContact);
    }

    private TestimonialSectionViewDto toSectionView(TestimonialSection section) {
        List<PhotoRefDto> photos = section.getPhotos().stream()
                .sorted(Comparator.comparing(Photo::getDisplayOrder))
                .map(this::toPhotoRef)
                .toList();
        return new TestimonialSectionViewDto(
                section.getTopic().getSlug(),
                section.getTopic().getLabel(),
                section.getAnswerText(),
                photos,
                section.isModified());
    }

    private PhotoRefDto toPhotoRef(Photo photo) {
        List<String> tags =
                photo.getTags().stream().map(PhotoTag::getTagText).toList();
        return new PhotoRefDto(photoUrlResolver.resolve(photo.getFilePath()), tags);
    }

    /** "First name + last-initial." (decision 10), e.g. "David J." */
    private String displayName(Testimonial testimonial) {
        String lastInitial =
                testimonial.getLastName().substring(0, 1).toUpperCase(Locale.ROOT);
        return testimonial.getFirstName() + " " + lastInitial + ".";
    }

    /**
     * Reveals a testimonial's public contact methods on demand
     * (UC-REVEAL-CONTACT, decisions 5, 6). Unknown id, not-approved, and
     * approved-but-zero-public-contact-methods all throw the exact same
     * {@link NotFoundException} as {@link #getDetail(Long)} — deliberately
     * indistinguishable to the caller (api-spec.yaml).
     */
    public List<ContactMethodViewDto> revealContact(Long id) {
        Testimonial testimonial = testimonialRepository
                .findById(id)
                .filter(t -> t.getStatus() == TestimonialStatus.APPROVED)
                .orElseThrow(() -> new NotFoundException(TESTIMONIAL_NOT_FOUND_MESSAGE));

        List<ContactMethodViewDto> publicContacts = testimonial.getContactMethods().stream()
                .filter(ContactMethod::isPublic)
                .map(cm -> new ContactMethodViewDto(cm.getContactType().getSlug(), cm.getValue()))
                .toList();
        if (publicContacts.isEmpty()) {
            throw new NotFoundException(TESTIMONIAL_NOT_FOUND_MESSAGE);
        }
        return publicContacts;
    }

    /** Countries usable in the gallery's filter dropdown — already sorted by name by the repository query. */
    public List<CountryDto> listCountriesWithApproved() {
        return testimonialRepository.findDistinctCountriesByStatus(TestimonialStatus.APPROVED).stream()
                .map(this::toCountryDto)
                .toList();
    }

    /**
     * Active top-level topic-catalog entries (decision 11) with at least
     * one APPROVED-testimonial section — groups nest only their qualifying
     * subtopics; a group left with zero qualifying subtopics (or an
     * inactive group) is dropped entirely; a standalone topic appears only
     * if it directly qualifies and is active.
     */
    public List<TopicCatalogEntryDto> listTopicCatalogWithApproved() {
        Set<Long> qualifyingTopicIds = new HashSet<>(
                testimonialSectionRepository.findDistinctTopicIdsByTestimonialStatus(TestimonialStatus.APPROVED));

        List<Topic> allTopics = topicRepository.findAll();
        Map<Long, List<Topic>> topicsByGroupId = allTopics.stream()
                .filter(t -> t.getTopicGroup() != null)
                .collect(Collectors.groupingBy(t -> t.getTopicGroup().getId()));

        List<OrderedEntry> entries = new ArrayList<>();

        for (TopicGroup group : topicGroupRepository.findAll()) {
            if (!group.isActive()) {
                continue;
            }
            List<TopicPickDto> subtopics = topicsByGroupId.getOrDefault(group.getId(), List.of()).stream()
                    .filter(Topic::isActive)
                    .filter(t -> qualifyingTopicIds.contains(t.getId()))
                    .sorted(Comparator.comparing(Topic::getDisplayOrder))
                    .map(t -> new TopicPickDto(t.getId(), t.getSlug(), t.getLabel(), t.getGuidingPrompt()))
                    .toList();
            if (!subtopics.isEmpty()) {
                entries.add(new OrderedEntry(group.getDisplayOrder(), TopicCatalogEntryDto.group(group, subtopics)));
            }
        }

        for (Topic topic : allTopics) {
            if (topic.getTopicGroup() != null || !topic.isActive() || !qualifyingTopicIds.contains(topic.getId())) {
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
