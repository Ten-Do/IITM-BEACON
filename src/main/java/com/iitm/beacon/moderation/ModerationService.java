package com.iitm.beacon.moderation;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.NotificationNotSentException;
import com.iitm.beacon.common.error.TestimonialNotPendingException;
import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.AchievementTick;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.testimonial.ContactMethodRepository;
import com.iitm.beacon.domain.testimonial.ContactMethodValue;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoRepository;
import com.iitm.beacon.domain.testimonial.PhotoTagRepository;
import com.iitm.beacon.domain.testimonial.PhotoTagText;
import com.iitm.beacon.domain.testimonial.SectionPhoto;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialHeader;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.testimonial.VisibleSection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Core service for the {@code moderation} slice (UC-VIEW-PENDING-QUEUE,
 * UC-APPROVE-TESTIMONIAL, UC-REJECT-TESTIMONIAL, decision 18), and the purge
 * of long-rejected testimonials behind {@link RejectedTestimonialCleanupJob}
 * (UC-PURGE-REJECTED, decision 3).
 *
 * <p>The queue costs a fixed number of queries per page (BL-049): one for
 * the page of testimonials, then one per level of their parts — sections,
 * photos, tags, contact methods, achievement ticks — for the whole page.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    /** How long a rejected testimonial is kept before the purge deletes it for good (decision 3). */
    static final Duration REJECTION_RETENTION = Duration.ofDays(30);

    private static final String NO_REASON_BODY =
            "Your IITM Beacon testimonial was not approved. Please log in to review and resubmit it.";
    private static final String REJECT_EMAIL_SUBJECT = "Your IITM Beacon testimonial needs changes";
    private static final String REJECT_EMAIL_NOT_SENT_MESSAGE =
            "The email to the submitter couldn't be sent, so the testimonial was not rejected. Try again later.";

    private final TestimonialRepository testimonialRepository;
    private final TestimonialSectionRepository testimonialSectionRepository;
    private final PhotoRepository photoRepository;
    private final PhotoTagRepository photoTagRepository;
    private final ContactMethodRepository contactMethodRepository;
    private final TestimonialAchievementRepository testimonialAchievementRepository;
    private final NotificationMailer notificationMailer;
    private final PhotoUrlResolver photoUrlResolver;
    private final PhotoFileDeleter photoFileDeleter;
    private final Clock clock;

    public ModerationService(
            TestimonialRepository testimonialRepository,
            TestimonialSectionRepository testimonialSectionRepository,
            PhotoRepository photoRepository,
            PhotoTagRepository photoTagRepository,
            ContactMethodRepository contactMethodRepository,
            TestimonialAchievementRepository testimonialAchievementRepository,
            NotificationMailer notificationMailer,
            PhotoUrlResolver photoUrlResolver,
            PhotoFileDeleter photoFileDeleter,
            Clock clock) {
        this.testimonialRepository = testimonialRepository;
        this.testimonialSectionRepository = testimonialSectionRepository;
        this.photoRepository = photoRepository;
        this.photoTagRepository = photoTagRepository;
        this.contactMethodRepository = contactMethodRepository;
        this.testimonialAchievementRepository = testimonialAchievementRepository;
        this.notificationMailer = notificationMailer;
        this.photoUrlResolver = photoUrlResolver;
        this.photoFileDeleter = photoFileDeleter;
        this.clock = clock;
    }

    /** Lists PENDING testimonials, oldest first (UC-VIEW-PENDING-QUEUE). */
    @Transactional(readOnly = true)
    public PageResponse<ModerationTestimonialDetailDto> listPending(Pageable pageable) {
        Page<TestimonialHeader> page = pendingOldestFirst(pageable);
        ReviewedParts parts = partsOf(page.getContent());
        List<ModerationTestimonialDetailDto> content =
                page.getContent().stream().map(header -> toDetail(header, parts)).toList();
        return PageResponse.of(content, page);
    }

    /**
     * Lists PENDING testimonials for the admin queue page (UC-VIEW-PENDING-QUEUE),
     * oldest first — same queries as {@link #listPending}, but mapped to the
     * Thymeleaf-friendly {@link ModerationQueueCardDto} shape.
     */
    @Transactional(readOnly = true)
    public PageResponse<ModerationQueueCardDto> listPendingForView(Pageable pageable) {
        Page<TestimonialHeader> page = pendingOldestFirst(pageable);
        ReviewedParts parts = partsOf(page.getContent());
        List<ModerationQueueCardDto> content =
                page.getContent().stream().map(header -> toQueueCard(header, parts)).toList();
        return PageResponse.of(content, page);
    }

    /** The page of PENDING testimonials, oldest first — ties in creation time in id order. */
    private Page<TestimonialHeader> pendingOldestFirst(Pageable pageable) {
        return testimonialRepository.findHeadersByStatusOldestFirst(TestimonialStatus.PENDING, pageable);
    }

    /**
     * Approves a pending testimonial (UC-APPROVE-TESTIMONIAL): clears the
     * diff flags of everything the admin reviewed (decision 18). A section
     * of a hidden topic was not on the admin's screen, so it keeps its
     * {@code modified} flag until it has been reviewed (decision 28). The
     * sections come with their topics and groups, in one query.
     */
    @Transactional
    public void approve(Long id) {
        Testimonial testimonial = pendingOrThrow(testimonialRepository.findWithSectionTopicsById(id), id);
        testimonial.setStatus(TestimonialStatus.APPROVED);
        testimonial.setReviewedAt(Instant.now(clock));
        testimonial.setIdentityModified(false);
        testimonial.setScoreModified(false);
        testimonial.getSections().stream()
                .filter(section -> section.getTopic().isVisible())
                .forEach(section -> section.setModified(false));
    }

    /**
     * Rejects a pending testimonial (UC-REJECT-TESTIMONIAL). Deliberately
     * leaves {@code identityModified}/{@code scoreModified}/every section's
     * {@code modified} untouched — those flags are unrelated to reject
     * (decision 18) — and always emails the submitter, varying the body
     * depending on whether a reason was given.
     *
     * <p>The email goes out first: a testimonial is only rejected once its
     * submitter can be told. If it can't be sent, nothing changes — the
     * testimonial stays {@code PENDING}, its review timestamps as they were —
     * a warning is logged with the mail failure's type only (its text can
     * name the recipient), and {@link NotificationNotSentException} tells
     * the admin to try again.
     *
     * @throws NotificationNotSentException if the email couldn't be sent
     */
    @Transactional
    public void reject(Long id, String reason) {
        Testimonial testimonial = pendingOrThrow(testimonialRepository.findById(id), id);
        try {
            notificationMailer.send(testimonial.getEmail(), REJECT_EMAIL_SUBJECT, rejectEmailBody(reason));
        } catch (MailException ex) {
            log.warn("Testimonial {} was not rejected: the email to its submitter could not be sent ({})",
                    id, ex.getClass().getSimpleName());
            throw new NotificationNotSentException(REJECT_EMAIL_NOT_SENT_MESSAGE);
        }
        Instant now = Instant.now(clock);
        testimonial.setStatus(TestimonialStatus.REJECTED);
        testimonial.setReviewedAt(now);
        testimonial.setRejectedAt(now);
    }

    /**
     * Deletes every testimonial rejected more than {@link
     * #REJECTION_RETENTION} ago (UC-PURGE-REJECTED, decision 3), all in this
     * one transaction. Each delete re-checks that the testimonial is still
     * {@code REJECTED} and still due, so one the visitor resubmitted — or that
     * was rejected again — after it was found is kept; a pending or approved
     * testimonial whose old {@code rejectedAt} was never cleared is never
     * due. Its sections, photos, photo tags, contact methods and achievement
     * ticks go with it. The deleted photos' files are removed only once the
     * transaction has committed — never on a rollback — and a file already
     * missing is logged as a warning without stopping the rest.
     *
     * @return how many testimonials, and how many of their photos, were deleted
     */
    @Transactional
    public RejectionPurgeResult purgeExpiredRejections() {
        Instant cutoff = Instant.now(clock).minus(REJECTION_RETENTION);
        List<Long> dueIds = testimonialRepository.findIdsByStatusAndRejectedAtBefore(
                TestimonialStatus.REJECTED, cutoff);
        // Collected before the first delete, which clears the persistence context.
        Map<Long, List<Photo>> photosByTestimonialId = photosOf(dueIds);

        int purgedTestimonials = 0;
        List<Photo> purgedPhotos = new ArrayList<>();
        for (Long id : dueIds) {
            int deleted = testimonialRepository.deleteByIdAndStatusAndRejectedAtBefore(
                    id, TestimonialStatus.REJECTED, cutoff);
            if (deleted > 0) {
                purgedTestimonials++;
                purgedPhotos.addAll(photosByTestimonialId.getOrDefault(id, List.of()));
            } else {
                log.debug("Retention: testimonial {} changed since it was found, kept", id);
            }
        }
        deletePurgedPhotoFilesAfterCommit(purgedPhotos);
        return new RejectionPurgeResult(purgedTestimonials, purgedPhotos.size());
    }

    /** Every photo of the testimonials of {@code testimonialIds}, by testimonial id, in one query. */
    private Map<Long, List<Photo>> photosOf(List<Long> testimonialIds) {
        if (testimonialIds.isEmpty()) {
            return Map.of();
        }
        return photoRepository.findWithSectionByTestimonialIdIn(testimonialIds).stream()
                .collect(Collectors.groupingBy(photo -> photo.getSection().getTestimonial().getId()));
    }

    /**
     * Deletes the purged photos' files once the surrounding transaction has
     * committed; never on rollback. A photo with a file already missing (or
     * not removable) is logged and the rest carry on.
     */
    private void deletePurgedPhotoFilesAfterCommit(List<Photo> photos) {
        if (photos.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Photo photo : photos) {
                    if (!photoFileDeleter.delete(photo)) {
                        log.warn("Retention: purged photo {} had a file already missing or not removable"
                                + " (file {}, thumbnail {})",
                                photo.getId(), photo.getFilePath(), photo.getThumbnailPath());
                    }
                }
            }
        });
    }

    private static Testimonial pendingOrThrow(Optional<Testimonial> found, Long id) {
        Testimonial testimonial = found.orElseThrow(() -> new NotFoundException("Testimonial not found: " + id));
        if (testimonial.getStatus() != TestimonialStatus.PENDING) {
            throw new TestimonialNotPendingException(
                    "Testimonial " + id + " is not pending (status=" + testimonial.getStatus() + ").");
        }
        return testimonial;
    }

    private String rejectEmailBody(String reason) {
        if (reason == null || reason.isBlank()) {
            return NO_REASON_BODY;
        }
        return "Your IITM Beacon testimonial was not approved for the following reason: " + reason.trim()
                + ". Please log in to review and resubmit it.";
    }

    /**
     * What the admin reviews of a page of testimonials besides their headers,
     * by testimonial id, in one query per level for the whole page: the
     * sections of visible topics, in catalog order, with their photos (in
     * display order) and the photos' tags — a hidden section, with its photos
     * and diff flag, stays out of the queue until its topic is visible again
     * (decision 28) — every contact method, public or not, and the ticks of
     * visible achievements. Nothing for an empty page.
     */
    private ReviewedParts partsOf(List<TestimonialHeader> headers) {
        if (headers.isEmpty()) {
            return ReviewedParts.NONE;
        }
        List<Long> ids = headers.stream().map(TestimonialHeader::id).toList();
        List<VisibleSection> sections = testimonialSectionRepository.findVisibleByTestimonialIdIn(ids);
        Map<Long, List<ModerationPhotoRefDto>> photosBySectionId = photosBySectionId(sections);
        return new ReviewedParts(
                sections.stream().collect(Collectors.groupingBy(
                        VisibleSection::testimonialId,
                        Collectors.mapping(
                                section -> toSectionView(
                                        section, photosBySectionId.getOrDefault(section.id(), List.of())),
                                Collectors.toList()))),
                testimonialAchievementRepository.findVisibleTicksByTestimonialIdIn(ids).stream()
                        .collect(Collectors.groupingBy(AchievementTick::testimonialId)),
                contactMethodRepository.findValuesByTestimonialIdIn(ids).stream()
                        .collect(Collectors.groupingBy(
                                ContactMethodValue::testimonialId,
                                Collectors.mapping(ModerationService::toContactMethodView, Collectors.toList()))));
    }

    /** A page's sections, photos, contact methods and achievement ticks, by testimonial id. */
    private record ReviewedParts(
            Map<Long, List<ModerationSectionViewDto>> sections,
            Map<Long, List<AchievementTick>> achievementTicks,
            Map<Long, List<ModerationContactMethodViewDto>> contactMethods) {

        static final ReviewedParts NONE = new ReviewedParts(Map.of(), Map.of(), Map.of());

        List<ModerationSectionViewDto> sectionsOf(Long testimonialId) {
            return sections.getOrDefault(testimonialId, List.of());
        }

        List<AchievementTick> achievementTicksOf(Long testimonialId) {
            return achievementTicks.getOrDefault(testimonialId, List.of());
        }

        List<ModerationContactMethodViewDto> contactMethodsOf(Long testimonialId) {
            return contactMethods.getOrDefault(testimonialId, List.of());
        }
    }

    private Map<Long, List<ModerationPhotoRefDto>> photosBySectionId(List<VisibleSection> sections) {
        if (sections.isEmpty()) {
            return Map.of();
        }
        List<SectionPhoto> photos =
                photoRepository.findSectionPhotosBySectionIdIn(sections.stream().map(VisibleSection::id).toList());
        Map<Long, List<String>> tagsByPhotoId = photos.isEmpty()
                ? Map.of()
                : photoTagRepository.findTextsByPhotoIdIn(photos.stream().map(SectionPhoto::id).toList()).stream()
                        .collect(Collectors.groupingBy(
                                PhotoTagText::photoId,
                                Collectors.mapping(PhotoTagText::tagText, Collectors.toList())));
        return photos.stream().collect(Collectors.groupingBy(
                SectionPhoto::sectionId,
                Collectors.mapping(
                        photo -> toPhotoRef(photo, tagsByPhotoId.getOrDefault(photo.id(), List.of())),
                        Collectors.toList())));
    }

    private ModerationTestimonialDetailDto toDetail(TestimonialHeader header, ReviewedParts parts) {
        List<String> achievementSlugs = parts.achievementTicksOf(header.id()).stream()
                .map(AchievementTick::slug)
                .toList();
        return new ModerationTestimonialDetailDto(
                header.id(),
                header.firstName(),
                header.lastName(),
                header.rollNumber(),
                header.admissionYear(),
                header.email(),
                new ModerationCountryDto(header.countryCode(), header.countryName()),
                header.recommendationScore(),
                header.status(),
                parts.sectionsOf(header.id()),
                achievementSlugs,
                parts.contactMethodsOf(header.id()),
                header.identityModified(),
                header.scoreModified());
    }

    private ModerationQueueCardDto toQueueCard(TestimonialHeader header, ReviewedParts parts) {
        List<ModerationAchievementViewDto> achievements = parts.achievementTicksOf(header.id()).stream()
                .map(tick -> new ModerationAchievementViewDto(tick.slug(), tick.label()))
                .toList();
        return new ModerationQueueCardDto(
                header.id(),
                header.firstName(),
                header.lastName(),
                header.rollNumber(),
                header.admissionYear(),
                header.email(),
                new ModerationCountryDto(header.countryCode(), header.countryName()),
                header.recommendationScore(),
                header.status(),
                parts.sectionsOf(header.id()),
                achievements,
                parts.contactMethodsOf(header.id()),
                header.identityModified(),
                header.scoreModified(),
                header.createdAt(),
                header.reviewedAt() != null);
    }

    private static ModerationContactMethodViewDto toContactMethodView(ContactMethodValue contact) {
        return new ModerationContactMethodViewDto(contact.typeSlug(), contact.value(), contact.isPublic());
    }

    private static ModerationSectionViewDto toSectionView(VisibleSection section, List<ModerationPhotoRefDto> photos) {
        return new ModerationSectionViewDto(
                section.topicSlug(), section.topicLabel(), section.answerText(), photos, section.modified());
    }

    /** A legacy photo, stored before thumbnails existed, has its full-size file as its thumbnail. */
    private ModerationPhotoRefDto toPhotoRef(SectionPhoto photo, List<String> tags) {
        return new ModerationPhotoRefDto(
                photoUrlResolver.resolve(photo.filePath()),
                photoUrlResolver.resolve(photo.thumbnailPath() != null ? photo.thumbnailPath() : photo.filePath()),
                photo.width(),
                photo.height(),
                tags);
    }
}
