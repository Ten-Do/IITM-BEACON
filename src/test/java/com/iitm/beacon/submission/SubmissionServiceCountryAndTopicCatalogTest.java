package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubmissionService#listAllCountries()} and {@link
 * SubmissionService#listTopicCatalog()} — the submission form's own
 * reference-data listings, deliberately unfiltered by approved-testimonial
 * existence (unlike {@code gallery.GalleryService}'s equivalents), and built
 * from independent, submission-local DTOs (decision 9: no import from {@code
 * gallery}).
 */
@SpringBootTest
@Transactional
class SubmissionServiceCountryAndTopicCatalogTest {

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Test
    void listAllCountries_returnsEveryCountryRegardlessOfTestimonialExistence() {
        List<CountryDto> result = submissionService.listAllCountries();

        assertThat(result).hasSize((int) countryRepository.count());
        assertThat(result).extracting(CountryDto::code).contains("IN", "US", "AD");
    }

    @Test
    void listAllCountries_sortedByName() {
        List<CountryDto> result = submissionService.listAllCountries();

        List<String> names = result.stream().map(CountryDto::name).toList();
        assertThat(names).isSorted();
    }

    @Test
    void listTopicCatalog_nonEmptyWithoutAnyTestimonialInTheDatabase() {
        // No testimonial exists at all in this fresh transactional test —
        // gallery's approved-filtered equivalent would return an empty list
        // here; submission's must not.
        List<TopicCatalogEntryDto> result = submissionService.listTopicCatalog();

        assertThat(result).isNotEmpty();
    }

    @Test
    void listTopicCatalog_includesStandaloneGeneralTopic() {
        List<TopicCatalogEntryDto> result = submissionService.listTopicCatalog();

        assertThat(result)
                .filteredOn(e -> "general".equals(e.slug()))
                .hasSize(1)
                .allSatisfy(e -> assertThat(e.kind()).isEqualTo("STANDALONE"));
    }

    @Test
    void listTopicCatalog_includesGroupWithItsActiveSubtopics() {
        List<TopicCatalogEntryDto> result = submissionService.listTopicCatalog();

        TopicCatalogEntryDto academics = result.stream()
                .filter(e -> "Academics".equals(e.label()))
                .findFirst()
                .orElseThrow();
        assertThat(academics.kind()).isEqualTo("GROUP");
        assertThat(academics.subtopics()).isNotEmpty();
        assertThat(academics.subtopics()).extracting(TopicPickDto::slug).contains("academics_teaching");
    }

    @Test
    void listTopicCatalog_excludesInactiveStandaloneTopic() {
        Topic general = topicRepository.findBySlug("general").orElseThrow();
        general.setActive(false);
        topicRepository.saveAndFlush(general);

        List<TopicCatalogEntryDto> result = submissionService.listTopicCatalog();

        assertThat(result).noneMatch(e -> "general".equals(e.slug()));
    }

    @Test
    void listTopicCatalog_excludesInactiveGroupEntirely() {
        TopicGroup academics = topicGroupRepository.findById(1L).orElseThrow();
        academics.setActive(false);
        topicGroupRepository.saveAndFlush(academics);

        List<TopicCatalogEntryDto> result = submissionService.listTopicCatalog();

        assertThat(result).noneMatch(e -> "Academics".equals(e.label()));
    }

    @Test
    void listTopicCatalog_activeGroupWithAllSubtopicsInactive_isDroppedEntirely() {
        // An active group left with zero active subtopics would render as a
        // dead-end heading with nothing to fill in, so it is dropped exactly
        // like an inactive group — same reasoning as gallery's own
        // "qualifying" filter, substituting "active" for "has an approved
        // testimonial".
        List<Topic> academicsTopics = topicRepository.findByTopicGroupId(1L);
        academicsTopics.forEach(t -> t.setActive(false));
        topicRepository.saveAllAndFlush(academicsTopics);

        List<TopicCatalogEntryDto> result = submissionService.listTopicCatalog();

        assertThat(result).noneMatch(e -> "Academics".equals(e.label()));
    }

    @Test
    void listTopicCatalog_orderedByTopLevelDisplayOrder() {
        List<TopicCatalogEntryDto> result = submissionService.listTopicCatalog();

        assertThat(result.get(0).label()).isEqualTo("Academics");
        assertThat(result.get(result.size() - 1).slug()).isEqualTo("general");
    }
}
