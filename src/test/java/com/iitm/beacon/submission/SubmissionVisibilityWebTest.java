package com.iitm.beacon.submission;

import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.submission.TestimonialSubmissionRequest.SectionInput;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.ModelAndView;

/**
 * The author's edit surfaces under cascading visibility (decision 28):
 * {@code GET /api/submissions/mine} and the edit form page leave out
 * sections of invisible topics and ticks of inactive achievements.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class SubmissionVisibilityWebTest {

    private static final String EMAIL = "vis-web-author@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void testimonialWithContentThatIsThenHidden() {
        CatalogVisibilityFixture catalog =
                CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
        catalog.reactivateAll(topicGroupRepository, topicRepository, achievementRepository);
        submissionService.create(
                EMAIL,
                new TestimonialSubmissionRequest(
                        "David",
                        "Jones",
                        "GE26Z001",
                        2024,
                        "IN",
                        8,
                        List.of(
                                new SectionInput("general", "General words.", List.of()),
                                new SectionInput(
                                        CatalogVisibilityFixture.INACTIVE_TOPIC_SLUG, "Inactive words.", List.of()),
                                new SectionInput(
                                        CatalogVisibilityFixture.TOPIC_IN_INACTIVE_GROUP_SLUG,
                                        "Hidden group words.",
                                        List.of())),
                        List.of(
                                CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG,
                                CatalogVisibilityFixture.INACTIVE_ACHIEVEMENT_SLUG),
                        List.of(),
                        true),
                Map.of());
        catalog.deactivateAgain(topicGroupRepository, topicRepository, achievementRepository);
        entityManager.flush();
        entityManager.clear();
    }

    private static Authentication visitor() {
        return new UsernamePasswordAuthenticationToken(
                EMAIL, null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    @Test
    void editForm_showsNoHiddenSectionText_andCarriesNoHiddenTick() throws Exception {
        var result = mockMvc.perform(get("/submissions/form").with(authentication(visitor())))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        ModelAndView mav = result.getModelAndView();

        assertThat(html).contains("General words.").doesNotContain("Inactive words.", "Hidden group words.");
        assertThat(((SubmissionFormCommand) mav.getModel().get("command")).getAchievementSlugs())
                .containsExactly(CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG);
        assertThat(openingTags(html, "input"))
                .filteredOn(tag -> attribute(tag, "name").orElse("").equals("achievementSlugs"))
                .extracting(tag -> attribute(tag, "value").orElseThrow())
                .contains(CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG)
                .doesNotContain(CatalogVisibilityFixture.INACTIVE_ACHIEVEMENT_SLUG);
    }
}
