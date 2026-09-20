package com.iitm.beacon.submission;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.common.error.SubmissionValidationException;
import com.iitm.beacon.common.error.TestimonialAlreadyExistsException;
import com.iitm.beacon.config.PhotoStorageProperties;
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
import com.iitm.beacon.submission.TestimonialSubmissionRequest.ContactMethodInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Core service for the write side of the {@code submission} slice
 * (UC-CREATE-TESTIMONIAL, UC-EDIT-TESTIMONIAL, decision 18) and for routing a
 * just-verified visitor to create vs. edit (UC-VISITOR-LOGIN, decision 6).
 */
@Service
public class SubmissionService {

    private final TestimonialRepository testimonialRepository;
    private final TopicRepository topicRepository;
    private final AchievementRepository achievementRepository;
    private final ContactTypeRepository contactTypeRepository;
    private final CountryRepository countryRepository;
    private final EmailLookupHashService emailLookupHashService;
    private final PhotoStorageService photoStorageService;
    private final PhotoStorageProperties photoStorageProperties;
    private final Clock clock;

    public SubmissionService(
            TestimonialRepository testimonialRepository,
            TopicRepository topicRepository,
            AchievementRepository achievementRepository,
            ContactTypeRepository contactTypeRepository,
            CountryRepository countryRepository,
            EmailLookupHashService emailLookupHashService,
            PhotoStorageService photoStorageService,
            PhotoStorageProperties photoStorageProperties,
            Clock clock) {
        this.testimonialRepository = testimonialRepository;
        this.topicRepository = topicRepository;
        this.achievementRepository = achievementRepository;
        this.contactTypeRepository = contactTypeRepository;
        this.countryRepository = countryRepository;
        this.emailLookupHashService = emailLookupHashService;
        this.photoStorageService = photoStorageService;
        this.photoStorageProperties = photoStorageProperties;
        this.clock = clock;
    }

    /**
     * Routes a just-verified visitor to UC-CREATE-TESTIMONIAL or
     * UC-EDIT-TESTIMONIAL, per {@code email_lookup_hash} (decision 6),
     * regardless of that testimonial's status.
     */
    public SubmissionModeResult determineMode(String email) {
        String hash = emailLookupHashService.hash(email);
        return testimonialRepository
                .findByEmailLookupHash(hash)
                .map(t -> new SubmissionModeResult(SubmissionMode.EDIT, t.getId()))
                .orElseGet(() -> new SubmissionModeResult(SubmissionMode.CREATE, null));
    }

    /** Active contact types for the submission form's contacts block (decision 5). */
    public List<ContactTypeView> listActiveContactTypes() {
        return contactTypeRepository.findAll().stream()
                .filter(ContactType::isActive)
                .sorted(Comparator.comparing(ContactType::getDisplayOrder))
                .map(ct -> new ContactTypeView(ct.getSlug(), ct.getLabel()))
                .toList();
    }

    /** Active achievements for the submission form's checklist (decision 20). */
    public List<AchievementView> listActiveAchievements() {
        return achievementRepository.findAll().stream()
                .filter(Achievement::isActive)
                .sorted(Comparator.comparing(Achievement::getDisplayOrder))
                .map(a -> new AchievementView(a.getSlug(), a.getLabel()))
                .toList();
    }

    /**
     * Creates a new testimonial for a visitor with no existing one
     * (UC-CREATE-TESTIMONIAL). Validates every business rule that isn't
     * already expressed as Bean Validation on the DTO, then stores photos
     * only after every slug/count check has passed, to avoid orphaned
     * uploads on rejection (decision 2).
     */
    @Transactional
    public SubmissionResultResponse create(
            String email, TestimonialSubmissionRequest req, Map<String, MultipartFile> fileParts) {
        String hash = emailLookupHashService.hash(email);
        if (testimonialRepository.findByEmailLookupHash(hash).isPresent()) {
            throw new TestimonialAlreadyExistsException("A testimonial already exists for this visitor.");
        }

        ValidatedRefs refs = validateCommonRefs(req);
        Country country = refs.country();
        List<Achievement> achievements = refs.achievements();
        Map<String, ContactType> contactTypesBySlug = refs.contactTypesBySlug();

        List<SectionInput> filledSections = nonBlankSections(req.sections());
        if (filledSections.isEmpty()) {
            throw new SubmissionValidationException("At least one section must be filled in.");
        }

        Map<SectionInput, Topic> topicsBySection = new LinkedHashMap<>();
        int totalPhotos = 0;
        for (SectionInput section : filledSections) {
            Topic topic = resolveTopic(section.topicSlug());
            topicsBySection.put(section, topic);
            for (PhotoInput photo : section.photos()) {
                if (!fileParts.containsKey(photo.fileRef())) {
                    throw new SubmissionValidationException(
                            "Photo fileRef does not match an uploaded file: " + photo.fileRef());
                }
            }
            totalPhotos += section.photos().size();
        }
        if (totalPhotos > photoStorageProperties.maxPhotosPerTestimonial()) {
            throw new SubmissionValidationException(
                    "Too many photos: maximum is " + photoStorageProperties.maxPhotosPerTestimonial() + ".");
        }

        Testimonial testimonial = Testimonial.builder()
                .firstName(req.firstName())
                .lastName(req.lastName())
                .rollNumber(req.rollNumber())
                .admissionYear(req.admissionYear())
                .email(email)
                .emailLookupHash(hash)
                .country(country)
                .recommendationScore(req.recommendationScore())
                .dataProcessingConsent(req.dataProcessingConsent())
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.now(clock))
                .build();

        for (SectionInput section : filledSections) {
            TestimonialSection ts = TestimonialSection.builder()
                    .testimonial(testimonial)
                    .topic(topicsBySection.get(section))
                    .answerText(section.answer().trim())
                    .modified(false)
                    .build();
            int photoOrder = 0;
            for (PhotoInput photoInput : section.photos()) {
                MultipartFile file = fileParts.get(photoInput.fileRef());
                String storedPath = photoStorageService.store(file);
                Photo photo = Photo.builder()
                        .section(ts)
                        .filePath(storedPath)
                        .displayOrder(photoOrder++)
                        .build();
                addTags(photo, photoInput.tags());
                ts.getPhotos().add(photo);
            }
            testimonial.getSections().add(ts);
        }

        for (Achievement achievement : achievements) {
            testimonial.getAchievements().add(TestimonialAchievement.builder()
                    .testimonial(testimonial)
                    .achievement(achievement)
                    .build());
        }

        int contactOrder = 0;
        for (ContactMethodInput cm : req.contactMethods()) {
            testimonial.getContactMethods().add(ContactMethod.builder()
                    .testimonial(testimonial)
                    .contactType(contactTypesBySlug.get(cm.typeSlug()))
                    .value(cm.value())
                    .isPublic(cm.isPublic())
                    .displayOrder(contactOrder++)
                    .build());
        }

        Testimonial saved = testimonialRepository.save(testimonial);
        return new SubmissionResultResponse(saved.getId(), saved.getStatus());
    }

    /** Loads the visitor's own testimonial for editing (UC-EDIT-TESTIMONIAL). */
    @Transactional(readOnly = true)
    public TestimonialSubmissionView loadMine(String email) {
        String hash = emailLookupHashService.hash(email);
        Testimonial testimonial = testimonialRepository
                .findByEmailLookupHash(hash)
                .orElseThrow(() -> new NotFoundException("No testimonial exists yet for this visitor."));
        return toView(testimonial);
    }

    private TestimonialSubmissionView toView(Testimonial testimonial) {
        List<TestimonialSubmissionView.SectionView> sections = testimonial.getSections().stream()
                .map(section -> new TestimonialSubmissionView.SectionView(
                        section.getTopic().getSlug(),
                        section.getAnswerText(),
                        section.getPhotos().stream()
                                .map(photo -> new TestimonialSubmissionView.PhotoRef(
                                        photoStorageService.urlFor(photo.getFilePath()),
                                        photo.getTags().stream()
                                                .map(PhotoTag::getTagText)
                                                .toList()))
                                .toList()))
                .toList();
        List<String> achievementSlugs = testimonial.getAchievements().stream()
                .map(ta -> ta.getAchievement().getSlug())
                .toList();
        List<TestimonialSubmissionView.ContactMethodView> contactMethods = testimonial.getContactMethods().stream()
                .map(cm -> new TestimonialSubmissionView.ContactMethodView(
                        cm.getContactType().getSlug(), cm.getValue(), cm.isPublic()))
                .toList();
        return new TestimonialSubmissionView(
                testimonial.getFirstName(),
                testimonial.getLastName(),
                testimonial.getRollNumber(),
                testimonial.getAdmissionYear(),
                testimonial.getCountry().getCode(),
                testimonial.getRecommendationScore(),
                sections,
                achievementSlugs,
                contactMethods,
                testimonial.isDataProcessingConsent());
    }

    /**
     * Edits the visitor's existing testimonial (UC-EDIT-TESTIMONIAL),
     * implementing decision 18's diffing/short-circuit algorithm exactly:
     * a kept photo is identified by the incoming request re-sending its own
     * current {@code url} as {@code fileRef}; anything else must name a
     * genuinely new multipart part. Section/photo text-or-photo changes and
     * identity-field changes always drive full re-moderation; country,
     * score, achievements, and contact-method changes never do, on their
     * own, once the testimonial has already been approved once.
     */
    @Transactional
    public SubmissionResultResponse edit(
            String email, TestimonialSubmissionRequest req, Map<String, MultipartFile> fileParts) {
        String hash = emailLookupHashService.hash(email);
        Testimonial testimonial = testimonialRepository
                .findByEmailLookupHash(hash)
                .orElseThrow(() -> new NotFoundException("No testimonial exists yet for this visitor."));

        ValidatedRefs refs = validateCommonRefs(req);

        boolean identityModified = !Objects.equals(testimonial.getFirstName(), req.firstName())
                || !Objects.equals(testimonial.getLastName(), req.lastName())
                || !Objects.equals(testimonial.getRollNumber(), req.rollNumber())
                || !Objects.equals(testimonial.getAdmissionYear(), req.admissionYear());
        boolean scoreModified = !Objects.equals(testimonial.getRecommendationScore(), req.recommendationScore());

        List<SectionInput> filledSections = nonBlankSections(req.sections());

        Map<Long, Topic> topicByTopicId = new LinkedHashMap<>();
        Map<Long, SectionInput> incomingByTopicId = new LinkedHashMap<>();
        for (SectionInput incoming : filledSections) {
            Topic topic = resolveTopic(incoming.topicSlug());
            topicByTopicId.put(topic.getId(), topic);
            // A duplicate topicSlug across incoming sections is not a
            // documented case; last-one-wins here, matching a plain map put.
            incomingByTopicId.put(topic.getId(), incoming);
        }
        if (incomingByTopicId.isEmpty()) {
            throw new SubmissionValidationException("At least one section must be filled in.");
        }

        Map<Long, TestimonialSection> existingSectionsByTopicId = new LinkedHashMap<>();
        for (TestimonialSection section : testimonial.getSections()) {
            existingSectionsByTopicId.put(section.getTopic().getId(), section);
        }

        // Phase 1: validate every photo fileRef and compute the resulting
        // total photo count with zero IO, so a rejection here never leaves
        // an orphaned upload or a wrongly-deleted live file behind.
        int totalPhotos = 0;
        for (Map.Entry<Long, SectionInput> entry : incomingByTopicId.entrySet()) {
            SectionInput incoming = entry.getValue();
            TestimonialSection existing = existingSectionsByTopicId.get(entry.getKey());
            Set<String> currentUrls = existing == null
                    ? Set.of()
                    : existing.getPhotos().stream()
                            .map(p -> photoStorageService.urlFor(p.getFilePath()))
                            .collect(Collectors.toSet());
            for (PhotoInput photo : incoming.photos()) {
                boolean isKept = currentUrls.contains(photo.fileRef());
                if (!isKept && !fileParts.containsKey(photo.fileRef())) {
                    throw new SubmissionValidationException(
                            "Photo fileRef does not match an existing photo or an uploaded file: "
                                    + photo.fileRef());
                }
            }
            totalPhotos += incoming.photos().size();
        }
        if (totalPhotos > photoStorageProperties.maxPhotosPerTestimonial()) {
            throw new SubmissionValidationException(
                    "Too many photos: maximum is " + photoStorageProperties.maxPhotosPerTestimonial() + ".");
        }

        // Phase 2: apply. Every incoming section is either brand new or an
        // existing one being diffed; whatever's left in
        // existingSectionsByTopicId afterward was left out of the request
        // entirely, and is removed without setting `modified` on anything
        // (decision 18).
        List<TestimonialSection> resultSections = new ArrayList<>();
        for (Map.Entry<Long, SectionInput> entry : incomingByTopicId.entrySet()) {
            SectionInput incoming = entry.getValue();
            Topic topic = topicByTopicId.get(entry.getKey());
            TestimonialSection existing = existingSectionsByTopicId.remove(entry.getKey());
            if (existing == null) {
                resultSections.add(buildNewSection(testimonial, topic, incoming, fileParts));
            } else {
                applyExistingSectionEdit(existing, incoming, fileParts);
                resultSections.add(existing);
            }
        }
        for (TestimonialSection removed : existingSectionsByTopicId.values()) {
            for (Photo photo : removed.getPhotos()) {
                photoStorageService.delete(photo.getFilePath());
            }
        }

        testimonial.getSections().clear();
        testimonial.getSections().addAll(resultSections);

        testimonial.getContactMethods().clear();
        int contactOrder = 0;
        for (ContactMethodInput cm : req.contactMethods()) {
            testimonial.getContactMethods().add(ContactMethod.builder()
                    .testimonial(testimonial)
                    .contactType(refs.contactTypesBySlug().get(cm.typeSlug()))
                    .value(cm.value())
                    .isPublic(cm.isPublic())
                    .displayOrder(contactOrder++)
                    .build());
        }

        testimonial.getAchievements().clear();
        for (Achievement achievement : refs.achievements()) {
            testimonial.getAchievements().add(TestimonialAchievement.builder()
                    .testimonial(testimonial)
                    .achievement(achievement)
                    .build());
        }

        testimonial.setCountry(refs.country());
        testimonial.setFirstName(req.firstName());
        testimonial.setLastName(req.lastName());
        testimonial.setRollNumber(req.rollNumber());
        testimonial.setAdmissionYear(req.admissionYear());
        testimonial.setRecommendationScore(req.recommendationScore());
        testimonial.setDataProcessingConsent(req.dataProcessingConsent());

        boolean wasApproved = testimonial.getStatus() == TestimonialStatus.APPROVED;
        boolean anySectionModified =
                testimonial.getSections().stream().anyMatch(TestimonialSection::isModified);

        if (wasApproved && !identityModified && !anySectionModified) {
            testimonial.setStatus(TestimonialStatus.APPROVED);
            scoreModified = false;
        } else {
            testimonial.setStatus(TestimonialStatus.PENDING);
        }
        testimonial.setIdentityModified(identityModified);
        testimonial.setScoreModified(scoreModified);

        Testimonial saved = testimonialRepository.save(testimonial);
        return new SubmissionResultResponse(saved.getId(), saved.getStatus());
    }

    private TestimonialSection buildNewSection(
            Testimonial testimonial, Topic topic, SectionInput incoming, Map<String, MultipartFile> fileParts) {
        TestimonialSection created = TestimonialSection.builder()
                .testimonial(testimonial)
                .topic(topic)
                .answerText(incoming.answer().trim())
                .modified(true)
                .build();
        int order = 0;
        for (PhotoInput photoInput : incoming.photos()) {
            MultipartFile file = fileParts.get(photoInput.fileRef());
            String stored = photoStorageService.store(file);
            Photo photo = Photo.builder()
                    .section(created)
                    .filePath(stored)
                    .displayOrder(order++)
                    .build();
            addTags(photo, photoInput.tags());
            created.getPhotos().add(photo);
        }
        return created;
    }

    /**
     * Diffs one already-existing section against its incoming edit,
     * mutating it in place: kept photos are matched by their current {@code
     * url}, tag changes on a kept photo count as a modification just like
     * edited text (decision 18), and any current photo left unreferenced is
     * dropped (file deleted, row removed via {@code orphanRemoval}).
     */
    private void applyExistingSectionEdit(
            TestimonialSection existing, SectionInput incoming, Map<String, MultipartFile> fileParts) {
        String newAnswerText = incoming.answer().trim();
        boolean textChanged = !Objects.equals(existing.getAnswerText(), newAnswerText);

        Map<String, Photo> currentPhotosByUrl = new LinkedHashMap<>();
        for (Photo photo : existing.getPhotos()) {
            currentPhotosByUrl.put(photoStorageService.urlFor(photo.getFilePath()), photo);
        }

        boolean photosChanged = false;
        List<Photo> newOrder = new ArrayList<>();
        for (PhotoInput incomingPhoto : incoming.photos()) {
            Photo kept = currentPhotosByUrl.remove(incomingPhoto.fileRef());
            if (kept != null) {
                if (tagsChanged(kept, incomingPhoto.tags())) {
                    replaceTags(kept, incomingPhoto.tags());
                    photosChanged = true;
                }
                newOrder.add(kept);
            } else {
                MultipartFile file = fileParts.get(incomingPhoto.fileRef());
                String stored = photoStorageService.store(file);
                Photo newPhoto = Photo.builder()
                        .section(existing)
                        .filePath(stored)
                        .displayOrder(newOrder.size())
                        .build();
                addTags(newPhoto, incomingPhoto.tags());
                newOrder.add(newPhoto);
                photosChanged = true;
            }
        }
        for (Photo dropped : currentPhotosByUrl.values()) {
            photoStorageService.delete(dropped.getFilePath());
            photosChanged = true;
        }

        existing.getPhotos().clear();
        existing.getPhotos().addAll(newOrder);
        for (int i = 0; i < newOrder.size(); i++) {
            newOrder.get(i).setDisplayOrder(i);
        }
        existing.setAnswerText(newAnswerText);
        existing.setModified(textChanged || photosChanged);
    }

    private boolean tagsChanged(Photo photo, List<String> incomingTags) {
        Set<String> current =
                photo.getTags().stream().map(PhotoTag::getTagText).collect(Collectors.toSet());
        return !current.equals(dedupeTags(incomingTags));
    }

    private void replaceTags(Photo photo, List<String> incomingTags) {
        photo.getTags().clear();
        addTags(photo, incomingTags);
    }

    /** Shared validation for both {@link #create} and {@link #edit}. */
    private record ValidatedRefs(
            Country country, List<Achievement> achievements, Map<String, ContactType> contactTypesBySlug) {
    }

    private ValidatedRefs validateCommonRefs(TestimonialSubmissionRequest req) {
        Country country = resolveCountry(req.countryCode());
        List<Achievement> achievements = resolveAchievements(req.achievementSlugs());
        Map<String, ContactType> contactTypesBySlug = resolveContactTypesBySlug(req.contactMethods());
        return new ValidatedRefs(country, achievements, contactTypesBySlug);
    }

    private Country resolveCountry(String countryCode) {
        String normalized = countryCode.toUpperCase(Locale.ROOT);
        return countryRepository
                .findById(normalized)
                .orElseThrow(() -> new SubmissionValidationException("Unknown country code: " + countryCode));
    }

    private Topic resolveTopic(String slug) {
        return topicRepository
                .findBySlug(slug)
                .filter(Topic::isActive)
                .orElseThrow(() -> new SubmissionValidationException("Unknown or inactive topic: " + slug));
    }

    private List<Achievement> resolveAchievements(List<String> slugs) {
        List<Achievement> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String slug : slugs) {
            if (!seen.add(slug)) {
                continue;
            }
            Achievement achievement = achievementRepository
                    .findBySlug(slug)
                    .filter(Achievement::isActive)
                    .orElseThrow(
                            () -> new SubmissionValidationException("Unknown or inactive achievement: " + slug));
            result.add(achievement);
        }
        return result;
    }

    private Map<String, ContactType> resolveContactTypesBySlug(List<ContactMethodInput> contactMethods) {
        Map<String, ContactType> result = new LinkedHashMap<>();
        for (ContactMethodInput cm : contactMethods) {
            if (result.containsKey(cm.typeSlug())) {
                continue;
            }
            ContactType contactType = contactTypeRepository
                    .findBySlug(cm.typeSlug())
                    .filter(ContactType::isActive)
                    .orElseThrow(() -> new SubmissionValidationException(
                            "Unknown or inactive contact type: " + cm.typeSlug()));
            result.put(cm.typeSlug(), contactType);
        }
        return result;
    }

    private List<SectionInput> nonBlankSections(List<SectionInput> sections) {
        return sections.stream()
                .filter(s -> s.answer() != null && !s.answer().isBlank())
                .toList();
    }

    private Set<String> dedupeTags(List<String> tags) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                result.add(tag.trim());
            }
        }
        return result;
    }

    private void addTags(Photo photo, List<String> tags) {
        for (String tag : dedupeTags(tags)) {
            photo.getTags().add(PhotoTag.builder().photo(photo).tagText(tag).build());
        }
    }
}
