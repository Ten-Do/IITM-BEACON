package com.iitm.beacon.moderation;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.NotificationNotSentException;
import com.iitm.beacon.common.error.TestimonialNotPendingException;
import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Orders sections the same way the top-level topic catalog is ordered
     * (decision 11, V13 seed data): first by the group's own display order
     * (or the topic's own, for a standalone topic), then by the topic's
     * display order within its group (0 for a standalone topic, since it has
     * no siblings to order against).
     */
    private static final Comparator<TestimonialSection> SECTION_ORDER = Comparator
            .comparing((TestimonialSection section) -> section.getTopic().getTopicGroup() != null
                    ? section.getTopic().getTopicGroup().getDisplayOrder()
                    : section.getTopic().getDisplayOrder())
            .thenComparing(section -> section.getTopic().getTopicGroup() != null
                    ? section.getTopic().getDisplayOrder()
                    : 0);

    private final TestimonialRepository testimonialRepository;
    private final NotificationMailer notificationMailer;
    private final PhotoUrlResolver photoUrlResolver;
    private final PhotoFileDeleter photoFileDeleter;
    private final Clock clock;

    public ModerationService(
            TestimonialRepository testimonialRepository,
            NotificationMailer notificationMailer,
            PhotoUrlResolver photoUrlResolver,
            PhotoFileDeleter photoFileDeleter,
            Clock clock) {
        this.testimonialRepository = testimonialRepository;
        this.notificationMailer = notificationMailer;
        this.photoUrlResolver = photoUrlResolver;
        this.photoFileDeleter = photoFileDeleter;
        this.clock = clock;
    }

    /** Lists PENDING testimonials, oldest first (UC-VIEW-PENDING-QUEUE). */
    @Transactional(readOnly = true)
    public PageResponse<ModerationTestimonialDetailDto> listPending(Pageable pageable) {
        Page<Testimonial> page = testimonialRepository.findByStatusOrderByCreatedAtAsc(
                TestimonialStatus.PENDING, pageable);
        List<ModerationTestimonialDetailDto> content =
                page.getContent().stream().map(this::toDetail).toList();
        return PageResponse.of(content, page);
    }

    /**
     * Lists PENDING testimonials for the admin queue page (UC-VIEW-PENDING-QUEUE),
     * oldest first — same query as {@link #listPending}, but mapped to the
     * Thymeleaf-friendly {@link ModerationQueueCardDto} shape.
     */
    @Transactional(readOnly = true)
    public PageResponse<ModerationQueueCardDto> listPendingForView(Pageable pageable) {
        Page<Testimonial> page = testimonialRepository.findByStatusOrderByCreatedAtAsc(
                TestimonialStatus.PENDING, pageable);
        List<ModerationQueueCardDto> content =
                page.getContent().stream().map(this::toQueueCard).toList();
        return PageResponse.of(content, page);
    }

    /**
     * Approves a pending testimonial (UC-APPROVE-TESTIMONIAL): clears the
     * diff flags of everything the admin reviewed (decision 18). A section
     * of a hidden topic was not on the admin's screen, so it keeps its
     * {@code modified} flag until it has been reviewed (decision 28).
     */
    @Transactional
    public void approve(Long id) {
        Testimonial testimonial = findPendingOrThrow(id);
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
        Testimonial testimonial = findPendingOrThrow(id);
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
        List<Testimonial> due = testimonialRepository.findByStatusAndRejectedAtBeforeOrderByIdAsc(
                TestimonialStatus.REJECTED, cutoff);
        // Collected before the first delete, which clears the persistence context.
        Map<Long, List<Photo>> photosByTestimonialId = new LinkedHashMap<>();
        due.forEach(testimonial -> photosByTestimonialId.put(testimonial.getId(), photosOf(testimonial)));

        int purgedTestimonials = 0;
        List<Photo> purgedPhotos = new ArrayList<>();
        for (Map.Entry<Long, List<Photo>> entry : photosByTestimonialId.entrySet()) {
            int deleted = testimonialRepository.deleteByIdAndStatusAndRejectedAtBefore(
                    entry.getKey(), TestimonialStatus.REJECTED, cutoff);
            if (deleted > 0) {
                purgedTestimonials++;
                purgedPhotos.addAll(entry.getValue());
            } else {
                log.debug("Retention: testimonial {} changed since it was found, kept", entry.getKey());
            }
        }
        deletePurgedPhotoFilesAfterCommit(purgedPhotos);
        return new RejectionPurgeResult(purgedTestimonials, purgedPhotos.size());
    }

    private static List<Photo> photosOf(Testimonial testimonial) {
        return testimonial.getSections().stream()
                .flatMap(section -> section.getPhotos().stream())
                .toList();
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

    private Testimonial findPendingOrThrow(Long id) {
        Testimonial testimonial = testimonialRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Testimonial not found: " + id));
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
     * The sections the admin reviews, in catalog order: only those of
     * visible topics (decision 28) — a hidden section, with its photos and
     * diff flag, stays out of the queue until its topic is visible again.
     */
    private List<ModerationSectionViewDto> visibleSections(Testimonial testimonial) {
        return testimonial.getSections().stream()
                .filter(section -> section.getTopic().isVisible())
                .sorted(SECTION_ORDER)
                .map(this::toSectionView)
                .toList();
    }

    /** The achievement ticks the admin sees: only those of visible achievements (decision 28). */
    private static List<Achievement> visibleAchievements(Testimonial testimonial) {
        return testimonial.getAchievements().stream()
                .map(TestimonialAchievement::getAchievement)
                .filter(Achievement::isVisible)
                .toList();
    }

    private ModerationTestimonialDetailDto toDetail(Testimonial testimonial) {
        List<ModerationSectionViewDto> sections = visibleSections(testimonial);
        List<String> achievementSlugs = visibleAchievements(testimonial).stream()
                .map(Achievement::getSlug)
                .toList();
        List<ModerationContactMethodViewDto> contactMethods = testimonial.getContactMethods().stream()
                .map(cm -> new ModerationContactMethodViewDto(
                        cm.getContactType().getSlug(), cm.getValue(), cm.isPublic()))
                .toList();
        return new ModerationTestimonialDetailDto(
                testimonial.getId(),
                testimonial.getFirstName(),
                testimonial.getLastName(),
                testimonial.getRollNumber(),
                testimonial.getAdmissionYear(),
                testimonial.getEmail(),
                new ModerationCountryDto(testimonial.getCountry().getCode(), testimonial.getCountry().getName()),
                testimonial.getRecommendationScore(),
                testimonial.getStatus(),
                sections,
                achievementSlugs,
                contactMethods,
                testimonial.isIdentityModified(),
                testimonial.isScoreModified());
    }

    private ModerationQueueCardDto toQueueCard(Testimonial testimonial) {
        List<ModerationSectionViewDto> sections = visibleSections(testimonial);
        List<ModerationAchievementViewDto> achievements = visibleAchievements(testimonial).stream()
                .map(achievement -> new ModerationAchievementViewDto(achievement.getSlug(), achievement.getLabel()))
                .toList();
        List<ModerationContactMethodViewDto> contactMethods = testimonial.getContactMethods().stream()
                .map(cm -> new ModerationContactMethodViewDto(
                        cm.getContactType().getSlug(), cm.getValue(), cm.isPublic()))
                .toList();
        return new ModerationQueueCardDto(
                testimonial.getId(),
                testimonial.getFirstName(),
                testimonial.getLastName(),
                testimonial.getRollNumber(),
                testimonial.getAdmissionYear(),
                testimonial.getEmail(),
                new ModerationCountryDto(testimonial.getCountry().getCode(), testimonial.getCountry().getName()),
                testimonial.getRecommendationScore(),
                testimonial.getStatus(),
                sections,
                achievements,
                contactMethods,
                testimonial.isIdentityModified(),
                testimonial.isScoreModified(),
                testimonial.getCreatedAt(),
                testimonial.getReviewedAt() != null);
    }

    private ModerationSectionViewDto toSectionView(TestimonialSection section) {
        List<ModerationPhotoRefDto> photos = section.getPhotos().stream()
                .map(photo -> new ModerationPhotoRefDto(
                        photoUrlResolver.resolve(photo.getFilePath()),
                        photoUrlResolver.resolve(
                                photo.getThumbnailPath() != null ? photo.getThumbnailPath() : photo.getFilePath()),
                        photo.getWidth(),
                        photo.getHeight(),
                        photo.getTags().stream().map(PhotoTag::getTagText).toList()))
                .toList();
        return new ModerationSectionViewDto(
                section.getTopic().getSlug(),
                section.getTopic().getLabel(),
                section.getAnswerText(),
                photos,
                section.isModified());
    }
}
