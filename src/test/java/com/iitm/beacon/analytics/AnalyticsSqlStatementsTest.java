package com.iitm.beacon.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.SampleTestimonials;
import com.iitm.beacon.testsupport.SampleTestimonials.Size;
import com.iitm.beacon.testsupport.SqlStatementCounter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * How many SQL statements the dashboard costs (BL-049): its figures are
 * aggregate queries (decision 30), so they cost the same for one small
 * approved testimonial as for many large ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AnalyticsSqlStatementsTest {

    /** Score summary, countries, achievement ticks, topic groups, standalone topics. */
    private static final long SUMMARY_BOUND = 5;

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TestimonialRepository testimonialRepository;

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

    private long statements(SqlStatementCounter.Action action) throws Exception {
        entityManager.flush();
        entityManager.clear();
        return counter.count(action).prepared();
    }

    @Test
    void summary_costsTheSameForOneSmallTestimonialAsForManyLargeOnes() throws Exception {
        samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = statements(analyticsService::summary);
        samples.save(20, TestimonialStatus.APPROVED, Size.LARGE);
        long many = statements(analyticsService::summary);

        assertThat(many).as("21 testimonials vs one (%s)", one).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(SUMMARY_BOUND);
    }

    @Test
    void dashboardPage_costsTheSameForOneSmallTestimonialAsForManyLargeOnes() throws Exception {
        samples.save(TestimonialStatus.APPROVED, Size.SMALLEST);
        long one = statements(() -> mockMvc.perform(get("/")).andExpect(status().isOk()));
        samples.save(20, TestimonialStatus.APPROVED, Size.LARGE);
        long many = statements(() -> mockMvc.perform(get("/")).andExpect(status().isOk()));

        assertThat(many).as("21 testimonials vs one (%s)", one).isEqualTo(one);
    }
}
