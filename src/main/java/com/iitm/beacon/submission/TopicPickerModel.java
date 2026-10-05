package com.iitm.beacon.submission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * View model for the submission form's topic picker (UC-CREATE-TESTIMONIAL,
 * UC-EDIT-TESTIMONIAL): one {@link Pick} — a chip plus the fieldset it
 * reveals — per top-level topic-catalog entry, i.e. a whole topic group or
 * a single standalone topic.
 *
 * <p>Every topic {@link Field} keeps one fixed, catalog-wide {@code index}
 * into {@code command.sections}, so {@link SectionFormEntry} and {@link
 * SubmissionService}'s form adapter work unchanged. Unpicked fieldsets are
 * disabled in the browser and therefore not submitted, which makes a posted
 * {@code sections} list sparse; {@link #alignSections} restores one entry
 * per topic before the form is rendered again.
 */
final class TopicPickerModel {

    /** The standalone topic that is always picked when the form opens (it can still be unpicked). */
    static final String DEFAULT_TOPIC_SLUG = "general";

    private static final String GROUP_KIND = "GROUP";
    private static final Pattern SECTION_FIELD = Pattern.compile("sections\\[(\\d{1,9})](.*)");

    private TopicPickerModel() {
    }

    /**
     * One chip + fieldset. {@code key} is {@code group-{groupId}} or {@code
     * topic-{topicId}} — stable across requests and unique per catalog.
     */
    record Pick(String key, String label, boolean defaultPick, List<Field> fields) {

        Pick {
            fields = List.copyOf(fields);
        }
    }

    /** One topic's answer/photo block; {@code index} is its position in {@code command.sections}. */
    record Field(int index, String slug, String label, String guidingPrompt) {
    }

    static List<Pick> fromCatalog(List<TopicCatalogEntryDto> catalog) {
        List<Pick> picks = new ArrayList<>();
        int nextIndex = 0;
        for (TopicCatalogEntryDto entry : catalog) {
            List<Field> fields = new ArrayList<>();
            String key;
            if (GROUP_KIND.equals(entry.kind())) {
                key = "group-" + entry.groupId();
                for (TopicPickDto subtopic : entry.subtopics()) {
                    fields.add(new Field(nextIndex++, subtopic.slug(), subtopic.label(), subtopic.guidingPrompt()));
                }
            } else {
                key = "topic-" + entry.topicId();
                fields.add(new Field(nextIndex++, entry.slug(), entry.label(), entry.guidingPrompt()));
            }
            boolean defaultPick = fields.stream().anyMatch(f -> DEFAULT_TOPIC_SLUG.equals(f.slug()));
            picks.add(new Pick(key, entry.label(), defaultPick, fields));
        }
        return picks;
    }

    static int totalFields(List<Pick> picks) {
        return picks.stream().mapToInt(pick -> pick.fields().size()).sum();
    }

    /**
     * One entry per topic, in {@link Field#index} order, each carrying its
     * catalog slug. Posted entries are matched by slug (not by position), so
     * an answer can never end up under another topic; entries with an
     * unknown slug are dropped, and for a slug posted twice the first wins.
     */
    static List<SectionFormEntry> alignSections(List<Pick> picks, List<SectionFormEntry> posted) {
        Map<String, SectionFormEntry> postedBySlug = new HashMap<>();
        if (posted != null) {
            for (SectionFormEntry entry : posted) {
                if (entry != null && entry.getTopicSlug() != null) {
                    postedBySlug.putIfAbsent(entry.getTopicSlug(), entry);
                }
            }
        }
        List<SectionFormEntry> aligned = new ArrayList<>();
        for (Pick pick : picks) {
            for (Field field : pick.fields()) {
                SectionFormEntry entry = postedBySlug.get(field.slug());
                if (entry == null) {
                    entry = new SectionFormEntry();
                    entry.setTopicSlug(field.slug());
                }
                aligned.add(entry);
            }
        }
        return aligned;
    }

    /**
     * Keys of the picks the form opens with: the default ({@code general})
     * pick, plus every pick with at least one topic that already has a
     * non-blank answer, saved photos, or — on a rejected submission — a new
     * upload or typed upload tags (so an error about them is visible). {@code sections} is index-aligned with the
     * fields (see {@link #alignSections}); a shorter list is fine.
     */
    static List<String> initiallyPicked(List<Pick> picks, List<SectionFormEntry> sections) {
        List<String> picked = new ArrayList<>();
        for (Pick pick : picks) {
            boolean hasContent = pick.fields().stream()
                    .map(field -> field.index() < sections.size() ? sections.get(field.index()) : null)
                    .filter(Objects::nonNull)
                    .anyMatch(TopicPickerModel::hasContent);
            if (pick.defaultPick() || hasContent) {
                picked.add(pick.key());
            }
        }
        return picked;
    }

    /**
     * A form field path re-pointed from the posted {@code sections} list onto
     * the one {@link #alignSections} built from it: {@code sections[k]...}
     * follows its entry to wherever it now sits (entries are moved, not
     * copied), so an error on an answer posted under a stale index still
     * shows next to its own topic. Empty when alignment dropped that entry
     * (unknown or repeated slug) — there is no field left to show it next
     * to. Any other path is returned unchanged.
     */
    static Optional<String> realignSectionField(
            String field, List<SectionFormEntry> posted, List<SectionFormEntry> aligned) {
        Matcher m = SECTION_FIELD.matcher(field);
        if (!m.matches()) {
            return Optional.of(field);
        }
        int postedIndex = Integer.parseInt(m.group(1));
        if (posted == null || postedIndex >= posted.size() || posted.get(postedIndex) == null) {
            return Optional.empty();
        }
        Map<SectionFormEntry, Integer> alignedIndexes = new IdentityHashMap<>();
        for (int i = 0; i < aligned.size(); i++) {
            alignedIndexes.put(aligned.get(i), i);
        }
        return Optional.ofNullable(alignedIndexes.get(posted.get(postedIndex)))
                .map(alignedIndex -> "sections[" + alignedIndex + "]" + m.group(2));
    }

    private static boolean hasContent(SectionFormEntry entry) {
        boolean hasAnswer = entry.getAnswerText() != null && !entry.getAnswerText().isBlank();
        boolean hasPhotos = entry.getExistingPhotoUrls() != null && !entry.getExistingPhotoUrls().isEmpty();
        boolean hasNewUpload = entry.getPhotos() != null
                && entry.getPhotos().stream().anyMatch(file -> file != null && !file.isEmpty());
        boolean hasUploadTags = entry.getPhotoTags() != null
                && entry.getPhotoTags().stream().anyMatch(tags -> tags != null && !tags.isBlank());
        return hasAnswer || hasPhotos || hasNewUpload || hasUploadTags;
    }
}
