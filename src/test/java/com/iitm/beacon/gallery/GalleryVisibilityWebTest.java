package com.iitm.beacon.gallery;

import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievement;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gallery's REST and page routes under cascading visibility (decision
 * 28) and the split topic filter (decision 29): {@code groupIds} and {@code
 * topicIds} are separate request parameters on both {@code
 * /api/gallery/testimonials} and {@code /gallery}, the filter chips submit
 * the one matching their kind, and pagination keeps both.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class GalleryVisibilityWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    private CatalogVisibilityFixture catalog;

    @BeforeEach
    void createCatalog() {
        catalog = CatalogVisibilityFixture.create(topicGroupRepository, topicRepository, achievementRepository);
    }

    private Testimonial approved(String email) {
        return Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(TestimonialStatus.APPROVED)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private static void addSection(Testimonial t, Topic topic, String answer) {
        t.getSections().add(TestimonialSection.builder()
                .testimonial(t)
                .topic(topic)
                .answerText(answer)
                .modified(false)
                .build());
    }

    private static void addAchievement(Testimonial t, Achievement achievement) {
        t.getAchievements().add(TestimonialAchievement.builder().testimonial(t).achievement(achievement).build());
    }

    private Testimonial saved(String email, Topic topic, String answer) {
        Testimonial t = approved(email);
        addSection(t, topic, answer);
        return testimonialRepository.saveAndFlush(t);
    }

    private Topic general() {
        return topicRepository.findBySlug("general").orElseThrow();
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** The filter bar's chip checkboxes. */
    private static List<String> chipInputs(String html) {
        return openingTags(html, "input").stream()
                .filter(tag -> attribute(tag, "class").orElse("").contains("gallery-chip-checkbox"))
                .toList();
    }

    private static String chipWithValue(List<String> chips, Long value) {
        return chips.stream()
                .filter(tag -> attribute(tag, "value").orElse("").equals(String.valueOf(value)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No chip with value " + value + " in " + chips));
    }

    @Test
    void api_browse_sharedGroupAndTopicId_isReadAsWhicheverParameterCarriesIt() throws Exception {
        CatalogVisibilityFixture.IdClash clash =
                CatalogVisibilityFixture.forceIdClash(topicGroupRepository, topicRepository);
        Testimonial viaTopic = saved("web-api-clash-topic@example.com", clash.standalone(), "Standalone.");
        Testimonial viaGroup = saved("web-api-clash-group@example.com", clash.member(), "Member.");
        String sharedId = String.valueOf(clash.sharedId());

        mockMvc.perform(get("/api/gallery/testimonials").param("topicIds", sharedId))
                .andExpect(jsonPath("$.content[*].id", contains(viaTopic.getId().intValue())));
        mockMvc.perform(get("/api/gallery/testimonials").param("groupIds", sharedId))
                .andExpect(jsonPath("$.content[*].id", contains(viaGroup.getId().intValue())));
    }

    // -- REST: GET /api/gallery/testimonials/{id} --

    @Test
    void api_detail_leavesOutHiddenSectionsAndAchievements() throws Exception {
        Testimonial t = approved("web-api-detail@example.com");
        addSection(t, catalog.inactiveTopic(), "Inactive words.");
        addSection(t, catalog.topicInInactiveGroup(), "Hidden group words.");
        addSection(t, catalog.visibleTopic(), "Visible words.");
        addAchievement(t, catalog.visibleAchievement());
        addAchievement(t, catalog.inactiveAchievement());
        testimonialRepository.saveAndFlush(t);

        mockMvc.perform(get("/api/gallery/testimonials/{id}", t.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[*].answer", contains("Visible words.")))
                .andExpect(jsonPath("$.achievements", contains(CatalogVisibilityFixture.VISIBLE_ACHIEVEMENT_SLUG)));
    }

    // -- page: GET /gallery --

    @Test
    void list_groupChipSubmitsGroupIds_standaloneChipSubmitsTopicIds() throws Exception {
        saved("web-chips-group@example.com", catalog.visibleTopic(), "Group words.");
        saved("web-chips-general@example.com", general(), "General words.");

        List<String> chips = chipInputs(html(get("/gallery")));

        assertThat(attribute(chipWithValue(chips, catalog.activeGroup().getId()), "name")).contains("groupIds");
        // The standalone "general" chip: the only chip whose value is general's id under topicIds.
        assertThat(chips)
                .filteredOn(tag -> attribute(tag, "name").orElse("").equals("topicIds"))
                .extracting(tag -> attribute(tag, "value").orElseThrow())
                .contains(String.valueOf(general().getId()));
        assertThat(chips)
                .extracting(tag -> attribute(tag, "name").orElseThrow())
                .containsOnly("groupIds", "topicIds");
    }

    @Test
    void list_sharedGroupAndTopicId_checksOnlyTheChipOfTheParameterSent() throws Exception {
        CatalogVisibilityFixture.IdClash clash =
                CatalogVisibilityFixture.forceIdClash(topicGroupRepository, topicRepository);
        saved("web-clash-topic@example.com", clash.standalone(), "Standalone clash words.");
        saved("web-clash-group@example.com", clash.member(), "Member clash words.");
        String sharedId = String.valueOf(clash.sharedId());

        String byTopic = html(get("/gallery").param("topicIds", sharedId));
        String byGroup = html(get("/gallery").param("groupIds", sharedId));

        assertThat(byTopic).contains("Standalone clash words.").doesNotContain("Member clash words.");
        assertThat(chipInputs(byTopic))
                .filteredOn(tag -> attribute(tag, "checked").isPresent())
                .extracting(tag -> attribute(tag, "name").orElseThrow())
                .containsExactly("topicIds");
        assertThat(byGroup).contains("Member clash words.").doesNotContain("Standalone clash words.");
        assertThat(chipInputs(byGroup))
                .filteredOn(tag -> attribute(tag, "checked").isPresent())
                .extracting(tag -> attribute(tag, "name").orElseThrow())
                .containsExactly("groupIds");
    }

    @Test
    void list_paginationLinks_keepBothGroupIdsAndTopicIds() throws Exception {
        for (int i = 0; i < 21; i++) {
            saved("web-page-" + i + "@example.com", catalog.visibleTopic(), "Paged words " + i);
        }
        Long groupId = catalog.activeGroup().getId();
        Long generalId = general().getId();

        String html = html(get("/gallery")
                .param("groupIds", String.valueOf(groupId))
                .param("topicIds", String.valueOf(generalId)));

        String next = openingTags(html, "a").stream()
                .filter(tag -> attribute(tag, "class").orElse("").contains("gallery-pagination-link"))
                .findFirst()
                .orElseThrow();
        assertThat(attribute(next, "href").orElseThrow())
                .contains("page=1")
                .contains("groupIds=" + groupId)
                .contains("topicIds=" + generalId);
    }

    // -- page: GET /gallery/{id} --

    @Test
    void article_leavesOutHiddenSectionsTheirGroupHeadingAndHiddenAchievements() throws Exception {
        Testimonial t = approved("web-article@example.com");
        addSection(t, catalog.inactiveTopic(), "Inactive words.");
        addSection(t, catalog.topicInInactiveGroup(), "Hidden group words.");
        addSection(t, catalog.visibleTopic(), "Visible words.");
        addAchievement(t, catalog.visibleAchievement());
        addAchievement(t, catalog.inactiveAchievement());
        testimonialRepository.saveAndFlush(t);

        String html = html(get("/gallery/{id}", t.getId()));

        assertThat(html)
                .contains("Visible words.", "Visible group", "Visible topic", "Vis visible feat")
                .doesNotContain(
                        "Inactive words.",
                        "Inactive topic",
                        "Hidden group words.",
                        "Hidden group",
                        "Topic in hidden group",
                        "Vis inactive feat");
    }

    @Test
    void article_withEverySectionHidden_stillRenders() throws Exception {
        Testimonial t = approved("web-article-all-hidden@example.com");
        addSection(t, catalog.inactiveTopic(), "Inactive words.");
        addSection(t, catalog.topicInInactiveGroup(), "Hidden group words.");
        testimonialRepository.saveAndFlush(t);

        String html = mockMvc.perform(get("/gallery/{id}", t.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("gallery/detail"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("David J.").doesNotContain("Inactive words.", "Hidden group words.");
    }

}
