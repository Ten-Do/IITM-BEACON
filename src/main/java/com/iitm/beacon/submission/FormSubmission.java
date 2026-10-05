package com.iitm.beacon.submission;

import com.iitm.beacon.common.error.FieldViolation;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.ContactMethodInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.PhotoInput;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.multipart.MultipartFile;

/**
 * A {@link SubmissionFormCommand} from the plain multipart HTML form,
 * translated into the exact {@link TestimonialSubmissionRequest} + file-map
 * shape {@link SubmissionService#create}/{@link SubmissionService#edit}
 * expect (decision 21) — while remembering which form entry each request
 * entry came from, so the core's request-level violations can be mapped
 * back onto the form fields that caused them.
 *
 * <p>The request's lists are filtered: {@code sections} skip form entries
 * without a topic slug (the topic picker posts a sparse list), {@code
 * contactMethods} skip rows with no type or no value (the form shows one
 * row per contact type). Two rules only make sense for the form and are
 * checked here: tags posted for a new photo that has no file, and a contact
 * row marked public without a value.
 *
 * <p>Form field anchors: {@code sections[k].answerText}, {@code
 * sections[k].photos} (any photo-related violation of that section), {@code
 * sections[k].photoTags[n]}, {@code contactMethods[k].value}, {@code
 * contactMethods[k].publicContact}, and the top-level fields one to one
 * (including {@code sections} for "at least one section"). A violation on a
 * hidden input (a topic or contact type slug) or with no form counterpart
 * becomes global.
 */
final class FormSubmission {

    static final String TAGS_NEED_A_PHOTO_MESSAGE = "Tags need a photo — attach one or clear the tags.";
    static final String PUBLIC_NEEDS_A_VALUE_MESSAGE = "Enter a contact before making it public.";

    private static final Set<String> ONE_TO_ONE_FIELDS = Set.of(
            "firstName",
            "lastName",
            "rollNumber",
            "admissionYear",
            "countryCode",
            "recommendationScore",
            "dataProcessingConsent",
            "achievementSlugs",
            "sections");
    private static final Pattern INDEXED_FIELD = Pattern.compile("(sections|contactMethods)\\[(\\d+)](?:\\.(.+))?");

    private final TestimonialSubmissionRequest request;
    private final Map<String, MultipartFile> fileMap;
    private final List<Integer> sectionFormIndexes;
    private final List<Integer> contactFormIndexes;
    private final List<FieldViolation> formOnlyViolations;

    private FormSubmission(
            TestimonialSubmissionRequest request,
            Map<String, MultipartFile> fileMap,
            List<Integer> sectionFormIndexes,
            List<Integer> contactFormIndexes,
            List<FieldViolation> formOnlyViolations) {
        this.request = request;
        this.fileMap = fileMap;
        this.sectionFormIndexes = sectionFormIndexes;
        this.contactFormIndexes = contactFormIndexes;
        this.formOnlyViolations = formOnlyViolations;
    }

    static FormSubmission from(SubmissionFormCommand command) {
        Map<String, MultipartFile> fileMap = new LinkedHashMap<>();
        List<FieldViolation> formOnlyViolations = new ArrayList<>();

        List<SectionFormEntry> commandSections = nullSafeList(command.getSections());
        List<SectionInput> sections = new ArrayList<>();
        List<Integer> sectionFormIndexes = new ArrayList<>();
        for (int k = 0; k < commandSections.size(); k++) {
            SectionFormEntry entry = commandSections.get(k);
            if (entry == null || isBlank(entry.getTopicSlug())) {
                continue;
            }
            sections.add(toSectionInput(k, entry, fileMap, formOnlyViolations));
            sectionFormIndexes.add(k);
        }

        // One row per active contact type is rendered so the visitor never
        // has to "add" one — an unused row (no value, or no type at all) is
        // dropped rather than passed through as an invalid
        // ContactMethodInput. Marking an unused row public is a mistake,
        // though, not an unused row.
        List<ContactFormEntry> commandContacts = nullSafeList(command.getContactMethods());
        List<ContactMethodInput> contactMethods = new ArrayList<>();
        List<Integer> contactFormIndexes = new ArrayList<>();
        for (int k = 0; k < commandContacts.size(); k++) {
            ContactFormEntry row = commandContacts.get(k);
            if (row == null || isBlank(row.getTypeSlug())) {
                continue;
            }
            if (isBlank(row.getValue())) {
                if (row.isPublicContact()) {
                    formOnlyViolations.add(new FieldViolation(
                            "contactMethods[" + k + "].publicContact", PUBLIC_NEEDS_A_VALUE_MESSAGE));
                }
                continue;
            }
            contactMethods.add(new ContactMethodInput(row.getTypeSlug(), row.getValue(), row.isPublicContact()));
            contactFormIndexes.add(k);
        }

        TestimonialSubmissionRequest request = new TestimonialSubmissionRequest(
                command.getFirstName(),
                command.getLastName(),
                command.getRollNumber(),
                command.getAdmissionYear(),
                command.getCountryCode(),
                command.getRecommendationScore(),
                sections,
                nullSafeList(command.getAchievementSlugs()),
                contactMethods,
                command.isDataProcessingConsent());
        return new FormSubmission(request, fileMap, sectionFormIndexes, contactFormIndexes, formOnlyViolations);
    }

    TestimonialSubmissionRequest request() {
        return request;
    }

    Map<String, MultipartFile> fileMap() {
        return Map.copyOf(fileMap);
    }

    /**
     * The core's request-level violations translated to form field paths,
     * followed by the form-only ones — everything the visitor has to fix.
     */
    List<FieldViolation> toFormViolations(List<FieldViolation> requestViolations) {
        List<FieldViolation> result = new ArrayList<>();
        for (FieldViolation violation : requestViolations) {
            Optional<String> formField = violation.isGlobal() ? Optional.empty() : toFormField(violation.field());
            result.add(formField
                    .map(field -> new FieldViolation(field, violation.message()))
                    .orElseGet(() -> FieldViolation.global(violation.message())));
        }
        result.addAll(formOnlyViolations);
        return result;
    }

    private Optional<String> toFormField(String requestField) {
        if (ONE_TO_ONE_FIELDS.contains(requestField)) {
            return Optional.of(requestField);
        }
        Matcher m = INDEXED_FIELD.matcher(requestField);
        if (!m.matches()) {
            return Optional.empty();
        }
        String rest = m.group(3) == null ? "" : m.group(3);
        if ("sections".equals(m.group(1))) {
            return formIndex(sectionFormIndexes, m.group(2)).flatMap(k -> sectionField(k, rest));
        }
        return formIndex(contactFormIndexes, m.group(2)).flatMap(k -> contactField(k, rest));
    }

    private static Optional<String> sectionField(int k, String rest) {
        if ("answer".equals(rest)) {
            return Optional.of("sections[" + k + "].answerText");
        }
        if (rest.startsWith("photos")) {
            return Optional.of("sections[" + k + "].photos");
        }
        return Optional.empty();
    }

    private static Optional<String> contactField(int k, String rest) {
        if ("value".equals(rest)) {
            return Optional.of("contactMethods[" + k + "].value");
        }
        if ("isPublic".equals(rest)) {
            return Optional.of("contactMethods[" + k + "].publicContact");
        }
        return Optional.empty();
    }

    private static Optional<Integer> formIndex(List<Integer> formIndexes, String requestIndex) {
        try {
            int i = Integer.parseInt(requestIndex);
            return i < formIndexes.size() ? Optional.of(formIndexes.get(i)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Kept existing photos first (edit mode — their own url as {@code
     * fileRef}, decision 18), then new uploads in the order they were posted,
     * keyed {@code section-{k}-photo-{j}} and tagged with {@code photoTags[j]}
     * — {@code j} being the file's index in the posted list, so each file
     * keeps its own tags even when an empty or missing part is skipped. Tags
     * at an index without a file are reported.
     */
    private static SectionInput toSectionInput(
            int k,
            SectionFormEntry entry,
            Map<String, MultipartFile> fileMap,
            List<FieldViolation> formOnlyViolations) {
        List<PhotoInput> photos = new ArrayList<>();

        List<String> existingUrls = nullSafeList(entry.getExistingPhotoUrls());
        List<String> existingTags = nullSafeList(entry.getExistingPhotoTags());
        Set<String> removedUrls = new HashSet<>(nullSafeList(entry.getRemovedPhotoUrls()));
        for (int u = 0; u < existingUrls.size(); u++) {
            String url = existingUrls.get(u);
            if (isBlank(url) || removedUrls.contains(url)) {
                continue;
            }
            String rawTags = u < existingTags.size() ? existingTags.get(u) : null;
            photos.add(new PhotoInput(url, parseCommaSeparatedTags(rawTags)));
        }

        List<MultipartFile> newFiles = nullSafeList(entry.getPhotos());
        List<String> newTags = nullSafeList(entry.getPhotoTags());
        for (int j = 0; j < Math.max(newFiles.size(), newTags.size()); j++) {
            MultipartFile file = j < newFiles.size() ? newFiles.get(j) : null;
            List<String> tags = parseCommaSeparatedTags(j < newTags.size() ? newTags.get(j) : null);
            if (file == null || file.isEmpty()) {
                if (!tags.isEmpty()) {
                    formOnlyViolations.add(new FieldViolation(
                            "sections[" + k + "].photoTags[" + j + "]", TAGS_NEED_A_PHOTO_MESSAGE));
                }
                continue;
            }
            String fileRef = "section-" + k + "-photo-" + j;
            fileMap.put(fileRef, file);
            photos.add(new PhotoInput(fileRef, tags));
        }

        return new SectionInput(entry.getTopicSlug(), entry.getAnswerText(), photos);
    }

    private static <T> List<T> nullSafeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static List<String> parseCommaSeparatedTags(String rawCommaSeparated) {
        if (isBlank(rawCommaSeparated)) {
            return List.of();
        }
        return Arrays.stream(rawCommaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
