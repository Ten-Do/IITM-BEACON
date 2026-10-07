package com.iitm.beacon.analytics;

import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link AnalyticsController} with the real {@code
 * SecurityConfig} filters: {@code GET /api/analytics/summary} is public
 * (UC-VIEW-DASHBOARD has no precondition), answers the {@code
 * AnalyticsSummary} shape of api-spec, and no other method gets through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class AnalyticsControllerTest {

    private static final String SUMMARY = "/api/analytics/summary";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private AnalyticsTestData data;

    @BeforeEach
    void setUp() {
        data = new AnalyticsTestData(
                testimonialRepository,
                countryRepository,
                topicRepository,
                achievementRepository,
                emailLookupHashService);
    }

    private static Authentication visitor() {
        return new UsernamePasswordAuthenticationToken(
                "analytics-visitor@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void anonymousGet_withNoApprovedTestimonial_answersZeroEmptyListsAndExplicitNullScoreFigures() throws Exception {
        data.pending().score(9).sections("general").ticks("made_new_friends").save();

        mockMvc.perform(get(SUMMARY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalApprovedTestimonials").value(0))
                .andExpect(jsonPath("$", hasKey("averageRecommendationScore")))
                .andExpect(jsonPath("$.averageRecommendationScore").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("recommendingPercent")))
                .andExpect(jsonPath("$.recommendingPercent").value(nullValue()))
                .andExpect(jsonPath("$.testimonialsByCountry").isEmpty())
                .andExpect(jsonPath("$.achievementCounts").isEmpty())
                .andExpect(jsonPath("$.testimonialCountsByTopic").isEmpty());
    }

    @Test
    void anonymousGet_answersEveryFieldOfTheSpecShape() throws Exception {
        data.approved().from("IN").score(7)
                .sections("academics_teaching", "academics_difficulty", "general")
                .ticks("made_new_friends")
                .save();
        Topic academicsTopic = data.topic("academics_teaching");
        Topic general = data.topic("general");
        Achievement madeNewFriends = data.achievement("made_new_friends");

        mockMvc.perform(get(SUMMARY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApprovedTestimonials").value(1))
                .andExpect(jsonPath("$.averageRecommendationScore").value(7.0))
                .andExpect(jsonPath("$.recommendingPercent").value(100))
                .andExpect(jsonPath("$.testimonialsByCountry.length()").value(1))
                .andExpect(jsonPath("$.testimonialsByCountry[0].country.code").value("IN"))
                .andExpect(jsonPath("$.testimonialsByCountry[0].country.name").value("India"))
                .andExpect(jsonPath("$.testimonialsByCountry[0].count").value(1))
                .andExpect(jsonPath("$.achievementCounts.length()").value(1))
                .andExpect(jsonPath("$.achievementCounts[0].achievement.id").value(madeNewFriends.getId()))
                .andExpect(jsonPath("$.achievementCounts[0].achievement.slug").value("made_new_friends"))
                .andExpect(jsonPath("$.achievementCounts[0].achievement.label").value("Made new friends here"))
                .andExpect(jsonPath("$.achievementCounts[0].achievement.displayOrder").value(1))
                .andExpect(jsonPath("$.achievementCounts[0].achievement.active").value(true))
                .andExpect(jsonPath("$.achievementCounts[0].count").value(1))
                .andExpect(jsonPath("$.testimonialCountsByTopic.length()").value(2))
                .andExpect(jsonPath("$.testimonialCountsByTopic[0].kind").value("GROUP"))
                .andExpect(jsonPath("$.testimonialCountsByTopic[0].id")
                        .value(academicsTopic.getTopicGroup().getId()))
                .andExpect(jsonPath("$.testimonialCountsByTopic[0].label").value("Academics"))
                .andExpect(jsonPath("$.testimonialCountsByTopic[0].count").value(1))
                .andExpect(jsonPath("$.testimonialCountsByTopic[0].displayOrder").doesNotExist())
                .andExpect(jsonPath("$.testimonialCountsByTopic[1].kind").value("STANDALONE"))
                .andExpect(jsonPath("$.testimonialCountsByTopic[1].id").value(general.getId()))
                .andExpect(jsonPath("$.testimonialCountsByTopic[1].label").value("General"))
                .andExpect(jsonPath("$.testimonialCountsByTopic[1].count").value(1));
    }

    @Test
    void visitorSessionGet_returns200() throws Exception {
        mockMvc.perform(get(SUMMARY).with(authentication(visitor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(anEmptyMap())));
    }

    @Test
    void adminSessionGet_returns200() throws Exception {
        mockMvc.perform(get(SUMMARY).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(anEmptyMap())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void anonymousWrite_isRefusedWith401(String method) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), SUMMARY).with(csrfHeader()))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void adminWrite_isRefusedWith403(String method) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), SUMMARY).with(csrfHeader()).with(authentication(admin())))
                .andExpect(status().isForbidden());
    }
}
