package com.iitm.beacon.submission;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.error.FieldViolation;
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
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.ContactMethodInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    static final String PHOTOS_NEED_TEXT_MESSAGE =
            "Photos need some text — write something here, or remove the photos.";

    private final TestimonialRepository testimonialRepository;
    private final TopicRepository topicRepository;
    private final TopicGroupRepository topicGroupRepository;
    private final AchievementRepository achievementRepository;
    private final ContactTypeRepository contactTypeRepository;
    private final CountryRepository countryRepository;
    private final EmailLookupHashService emailLookupHashService;
    private final PhotoStorageService photoStorageService;
    private final PhotoStorageProperties photoStorageProperties;
    private final Clock clock;
    private final Validator validator;

    public SubmissionService(
            TestimonialRepository testimonialRepository,
            TopicRepository topicRepository,
            AchievementRepository achievementRepository,
            ContactTypeRepository contactTypeRepository,
            CountryRepository countryRepository,
            EmailLookupHashService emailLookupHashService,
            PhotoStorageService photoStorageService,
            PhotoStorageProperties photoStorageProperties,
            Clock clock,
            TopicGroupRepository topicGroupRepository,
            Validator validator) {
        this.testimonialRepository = testimonialRepository;
        this.topicRepository = topicRepository;
        this.achievementRepository = achievementRepository;
        this.contactTypeRepository = contactTypeRepository;
        this.countryRepository = countryRepository;
        this.emailLookupHashService = emailLookupHashService;
        this.photoStorageService = photoStorageService;
        this.photoStorageProperties = photoStorageProperties;
        this.clock = clock;
        this.topicGroupRepository = topicGroupRepository;
        this.validator = validator;
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
                .map(ct -> new ContactTypeView(ct.getSlug(), ct.getName(), ct.getLabel(), ct.getValuePattern()))
                .toList();
    }

    /**
     * The latest admission year a submission may carry — the current year per
     * the application {@code Clock} (decision 10). Also the form's {@code max}.
     */
    public int latestAdmissionYear() {
        return Year.now(clock).getValue();
    }

    /** The photo limits the submission form states and its script enforces; this service enforces them too. */
    public PhotoUploadLimits photoUploadLimits() {
        return PhotoUploadLimits.from(photoStorageProperties);
    }

    /** Visible achievements for the submission form's checklist (decisions 20, 28). */
    public List<AchievementView> listActiveAchievements() {
        return achievementRepository.findAll().stream()
                .filter(Achievement::isVisible)
                .sorted(Comparator.comparing(Achievement::getDisplayOrder))
                .map(a -> new AchievementView(a.getSlug(), a.getLabel()))
                .toList();
    }

    /**
     * Every country (decision 1), for the submission form's country {@code
     * <select>} — deliberately unfiltered by approved-testimonial existence,
     * unlike {@code gallery.GalleryService#listCountriesWithApproved}: a
     * visitor must be able to pick their own country even if nobody from it
     * has an approved testimonial yet.
     */
    @Transactional(readOnly = true)
    public List<CountryDto> listAllCountries() {
        return countryRepository.findAll().stream()
                .sorted(Comparator.comparing(Country::getName))
                .map(c -> new CountryDto(c.getCode(), c.getName()))
                .toList();
    }

    /**
     * Every visible top-level topic-catalog entry (decisions 11, 28) for the
     * submission form's topic picker — deliberately unfiltered by
     * approved-testimonial existence, unlike {@code
     * GalleryService#listTopicCatalogWithApproved}: a visitor must be able to
     * write about any active topic, not just ones already covered by an
     * approved testimonial. An active group left with zero active subtopics
     * is still dropped (nothing to fill in), same reasoning as the gallery
     * side just substituting "active" for "has an approved testimonial".
     */
    @Transactional(readOnly = true)
    public List<TopicCatalogEntryDto> listTopicCatalog() {
        List<Topic> allTopics = topicRepository.findAll();
        Map<Long, List<Topic>> topicsByGroupId = allTopics.stream()
                .filter(t -> t.getTopicGroup() != null)
                .collect(Collectors.groupingBy(t -> t.getTopicGroup().getId()));

        List<OrderedCatalogEntry> entries = new ArrayList<>();

        for (TopicGroup group : topicGroupRepository.findAll()) {
            if (!group.isActive()) {
                continue;
            }
            List<TopicPickDto> subtopics = topicsByGroupId.getOrDefault(group.getId(), List.of()).stream()
                    .filter(Topic::isVisible)
                    .sorted(Comparator.comparing(Topic::getDisplayOrder))
                    .map(t -> new TopicPickDto(t.getId(), t.getSlug(), t.getLabel(), t.getGuidingPrompt()))
                    .toList();
            if (!subtopics.isEmpty()) {
                entries.add(
                        new OrderedCatalogEntry(group.getDisplayOrder(), TopicCatalogEntryDto.group(group, subtopics)));
            }
        }

        for (Topic topic : allTopics) {
            if (topic.getTopicGroup() != null || !topic.isVisible()) {
                continue;
            }
            entries.add(new OrderedCatalogEntry(topic.getDisplayOrder(), TopicCatalogEntryDto.standalone(topic)));
        }

        return entries.stream()
                .sorted(Comparator.comparingInt(OrderedCatalogEntry::topLevelDisplayOrder))
                .map(OrderedCatalogEntry::dto)
                .toList();
    }

    /** Pairs a catalog entry with its top-level sort key ahead of the final combined sort. */
    private record OrderedCatalogEntry(int topLevelDisplayOrder, TopicCatalogEntryDto dto) {
    }

    /**
     * Creates a new testimonial for a visitor with no existing one
     * (UC-CREATE-TESTIMONIAL). Every rule — Bean Validation on the request
     * plus the business rules below — is checked in one pass, and all
     * violations are thrown together as one {@link
     * SubmissionValidationException} (decision 21). Photos are stored only
     * after everything has passed, to avoid orphaned uploads on rejection
     * (decision 2).
     */
    @Transactional
    public SubmissionResultResponse create(
            String email, TestimonialSubmissionRequest req, Map<String, MultipartFile> fileParts) {
        return create(email, req, fileParts, UnaryOperator.identity());
    }

    /**
     * {@link #create}, with {@code reportAs} turning the request-level
     * violations into the ones actually thrown (the HTML form translates
     * them to its own field paths and adds its form-only rules) — so the
     * throw still happens before anything is stored, even when only a
     * form-only rule is broken.
     */
    private SubmissionResultResponse create(
            String email,
            TestimonialSubmissionRequest req,
            Map<String, MultipartFile> fileParts,
            UnaryOperator<List<FieldViolation>> reportAs) {
        String hash = emailLookupHashService.hash(email);
        if (testimonialRepository.findByEmailLookupHash(hash).isPresent()) {
            throw new TestimonialAlreadyExistsException("A testimonial already exists for this visitor.");
        }

        List<FieldViolation> violations = new ArrayList<>();
        ValidatedRefs refs = validateCommonRules(req, violations);
        List<FilledSection> filledSections = validateSections(
                req.sections(),
                violations,
                (topic, fileRef) -> fileParts.containsKey(fileRef),
                "Photo fileRef does not match an uploaded file: ");
        checkTotalPhotoCount(filledSections, violations);
        throwIfAny(reportAs.apply(violations));

        Country country = refs.country().orElseThrow();
        List<Achievement> achievements = refs.achievements();
        Map<String, ContactType> contactTypesBySlug = refs.contactTypesBySlug();

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

        for (FilledSection filled : filledSections) {
            SectionInput section = filled.input();
            TestimonialSection ts = TestimonialSection.builder()
                    .testimonial(testimonial)
                    .topic(filled.topic())
                    .answerText(section.answer().trim())
                    .modified(false)
                    .build();
            int photoOrder = 0;
            for (PhotoInput photoInput : section.photos()) {
                Photo photo = storePhoto(ts, fileParts.get(photoInput.fileRef()), photoOrder++);
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

    /**
     * The author's own testimonial as the edit form and {@code GET
     * /submissions/mine} show it: only sections of visible topics and ticks
     * of visible achievements (decision 28) — {@link #edit} leaves the
     * hidden ones untouched.
     */
    private TestimonialSubmissionView toView(Testimonial testimonial) {
        List<TestimonialSubmissionView.SectionView> sections = testimonial.getSections().stream()
                .filter(SubmissionService::isVisible)
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
                .map(TestimonialAchievement::getAchievement)
                .filter(Achievement::isVisible)
                .map(Achievement::getSlug)
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
        return edit(email, req, fileParts, UnaryOperator.identity());
    }

    /** {@link #edit}, with the same {@code reportAs} hook as the private {@code create} overload. */
    private SubmissionResultResponse edit(
            String email,
            TestimonialSubmissionRequest req,
            Map<String, MultipartFile> fileParts,
            UnaryOperator<List<FieldViolation>> reportAs) {
        String hash = emailLookupHashService.hash(email);
        Testimonial testimonial = testimonialRepository
                .findByEmailLookupHash(hash)
                .orElseThrow(() -> new NotFoundException("No testimonial exists yet for this visitor."));

        List<FieldViolation> violations = new ArrayList<>();
        ValidatedRefs refs = validateCommonRules(req, violations);

        // Sections of invisible topics are not on the visitor's form, so they
        // are neither diffed nor removed: they are kept exactly as they are,
        // photos and `modified` flag included (decision 28).
        List<TestimonialSection> hiddenSections = new ArrayList<>();
        Map<Long, TestimonialSection> existingSectionsByTopicId = new LinkedHashMap<>();
        Map<Long, Set<String>> currentPhotoUrlsByTopicId = new LinkedHashMap<>();
        for (TestimonialSection section : testimonial.getSections()) {
            if (!isVisible(section)) {
                hiddenSections.add(section);
                continue;
            }
            existingSectionsByTopicId.put(section.getTopic().getId(), section);
            currentPhotoUrlsByTopicId.put(
                    section.getTopic().getId(),
                    section.getPhotos().stream()
                            .map(p -> photoStorageService.urlFor(p.getFilePath()))
                            .collect(Collectors.toSet()));
        }

        // Validate every section and photo fileRef with zero IO, so a
        // rejection never leaves an orphaned upload or a wrongly-deleted
        // live file behind. A kept photo is one whose fileRef is the url of
        // a photo its own section already has; anything else must name a
        // genuinely new multipart part (decision 18).
        List<FilledSection> filledSections = validateSections(
                req.sections(),
                violations,
                (topic, fileRef) -> currentPhotoUrlsByTopicId.getOrDefault(topic.getId(), Set.of()).contains(fileRef)
                        || fileParts.containsKey(fileRef),
                "Photo fileRef does not match an existing photo or an uploaded file: ");
        Map<Long, Topic> topicByTopicId = new LinkedHashMap<>();
        Map<Long, SectionInput> incomingByTopicId = new LinkedHashMap<>();
        for (FilledSection filled : filledSections) {
            topicByTopicId.put(filled.topic().getId(), filled.topic());
            // A duplicate topicSlug across incoming sections is not a
            // documented case; last-one-wins here, matching a plain map put.
            incomingByTopicId.put(filled.topic().getId(), filled.input());
        }
        checkTotalPhotoCount(
                incomingByTopicId.values().stream().mapToInt(section -> section.photos().size()).sum(), violations);
        throwIfAny(reportAs.apply(violations));

        boolean identityModified = !Objects.equals(testimonial.getFirstName(), req.firstName())
                || !Objects.equals(testimonial.getLastName(), req.lastName())
                || !Objects.equals(testimonial.getRollNumber(), req.rollNumber())
                || !Objects.equals(testimonial.getAdmissionYear(), req.admissionYear());
        boolean scoreModified = !Objects.equals(testimonial.getRecommendationScore(), req.recommendationScore());

        // Apply. Every incoming section is either brand new or an
        // existing one being diffed; whatever's left in
        // existingSectionsByTopicId afterward was left out of the request
        // entirely, and is removed without setting `modified` on anything
        // (decision 18). Hidden sections were never in that map, and are
        // put back unchanged.
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
                photoStorageService.delete(photo);
            }
        }

        testimonial.getSections().clear();
        testimonial.getSections().addAll(resultSections);
        testimonial.getSections().addAll(hiddenSections);

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

        syncAchievements(testimonial, refs.achievements());

        testimonial.setCountry(refs.country().orElseThrow());
        testimonial.setFirstName(req.firstName());
        testimonial.setLastName(req.lastName());
        testimonial.setRollNumber(req.rollNumber());
        testimonial.setAdmissionYear(req.admissionYear());
        testimonial.setRecommendationScore(req.recommendationScore());
        testimonial.setDataProcessingConsent(req.dataProcessingConsent());

        boolean wasApproved = testimonial.getStatus() == TestimonialStatus.APPROVED;
        boolean anySectionModified = resultSections.stream().anyMatch(TestimonialSection::isModified);

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

    /**
     * Translates a {@link SubmissionFormCommand} from the plain multipart
     * HTML submission form into the exact same {@code
     * TestimonialSubmissionRequest} + file-map shape {@link #create} already
     * expects (see {@link FormSubmission}), then delegates to it — no
     * business rule is duplicated here. Every violation comes back in one
     * {@link SubmissionValidationException}, at FORM field paths ({@code
     * sections[k].answerText}, {@code contactMethods[k].value}, ...) and
     * merged with the form-only rules, so the view can show each message
     * next to its own field (decision 21).
     */
    @Transactional
    public SubmissionResultResponse createFromForm(String email, SubmissionFormCommand command) {
        FormSubmission form = FormSubmission.from(command);
        return create(email, form.request(), form.fileMap(), form::toFormViolations);
    }

    /**
     * Same translation as {@link #createFromForm}, delegating to {@link
     * #edit} instead. A kept existing photo is represented by resending its
     * own url as {@code fileRef} (decision 18) — exactly what {@link #edit}
     * already expects from the JSON path.
     */
    @Transactional
    public SubmissionResultResponse editFromForm(String email, SubmissionFormCommand command) {
        FormSubmission form = FormSubmission.from(command);
        return edit(email, form.request(), form.fileMap(), form::toFormViolations);
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
            Photo photo = storePhoto(created, fileParts.get(photoInput.fileRef()), order++);
            addTags(photo, photoInput.tags());
            created.getPhotos().add(photo);
        }
        return created;
    }

    /**
     * Converts and stores one uploaded file ({@link PhotoStorageService}) and
     * builds its {@link Photo} row: full-size file, thumbnail, and size.
     */
    private Photo storePhoto(TestimonialSection section, MultipartFile file, int displayOrder) {
        StoredPhoto stored = photoStorageService.store(file);
        return Photo.builder()
                .section(section)
                .filePath(stored.filePath())
                .thumbnailPath(stored.thumbnailPath())
                .width(stored.width())
                .height(stored.height())
                .displayOrder(displayOrder)
                .build();
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
                    syncTags(kept, incomingPhoto.tags());
                    photosChanged = true;
                }
                newOrder.add(kept);
            } else {
                Photo newPhoto = storePhoto(existing, fileParts.get(incomingPhoto.fileRef()), newOrder.size());
                addTags(newPhoto, incomingPhoto.tags());
                newOrder.add(newPhoto);
                photosChanged = true;
            }
        }
        for (Photo dropped : currentPhotosByUrl.values()) {
            photoStorageService.delete(dropped);
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

    /**
     * Diffs a kept photo's tags against the incoming ones instead of
     * clear-and-re-add: Hibernate executes orphan deletes after inserts at
     * flush, so re-inserting a tag text the photo already has would violate
     * {@code photo_tag}'s {@code (photo_id, tag_text)} uniqueness before the
     * old row is gone. Only tags no longer wanted are removed, and only
     * genuinely new ones are added.
     */
    private void syncTags(Photo photo, List<String> incomingTags) {
        Set<String> wanted = dedupeTags(incomingTags);
        photo.getTags().removeIf(tag -> !wanted.contains(tag.getTagText()));
        Set<String> present =
                photo.getTags().stream().map(PhotoTag::getTagText).collect(Collectors.toSet());
        for (String tag : wanted) {
            if (present.add(tag)) {
                photo.getTags().add(PhotoTag.builder().photo(photo).tagText(tag).build());
            }
        }
    }

    /**
     * Diffs the testimonial's achievement join rows against the incoming
     * selection instead of clear-and-re-add: a {@link TestimonialAchievement}'s
     * composite id is derived from (testimonial, achievement), so a fresh
     * instance for an achievement that is already selected would share the id
     * of the old row still in the persistence context and fail the flush
     * ({@code NonUniqueObjectException}). Only deselected rows are removed
     * (via {@code orphanRemoval}), and only newly selected ones are added. A
     * tick of an invisible achievement is not on the visitor's form, so it is
     * kept as it is (decision 28).
     */
    private void syncAchievements(Testimonial testimonial, List<Achievement> selected) {
        Set<Long> selectedIds = selected.stream().map(Achievement::getId).collect(Collectors.toSet());
        testimonial.getAchievements().removeIf(ta -> ta.getAchievement().isVisible()
                && !selectedIds.contains(ta.getAchievement().getId()));
        Set<Long> presentIds = testimonial.getAchievements().stream()
                .map(ta -> ta.getAchievement().getId())
                .collect(Collectors.toSet());
        for (Achievement achievement : selected) {
            if (presentIds.add(achievement.getId())) {
                testimonial.getAchievements().add(TestimonialAchievement.builder()
                        .testimonial(testimonial)
                        .achievement(achievement)
                        .build());
            }
        }
    }

    /** References resolved while validating; {@code country} is empty only if a violation was recorded. */
    private record ValidatedRefs(
            Optional<Country> country, List<Achievement> achievements, Map<String, ContactType> contactTypesBySlug) {
    }

    /** A section with a non-blank answer that passed validation, with its resolved topic. */
    private record FilledSection(SectionInput input, Topic topic) {
    }

    /**
     * Every rule shared by {@link #create} and {@link #edit} that doesn't
     * concern sections: Bean Validation on the request, the admission
     * year's current-year ceiling (decision 10), and the country,
     * achievement, and contact references — each contact value checked
     * against its type's {@code valuePattern} (decision 5). Violations are
     * appended to {@code violations}; a reference check is skipped for a
     * field Bean Validation already reported, so one mistake yields one
     * violation.
     */
    private ValidatedRefs validateCommonRules(TestimonialSubmissionRequest req, List<FieldViolation> violations) {
        List<FieldViolation> beanViolations = validator.validate(req).stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        violations.addAll(beanViolations);
        Set<String> reported = beanViolations.stream().map(FieldViolation::field).collect(Collectors.toSet());

        int latestYear = latestAdmissionYear();
        if (req.admissionYear() != null && !reported.contains("admissionYear") && req.admissionYear() > latestYear) {
            violations.add(new FieldViolation(
                    "admissionYear",
                    "must be between " + TestimonialSubmissionRequest.EARLIEST_ADMISSION_YEAR + " and " + latestYear));
        }

        Optional<Country> country = reported.contains("countryCode")
                ? Optional.empty()
                : resolveCountry(req.countryCode(), violations);
        List<Achievement> achievements = resolveAchievements(req.achievementSlugs(), violations);
        Map<String, ContactType> contactTypesBySlug = resolveContactMethods(req.contactMethods(), violations);
        return new ValidatedRefs(country, achievements, contactTypesBySlug);
    }

    private Optional<Country> resolveCountry(String countryCode, List<FieldViolation> violations) {
        Optional<Country> country = countryRepository.findById(countryCode.toUpperCase(Locale.ROOT));
        if (country.isEmpty()) {
            violations.add(new FieldViolation("countryCode", "Unknown country code: " + countryCode));
        }
        return country;
    }

    private List<Achievement> resolveAchievements(List<String> slugs, List<FieldViolation> violations) {
        List<Achievement> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String slug : slugs) {
            if (!seen.add(slug)) {
                continue;
            }
            achievementRepository
                    .findBySlug(slug)
                    .filter(Achievement::isVisible)
                    .ifPresentOrElse(
                            result::add,
                            () -> violations.add(new FieldViolation(
                                    "achievementSlugs", "Unknown or inactive achievement: " + slug)));
        }
        return result;
    }

    /**
     * Resolves each contact method's type and checks its (already trimmed)
     * value against the type's {@code valuePattern}. A blank type slug or
     * value is left to Bean Validation.
     */
    private Map<String, ContactType> resolveContactMethods(
            List<ContactMethodInput> contactMethods, List<FieldViolation> violations) {
        Map<String, Optional<ContactType>> lookedUp = new HashMap<>();
        Map<String, ContactType> result = new LinkedHashMap<>();
        for (int j = 0; j < contactMethods.size(); j++) {
            ContactMethodInput cm = contactMethods.get(j);
            if (isBlank(cm.typeSlug())) {
                continue;
            }
            Optional<ContactType> contactType = lookedUp.computeIfAbsent(
                    cm.typeSlug(), slug -> contactTypeRepository.findBySlug(slug).filter(ContactType::isActive));
            if (contactType.isEmpty()) {
                violations.add(new FieldViolation(
                        "contactMethods[" + j + "].typeSlug", "Unknown or inactive contact type: " + cm.typeSlug()));
                continue;
            }
            ContactType type = contactType.get();
            result.put(cm.typeSlug(), type);
            if (!isBlank(cm.value()) && !matchesValuePattern(type, cm.value())) {
                violations.add(new FieldViolation(
                        "contactMethods[" + j + "].value",
                        "doesn't look like a valid " + type.getName() + " contact — expected: " + type.getLabel()));
            }
        }
        return result;
    }

    /**
     * Full match against the type's pattern (decision 5). A type without
     * one only needs a non-blank value; so does one whose pattern doesn't
     * compile — the same thing a browser does with an invalid HTML {@code
     * pattern} — rather than failing every submission for that type.
     */
    private static boolean matchesValuePattern(ContactType type, String value) {
        String valuePattern = type.getValuePattern();
        if (isBlank(valuePattern)) {
            return true;
        }
        try {
            return Pattern.compile(valuePattern).matcher(value).matches();
        } catch (PatternSyntaxException e) {
            log.warn("Contact type {} has a value_pattern that does not compile, so only a non-blank value is"
                    + " required: {}", type.getSlug(), e.getDescription());
            return true;
        }
    }

    /**
     * Validates the request's sections, returning the non-blank ones (with
     * their topics) to save. A blank-answer section is dropped silently —
     * unless it carries photos, which is a violation at its {@code answer}
     * (UC-CREATE-TESTIMONIAL). {@code isResolvableFileRef} decides whether a
     * photo's {@code fileRef} names something real: a multipart part on
     * create, or also a kept photo of the same topic's section on edit. A
     * section may end up with at most {@code max-photos-per-section} photos,
     * kept and new together — a violation at its {@code photos}.
     */
    private List<FilledSection> validateSections(
            List<SectionInput> sections,
            List<FieldViolation> violations,
            BiPredicate<Topic, String> isResolvableFileRef,
            String unresolvedFileRefMessage) {
        if (sections == null || sections.isEmpty()) {
            // Already reported at `sections` by Bean Validation (@NotEmpty).
            return List.of();
        }
        List<FilledSection> filled = new ArrayList<>();
        boolean anyAnswered = false;
        for (int i = 0; i < sections.size(); i++) {
            SectionInput section = sections.get(i);
            String path = "sections[" + i + "]";
            if (isBlank(section.answer())) {
                if (!section.photos().isEmpty()) {
                    violations.add(new FieldViolation(path + ".answer", PHOTOS_NEED_TEXT_MESSAGE));
                }
                continue;
            }
            anyAnswered = true;
            if (isBlank(section.topicSlug())) {
                continue;
            }
            Optional<Topic> topic = topicRepository.findBySlug(section.topicSlug()).filter(Topic::isVisible);
            if (topic.isEmpty()) {
                violations.add(new FieldViolation(
                        path + ".topicSlug", "Unknown or inactive topic: " + section.topicSlug()));
                continue;
            }
            List<PhotoInput> photos = section.photos();
            int maxPhotosPerSection = photoStorageProperties.maxPhotosPerSection();
            if (photos.size() > maxPhotosPerSection) {
                violations.add(new FieldViolation(
                        path + ".photos", "At most " + PhotoUploadLimits.photos(maxPhotosPerSection) + " per topic."));
            }
            for (int p = 0; p < photos.size(); p++) {
                String fileRef = photos.get(p).fileRef();
                if (!isBlank(fileRef) && !isResolvableFileRef.test(topic.get(), fileRef)) {
                    violations.add(new FieldViolation(
                            path + ".photos[" + p + "].fileRef", unresolvedFileRefMessage + fileRef));
                }
            }
            filled.add(new FilledSection(section, topic.get()));
        }
        if (!anyAnswered) {
            violations.add(new FieldViolation("sections", TestimonialSubmissionRequest.AT_LEAST_ONE_SECTION_MESSAGE));
        }
        return filled;
    }

    private void checkTotalPhotoCount(List<FilledSection> filledSections, List<FieldViolation> violations) {
        checkTotalPhotoCount(
                filledSections.stream().mapToInt(filled -> filled.input().photos().size()).sum(), violations);
    }

    private void checkTotalPhotoCount(int totalPhotos, List<FieldViolation> violations) {
        int max = photoStorageProperties.maxPhotosPerTestimonial();
        if (totalPhotos > max) {
            violations.add(FieldViolation.global("Too many photos: maximum is " + max + "."));
        }
    }

    private static void throwIfAny(List<FieldViolation> violations) {
        if (!violations.isEmpty()) {
            throw new SubmissionValidationException(violations);
        }
    }

    /** Whether the section's topic is visible (decision 28) — the only sections the visitor sees and edits. */
    private static boolean isVisible(TestimonialSection section) {
        return section.getTopic().isVisible();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
