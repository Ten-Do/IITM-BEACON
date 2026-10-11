package com.iitm.beacon.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.config.NotificationMailer;
import com.iitm.beacon.config.PhotoFileDeleter;
import com.iitm.beacon.config.PhotoUrlResolver;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethodRepository;
import com.iitm.beacon.domain.testimonial.PhotoRepository;
import com.iitm.beacon.domain.testimonial.PhotoTagRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSectionRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.SampleTestimonials;
import com.iitm.beacon.testsupport.SampleTestimonials.Size;
import com.iitm.beacon.testsupport.SqlStatementCounter;
import com.iitm.beacon.testsupport.SqlStatementCounter.Statements;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * How many SQL statements moderation costs (BL-049): the pending queue —
 * page and API — costs the same for one small testimonial as for a page of
 * large ones, within a fixed bound; approving one loads it in the same
 * number of statements whatever its size; and the purge of long-rejected
 * testimonials costs a fixed number of statements plus one conditional
 * delete per testimonial (decision 3), whatever they hold.
 *
 * <p>A first page that isn't full needs no count query, so the queue's one
 * card is compared on a page of size 1; the queue page always shows 20,
 * so there a full page costs exactly that count query more.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ModerationSqlStatementsTest {

    /** Page query, count query, sections, photos, tags, contact methods, achievement ticks. */
    private static final long QUEUE_BOUND = 7;

    @Autowired
    private ModerationService moderationService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TestimonialSectionRepository testimonialSectionRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private PhotoTagRepository photoTagRepository;

    @Autowired
    private ContactMethodRepository contactMethodRepository;

    @Autowired
    private TestimonialAchievementRepository testimonialAchievementRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private SqlStatementCounter counter;
    private SampleTestimonials samples;

    @BeforeEach
    void setUp() {
        counter = new SqlStatementCounter(entityManagerFactory);
        samples = new SampleTestimonials(testimonialRepository, topicRepository, countryRepository,
                contactTypeRepository, achievementRepository, emailLookupHashService);
    }

    /**
     * What {@code action} costs, starting from an empty persistence context
     * and ending with a flush, as a request's transaction does.
     */
    private Statements count(SqlStatementCounter.Action action) throws Exception {
        entityManager.flush();
        entityManager.clear();
        return counter.count(() -> {
            action.run();
            entityManager.flush();
        });
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    /**
     * The purge, as of a "now" 40 days after every sample's rejection, so the
     * samples are due — and no testimonial another test left behind is.
     */
    private ModerationService purgingService() {
        return new ModerationService(
                testimonialRepository,
                testimonialSectionRepository,
                photoRepository,
                photoTagRepository,
                contactMethodRepository,
                testimonialAchievementRepository,
                mock(NotificationMailer.class),
                new PhotoUrlResolver(),
                mock(PhotoFileDeleter.class),
                Clock.fixed(SampleTestimonials.REJECTED_AT_REFERENCE.plus(Duration.ofDays(40)), ZoneOffset.UTC));
    }

    // -- the queue --

    @Test
    void listPending_costsTheSameForOneSmallTestimonialAsForAPageOfLargeOnes() throws Exception {
        samples.save(TestimonialStatus.PENDING, Size.SMALLEST);
        long one = count(() -> moderationService.listPending(PageRequest.of(0, 1))).prepared();
        samples.save(19, TestimonialStatus.PENDING, Size.LARGE);
        long many = count(() -> moderationService.listPending(PageRequest.of(0, 20))).prepared();

        assertThat(many).as("a page of 20 large testimonials vs one small one (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(QUEUE_BOUND);
    }

    @Test
    void listPendingForView_costsTheSameForOneSmallTestimonialAsForAPageOfLargeOnes() throws Exception {
        samples.save(TestimonialStatus.PENDING, Size.SMALLEST);
        long one = count(() -> moderationService.listPendingForView(PageRequest.of(0, 1))).prepared();
        samples.save(19, TestimonialStatus.PENDING, Size.LARGE);
        long many = count(() -> moderationService.listPendingForView(PageRequest.of(0, 20))).prepared();

        assertThat(many).as("a page of 20 large testimonials vs one small one (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(QUEUE_BOUND);
    }

    @Test
    void queuePage_costsOneCountQueryMoreForAFullPageOfLargeTestimonialsThanForOneSmallOne() throws Exception {
        samples.save(TestimonialStatus.PENDING, Size.SMALLEST);
        long one = count(() -> mockMvc.perform(get("/moderation/queue").with(authentication(admin())))
                .andExpect(status().isOk())).prepared();
        samples.save(19, TestimonialStatus.PENDING, Size.LARGE);
        long many = count(() -> mockMvc.perform(get("/moderation/queue").with(authentication(admin())))
                .andExpect(status().isOk())).prepared();

        assertThat(many).as("a page of 20 large testimonials vs one small one (%s), plus the count query", one)
                .isEqualTo(one + 1);
    }

    @Test
    void pendingApi_costsTheSameForOneSmallTestimonialAsForAPageOfLargeOnes() throws Exception {
        samples.save(TestimonialStatus.PENDING, Size.SMALLEST);
        long one = count(() -> mockMvc.perform(get("/api/moderation/testimonials/pending").param("size", "1")
                .with(authentication(admin()))).andExpect(status().isOk())).prepared();
        samples.save(19, TestimonialStatus.PENDING, Size.LARGE);
        long many = count(() -> mockMvc.perform(get("/api/moderation/testimonials/pending").param("size", "20")
                .with(authentication(admin()))).andExpect(status().isOk())).prepared();

        assertThat(many).as("a page of 20 large testimonials vs one small one (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(QUEUE_BOUND);
    }

    // -- approve --

    @Test
    void approve_loadsASmallAndALargeTestimonialInTheSameStatements() throws Exception {
        Testimonial small = samples.save(TestimonialStatus.PENDING, Size.SMALLEST);
        Testimonial large = samples.save(TestimonialStatus.PENDING, Size.LARGE);

        Statements one = count(() -> moderationService.approve(small.getId()));
        Statements many = count(() -> moderationService.approve(large.getId()));

        assertThat(many.withoutEntityWrites())
                .as("loading a large testimonial vs a small one (%s)", one.withoutEntityWrites())
                .isEqualTo(one.withoutEntityWrites());
        assertThat(many.withoutEntityWrites()).isLessThanOrEqualTo(1);
    }

    // -- the purge --

    @Test
    void purgeExpiredRejections_costsTheSameForSmallAndLargeTestimonials() throws Exception {
        samples.save(3, TestimonialStatus.REJECTED, Size.SMALLEST);
        Statements small = count(() -> assertThat(purgingService().purgeExpiredRejections().testimonialsPurged())
                .isEqualTo(3));
        samples.save(3, TestimonialStatus.REJECTED, Size.LARGE);
        Statements large = count(() -> assertThat(purgingService().purgeExpiredRejections().testimonialsPurged())
                .isEqualTo(3));

        assertThat(large.prepared()).as("three large testimonials vs three small ones (%s)", small.prepared())
                .isEqualTo(small.prepared());
    }

    @Test
    void purgeExpiredRejections_costsOneConditionalDeleteMorePerTestimonial() throws Exception {
        samples.save(TestimonialStatus.REJECTED, Size.LARGE);
        Statements one = count(() -> assertThat(purgingService().purgeExpiredRejections().testimonialsPurged())
                .isEqualTo(1));
        samples.save(5, TestimonialStatus.REJECTED, Size.LARGE);
        Statements five = count(() -> assertThat(purgingService().purgeExpiredRejections().testimonialsPurged())
                .isEqualTo(5));

        assertThat(five.prepared()).as("five testimonials vs one (%s), plus four deletes", one.prepared())
                .isEqualTo(one.prepared() + 4);
        assertThat(one.prepared()).as("finding them, their photos, and the one delete").isLessThanOrEqualTo(3);
    }
}
