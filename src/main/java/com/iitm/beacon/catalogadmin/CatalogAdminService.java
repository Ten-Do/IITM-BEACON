package com.iitm.beacon.catalogadmin;

import com.iitm.beacon.common.error.CatalogConflictException;
import com.iitm.beacon.common.error.CatalogValidationException;
import com.iitm.beacon.common.error.FieldViolation;
import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.testimonial.Photo;
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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Core service for the {@code catalogadmin} slice (UC-MANAGE-TOPIC-GROUPS,
 * UC-MANAGE-TOPICS, UC-MANAGE-ACHIEVEMENTS, decisions 11, 13, 28): reads,
 * creates, partial updates and cascading deletes of topic groups, topics and
 * achievements, shared by the REST controller and the admin pages.
 *
 * <ul>
 *   <li>Slugs are unique per table, on create and on edit: a clash is a
 *       {@link CatalogConflictException} on {@code slug} (409) — also when
 *       two saves race past the check and the database's unique constraint
 *       stops the second one.
 *   <li>A {@code topicGroupId} naming no group is a {@link
 *       CatalogValidationException} on {@code topicGroupId} (400).
 *   <li>The {@code general} topic ({@link Topic#GENERAL_SLUG}) can't be
 *       deactivated, deleted, moved into a group, or re-slugged (409); a
 *       request that merely restates its current values is not a change.
 *   <li>Deletes cascade for good: a topic's sections go through their
 *       owning {@code Testimonial.sections} collection, so orphan removal
 *       takes their photos and tags along; a group's topics go the same way;
 *       an achievement's ticks go through {@code Testimonial.achievements}.
 *       Photo files are deleted only after the transaction commits, so a
 *       rollback never loses one. A delete never touches testimonial status.
 *   <li>A hidden edit is never published unreviewed: when a patch turns a
 *       topic from invisible to visible, every {@code APPROVED} testimonial
 *       with a {@code modified} section of it goes back to {@code PENDING}
 *       (no email; {@code reviewedAt} and the other flags untouched).
 * </ul>
 */
@Service
@Transactional
public class CatalogAdminService {

    private static final Logger log = LoggerFactory.getLogger(CatalogAdminService.class);

    private static final String UNKNOWN_GROUP_MESSAGE = "must name an existing topic group";
    private static final String GENERAL_DEACTIVATE_MESSAGE = "The general topic can't be deactivated.";
    private static final String GENERAL_DELETE_MESSAGE = "The general topic can't be deleted.";
    private static final String GENERAL_REGROUP_MESSAGE = "The general topic can't be moved into a group.";
    private static final String GENERAL_RESLUG_MESSAGE = "The general topic's slug can't be changed.";
    private static final String GROUP_HOLDS_GENERAL_MESSAGE =
            "This group holds the general topic, which can't be deleted.";

    private final TopicGroupRepository topicGroupRepository;
    private final TopicRepository topicRepository;
    private final AchievementRepository achievementRepository;
    private final TestimonialRepository testimonialRepository;
    private final TestimonialSectionRepository testimonialSectionRepository;
    private final TestimonialAchievementRepository testimonialAchievementRepository;
    private final PhotoFileDeleter photoFileDeleter;

    public CatalogAdminService(
            TopicGroupRepository topicGroupRepository,
            TopicRepository topicRepository,
            AchievementRepository achievementRepository,
            TestimonialRepository testimonialRepository,
            TestimonialSectionRepository testimonialSectionRepository,
            TestimonialAchievementRepository testimonialAchievementRepository,
            PhotoFileDeleter photoFileDeleter) {
        this.topicGroupRepository = topicGroupRepository;
        this.topicRepository = topicRepository;
        this.achievementRepository = achievementRepository;
        this.testimonialRepository = testimonialRepository;
        this.testimonialSectionRepository = testimonialSectionRepository;
        this.testimonialAchievementRepository = testimonialAchievementRepository;
        this.photoFileDeleter = photoFileDeleter;
    }

    // -- topic groups --

    /** Every group, inactive ones included, by display order then id. */
    @Transactional(readOnly = true)
    public List<TopicGroupDto> listTopicGroups() {
        return topicGroupRepository.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .map(CatalogAdminService::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public TopicGroupDto getTopicGroup(Long id) {
        return toDto(findGroup(id));
    }

    public TopicGroupDto createTopicGroup(TopicGroupCreateRequest request) {
        TopicGroup group = topicGroupRepository.saveAndFlush(TopicGroup.builder()
                .label(request.label())
                .displayOrder(request.displayOrder())
                .active(true)
                .build());
        log.info("Catalog: created topic group {} \"{}\"", group.getId(), group.getLabel());
        return toDto(group);
    }

    /** Changes only the fields present in {@code request}. */
    public TopicGroupDto patchTopicGroup(Long id, TopicGroupPatchRequest request) {
        TopicGroup group = findGroup(id);
        boolean wasActive = group.isActive();
        if (request.label() != null) {
            group.setLabel(request.label());
        }
        if (request.displayOrder() != null) {
            group.setDisplayOrder(request.displayOrder());
        }
        if (request.active() != null) {
            group.setActive(request.active());
        }
        TopicGroup saved = topicGroupRepository.saveAndFlush(group);
        log.info("Catalog: updated topic group {} (label=\"{}\", order={}, active={})",
                saved.getId(), saved.getLabel(), saved.getDisplayOrder(), saved.isActive());
        if (!wasActive && saved.isActive()) {
            // Reactivating the group makes every active member topic visible again.
            rependHiddenEdits(topicRepository.findByTopicGroupId(id).stream()
                    .filter(Topic::isVisible)
                    .map(Topic::getId)
                    .toList());
        }
        return toDto(saved);
    }

    /** Deletes the group, all of its topics, and every section (with photos) under them. */
    public void deleteTopicGroup(Long id) {
        TopicGroup group = findGroup(id);
        List<Topic> topics = topicRepository.findByTopicGroupId(id);
        if (topics.stream().anyMatch(CatalogAdminService::isGeneral)) {
            throw new CatalogConflictException(GROUP_HOLDS_GENERAL_MESSAGE);
        }
        List<Long> topicIds = topics.stream().map(Topic::getId).toList();
        SectionRemoval removal = removeSectionsOf(topicIds);
        topicRepository.deleteAll(topics);
        topicRepository.flush();
        topicGroupRepository.delete(group);
        topicGroupRepository.flush();
        deletePhotoFilesAfterCommit(removal.photos());
        log.info("Catalog: deleted topic group {} \"{}\" with {} topics, {} sections and {} photos",
                id, group.getLabel(), topics.size(), removal.sectionCount(), removal.photos().size());
    }

    @Transactional(readOnly = true)
    public DeletePreview previewTopicGroupDelete(Long id) {
        TopicGroup group = findGroup(id);
        List<Long> topicIds = topicRepository.findByTopicGroupId(id).stream().map(Topic::getId).toList();
        long affected = topicIds.isEmpty()
                ? 0
                : testimonialSectionRepository.countDistinctTestimonialsByTopicIdIn(topicIds);
        return new DeletePreview(group.getLabel(), affected, topicIds.size());
    }

    // -- topics --

    /** Every topic, inactive ones included, by display order then id. */
    @Transactional(readOnly = true)
    public List<TopicDto> listTopics() {
        return topicRepository.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .map(CatalogAdminService::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public TopicDto getTopic(Long id) {
        return toDto(findTopic(id));
    }

    public TopicDto createTopic(TopicCreateRequest request) {
        TopicGroup group = request.topicGroupId() == null ? null : resolveGroup(request.topicGroupId());
        if (topicRepository.existsBySlug(request.slug())) {
            throw duplicateSlug(request.slug(), "topic");
        }
        Topic topic = saveTopic(Topic.builder()
                .topicGroup(group)
                .slug(request.slug())
                .label(request.label())
                .guidingPrompt(request.guidingPrompt())
                .displayOrder(request.displayOrder())
                .active(true)
                .build());
        log.info("Catalog: created topic {} \"{}\" in group {}", topic.getId(), topic.getSlug(), groupId(topic));
        return toDto(topic);
    }

    /**
     * Changes only the fields present in {@code request}; {@code
     * topicGroupId} absent leaves the group, empty makes the topic
     * standalone, a value re-parents it. Everything is checked before
     * anything changes, so a refused request leaves the topic as it was.
     */
    public TopicDto patchTopic(Long id, TopicPatchRequest request) {
        Topic topic = findTopic(id);
        Optional<TopicGroup> newGroup = Optional.empty();
        boolean regroup = request.topicGroupId() != null;
        if (regroup && request.topicGroupId().isPresent()) {
            newGroup = Optional.of(resolveGroup(request.topicGroupId().get()));
        }
        boolean slugChange = request.slug() != null && !request.slug().equals(topic.getSlug());
        boolean groupChange = regroup && !Objects.equals(
                newGroup.map(TopicGroup::getId).orElse(null), groupId(topic));
        if (isGeneral(topic)) {
            refuseGeneralChanges(topic, request, slugChange, groupChange);
        }
        if (slugChange && topicRepository.existsBySlugAndIdNot(request.slug(), id)) {
            throw duplicateSlug(request.slug(), "topic");
        }
        boolean wasVisible = topic.isVisible();

        if (request.slug() != null) {
            topic.setSlug(request.slug());
        }
        if (request.label() != null) {
            topic.setLabel(request.label());
        }
        if (request.guidingPrompt() != null) {
            topic.setGuidingPrompt(request.guidingPrompt());
        }
        if (request.displayOrder() != null) {
            topic.setDisplayOrder(request.displayOrder());
        }
        if (request.active() != null) {
            topic.setActive(request.active());
        }
        if (regroup) {
            topic.setTopicGroup(newGroup.orElse(null));
        }
        Topic saved = saveTopic(topic);
        log.info("Catalog: updated topic {} (slug={}, group={}, order={}, active={})",
                saved.getId(), saved.getSlug(), groupId(saved), saved.getDisplayOrder(), saved.isActive());
        if (!wasVisible && saved.isVisible()) {
            rependHiddenEdits(List.of(saved.getId()));
        }
        return toDto(saved);
    }

    /** Deletes the topic and every section (with photos) of it. {@code general} is refused. */
    public void deleteTopic(Long id) {
        Topic topic = findTopic(id);
        if (isGeneral(topic)) {
            throw new CatalogConflictException(GENERAL_DELETE_MESSAGE);
        }
        SectionRemoval removal = removeSectionsOf(List.of(id));
        topicRepository.delete(topic);
        topicRepository.flush();
        deletePhotoFilesAfterCommit(removal.photos());
        log.info("Catalog: deleted topic {} \"{}\" with {} sections and {} photos",
                id, topic.getSlug(), removal.sectionCount(), removal.photos().size());
    }

    /** The topic delete's confirmation; {@code general} is refused, as its delete would be. */
    @Transactional(readOnly = true)
    public DeletePreview previewTopicDelete(Long id) {
        Topic topic = findTopic(id);
        if (isGeneral(topic)) {
            throw new CatalogConflictException(GENERAL_DELETE_MESSAGE);
        }
        long affected = testimonialSectionRepository.countDistinctTestimonialsByTopicIdIn(List.of(id));
        return new DeletePreview(topic.getLabel(), affected, 1);
    }

    // -- achievements --

    /** Every achievement, inactive ones included, by display order then id. */
    @Transactional(readOnly = true)
    public List<AchievementDto> listAchievements() {
        return achievementRepository.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .map(CatalogAdminService::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public AchievementDto getAchievement(Long id) {
        return toDto(findAchievement(id));
    }

    public AchievementDto createAchievement(AchievementCreateRequest request) {
        if (achievementRepository.existsBySlug(request.slug())) {
            throw duplicateSlug(request.slug(), "achievement");
        }
        Achievement achievement = saveAchievement(Achievement.builder()
                .slug(request.slug())
                .label(request.label())
                .displayOrder(request.displayOrder())
                .active(true)
                .build());
        log.info("Catalog: created achievement {} \"{}\"", achievement.getId(), achievement.getSlug());
        return toDto(achievement);
    }

    /** Changes only the fields present in {@code request}. */
    public AchievementDto patchAchievement(Long id, AchievementPatchRequest request) {
        Achievement achievement = findAchievement(id);
        if (request.slug() != null
                && !request.slug().equals(achievement.getSlug())
                && achievementRepository.existsBySlugAndIdNot(request.slug(), id)) {
            throw duplicateSlug(request.slug(), "achievement");
        }
        if (request.slug() != null) {
            achievement.setSlug(request.slug());
        }
        if (request.label() != null) {
            achievement.setLabel(request.label());
        }
        if (request.displayOrder() != null) {
            achievement.setDisplayOrder(request.displayOrder());
        }
        if (request.active() != null) {
            achievement.setActive(request.active());
        }
        Achievement saved = saveAchievement(achievement);
        log.info("Catalog: updated achievement {} (slug={}, order={}, active={})",
                saved.getId(), saved.getSlug(), saved.getDisplayOrder(), saved.isActive());
        return toDto(saved);
    }

    /** Deletes the achievement and every tick of it. */
    public void deleteAchievement(Long id) {
        Achievement achievement = findAchievement(id);
        List<TestimonialAchievement> ticks = testimonialAchievementRepository.findByAchievementId(id);
        for (TestimonialAchievement tick : ticks) {
            tick.getTestimonial().getAchievements().remove(tick);
        }
        testimonialAchievementRepository.flush();
        achievementRepository.delete(achievement);
        achievementRepository.flush();
        log.info("Catalog: deleted achievement {} \"{}\" with {} ticks", id, achievement.getSlug(), ticks.size());
    }

    @Transactional(readOnly = true)
    public DeletePreview previewAchievementDelete(Long id) {
        Achievement achievement = findAchievement(id);
        return new DeletePreview(achievement.getLabel(), testimonialAchievementRepository.countByAchievementId(id), 0);
    }

    // -- helpers --

    private TopicGroup findGroup(Long id) {
        return topicGroupRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Topic group not found: " + id));
    }

    private Topic findTopic(Long id) {
        return topicRepository.findById(id).orElseThrow(() -> new NotFoundException("Topic not found: " + id));
    }

    private Achievement findAchievement(Long id) {
        return achievementRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Achievement not found: " + id));
    }

    /** The group a request names, or a 400 on {@code topicGroupId}. */
    private TopicGroup resolveGroup(Long topicGroupId) {
        return topicGroupRepository.findById(topicGroupId).orElseThrow(() -> new CatalogValidationException(
                List.of(new FieldViolation("topicGroupId", UNKNOWN_GROUP_MESSAGE))));
    }

    private static void refuseGeneralChanges(
            Topic general, TopicPatchRequest request, boolean slugChange, boolean groupChange) {
        if (Boolean.FALSE.equals(request.active()) && general.isActive()) {
            throw new CatalogConflictException(GENERAL_DEACTIVATE_MESSAGE);
        }
        if (groupChange && request.topicGroupId().isPresent()) {
            throw new CatalogConflictException(GENERAL_REGROUP_MESSAGE);
        }
        if (slugChange) {
            throw new CatalogConflictException(GENERAL_RESLUG_MESSAGE);
        }
    }

    private static boolean isGeneral(Topic topic) {
        return Topic.GENERAL_SLUG.equals(topic.getSlug());
    }

    private static Long groupId(Topic topic) {
        return topic.getTopicGroup() == null ? null : topic.getTopicGroup().getId();
    }

    private static CatalogConflictException duplicateSlug(String slug, String kind) {
        return new CatalogConflictException(
                "slug", "The slug \"" + slug + "\" is already used by another " + kind + ".");
    }

    /**
     * Saves and flushes, so a unique-slug violation from a concurrent save
     * surfaces here, as the same 409 the up-front check gives.
     */
    private Topic saveTopic(Topic topic) {
        try {
            return topicRepository.saveAndFlush(topic);
        } catch (DataIntegrityViolationException ex) {
            log.info("Catalog: topic save with slug {} hit a constraint: {}", topic.getSlug(), ex.getMessage());
            throw duplicateSlug(topic.getSlug(), "topic");
        }
    }

    private Achievement saveAchievement(Achievement achievement) {
        try {
            return achievementRepository.saveAndFlush(achievement);
        } catch (DataIntegrityViolationException ex) {
            log.info("Catalog: achievement save with slug {} hit a constraint: {}",
                    achievement.getSlug(), ex.getMessage());
            throw duplicateSlug(achievement.getSlug(), "achievement");
        }
    }

    /**
     * Removes every section filed under {@code topicIds} from its owning
     * testimonial (orphan removal deletes it with its photos and tags) and
     * flushes, so the topics can be deleted next.
     */
    private SectionRemoval removeSectionsOf(Collection<Long> topicIds) {
        if (topicIds.isEmpty()) {
            return new SectionRemoval(0, List.of());
        }
        List<TestimonialSection> sections = testimonialSectionRepository.findByTopicIdIn(topicIds);
        List<Photo> photos = new ArrayList<>();
        for (TestimonialSection section : sections) {
            photos.addAll(section.getPhotos());
            section.getTestimonial().getSections().remove(section);
        }
        testimonialSectionRepository.flush();
        return new SectionRemoval(sections.size(), List.copyOf(photos));
    }

    /**
     * Sends every {@code APPROVED} testimonial with a {@code modified}
     * section under {@code topicIds} — topics that just became visible
     * again — back to {@code PENDING}, so an edit made while they were hidden
     * is reviewed before it shows (decisions 18, 28). Nothing else on the
     * testimonial changes, and no email is sent.
     */
    private void rependHiddenEdits(List<Long> topicIds) {
        if (topicIds.isEmpty()) {
            return;
        }
        List<Testimonial> approved = testimonialRepository.findDistinctByStatusAndModifiedSectionTopicIdIn(
                TestimonialStatus.APPROVED, topicIds);
        approved.forEach(testimonial -> testimonial.setStatus(TestimonialStatus.PENDING));
        testimonialRepository.flush();
        log.info("Catalog: topics {} visible again, {} approved testimonials with an edit made while hidden "
                + "sent back to pending", topicIds, approved.size());
    }

    /** Deletes the photos' files once the surrounding transaction has committed; never on rollback. */
    private void deletePhotoFilesAfterCommit(List<Photo> photos) {
        if (photos.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                photos.forEach(photoFileDeleter::delete);
            }
        });
    }

    private static TopicGroupDto toDto(TopicGroup group) {
        return new TopicGroupDto(group.getId(), group.getLabel(), group.getDisplayOrder(), group.isActive());
    }

    private static TopicDto toDto(Topic topic) {
        return new TopicDto(
                topic.getId(),
                groupId(topic),
                topic.getSlug(),
                topic.getLabel(),
                topic.getGuidingPrompt(),
                topic.getDisplayOrder(),
                topic.isActive());
    }

    private static AchievementDto toDto(Achievement achievement) {
        return new AchievementDto(
                achievement.getId(),
                achievement.getSlug(),
                achievement.getLabel(),
                achievement.getDisplayOrder(),
                achievement.isActive());
    }

    /** What removing a set of topics' sections took along: how many sections, and their photos. */
    private record SectionRemoval(int sectionCount, List<Photo> photos) {
    }
}
