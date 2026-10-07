package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.iitm.beacon.catalogadmin.CatalogAdminService;
import com.iitm.beacon.catalogadmin.TopicPatchRequest;
import com.iitm.beacon.common.error.NotFoundException;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.gallery.GalleryService;
import com.iitm.beacon.gallery.TestimonialSectionViewDto;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole "hidden edit is never published unreviewed" loop (decision 28,
 * UC-APPROVE-TESTIMONIAL alt flow) across the real catalog, moderation and
 * gallery services: a pending edit to a section is hidden by the catalog,
 * approved without the admin seeing it, and comes back to the queue —
 * flagged, and still out of the gallery — once its topic is reactivated.
 */
@SpringBootTest
@Transactional
class HiddenEditReviewFlowTest {

    @Autowired
    private CatalogAdminService catalogAdminService;

    @Autowired
    private ModerationService moderationService;

    @Autowired
    private GalleryService galleryService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EntityManager entityManager;

    private static TopicPatchRequest active(boolean active) {
        return new TopicPatchRequest(null, null, null, null, active, null);
    }

    private static TestimonialSection section(Testimonial t, Topic topic, String answer) {
        return TestimonialSection.builder().testimonial(t).topic(topic).answerText(answer).modified(true).build();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void anEditApprovedWhileItsTopicWasHidden_returnsToTheQueueFlagged_onceTheTopicIsVisibleAgain() {
        Topic general = topicRepository.findBySlug(Topic.GENERAL_SLUG).orElseThrow();
        Topic topicX = topicRepository.saveAndFlush(
                CatalogVisibilityFixture.topic("hidden_flow_x", "Topic X", null, 1, true));
        Testimonial t = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email("hidden-flow@example.com")
                .emailLookupHash(UUID.randomUUID().toString())
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.PENDING)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        t.getSections().add(section(t, general, "General words."));
        t.getSections().add(section(t, topicX, "Words about X."));
        Long id = testimonialRepository.saveAndFlush(t).getId();

        catalogAdminService.patchTopic(topicX.getId(), active(false));
        flushAndClear();
        moderationService.approve(id);
        flushAndClear();

        assertThat(testimonialRepository.findById(id).orElseThrow().getSections())
                .extracting(s -> s.getTopic().getSlug(), TestimonialSection::isModified)
                .containsExactlyInAnyOrder(tuple(Topic.GENERAL_SLUG, false), tuple("hidden_flow_x", true));
        assertThat(galleryService.getDetail(id).sections())
                .extracting(TestimonialSectionViewDto::topicSlug)
                .containsExactly(Topic.GENERAL_SLUG);

        catalogAdminService.patchTopic(topicX.getId(), active(true));
        flushAndClear();

        assertThat(testimonialRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TestimonialStatus.PENDING);
        assertThatThrownBy(() -> galleryService.getDetail(id)).isInstanceOf(NotFoundException.class);
        assertThat(moderationService.listPending(PageRequest.of(0, 100)).content())
                .filteredOn(d -> d.id().equals(id))
                .singleElement()
                .satisfies(d -> assertThat(d.sections())
                        .extracting(ModerationSectionViewDto::topicSlug, ModerationSectionViewDto::updated)
                        .containsExactlyInAnyOrder(tuple(Topic.GENERAL_SLUG, false), tuple("hidden_flow_x", true)));
    }
}
