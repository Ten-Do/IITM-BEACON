package com.iitm.beacon.gallery;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class GalleryServiceCountriesAndTopicsTest {

    @Autowired
    private GalleryService galleryService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private Testimonial.TestimonialBuilder testimonial(String email, Country country) {
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(country)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private Topic topic(String slug) {
        return topicRepository.findBySlug(slug).orElseThrow();
    }

    private TestimonialSection section(Testimonial t, Topic topic, String answer) {
        return TestimonialSection.builder()
                .testimonial(t)
                .topic(topic)
                .answerText(answer)
                .modified(false)
                .build();
    }

    // ---- countries ----

    @Test
    void listCountries_onlyCountriesWithApprovedTestimonials_areReturned() {
        Country india = countryRepository.findById("IN").orElseThrow();
        Country unitedStates = countryRepository.findById("US").orElseThrow();
        Country germany = countryRepository.findById("DE").orElseThrow();

        Testimonial approvedIn = testimonial("countries-approved-in@example.com", india)
                .status(TestimonialStatus.APPROVED)
                .build();
        approvedIn.getSections().add(section(approvedIn, topic("general"), "Text."));
        testimonialRepository.saveAndFlush(approvedIn);

        Testimonial pendingUs = testimonial("countries-pending-us@example.com", unitedStates)
                .status(TestimonialStatus.PENDING)
                .build();
        pendingUs.getSections().add(section(pendingUs, topic("general"), "Text."));
        testimonialRepository.saveAndFlush(pendingUs);

        Testimonial rejectedDe = testimonial("countries-rejected-de@example.com", germany)
                .status(TestimonialStatus.REJECTED)
                .build();
        rejectedDe.getSections().add(section(rejectedDe, topic("general"), "Text."));
        testimonialRepository.saveAndFlush(rejectedDe);

        List<CountryDto> countries = galleryService.listCountriesWithApproved();

        assertThat(countries).extracting(CountryDto::code).containsExactly("IN");
    }

    @Test
    void listCountries_noApprovedTestimonialsAtAll_returnsEmptyList() {
        Country india = countryRepository.findById("IN").orElseThrow();
        Testimonial pending = testimonial("countries-only-pending@example.com", india)
                .status(TestimonialStatus.PENDING)
                .build();
        pending.getSections().add(section(pending, topic("general"), "Text."));
        testimonialRepository.saveAndFlush(pending);

        List<CountryDto> countries = galleryService.listCountriesWithApproved();

        assertThat(countries).isEmpty();
    }

    // ---- topics ----

    private Country india() {
        return countryRepository.findById("IN").orElseThrow();
    }

    private Testimonial approvedWithSection(String email, Topic topicEntity) {
        Testimonial t = testimonial(email, india()).status(TestimonialStatus.APPROVED).build();
        t.getSections().add(section(t, topicEntity, "Some answer text."));
        return testimonialRepository.saveAndFlush(t);
    }

    @Test
    void listTopics_groupWithOneQualifyingSubtopic_includesGroupWithOnlyThatSubtopic() {
        approvedWithSection("topics-group-partial@example.com", topic("academics_teaching"));

        List<TopicCatalogEntryDto> catalog = galleryService.listTopicCatalogWithApproved();

        TopicCatalogEntryDto academicsGroup = catalog.stream()
                .filter(e -> "GROUP".equals(e.kind()) && "Academics".equals(e.label()))
                .findFirst()
                .orElseThrow();
        assertThat(academicsGroup.subtopics()).extracting(TopicPickDto::slug).containsExactly("academics_teaching");
    }

    @Test
    void listTopics_groupWithNoQualifyingSubtopics_isExcludedEntirely() {
        // No testimonial at all references any "Housing & Food" subtopic.
        approvedWithSection("topics-other-group@example.com", topic("academics_teaching"));

        List<TopicCatalogEntryDto> catalog = galleryService.listTopicCatalogWithApproved();

        assertThat(catalog).noneMatch(e -> "Housing & Food".equals(e.label()));
    }

    @Test
    void listTopics_standaloneTopicQualifying_isIncludedAsStandalone() {
        approvedWithSection("topics-standalone-qualify@example.com", topic("general"));

        List<TopicCatalogEntryDto> catalog = galleryService.listTopicCatalogWithApproved();

        TopicCatalogEntryDto general = catalog.stream()
                .filter(e -> "STANDALONE".equals(e.kind()) && "general".equals(e.slug()))
                .findFirst()
                .orElseThrow();
        assertThat(general.topicId()).isEqualTo(topic("general").getId());
        assertThat(general.groupId()).isNull();
        assertThat(general.subtopics()).isNull();
    }

    @Test
    void listTopics_standaloneTopicNotQualifying_isExcluded() {
        approvedWithSection("topics-standalone-other@example.com", topic("academics_teaching"));

        List<TopicCatalogEntryDto> catalog = galleryService.listTopicCatalogWithApproved();

        assertThat(catalog).noneMatch(e -> "general".equals(e.slug()));
    }

    @Test
    void listTopics_inactiveTopicExcludedEvenIfItQualifies() {
        Testimonial saved = approvedWithSection("topics-inactive-topic@example.com", topic("general"));
        assertThat(saved.getId()).isNotNull();

        Topic general = topic("general");
        general.setActive(false);
        topicRepository.saveAndFlush(general);

        List<TopicCatalogEntryDto> catalog = galleryService.listTopicCatalogWithApproved();

        assertThat(catalog).noneMatch(e -> "general".equals(e.slug()));
    }

    @Test
    void listTopics_inactiveGroupExcludedEvenIfMemberTopicQualifies() {
        approvedWithSection("topics-inactive-group@example.com", topic("academics_teaching"));

        TopicGroup academicsGroup = topic("academics_teaching").getTopicGroup();
        academicsGroup.setActive(false);
        topicGroupRepository.saveAndFlush(academicsGroup);

        List<TopicCatalogEntryDto> catalog = galleryService.listTopicCatalogWithApproved();

        assertThat(catalog).noneMatch(e -> "Academics".equals(e.label()));
    }

    @Test
    void listTopics_noApprovedSectionsAtAll_returnsEmptyList() {
        List<TopicCatalogEntryDto> catalog = galleryService.listTopicCatalogWithApproved();

        assertThat(catalog).isEmpty();
    }

    @Test
    void listTopics_pendingOrRejectedSectionsDoNotQualify() {
        Testimonial pending = testimonial("topics-pending@example.com", india())
                .status(TestimonialStatus.PENDING)
                .build();
        pending.getSections().add(section(pending, topic("general"), "Text."));
        testimonialRepository.saveAndFlush(pending);

        Testimonial rejected = testimonial("topics-rejected@example.com", india())
                .status(TestimonialStatus.REJECTED)
                .build();
        rejected.getSections().add(section(rejected, topic("academics_teaching"), "Text."));
        testimonialRepository.saveAndFlush(rejected);

        List<TopicCatalogEntryDto> catalog = galleryService.listTopicCatalogWithApproved();

        assertThat(catalog).isEmpty();
    }

    @Test
    void listTopics_combinedGroupAndStandaloneEntries_areOrderedByTopLevelDisplayOrder() {
        // Academics (group, display_order 1), new_friendships (standalone,
        // display_order 7), general (standalone, display_order 17).
        approvedWithSection("topics-order-1@example.com", topic("academics_teaching"));
        approvedWithSection("topics-order-2@example.com", topic("new_friendships"));
        approvedWithSection("topics-order-3@example.com", topic("general"));

        List<TopicCatalogEntryDto> catalog = galleryService.listTopicCatalogWithApproved();

        assertThat(catalog).extracting(TopicCatalogEntryDto::label)
                .containsExactly("Academics", "New Friendships", "General");
    }
}
