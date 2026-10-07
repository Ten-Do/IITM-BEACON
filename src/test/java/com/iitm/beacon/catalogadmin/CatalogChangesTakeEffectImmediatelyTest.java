package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.Csrf;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NFR-CATALOG-CONFIGURABILITY: a catalog change takes effect immediately —
 * the very next read of the gallery's topic filter ({@code GET
 * /api/gallery/topics}), the submission form ({@code GET /submissions/form})
 * or the achievement checklist ({@code GET /api/submissions/achievements})
 * after an admin's add, rename, reorder, (de/re)activation, re-parenting or
 * delete through {@code /api/catalog/**} shows it, as does an affected
 * gallery article. Each test reads first, so a later read can only differ
 * because of the change in between.
 *
 * <p>Not {@code @Transactional}: every request commits and runs in its own
 * persistence context, as in production, so no shared first-level cache
 * can stand in for a fresh read. Everything a test creates is deleted again
 * afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CatalogChangesTakeEffectImmediatelyTest {

    private static final String READER = "catalog-reader@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private CatalogFixtures fixtures;
    private String unique;
    private final List<Long> testimonialIds = new ArrayList<>();
    private final List<Long> topicIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private final List<Long> achievementIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new CatalogFixtures(
                topicGroupRepository, topicRepository, achievementRepository, testimonialRepository,
                countryRepository);
        unique = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void deleteEverythingCreated() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            testimonialIds.stream()
                    .filter(testimonialRepository::existsById)
                    .forEach(testimonialRepository::deleteById);
            topicIds.stream().filter(topicRepository::existsById).forEach(topicRepository::deleteById);
            groupIds.stream().filter(topicGroupRepository::existsById).forEach(topicGroupRepository::deleteById);
            achievementIds.stream()
                    .filter(achievementRepository::existsById)
                    .forEach(achievementRepository::deleteById);
        });
    }

    // -- fixtures (committed) --

    private TopicGroup group(String label, int displayOrder) {
        TopicGroup group = fixtures.group(label + " " + unique, displayOrder, true);
        groupIds.add(group.getId());
        return group;
    }

    private Topic topic(TopicGroup group, String label, int displayOrder) {
        Topic topic = fixtures.topic(group, label + " " + unique, displayOrder, true);
        topicIds.add(topic.getId());
        return topic;
    }

    private Achievement achievement(String label, int displayOrder) {
        Achievement achievement = fixtures.achievement(label + " " + unique, displayOrder, true);
        achievementIds.add(achievement.getId());
        return achievement;
    }

    /** An approved testimonial with a section ("About <label>") per topic, so each shows in the gallery filter. */
    private Testimonial approvedUsing(Topic... topics) {
        List<Topic> sections = new ArrayList<>(List.of(topics));
        sections.add(0, fixtures.general());
        Testimonial testimonial = fixtures.testimonial(TestimonialStatus.APPROVED, sections);
        testimonialIds.add(testimonial.getId());
        return testimonial;
    }

    // -- reads --

    private List<Map<String, Object>> galleryTopics() throws Exception {
        String json = mockMvc.perform(get("/api/gallery/topics"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    /** The gallery filter's top-level labels (groups and standalone topics), in order. */
    private List<String> galleryTopLevelLabels() throws Exception {
        return galleryTopics().stream().map(entry -> (String) entry.get("label")).toList();
    }

    /** The labels of the subtopics the gallery filter nests under the group labelled {@code groupLabel}. */
    @SuppressWarnings("unchecked")
    private List<String> gallerySubtopicLabels(String groupLabel) throws Exception {
        return galleryTopics().stream()
                .filter(entry -> "GROUP".equals(entry.get("kind")) && groupLabel.equals(entry.get("label")))
                .flatMap(entry -> ((List<Map<String, Object>>) entry.get("subtopics")).stream())
                .map(subtopic -> (String) subtopic.get("label"))
                .toList();
    }

    private String formHtml() throws Exception {
        return mockMvc.perform(get("/submissions/form").with(authentication(visitor())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Whether the form shows {@code label} as an element's whole text (a
     * topic chip or block title, an achievement checkbox) — not merely inside
     * a longer text such as a topic's guiding prompt.
     */
    private static boolean formShows(String html, String label) {
        return html.contains(">" + label + "<");
    }

    private List<String> achievementLabels() throws Exception {
        String json = mockMvc.perform(get("/api/submissions/achievements"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> achievements = objectMapper.readValue(json, new TypeReference<>() {
        });
        return achievements.stream().map(a -> (String) a.get("label")).toList();
    }

    private String articleJson(Testimonial testimonial) throws Exception {
        return mockMvc.perform(get("/api/gallery/testimonials/{id}", testimonial.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // -- admin writes --

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication visitor() {
        return new UsernamePasswordAuthenticationToken(
                READER, null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(authentication(admin())).with(Csrf.csrfHeader());
    }

    private void patchAsAdmin(String path, Long id, String json) throws Exception {
        mockMvc.perform(asAdmin(patch(path, id)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private void deleteAsAdmin(String path, Long id) throws Exception {
        mockMvc.perform(asAdmin(delete(path, id))).andExpect(status().isNoContent());
    }

    private Long createAsAdmin(String path, String json) throws Exception {
        String body = mockMvc.perform(asAdmin(post(path)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) objectMapper.readValue(body, Map.class).get("id")).longValue();
    }

    // -- topics --

    @Test
    void renamingAStandaloneTopic_theVeryNextFilterAndFormReadsShowTheNewLabel() throws Exception {
        Topic topic = topic(null, "Before", 950);
        approvedUsing(topic);
        assertThat(galleryTopLevelLabels()).contains(topic.getLabel());
        assertThat(formShows(formHtml(), topic.getLabel())).isTrue();

        patchAsAdmin("/api/catalog/topics/{id}", topic.getId(), "{\"label\":\"After " + unique + "\"}");

        assertThat(galleryTopLevelLabels()).contains("After " + unique).doesNotContain(topic.getLabel());
        String form = formHtml();
        assertThat(formShows(form, "After " + unique)).isTrue();
        assertThat(formShows(form, topic.getLabel())).isFalse();
    }

    @Test
    void reorderingTopics_theVeryNextFilterAndFormReadsShowTheNewOrder() throws Exception {
        Topic first = topic(null, "First", 950);
        Topic second = topic(null, "Second", 951);
        approvedUsing(first, second);
        assertThat(galleryTopLevelLabels()).containsSubsequence(first.getLabel(), second.getLabel());
        String before = formHtml();
        assertThat(before.indexOf(">" + first.getLabel() + "<")).isNotNegative()
                .isLessThan(before.indexOf(">" + second.getLabel() + "<"));

        patchAsAdmin("/api/catalog/topics/{id}", second.getId(), "{\"displayOrder\":949}");

        assertThat(galleryTopLevelLabels()).containsSubsequence(second.getLabel(), first.getLabel());
        String after = formHtml();
        assertThat(after.indexOf(">" + second.getLabel() + "<")).isNotNegative()
                .isLessThan(after.indexOf(">" + first.getLabel() + "<"));
    }

    @Test
    void deactivatingATopic_theVeryNextReadsDropIt_andReactivatingBringsItBack() throws Exception {
        Topic topic = topic(null, "Seasonal", 950);
        Testimonial testimonial = approvedUsing(topic);
        assertThat(galleryTopLevelLabels()).contains(topic.getLabel());
        assertThat(formShows(formHtml(), topic.getLabel())).isTrue();
        assertThat(articleJson(testimonial)).contains("About " + topic.getLabel());

        patchAsAdmin("/api/catalog/topics/{id}", topic.getId(), "{\"active\":false}");

        assertThat(galleryTopLevelLabels()).doesNotContain(topic.getLabel());
        assertThat(formHtml()).doesNotContain(topic.getLabel());
        assertThat(articleJson(testimonial)).doesNotContain("About " + topic.getLabel());

        patchAsAdmin("/api/catalog/topics/{id}", topic.getId(), "{\"active\":true}");

        assertThat(galleryTopLevelLabels()).contains(topic.getLabel());
        assertThat(formShows(formHtml(), topic.getLabel())).isTrue();
        assertThat(articleJson(testimonial)).contains("About " + topic.getLabel());
    }

    @Test
    void deletingATopic_theVeryNextReadsDropIt() throws Exception {
        Topic topic = topic(null, "Doomed", 950);
        Testimonial testimonial = approvedUsing(topic);
        assertThat(galleryTopLevelLabels()).contains(topic.getLabel());
        assertThat(formShows(formHtml(), topic.getLabel())).isTrue();
        assertThat(articleJson(testimonial)).contains("About " + topic.getLabel());

        deleteAsAdmin("/api/catalog/topics/{id}", topic.getId());

        assertThat(galleryTopLevelLabels()).doesNotContain(topic.getLabel());
        assertThat(formHtml()).doesNotContain(topic.getLabel());
        assertThat(articleJson(testimonial)).doesNotContain("About " + topic.getLabel());
    }

    @Test
    void reparentingATopicIntoAGroup_theVeryNextFilterReadNestsIt() throws Exception {
        TopicGroup group = group("Host group", 950);
        Topic member = topic(group, "Member", 1);
        Topic standalone = topic(null, "Wanderer", 951);
        approvedUsing(member, standalone);
        assertThat(galleryTopLevelLabels()).contains(standalone.getLabel(), group.getLabel());
        assertThat(gallerySubtopicLabels(group.getLabel())).containsExactly(member.getLabel());

        patchAsAdmin("/api/catalog/topics/{id}", standalone.getId(), "{\"topicGroupId\":" + group.getId() + "}");

        assertThat(galleryTopLevelLabels()).doesNotContain(standalone.getLabel());
        assertThat(gallerySubtopicLabels(group.getLabel())).containsExactlyInAnyOrder(
                member.getLabel(), standalone.getLabel());
    }

    @Test
    void addingATopic_theVeryNextFormOffersIt() throws Exception {
        String label = "Brand new " + unique;
        assertThat(formHtml()).doesNotContain(label);

        topicIds.add(createAsAdmin("/api/catalog/topics", "{\"slug\":\"new_" + unique.replace("-", "")
                + "\",\"label\":\"" + label + "\",\"guidingPrompt\":\"Tell us.\",\"displayOrder\":950}"));

        assertThat(formShows(formHtml(), label)).isTrue();
    }

    // -- topic groups --

    @Test
    void renamingThenDeactivatingATopicGroup_theVeryNextReadsShowEachChange() throws Exception {
        TopicGroup group = group("Old group", 950);
        Topic member = topic(group, "Grouped", 1);
        approvedUsing(member);
        assertThat(galleryTopLevelLabels()).contains(group.getLabel());
        String before = formHtml();
        assertThat(formShows(before, group.getLabel())).isTrue();
        assertThat(before).contains(member.getSlug());

        patchAsAdmin("/api/catalog/topic-groups/{id}", group.getId(), "{\"label\":\"New group " + unique + "\"}");

        assertThat(galleryTopLevelLabels()).contains("New group " + unique).doesNotContain(group.getLabel());
        String form = formHtml();
        assertThat(formShows(form, "New group " + unique)).isTrue();
        assertThat(formShows(form, group.getLabel())).isFalse();

        patchAsAdmin("/api/catalog/topic-groups/{id}", group.getId(), "{\"active\":false}");

        assertThat(galleryTopLevelLabels()).doesNotContain("New group " + unique);
        assertThat(formHtml()).doesNotContain("New group " + unique).doesNotContain(member.getSlug());
    }

    @Test
    void deletingATopicGroup_theVeryNextReadsDropItAndItsTopics() throws Exception {
        TopicGroup group = group("Short-lived group", 950);
        Topic member = topic(group, "Short-lived member", 1);
        approvedUsing(member);
        assertThat(galleryTopLevelLabels()).contains(group.getLabel());
        String before = formHtml();
        assertThat(formShows(before, group.getLabel())).isTrue();
        assertThat(before).contains(member.getSlug());

        deleteAsAdmin("/api/catalog/topic-groups/{id}", group.getId());

        assertThat(galleryTopLevelLabels()).doesNotContain(group.getLabel());
        assertThat(formHtml()).doesNotContain(group.getLabel()).doesNotContain(member.getSlug());
    }

    // -- achievements --

    @Test
    void achievementChanges_eachShowOnTheVeryNextChecklistRead() throws Exception {
        Achievement fixed = achievement("Anchor feat", 950);
        String label = "Fresh feat " + unique;
        assertThat(achievementLabels()).doesNotContain(label);

        Long id = createAsAdmin("/api/catalog/achievements", "{\"slug\":\"feat_" + unique.replace("-", "")
                + "\",\"label\":\"" + label + "\",\"displayOrder\":951}");
        achievementIds.add(id);
        assertThat(achievementLabels()).containsSubsequence(fixed.getLabel(), label);
        assertThat(formShows(formHtml(), label)).isTrue();

        patchAsAdmin("/api/catalog/achievements/{id}", id, "{\"label\":\"Renamed feat " + unique + "\"}");
        assertThat(achievementLabels()).contains("Renamed feat " + unique).doesNotContain(label);
        String form = formHtml();
        assertThat(formShows(form, "Renamed feat " + unique)).isTrue();
        assertThat(formShows(form, label)).isFalse();

        patchAsAdmin("/api/catalog/achievements/{id}", id, "{\"displayOrder\":949}");
        assertThat(achievementLabels()).containsSubsequence("Renamed feat " + unique, fixed.getLabel());

        patchAsAdmin("/api/catalog/achievements/{id}", id, "{\"active\":false}");
        assertThat(achievementLabels()).doesNotContain("Renamed feat " + unique);
        assertThat(formHtml()).doesNotContain("Renamed feat " + unique);

        patchAsAdmin("/api/catalog/achievements/{id}", id, "{\"active\":true}");
        assertThat(achievementLabels()).contains("Renamed feat " + unique);

        deleteAsAdmin("/api/catalog/achievements/{id}", id);
        assertThat(achievementLabels()).doesNotContain("Renamed feat " + unique).contains(fixed.getLabel());
    }
}
