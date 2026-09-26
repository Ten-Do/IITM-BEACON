package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.iitm.beacon.common.web.PageResponse;
import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.contacttype.ContactType;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ModerationService#listPending(org.springframework.data.domain.Pageable)}
 * (UC-VIEW-PENDING-QUEUE).
 */
@SpringBootTest
@Transactional
class ModerationServiceListPendingTest {

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    private ModerationService moderationService;

    @BeforeEach
    void setUp() {
        moderationService = new ModerationService(
                testimonialRepository, mock(NotificationMailer.class), new PhotoUrlResolver(), java.time.Clock.systemUTC());
    }

    private Country country(String code) {
        return countryRepository.findById(code).orElseThrow();
    }

    private Topic topic(String slug) {
        return topicRepository.findBySlug(slug).orElseThrow();
    }

    private ContactType contactType(String slug) {
        return contactTypeRepository.findBySlug(slug).orElseThrow();
    }

    private Achievement achievement(String slug) {
        return achievementRepository.findBySlug(slug).orElseThrow();
    }

    private Testimonial newTestimonial(String email, TestimonialStatus status, Instant createdAt) {
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(UUID.randomUUID().toString())
                .country(country("IN"))
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(status)
                .createdAt(createdAt)
                .build();
    }

    private void addSection(Testimonial t, String topicSlug, String answer, boolean modified) {
        TestimonialSection section = TestimonialSection.builder()
                .testimonial(t)
                .topic(topic(topicSlug))
                .answerText(answer)
                .modified(modified)
                .build();
        t.getSections().add(section);
    }

    private void addContactMethod(Testimonial t, String typeSlug, String value, boolean isPublic) {
        ContactMethod cm = ContactMethod.builder()
                .testimonial(t)
                .contactType(contactType(typeSlug))
                .value(value)
                .isPublic(isPublic)
                .displayOrder(t.getContactMethods().size())
                .build();
        t.getContactMethods().add(cm);
    }

    private void addAchievement(Testimonial t, String achievementSlug) {
        t.getAchievements()
                .add(TestimonialAchievement.builder()
                        .testimonial(t)
                        .achievement(achievement(achievementSlug))
                        .build());
    }

    @Test
    void listPending_emptyQueue_returnsEmptyContentAndZeroTotal() {
        PageResponse<ModerationTestimonialDetailDto> result =
                moderationService.listPending(PageRequest.of(0, 20));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    void listPending_excludesApprovedAndRejected_onlyPendingReturned() {
        Testimonial pending = newTestimonial("pending@example.com", TestimonialStatus.PENDING, Instant.now());
        addSection(pending, "general", "Pending answer.", false);
        testimonialRepository.save(pending);

        Testimonial approved = newTestimonial("approved@example.com", TestimonialStatus.APPROVED, Instant.now());
        addSection(approved, "general", "Approved answer.", false);
        testimonialRepository.save(approved);

        Testimonial rejected = newTestimonial("rejected@example.com", TestimonialStatus.REJECTED, Instant.now());
        addSection(rejected, "general", "Rejected answer.", false);
        testimonialRepository.save(rejected);

        PageResponse<ModerationTestimonialDetailDto> result =
                moderationService.listPending(PageRequest.of(0, 20));

        assertThat(result.content()).extracting(ModerationTestimonialDetailDto::email)
                .containsExactly("pending@example.com");
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void listPending_mapsFieldsIncludingDecryptedEmailAndPrivateContactMethods() {
        Testimonial testimonial =
                newTestimonial("full-detail@example.com", TestimonialStatus.PENDING, Instant.now());
        testimonial.setIdentityModified(true);
        testimonial.setScoreModified(true);
        addSection(testimonial, "general", "Great time overall.", true);
        addContactMethod(testimonial, "whatsapp", "+1234567890", false);
        addContactMethod(testimonial, "telegram", "@davidj", true);
        addAchievement(testimonial, "made_new_friends");
        testimonialRepository.save(testimonial);

        PageResponse<ModerationTestimonialDetailDto> result =
                moderationService.listPending(PageRequest.of(0, 20));

        assertThat(result.content()).hasSize(1);
        ModerationTestimonialDetailDto dto = result.content().get(0);
        assertThat(dto.email()).isEqualTo("full-detail@example.com");
        assertThat(dto.firstName()).isEqualTo("David");
        assertThat(dto.lastName()).isEqualTo("Jones");
        assertThat(dto.rollNumber()).isEqualTo("GE26Z001");
        assertThat(dto.admissionYear()).isEqualTo(2024);
        assertThat(dto.country().code()).isEqualTo("IN");
        assertThat(dto.recommendationScore()).isEqualTo(8);
        assertThat(dto.status()).isEqualTo(TestimonialStatus.PENDING);
        assertThat(dto.identityModified()).isTrue();
        assertThat(dto.scoreModified()).isTrue();
        assertThat(dto.achievements()).containsExactly("made_new_friends");
        assertThat(dto.sections()).hasSize(1);
        assertThat(dto.sections().get(0).topicSlug()).isEqualTo("general");
        assertThat(dto.sections().get(0).answer()).isEqualTo("Great time overall.");
        assertThat(dto.sections().get(0).updated()).isTrue();
        // Includes the private contact method too, unlike the public gallery view.
        assertThat(dto.contactMethods()).hasSize(2);
        assertThat(dto.contactMethods())
                .extracting(ModerationContactMethodViewDto::type, ModerationContactMethodViewDto::value, ModerationContactMethodViewDto::isPublic)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("whatsapp", "+1234567890", false),
                        org.assertj.core.groups.Tuple.tuple("telegram", "@davidj", true));
    }

    @Test
    void listPending_sortsSectionsByTopicGroupThenTopicDisplayOrder() {
        Testimonial testimonial = newTestimonial("ordering@example.com", TestimonialStatus.PENDING, Instant.now());
        // Inserted out of display-order on purpose.
        addSection(testimonial, "general", "General text.", false); // standalone, displayOrder 17
        addSection(testimonial, "academics_workload", "Workload text.", false); // group 1 (order 1), topic order 7
        addSection(testimonial, "academics_teaching", "Teaching text.", false); // group 1 (order 1), topic order 1
        addSection(testimonial, "new_friendships", "Friends text.", false); // standalone, displayOrder 7
        testimonialRepository.save(testimonial);

        PageResponse<ModerationTestimonialDetailDto> result =
                moderationService.listPending(PageRequest.of(0, 20));

        assertThat(result.content().get(0).sections())
                .extracting(ModerationSectionViewDto::topicSlug)
                .containsExactly("academics_teaching", "academics_workload", "new_friendships", "general");
    }

    @Test
    void listPending_paginationBoundaries_partialLastPage() {
        for (int i = 0; i < 3; i++) {
            Testimonial t = newTestimonial("page-" + i + "@example.com", TestimonialStatus.PENDING, Instant.now());
            addSection(t, "general", "Answer " + i, false);
            testimonialRepository.save(t);
        }

        PageResponse<ModerationTestimonialDetailDto> firstPage =
                moderationService.listPending(PageRequest.of(0, 2));
        PageResponse<ModerationTestimonialDetailDto> secondPage =
                moderationService.listPending(PageRequest.of(1, 2));

        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(secondPage.content()).hasSize(1);
        assertThat(secondPage.totalElements()).isEqualTo(3);
    }

    @Test
    void listPending_ordersByCreatedAtAscending() {
        Instant now = Instant.now();
        Testimonial newer = newTestimonial("newer@example.com", TestimonialStatus.PENDING, now.plusSeconds(60));
        addSection(newer, "general", "Newer.", false);
        testimonialRepository.save(newer);

        Testimonial older = newTestimonial("older@example.com", TestimonialStatus.PENDING, now);
        addSection(older, "general", "Older.", false);
        testimonialRepository.save(older);

        PageResponse<ModerationTestimonialDetailDto> result =
                moderationService.listPending(PageRequest.of(0, 20));

        assertThat(result.content()).extracting(ModerationTestimonialDetailDto::email)
                .containsExactly("older@example.com", "newer@example.com");
    }
}
