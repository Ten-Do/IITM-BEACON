package com.iitm.beacon.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.PostgresContainerSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * BL-036 on a real PostgreSQL 16, not H2's PostgreSQL mode: the gallery's
 * newest-approval-first order — {@code reviewedAt} descending, {@code id}
 * descending on a tie, never-reviewed last, built as a {@code CASE} in
 * {@code TestimonialSpecifications.newestApprovalFirst()} — runs there,
 * with and without filters, alongside the page's count query, and pages of
 * 3 show every approved testimonial exactly once. The approval-time tie
 * straddles a page boundary, and the testimonials are saved in an order that
 * matches neither their approval times nor their ids' rank, so neither
 * insertion nor index order can pass for the real one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GalleryBrowseOrderPostgresTest {

    private static final Instant T1 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-03-03T10:00:00Z");
    private static final Instant T4 = Instant.parse("2026-03-04T10:00:00Z");
    private static final Instant T5 = Instant.parse("2026-03-05T10:00:00Z");
    private static final Instant T9 = Instant.parse("2026-03-09T10:00:00Z");

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private int saved;

    /** Ids of the approved testimonials in the order the gallery must list them. */
    private List<Long> expectedOrder;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerDataSource(registry, "beacon_gallery_order_pg");
    }

    private Long save(TestimonialStatus status, String countryCode, Instant reviewedAt, String answer) {
        String email = "pg-order-" + (saved++) + "@example.com";
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
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText(answer)
                .modified(false)
                .build());
        return testimonialRepository.saveAndFlush(t).getId();
    }

    private Long approved(String countryCode, Instant reviewedAt, String answer) {
        return save(TestimonialStatus.APPROVED, countryCode, reviewedAt, answer);
    }

    @BeforeEach
    void eightApprovedWithATiedApprovalTime_plusAPendingAndARejectedOne() {
        Long tieOldestId = approved("IN", T3, "Monsoon in Chennai.");
        Long t2 = approved("US", T2, "Labs were great.");
        Long unreviewed = approved("DE", null, "Monsoon again.");
        Long newest = approved("IN", T5, "Hostel life.");
        Long tieMiddleId = approved("DE", T3, "Monsoon food.");
        save(TestimonialStatus.PENDING, "IN", T9, "Monsoon pending.");
        Long t1 = approved("IN", T1, "Campus clubs.");
        Long tieNewestId = approved("IN", T3, "Monsoon festival.");
        save(TestimonialStatus.REJECTED, "IN", T9, "Monsoon rejected.");
        Long t4 = approved("US", T4, "Monsoon trip.");
        expectedOrder = List.of(newest, t4, tieNewestId, tieMiddleId, tieOldestId, t2, t1, unreviewed);
    }

    private static List<Long> ids(PageResponse<TestimonialCardDto> page) {
        return page.content().stream().map(TestimonialCardDto::id).toList();
    }

    private PageResponse<TestimonialCardDto> page(int number) {
        return galleryService.browse(null, null, null, null, PageRequest.of(number, 3));
    }

    @Test
    void runsOnPostgresql16() {
        assertThat(jdbcTemplate.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL 16");
    }

    @Test
    void pagesOfThree_listNewestApprovalFirst_idDescendingOnTheTie_neverReviewedLast() {
        assertThat(ids(page(0))).containsExactlyElementsOf(expectedOrder.subList(0, 3));
        assertThat(ids(page(1))).containsExactlyElementsOf(expectedOrder.subList(3, 6));
        assertThat(ids(page(2))).containsExactlyElementsOf(expectedOrder.subList(6, 8));
    }

    @Test
    void pagingThroughEveryPage_showsEachApprovedTestimonialExactlyOnce_withAConsistentTotal() {
        List<Long> seen = new ArrayList<>();
        for (int number = 0; number < 4; number++) {
            PageResponse<TestimonialCardDto> page = page(number);
            assertThat(page.totalElements()).as("totalElements of page " + number).isEqualTo(8);
            seen.addAll(ids(page));
        }

        assertThat(seen).containsExactlyElementsOf(expectedOrder).doesNotHaveDuplicates();
    }

    @Test
    void pagePastTheEnd_isEmpty_butStillCountsEveryApprovedTestimonial() {
        PageResponse<TestimonialCardDto> past = page(3);

        assertThat(past.content()).isEmpty();
        assertThat(past.totalElements()).isEqualTo(8);
    }

    @Test
    void withCountryAndKeywordFilters_theOrderAndTheCountStillHold() {
        PageResponse<TestimonialCardDto> page =
                galleryService.browse("IN", null, null, "monsoon", PageRequest.of(0, 3));

        // IN and "monsoon": the tie's newest and oldest ids (the pending and rejected ones never count).
        assertThat(ids(page)).containsExactly(expectedOrder.get(2), expectedOrder.get(4));
        assertThat(page.totalElements()).isEqualTo(2);
    }

    @Test
    void withATopicFilter_theOrderAndTheCountStillHold() {
        Long generalId = topicRepository.findBySlug("general").orElseThrow().getId();

        PageResponse<TestimonialCardDto> page =
                galleryService.browse(null, null, List.of(generalId), null, PageRequest.of(1, 3));

        assertThat(ids(page)).containsExactlyElementsOf(expectedOrder.subList(3, 6));
        assertThat(page.totalElements()).isEqualTo(8);
    }

    @Test
    void overHttp_aCallersSortIsIgnored_andThePageIsTheSame() throws Exception {
        String json = mockMvc.perform(get("/api/gallery/testimonials")
                        .param("page", "1")
                        .param("size", "3")
                        .param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode body = objectMapper.readTree(json);
        List<Long> ids = new ArrayList<>();
        body.get("content").forEach(card -> ids.add(card.get("id").asLong()));
        assertThat(ids).containsExactlyElementsOf(expectedOrder.subList(3, 6));
        assertThat(body.get("totalElements").asLong()).isEqualTo(8);
    }
}
