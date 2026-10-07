package com.iitm.beacon.gallery;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gallery list's order (BL-036): newest approval first — {@code
 * reviewedAt} descending, then {@code id} descending as the tie-breaker —
 * whatever the filters, the page, or the sort the caller passes. An approved
 * testimonial without a {@code reviewedAt} (never the case for one approved
 * through moderation) comes after every reviewed one. Testimonials are saved
 * in an order that matches neither their approval times nor their countries,
 * so neither insertion nor index order can pass for the real one.
 */
@SpringBootTest
@Transactional
class GalleryServiceBrowseOrderTest {

    private static final Instant T1 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-03-03T10:00:00Z");
    private static final Instant T4 = Instant.parse("2026-03-04T10:00:00Z");

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

    private int saved;

    private Long approved(String countryCode, Instant reviewedAt) {
        return approved(countryCode, reviewedAt, "general", "Some words.");
    }

    private Long approved(String countryCode, Instant reviewedAt, String topicSlug, String answer) {
        return save(TestimonialStatus.APPROVED, countryCode, reviewedAt, topicSlug, answer);
    }

    private Long save(TestimonialStatus status, String countryCode, Instant reviewedAt, String topicSlug,
            String answer) {
        String email = "order-" + (saved++) + "@example.com";
        Testimonial t = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(countryRepository.findById(countryCode).orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(status)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .reviewedAt(reviewedAt)
                .build();
        t.getSections().add(TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug(topicSlug).orElseThrow())
                .answerText(answer)
                .modified(false)
                .build());
        return testimonialRepository.saveAndFlush(t).getId();
    }

    private List<Long> browseIds(Pageable pageable) {
        return ids(galleryService.browse(null, null, null, null, pageable));
    }

    private static List<Long> ids(PageResponse<TestimonialCardDto> page) {
        return page.content().stream().map(TestimonialCardDto::id).toList();
    }

    @Test
    void browse_listsNewestApprovalFirst_notByIdOrCountry() {
        Long middle = approved("IN", T2);
        Long newest = approved("US", T3);
        Long oldest = approved("DE", T1);

        assertThat(browseIds(PageRequest.of(0, 20))).containsExactly(newest, middle, oldest);
    }

    @Test
    void browse_sameApprovalTime_newerIdFirst() {
        Long a = approved("US", T2);
        Long b = approved("IN", T1);
        Long c = approved("DE", T2);
        Long d = approved("IN", T1);

        assertThat(browseIds(PageRequest.of(0, 20))).containsExactly(c, a, d, b);
    }

    @Test
    void browse_approvalTimesOneMicrosecondApart_areNotATie() {
        // Saved first, so a tie (the times truncated to the same value) would put it last.
        Long later = approved("IN", T2.plusNanos(1_000));
        Long earlier = approved("IN", T2);

        assertThat(browseIds(PageRequest.of(0, 20))).containsExactly(later, earlier);
    }

    @Test
    void browse_approvedWithoutReviewedAt_comesAfterEveryReviewedOne_newerIdFirst() {
        Long unreviewedFirst = approved("IN", null);
        Long reviewedRecently = approved("IN", T2);
        Long unreviewedSecond = approved("IN", null);
        Long reviewedLongAgo = approved("IN", Instant.EPOCH);

        assertThat(browseIds(PageRequest.of(0, 20)))
                .containsExactly(reviewedRecently, reviewedLongAgo, unreviewedSecond, unreviewedFirst);
    }

    @Test
    void browse_pendingOrRejectedWithALaterReviewedAt_stayOutOfTheList() {
        Long approvedOne = approved("IN", T1);
        save(TestimonialStatus.REJECTED, "IN", T4, "general", "Rejected words.");
        save(TestimonialStatus.PENDING, "IN", T3, "general", "Pending words, re-edited after approval.");

        assertThat(browseIds(PageRequest.of(0, 20))).containsExactly(approvedOne);
    }

    @Test
    void browse_pagingAcrossPageBoundaries_returnsEveryApprovedTestimonialExactlyOnce_inOrder() {
        // Seven testimonials, three a page: the T2 tie straddles the first page boundary.
        List<Long> expected = new ArrayList<>();
        Long t1a = approved("US", T1);
        Long t2a = approved("IN", T2);
        Long t4 = approved("DE", T4);
        Long t2b = approved("US", T2);
        Long t3 = approved("IN", T3);
        Long t2c = approved("DE", T2);
        Long t1b = approved("IN", T1);
        expected.addAll(List.of(t4, t3, t2c, t2b, t2a, t1b, t1a));

        PageResponse<TestimonialCardDto> first = galleryService.browse(null, null, null, null, PageRequest.of(0, 3));
        PageResponse<TestimonialCardDto> second = galleryService.browse(null, null, null, null, PageRequest.of(1, 3));
        PageResponse<TestimonialCardDto> third = galleryService.browse(null, null, null, null, PageRequest.of(2, 3));
        PageResponse<TestimonialCardDto> past = galleryService.browse(null, null, null, null, PageRequest.of(3, 3));

        List<Long> walked = new ArrayList<>();
        walked.addAll(ids(first));
        walked.addAll(ids(second));
        walked.addAll(ids(third));
        assertThat(ids(first)).hasSize(3);
        assertThat(ids(second)).hasSize(3);
        assertThat(ids(third)).hasSize(1);
        assertThat(walked).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(ids(past)).isEmpty();
        assertThat(List.of(first, second, third, past))
                .extracting(PageResponse::totalElements)
                .containsOnly(7L);
    }

    @Test
    void browse_pageOfOne_walksTheWholeOrderOneCardAtATime() {
        Long b = approved("IN", T1);
        Long a = approved("IN", T2);
        Long c = approved("IN", T1);

        List<Long> walked = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            walked.addAll(browseIds(PageRequest.of(page, 1)));
        }

        assertThat(walked).containsExactly(a, c, b);
    }

    @Test
    void browse_withEveryFilter_keepsTheOrder() {
        Long older = approved("IN", T1, "general", "A superb stay.");
        Long newest = approved("IN", T3, "general", "Superb food.");
        approved("US", T4, "general", "A superb stay abroad.");
        approved("IN", T4, "academics_teaching", "Superb lectures.");
        approved("IN", T4, "general", "Nothing to see.");
        Long middle = approved("IN", T2, "general", "Superb people.");
        Long generalId = topicRepository.findBySlug("general").orElseThrow().getId();

        PageResponse<TestimonialCardDto> result =
                galleryService.browse("IN", null, List.of(generalId), "superb", PageRequest.of(0, 20));

        assertThat(ids(result)).containsExactly(newest, middle, older);
    }

    @Test
    void browse_countryFilterAlone_keepsTheOrder() {
        Long older = approved("IN", T1);
        approved("US", T3);
        Long newer = approved("IN", T2);

        PageResponse<TestimonialCardDto> result =
                galleryService.browse("IN", null, null, null, PageRequest.of(0, 20));

        assertThat(ids(result)).containsExactly(newer, older);
    }

    @Test
    void browse_ignoresTheSortTheCallerPasses() {
        Long middle = approved("IN", T2);
        Long newest = approved("US", T3);
        Long oldest = approved("DE", T1);

        assertThat(browseIds(PageRequest.of(0, 20, Sort.by("id").ascending())))
                .containsExactly(newest, middle, oldest);
        assertThat(browseIds(PageRequest.of(0, 20, Sort.by("reviewedAt").ascending())))
                .containsExactly(newest, middle, oldest);
        assertThat(browseIds(PageRequest.of(0, 20, Sort.by("country.code", "firstName"))))
                .containsExactly(newest, middle, oldest);
    }

    @Test
    void browse_unpaged_listsEveryApprovedTestimonialInTheSameOrder() {
        Long middle = approved("IN", T2);
        Long newest = approved("US", T3);
        Long oldest = approved("DE", T1);

        assertThat(browseIds(Pageable.unpaged(Sort.by("id")))).containsExactly(newest, middle, oldest);
    }
}
