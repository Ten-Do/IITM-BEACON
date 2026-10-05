package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.submission.TopicPickerModel.Field;
import com.iitm.beacon.submission.TopicPickerModel.Pick;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * The submission form's topic picker view model: one pick (chip + fieldset)
 * per top-level catalog entry, each topic field carrying its fixed position
 * in {@code command.sections}.
 */
class TopicPickerModelTest {

    private static TopicCatalogEntryDto group(long groupId, String label, TopicPickDto... subtopics) {
        return new TopicCatalogEntryDto("GROUP", label, groupId, List.of(subtopics), null, null, null);
    }

    private static TopicCatalogEntryDto standalone(long topicId, String slug, String label) {
        return new TopicCatalogEntryDto("STANDALONE", label, null, null, topicId, slug, label + "?");
    }

    private static TopicPickDto sub(long id, String slug) {
        return new TopicPickDto(id, slug, slug + " label", slug + " prompt");
    }

    private static final List<TopicCatalogEntryDto> CATALOG = List.of(
            group(1, "Academics", sub(10, "academics_teaching"), sub(11, "academics_standout")),
            standalone(40, "networking", "Professional Networking"),
            group(2, "Travel", sub(20, "travel_did"), sub(21, "travel_recommend"), sub(22, "travel_ease")),
            standalone(46, "general", "General"));

    private static SectionFormEntry entry(String slug, String answer) {
        SectionFormEntry e = new SectionFormEntry();
        e.setTopicSlug(slug);
        e.setAnswerText(answer);
        return e;
    }

    // -- fromCatalog --

    @Test
    void fromCatalog_onePickPerTopLevelEntry_keyedByGroupOrTopicId() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);

        assertThat(picks).extracting(Pick::key).containsExactly("group-1", "topic-40", "group-2", "topic-46");
        assertThat(picks).extracting(Pick::label)
                .containsExactly("Academics", "Professional Networking", "Travel", "General");
    }

    @Test
    void fromCatalog_groupPickHoldsEverySubtopicInOrder() {
        Pick travel = TopicPickerModel.fromCatalog(CATALOG).get(2);

        assertThat(travel.fields()).extracting(Field::slug)
                .containsExactly("travel_did", "travel_recommend", "travel_ease");
        assertThat(travel.fields().get(0).guidingPrompt()).isEqualTo("travel_did prompt");
    }

    @Test
    void fromCatalog_standalonePickHoldsExactlyItsOwnTopic() {
        Pick networking = TopicPickerModel.fromCatalog(CATALOG).get(1);

        assertThat(networking.fields()).singleElement().satisfies(field -> {
            assertThat(field.slug()).isEqualTo("networking");
            assertThat(field.label()).isEqualTo("Professional Networking");
            assertThat(field.guidingPrompt()).isEqualTo("Professional Networking?");
        });
    }

    @Test
    void fromCatalog_sectionIndexRunsContinuouslyAcrossAllPicks() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);

        assertThat(picks.stream().flatMap(p -> p.fields().stream()).map(Field::index).toList())
                .containsExactly(0, 1, 2, 3, 4, 5, 6);
    }

    @Test
    void fromCatalog_onlyThePickHoldingTheGeneralTopicIsTheDefault() {
        assertThat(TopicPickerModel.fromCatalog(CATALOG)).extracting(Pick::defaultPick)
                .containsExactly(false, false, false, true);
    }

    @Test
    void fromCatalog_withoutAGeneralTopic_hasNoDefaultPick() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG.subList(0, 3));

        assertThat(picks).noneMatch(Pick::defaultPick);
    }

    @Test
    void fromCatalog_emptyCatalog_hasNoPicks() {
        assertThat(TopicPickerModel.fromCatalog(List.of())).isEmpty();
    }

    @Test
    void totalFields_countsEveryTopicOfEveryPick() {
        assertThat(TopicPickerModel.totalFields(TopicPickerModel.fromCatalog(CATALOG))).isEqualTo(7);
        assertThat(TopicPickerModel.totalFields(List.of())).isZero();
    }

    // -- alignSections --

    @Test
    void alignSections_nothingPosted_givesOneEmptyEntryPerTopicWithItsCatalogSlug() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);

        List<SectionFormEntry> aligned = TopicPickerModel.alignSections(picks, List.of());

        assertThat(aligned).extracting(SectionFormEntry::getTopicSlug).containsExactly(
                "academics_teaching", "academics_standout", "networking",
                "travel_did", "travel_recommend", "travel_ease", "general");
        assertThat(aligned).allSatisfy(e -> assertThat(e.getAnswerText()).isNull());
    }

    @Test
    void alignSections_nullSectionList_isTreatedAsNothingPosted() {
        assertThat(TopicPickerModel.alignSections(TopicPickerModel.fromCatalog(CATALOG), null)).hasSize(7);
    }

    @Test
    void alignSections_sparsePostWithGapsAndNulls_keepsEachAnswerWithItsTopic() {
        // What binding produces when only sections[4] was submitted (the
        // other fieldsets were disabled): auto-grown blanks, then the entry.
        List<SectionFormEntry> posted = new ArrayList<>(Arrays.asList(
                new SectionFormEntry(), null, new SectionFormEntry(), new SectionFormEntry(),
                entry("travel_recommend", "Pondicherry.")));

        List<SectionFormEntry> aligned =
                TopicPickerModel.alignSections(TopicPickerModel.fromCatalog(CATALOG), posted);

        assertThat(aligned).hasSize(7);
        assertThat(aligned.get(4).getTopicSlug()).isEqualTo("travel_recommend");
        assertThat(aligned.get(4).getAnswerText()).isEqualTo("Pondicherry.");
        assertThat(aligned.get(6).getTopicSlug()).isEqualTo("general");
    }

    @Test
    void alignSections_entryPostedUnderAnotherIndex_isMatchedBySlugNotPosition() {
        List<SectionFormEntry> posted = List.of(entry("networking", "Met great people."));

        List<SectionFormEntry> aligned =
                TopicPickerModel.alignSections(TopicPickerModel.fromCatalog(CATALOG), posted);

        assertThat(aligned.get(0).getTopicSlug()).isEqualTo("academics_teaching");
        assertThat(aligned.get(0).getAnswerText()).isNull();
        assertThat(aligned.get(2).getAnswerText()).isEqualTo("Met great people.");
    }

    @Test
    void alignSections_unknownSlugPosted_isDropped() {
        List<SectionFormEntry> posted = List.of(entry("no_such_topic", "Lost."));

        List<SectionFormEntry> aligned =
                TopicPickerModel.alignSections(TopicPickerModel.fromCatalog(CATALOG), posted);

        assertThat(aligned).extracting(SectionFormEntry::getAnswerText).containsOnlyNulls();
    }

    @Test
    void alignSections_sameSlugPostedTwice_firstOneWins() {
        List<SectionFormEntry> posted = List.of(entry("general", "First."), entry("general", "Second."));

        List<SectionFormEntry> aligned =
                TopicPickerModel.alignSections(TopicPickerModel.fromCatalog(CATALOG), posted);

        assertThat(aligned.get(6).getAnswerText()).isEqualTo("First.");
    }

    // -- initiallyPicked --

    @Test
    void initiallyPicked_nothingFilledIn_isJustTheGeneralPick() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);

        assertThat(TopicPickerModel.initiallyPicked(picks, TopicPickerModel.alignSections(picks, List.of())))
                .containsExactly("topic-46");
    }

    @Test
    void initiallyPicked_withoutAGeneralTopicAndNothingFilledIn_isEmpty() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG.subList(0, 3));

        assertThat(TopicPickerModel.initiallyPicked(picks, TopicPickerModel.alignSections(picks, List.of())))
                .isEmpty();
    }

    @Test
    void initiallyPicked_addsEveryPickWithAnAnswer_onceEach_inPickOrder() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);
        List<SectionFormEntry> aligned = TopicPickerModel.alignSections(picks, List.of(
                entry("travel_ease", "Cheap trains."),
                entry("travel_did", "Everywhere."),
                entry("academics_standout", "ML course.")));

        assertThat(TopicPickerModel.initiallyPicked(picks, aligned))
                .containsExactly("group-1", "group-2", "topic-46");
    }

    @Test
    void initiallyPicked_whitespaceOnlyAnswer_doesNotCount() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);
        List<SectionFormEntry> aligned =
                TopicPickerModel.alignSections(picks, List.of(entry("networking", "  \n\t ")));

        assertThat(TopicPickerModel.initiallyPicked(picks, aligned)).containsExactly("topic-46");
    }

    @Test
    void initiallyPicked_existingPhotosWithoutAnswer_stillCount() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);
        SectionFormEntry photosOnly = entry("networking", "");
        photosOnly.setExistingPhotoUrls(new ArrayList<>(List.of("/uploads/2026/01/a.png")));

        List<String> picked =
                TopicPickerModel.initiallyPicked(picks, TopicPickerModel.alignSections(picks, List.of(photosOnly)));

        assertThat(picked).containsExactly("topic-40", "topic-46");
    }

    @Test
    void initiallyPicked_sectionListShorterThanTheCatalog_doesNotFail() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);

        assertThat(TopicPickerModel.initiallyPicked(picks, List.of(entry("academics_teaching", "Good."))))
                .containsExactly("group-1", "topic-46");
    }

    @Test
    void initiallyPicked_newUploadWithoutAnswer_stillCounts() {
        // Its "photos need some text" error must be visible after a re-render.
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);
        SectionFormEntry uploadOnly = entry("networking", "");
        uploadOnly.setPhotos(new ArrayList<>(List.of(
                new MockMultipartFile("photo", "a.png", "image/png", new byte[] {1}))));

        List<String> picked =
                TopicPickerModel.initiallyPicked(picks, TopicPickerModel.alignSections(picks, List.of(uploadOnly)));

        assertThat(picked).containsExactly("topic-40", "topic-46");
    }

    @Test
    void initiallyPicked_uploadTagsTypedWithoutAnswer_stillCount() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);
        SectionFormEntry tagsOnly = entry("travel_did", null);
        tagsOnly.setPhotoTags(new ArrayList<>(Arrays.asList(null, "", "beach")));

        List<String> picked =
                TopicPickerModel.initiallyPicked(picks, TopicPickerModel.alignSections(picks, List.of(tagsOnly)));

        assertThat(picked).containsExactly("group-2", "topic-46");
    }

    @Test
    void initiallyPicked_emptyFileInputsAndBlankUploadTags_doNotCount() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);
        SectionFormEntry nothingReally = entry("networking", " ");
        List<MultipartFile> emptyInputs = new ArrayList<>(Arrays.asList(
                null, new MockMultipartFile("photo", "", "application/octet-stream", new byte[0])));
        nothingReally.setPhotos(emptyInputs);
        nothingReally.setPhotoTags(new ArrayList<>(List.of("", "  ")));

        List<String> picked =
                TopicPickerModel.initiallyPicked(picks, TopicPickerModel.alignSections(picks, List.of(nothingReally)));

        assertThat(picked).containsExactly("topic-46");
    }

    // -- realignSectionField --

    @Test
    void realignSectionField_followsAnEntryPostedUnderAStaleIndexToItsTopic() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);
        List<SectionFormEntry> posted = List.of(entry("networking", ""), entry("general", "Text."));
        List<SectionFormEntry> aligned = TopicPickerModel.alignSections(picks, posted);

        // networking is catalog index 2, general index 6.
        assertThat(TopicPickerModel.realignSectionField("sections[0].answerText", posted, aligned))
                .contains("sections[2].answerText");
        assertThat(TopicPickerModel.realignSectionField("sections[1].photoTags[2]", posted, aligned))
                .contains("sections[6].photoTags[2]");
    }

    @Test
    void realignSectionField_indexAlreadyMatchingTheCatalog_isUnchanged() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);
        List<SectionFormEntry> posted = TopicPickerModel.alignSections(picks, List.of(entry("travel_ease", "x")));
        List<SectionFormEntry> aligned = TopicPickerModel.alignSections(picks, posted);

        assertThat(TopicPickerModel.realignSectionField("sections[5].photos", posted, aligned))
                .contains("sections[5].photos");
    }

    @Test
    void realignSectionField_entryDroppedByAlignment_hasNoFieldToPointAt() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);
        List<SectionFormEntry> posted = List.of(
                entry("no_such_topic", ""), entry("general", "First."), entry("general", "Duplicate."));
        List<SectionFormEntry> aligned = TopicPickerModel.alignSections(picks, posted);

        assertThat(TopicPickerModel.realignSectionField("sections[0].answerText", posted, aligned)).isEmpty();
        assertThat(TopicPickerModel.realignSectionField("sections[2].answerText", posted, aligned)).isEmpty();
        assertThat(TopicPickerModel.realignSectionField("sections[3].answerText", posted, aligned)).isEmpty();
    }

    @Test
    void realignSectionField_nonSectionFields_areUnchanged() {
        List<Pick> picks = TopicPickerModel.fromCatalog(CATALOG);
        List<SectionFormEntry> aligned = TopicPickerModel.alignSections(picks, List.of());

        assertThat(TopicPickerModel.realignSectionField("rollNumber", List.of(), aligned)).contains("rollNumber");
        assertThat(TopicPickerModel.realignSectionField("sections", List.of(), aligned)).contains("sections");
        assertThat(TopicPickerModel.realignSectionField("contactMethods[3].value", List.of(), aligned))
                .isEqualTo(Optional.of("contactMethods[3].value"));
    }
}
