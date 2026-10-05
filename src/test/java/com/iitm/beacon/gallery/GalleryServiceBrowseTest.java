package com.iitm.beacon.gallery;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class GalleryServiceBrowseTest {

    @Autowired
    private GalleryService galleryService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private Country india;
    private Country unitedStates;

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

    private TestimonialSection section(Testimonial t, Topic topic, String answer) {
        return TestimonialSection.builder()
                .testimonial(t)
                .topic(topic)
                .answerText(answer)
                .modified(false)
                .build();
    }

    private Country india() {
        if (india == null) {
            india = countryRepository.findById("IN").orElseThrow();
        }
        return india;
    }

    private Country unitedStates() {
        if (unitedStates == null) {
            unitedStates = countryRepository.findById("US").orElseThrow();
        }
        return unitedStates;
    }

    private Topic topic(String slug) {
        return topicRepository.findBySlug(slug).orElseThrow();
    }

    @Test
    void browse_noFilters_returnsOnlyApprovedTestimonials() {
        Testimonial approved = testimonial("browse-approved@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        approved.getSections().add(section(approved, topic("general"), "Great time overall."));
        testimonialRepository.saveAndFlush(approved);

        Testimonial pending = testimonial("browse-pending@example.com", india())
                .status(TestimonialStatus.PENDING)
                .build();
        pending.getSections().add(section(pending, topic("general"), "Pending text."));
        testimonialRepository.saveAndFlush(pending);

        Testimonial rejected = testimonial("browse-rejected@example.com", india())
                .status(TestimonialStatus.REJECTED)
                .build();
        rejected.getSections().add(section(rejected, topic("general"), "Rejected text."));
        testimonialRepository.saveAndFlush(rejected);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, null, null, PageRequest.of(0, 20));

        assertThat(result.content()).extracting(TestimonialCardDto::id).containsExactly(approved.getId());
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void browse_countryFilter_returnsOnlyMatchingCountry() {
        Testimonial inTestimonial = testimonial("browse-country-in@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        inTestimonial.getSections().add(section(inTestimonial, topic("general"), "India text."));
        testimonialRepository.saveAndFlush(inTestimonial);

        Testimonial usTestimonial = testimonial("browse-country-us@example.com", unitedStates())
                .status(TestimonialStatus.APPROVED)
                .build();
        usTestimonial.getSections().add(section(usTestimonial, topic("general"), "US text."));
        testimonialRepository.saveAndFlush(usTestimonial);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse("IN", null, null, PageRequest.of(0, 20));

        assertThat(result.content()).extracting(TestimonialCardDto::id).containsExactly(inTestimonial.getId());
    }

    @Test
    void browse_countryFilter_isCaseInsensitive() {
        Testimonial inTestimonial = testimonial("browse-country-lower@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        inTestimonial.getSections().add(section(inTestimonial, topic("general"), "India text."));
        testimonialRepository.saveAndFlush(inTestimonial);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse("in", null, null, PageRequest.of(0, 20));

        assertThat(result.content()).extracting(TestimonialCardDto::id).containsExactly(inTestimonial.getId());
    }

    @Test
    void browse_blankCountryAndQ_areTreatedAsNoFilter() {
        Testimonial approved = testimonial("browse-blank-filters@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        approved.getSections().add(section(approved, topic("general"), "Some text."));
        testimonialRepository.saveAndFlush(approved);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse("  ", List.of(), "   ", PageRequest.of(0, 20));

        assertThat(result.content()).extracting(TestimonialCardDto::id).containsExactly(approved.getId());
    }

    @Test
    void browse_topicFilter_matchesTestimonialsWithThatTopicSection() {
        Testimonial withTopic = testimonial("browse-topic-match@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        withTopic.getSections().add(section(withTopic, topic("academics_teaching"), "Teaching was great."));
        testimonialRepository.saveAndFlush(withTopic);

        Testimonial withoutTopic = testimonial("browse-topic-nomatch@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        withoutTopic.getSections().add(section(withoutTopic, topic("general"), "Something else."));
        testimonialRepository.saveAndFlush(withoutTopic);

        Long academicsTeachingId = topic("academics_teaching").getId();
        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, List.of(academicsTeachingId), null, PageRequest.of(0, 20));

        assertThat(result.content()).extracting(TestimonialCardDto::id).containsExactly(withTopic.getId());
    }

    @Test
    void browse_topicFilterWithGroupId_expandsToMemberTopics() {
        Testimonial withGroupMember = testimonial("browse-topic-group@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        withGroupMember
                .getSections()
                .add(section(withGroupMember, topic("academics_difficulty"), "Fairly challenging."));
        testimonialRepository.saveAndFlush(withGroupMember);

        Testimonial withoutGroupMember = testimonial("browse-topic-group-nomatch@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        withoutGroupMember.getSections().add(section(withoutGroupMember, topic("general"), "Unrelated."));
        testimonialRepository.saveAndFlush(withoutGroupMember);

        Long academicsGroupId = topic("academics_difficulty").getTopicGroup().getId();
        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, List.of(academicsGroupId), null, PageRequest.of(0, 20));

        assertThat(result.content())
                .extracting(TestimonialCardDto::id)
                .containsExactly(withGroupMember.getId());
    }

    @Test
    void browse_topicFilterWithUnmatchedId_isSilentlyIgnoredAlongsideAValidId() {
        Testimonial withTopic = testimonial("browse-topic-unmatched@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        withTopic.getSections().add(section(withTopic, topic("general"), "General text."));
        testimonialRepository.saveAndFlush(withTopic);

        Long generalTopicId = topic("general").getId();
        Long bogusId = 999_999L;
        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, List.of(generalTopicId, bogusId), null, PageRequest.of(0, 20));

        assertThat(result.content()).extracting(TestimonialCardDto::id).containsExactly(withTopic.getId());
    }

    @Test
    void browse_topicFilterWithOnlyUnmatchedIds_isTreatedAsNoTopicFilterAtAll() {
        // Per the documented contract, an id matching neither a topic nor a
        // topic-group is silently dropped during expansion; if every
        // requested id is unmatched, the expanded list is empty and
        // hasAnyTopic(...) becomes a no-op (null), not a "matches nothing"
        // filter -- the topic filter behaves as if it had never been
        // supplied, same as an explicitly empty topicIds list.
        Testimonial withTopic = testimonial("browse-topic-only-unmatched@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        withTopic.getSections().add(section(withTopic, topic("general"), "General text."));
        testimonialRepository.saveAndFlush(withTopic);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, List.of(999_999L), null, PageRequest.of(0, 20));

        assertThat(result.content()).extracting(TestimonialCardDto::id).containsExactly(withTopic.getId());
    }

    @Test
    void browse_qFilter_matchesSectionAnswerTextCaseInsensitively() {
        Testimonial matching = testimonial("browse-q-section@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        matching.getSections().add(section(matching, topic("general"), "The FOOD here was amazing."));
        testimonialRepository.saveAndFlush(matching);

        Testimonial notMatching = testimonial("browse-q-section-nomatch@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        notMatching.getSections().add(section(notMatching, topic("general"), "Nothing relevant here."));
        testimonialRepository.saveAndFlush(notMatching);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, null, "food", PageRequest.of(0, 20));

        assertThat(result.content()).extracting(TestimonialCardDto::id).containsExactly(matching.getId());
    }

    @Test
    void browse_qFilter_matchesFirstOrLastNameCaseInsensitively() {
        Testimonial byLastName = testimonial("browse-q-name@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .lastName("Zubarev")
                .build();
        byLastName.getSections().add(section(byLastName, topic("general"), "Unrelated text."));
        testimonialRepository.saveAndFlush(byLastName);

        Testimonial other = testimonial("browse-q-name-nomatch@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .lastName("Smith")
                .build();
        other.getSections().add(section(other, topic("general"), "Unrelated text too."));
        testimonialRepository.saveAndFlush(other);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, null, "zuba", PageRequest.of(0, 20));

        assertThat(result.content()).extracting(TestimonialCardDto::id).containsExactly(byLastName.getId());
    }

    @Test
    void browse_allFiltersCombined_appliesAndSemantics() {
        Testimonial matchesAll = testimonial("browse-and-all@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        matchesAll.getSections().add(section(matchesAll, topic("academics_teaching"), "Teaching was superb."));
        testimonialRepository.saveAndFlush(matchesAll);

        // Right country, right topic, wrong keyword.
        Testimonial wrongKeyword = testimonial("browse-and-wrong-keyword@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        wrongKeyword.getSections().add(section(wrongKeyword, topic("academics_teaching"), "Something else."));
        testimonialRepository.saveAndFlush(wrongKeyword);

        // Right keyword and topic, wrong country.
        Testimonial wrongCountry = testimonial("browse-and-wrong-country@example.com", unitedStates())
                .status(TestimonialStatus.APPROVED)
                .build();
        wrongCountry.getSections().add(section(wrongCountry, topic("academics_teaching"), "Teaching was superb."));
        testimonialRepository.saveAndFlush(wrongCountry);

        Long topicId = topic("academics_teaching").getId();
        PageResponse<TestimonialCardDto> result =
                galleryService.browse("IN", List.of(topicId), "superb", PageRequest.of(0, 20));

        assertThat(result.content()).extracting(TestimonialCardDto::id).containsExactly(matchesAll.getId());
    }

    @Test
    void browse_zeroMatches_returnsEmptyPageWithZeroTotal() {
        PageResponse<TestimonialCardDto> result =
                galleryService.browse("IN", null, "no-such-keyword-anywhere", PageRequest.of(0, 20));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    void browse_pagination_secondPageReturnsRemainingItem() {
        for (int i = 0; i < 3; i++) {
            Testimonial t = testimonial("browse-page-" + i + "@example.com", india())
                    .status(TestimonialStatus.APPROVED)
                    .build();
            t.getSections().add(section(t, topic("general"), "Text " + i));
            testimonialRepository.saveAndFlush(t);
        }

        PageResponse<TestimonialCardDto> firstPage =
                galleryService.browse(null, null, null, PageRequest.of(0, 2));
        PageResponse<TestimonialCardDto> secondPage =
                galleryService.browse(null, null, null, PageRequest.of(1, 2));

        assertThat(firstPage.content()).hasSize(2);
        assertThat(secondPage.content()).hasSize(1);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(secondPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.page()).isZero();
        assertThat(secondPage.page()).isEqualTo(1);
    }

    @Test
    void browse_pagination_pageBeyondLastPage_returnsEmptyContentWithCorrectTotal() {
        Testimonial t = testimonial("browse-page-beyond@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        t.getSections().add(section(t, topic("general"), "Only one."));
        testimonialRepository.saveAndFlush(t);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, null, null, PageRequest.of(5, 20));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void browse_cardMapping_usesFirstSectionInDisplayOrderForPreviewAndThumbnail() {
        Testimonial t = testimonial("browse-card-mapping@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        // "general" is a standalone topic with display_order 17; "academics_teaching"
        // belongs to the "Academics" group (display_order 1) — so despite being
        // added second, it must be picked as the "first" section.
        TestimonialSection generalSection = section(t, topic("general"), "General text goes last.");
        TestimonialSection academicsSection = section(t, topic("academics_teaching"), "Academics text goes first.");
        Photo photo = Photo.builder()
                .section(academicsSection)
                .filePath("2026/01/photo.png")
                .displayOrder(0)
                .build();
        academicsSection.getPhotos().add(photo);
        t.getSections().add(generalSection);
        t.getSections().add(academicsSection);
        testimonialRepository.saveAndFlush(t);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, null, null, PageRequest.of(0, 20));

        TestimonialCardDto card = result.content().stream()
                .filter(c -> c.id().equals(t.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(card.previewText()).isEqualTo("Academics text goes first.");
        assertThat(card.thumbnailUrl()).isEqualTo("/uploads/2026/01/photo.png");
    }

    @Test
    void browse_cardCover_isTheFirstPhotosThumbnail() {
        Testimonial t = testimonial("browse-card-cover-thumb@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        TestimonialSection general = section(t, topic("general"), "With photos.");
        general.getPhotos().add(Photo.builder()
                .section(general)
                .filePath("second.webp")
                .thumbnailPath("second-thumb.webp")
                .displayOrder(1)
                .build());
        general.getPhotos().add(Photo.builder()
                .section(general)
                .filePath("first.webp")
                .thumbnailPath("first-thumb.webp")
                .displayOrder(0)
                .build());
        t.getSections().add(general);
        testimonialRepository.saveAndFlush(t);

        TestimonialCardDto card = galleryService.browse(null, null, null, PageRequest.of(0, 20)).content().stream()
                .filter(c -> c.id().equals(t.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(card.thumbnailUrl()).isEqualTo("/uploads/first-thumb.webp");
    }

    @Test
    void browse_cardMapping_firstSectionWithNoPhotos_thumbnailIsNull() {
        Testimonial t = testimonial("browse-card-no-photo@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        t.getSections().add(section(t, topic("general"), "No photos here."));
        testimonialRepository.saveAndFlush(t);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, null, null, PageRequest.of(0, 20));

        TestimonialCardDto card = result.content().stream()
                .filter(c -> c.id().equals(t.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(card.thumbnailUrl()).isNull();
    }

    @Test
    void browse_cardMapping_previewTextTruncatedAt200Characters() {
        String longAnswer = "a".repeat(250);
        Testimonial t = testimonial("browse-card-truncate@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        t.getSections().add(section(t, topic("general"), longAnswer));
        testimonialRepository.saveAndFlush(t);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, null, null, PageRequest.of(0, 20));

        TestimonialCardDto card = result.content().stream()
                .filter(c -> c.id().equals(t.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(card.previewText()).isEqualTo("a".repeat(200) + "...");
    }

    @Test
    void browse_cardMapping_previewTextExactly200Characters_isNotTruncated() {
        String exactAnswer = "b".repeat(200);
        Testimonial t = testimonial("browse-card-exact200@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .build();
        t.getSections().add(section(t, topic("general"), exactAnswer));
        testimonialRepository.saveAndFlush(t);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, null, null, PageRequest.of(0, 20));

        TestimonialCardDto card = result.content().stream()
                .filter(c -> c.id().equals(t.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(card.previewText()).isEqualTo(exactAnswer);
    }

    @Test
    void browse_cardMapping_neverIncludesNameDerivedInformation() {
        Testimonial t = testimonial("browse-card-no-name@example.com", india())
                .status(TestimonialStatus.APPROVED)
                .firstName("Aleksandra")
                .lastName("Volkova")
                .build();
        t.getSections().add(section(t, topic("general"), "Some text."));
        testimonialRepository.saveAndFlush(t);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse(null, null, null, PageRequest.of(0, 20));

        // TestimonialCardDto has no name-derived component at all (decision
        // 10) -- this is enforced by the record's shape at compile time; this
        // assertion documents the contract by checking the record's declared
        // components rather than any instance field.
        assertThat(TestimonialCardDto.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("firstName", "lastName", "displayName");
    }
}
