package com.iitm.beacon.moderation;

import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.TestimonialNotPendingException;
import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core service for the {@code moderation} slice (UC-VIEW-PENDING-QUEUE,
 * UC-APPROVE-TESTIMONIAL, UC-REJECT-TESTIMONIAL, decision 18).
 */
@Service
public class ModerationService {

    private static final String NO_REASON_BODY =
            "Your IITM Beacon testimonial was not approved. Please log in to review and resubmit it.";
    private static final String REJECT_EMAIL_SUBJECT = "Your IITM Beacon testimonial needs changes";

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
    private final Clock clock;

    public ModerationService(
            TestimonialRepository testimonialRepository,
            NotificationMailer notificationMailer,
            PhotoUrlResolver photoUrlResolver,
            Clock clock) {
        this.testimonialRepository = testimonialRepository;
        this.notificationMailer = notificationMailer;
        this.photoUrlResolver = photoUrlResolver;
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
     * Approves a pending testimonial (UC-APPROVE-TESTIMONIAL): clears every
     * diff flag back to {@code false}, since nothing is left un-reviewed
     * once approved (decision 18).
     */
    @Transactional
    public void approve(Long id) {
        Testimonial testimonial = findPendingOrThrow(id);
        testimonial.setStatus(TestimonialStatus.APPROVED);
        testimonial.setReviewedAt(Instant.now(clock));
        testimonial.setIdentityModified(false);
        testimonial.setScoreModified(false);
        testimonial.getSections().forEach(section -> section.setModified(false));
    }

    /**
     * Rejects a pending testimonial (UC-REJECT-TESTIMONIAL). Deliberately
     * leaves {@code identityModified}/{@code scoreModified}/every section's
     * {@code modified} untouched — those flags are unrelated to reject
     * (decision 18) — and always emails the submitter, varying the body
     * depending on whether a reason was given.
     */
    @Transactional
    public void reject(Long id, String reason) {
        Testimonial testimonial = findPendingOrThrow(id);
        Instant now = Instant.now(clock);
        testimonial.setStatus(TestimonialStatus.REJECTED);
        testimonial.setReviewedAt(now);
        testimonial.setRejectedAt(now);
        notificationMailer.send(testimonial.getEmail(), REJECT_EMAIL_SUBJECT, rejectEmailBody(reason));
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

    private ModerationTestimonialDetailDto toDetail(Testimonial testimonial) {
        List<ModerationSectionViewDto> sections = testimonial.getSections().stream()
                .sorted(SECTION_ORDER)
                .map(this::toSectionView)
                .toList();
        List<String> achievementSlugs = testimonial.getAchievements().stream()
                .map(ta -> ta.getAchievement().getSlug())
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
        List<ModerationSectionViewDto> sections = testimonial.getSections().stream()
                .sorted(SECTION_ORDER)
                .map(this::toSectionView)
                .toList();
        List<ModerationAchievementViewDto> achievements = testimonial.getAchievements().stream()
                .map(ta -> new ModerationAchievementViewDto(
                        ta.getAchievement().getSlug(), ta.getAchievement().getLabel()))
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
