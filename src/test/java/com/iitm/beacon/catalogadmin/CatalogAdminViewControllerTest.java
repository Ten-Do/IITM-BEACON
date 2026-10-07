package com.iitm.beacon.catalogadmin;

import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.achievement.TestimonialAchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link CatalogAdminViewController}'s admin pages under
 * {@code /catalog/**} (decision 28, docs/architecture.md §17): the two list
 * pages, the create/edit form pages (re-rendered with an error per field on
 * a broken rule or a 409), the Active toggle, and the delete confirmation —
 * every failure answered as a page or a redirect with a flash message, never
 * as JSON. Access without a session is {@code SecurityConfigLoginRedirectTest}'s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class CatalogAdminViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TestimonialAchievementRepository testimonialAchievementRepository;

    @Autowired
    private CountryRepository countryRepository;

    private CatalogFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new CatalogFixtures(
                topicGroupRepository, topicRepository, achievementRepository, testimonialRepository,
                countryRepository);
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication visitor() {
        return new UsernamePasswordAuthenticationToken(
                "visitor@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(authentication(admin())));
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return asAdmin(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** The {@code <tr>} of the table row that mentions {@code text}. */
    private static String rowWith(String html, String text) {
        return elements(html, "tr").stream()
                .filter(row -> row.contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row with " + text));
    }

    private static List<String> formActions(String html) {
        return openingTags(html, "form").stream().map(tag -> attribute(tag, "action").orElse("")).toList();
    }

    /** The text of each field error, as rendered next to its input. */
    private static List<String> fieldErrors(String html) {
        return elements(html, "span").stream()
                .filter(span -> span.contains("catalog-field-error"))
                .map(span -> span.replaceAll("<[^>]+>", "").strip())
                .toList();
    }

    // -- access --

    @Test
    void visitorSession_getsA403() throws Exception {
        mockMvc.perform(get("/catalog/topics").with(authentication(visitor()))).andExpect(status().isForbidden());
        mockMvc.perform(post("/catalog/topics/1/delete").with(authentication(visitor())))
                .andExpect(status().isForbidden());
        assertThat(topicRepository.findById(1L)).isPresent();
    }

    // -- list pages --

    @Test
    void topicsPage_listsGroupsAndTopics_inactiveOnesToo() throws Exception {
        TopicGroup inactiveGroup = fixtures.group("Retired group", 0, false);
        Topic grouped = fixtures.topic(inactiveGroup, "Grouped topic", 0, false);

        String html = html(get("/catalog/topics"));

        String groupRow = rowWith(html, "Retired group");
        assertThat(groupRow).contains("Inactive");
        assertThat(formActions(groupRow)).contains("/catalog/topic-groups/" + inactiveGroup.getId() + "/active");
        assertThat(groupRow).contains("href=\"/catalog/topic-groups/" + inactiveGroup.getId() + "\"");
        assertThat(groupRow).contains("href=\"/catalog/topic-groups/" + inactiveGroup.getId() + "/delete\"");

        String topicRow = rowWith(html, grouped.getSlug());
        assertThat(topicRow).contains("Grouped topic").contains("Retired group").contains("Inactive");
        assertThat(topicRow).contains("href=\"/catalog/topics/" + grouped.getId() + "\"");
        assertThat(topicRow).contains("href=\"/catalog/topics/" + grouped.getId() + "/delete\"");

        assertThat(html).contains("href=\"/catalog/topic-groups/new\"").contains("href=\"/catalog/topics/new\"");
    }

    @Test
    void topicsPage_toggleSendsTheOppositeState() throws Exception {
        fixtures.group("On group", 0, true);
        Topic inactive = fixtures.topic(null, "Off topic", 0, false);

        String html = html(get("/catalog/topics"));

        assertThat(rowWith(html, "On group")).contains("name=\"active\" value=\"false\"");
        assertThat(rowWith(html, inactive.getSlug())).contains("name=\"active\" value=\"true\"");
    }

    @Test
    void topicsPage_generalIsStandalone_andCanNeitherBeToggledNorDeleted() throws Exception {
        Topic general = fixtures.general();

        String row = rowWith(html(get("/catalog/topics")), ">general<");

        assertThat(row).contains("Standalone").contains("Active");
        assertThat(formActions(row)).doesNotContain("/catalog/topics/" + general.getId() + "/active");
        assertThat(row).doesNotContain("/catalog/topics/" + general.getId() + "/delete");
        assertThat(row).contains("href=\"/catalog/topics/" + general.getId() + "\"");
    }

    @Test
    void topicsPage_anActiveTopicInAnInactiveGroup_isMarkedHidden() throws Exception {
        TopicGroup off = fixtures.group("Switched off", 0, false);
        Topic topic = fixtures.topic(off, "Still active", 0, true);

        assertThat(rowWith(html(get("/catalog/topics")), topic.getSlug())).contains("Hidden: its group is inactive");
    }

    @Test
    void topicsPage_escapesCatalogText() throws Exception {
        fixtures.group("<script>alert('g')</script>", 0, true);

        String html = html(get("/catalog/topics"));

        assertThat(html).doesNotContain("<script>alert('g')</script>").contains("&lt;script&gt;");
    }

    @Test
    void topicsPage_showsFlashMessages() throws Exception {
        String html = asAdmin(get("/catalog/topics").flashAttr("notice", "Saved it.").flashAttr("error", "Broke it."))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Saved it.").contains("Broke it.");
    }

    @Test
    void adminHeader_linksQueueCatalogAndAchievements_markingTheCurrentPage() throws Exception {
        String topics = html(get("/catalog/topics"));
        String achievements = html(get("/catalog/achievements"));

        assertThat(openingTags(topics, "a"))
                .anyMatch(a -> a.contains("href=\"/catalog/topics\"") && a.contains("active"));
        assertThat(openingTags(topics, "a")).anyMatch(a -> a.contains("href=\"/catalog/achievements\"")
                && !a.contains("active"));
        assertThat(openingTags(topics, "a")).anyMatch(a -> a.contains("href=\"/moderation/queue\""));
        assertThat(openingTags(achievements, "a")).anyMatch(a -> a.contains("href=\"/catalog/achievements\"")
                && a.contains("active"));
    }

    @Test
    void achievementsPage_listsEveryAchievement_inactiveOnesToo() throws Exception {
        Achievement inactive = fixtures.achievement("Retired achievement", 0, false);

        String html = html(get("/catalog/achievements"));

        String row = rowWith(html, inactive.getSlug());
        assertThat(row).contains("Retired achievement").contains("Inactive");
        assertThat(formActions(row)).contains("/catalog/achievements/" + inactive.getId() + "/active");
        assertThat(row).contains("href=\"/catalog/achievements/" + inactive.getId() + "/delete\"");
        assertThat(html).contains("href=\"/catalog/achievements/new\"");
        assertThat(elements(html, "tr")).hasSize((int) achievementRepository.count() + 1);
    }

    @Test
    void listPages_labelEveryDataCell_forThePhoneCardLayout() throws Exception {
        fixtures.group("G", 0, false);
        fixtures.achievement("A", 0, false);

        for (String page : List.of("/catalog/topics", "/catalog/achievements")) {
            assertThat(openingTags(html(get(page)), "td"))
                    .as(page)
                    .isNotEmpty()
                    .allSatisfy(td -> assertThat(attribute(td, "data-label").isPresent()
                            || td.contains("catalog-row-actions")).isTrue());
        }
    }

    @Test
    void listPages_withNothingToList_sayWhatToDo() throws Exception {
        // Rolled back with the test: every group gone (its topics made standalone), every achievement gone.
        topicRepository.findAll().forEach(topic -> topic.setTopicGroup(null));
        topicRepository.flush();
        topicGroupRepository.deleteAll();
        testimonialAchievementRepository.deleteAll();
        achievementRepository.deleteAll();
        achievementRepository.flush();

        String topics = html(get("/catalog/topics"));
        String achievements = html(get("/catalog/achievements"));

        assertThat(topics).contains("No topic groups yet.");
        assertThat(rowWith(topics, ">general<")).contains("Standalone");
        assertThat(achievements).contains("No achievements yet.");
    }

    // -- topic group form --

    @Test
    void newTopicGroupForm_rendersAnEmptyForm() throws Exception {
        asAdmin(get("/catalog/topic-groups/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("catalogadmin/topic-group-form"));

        assertThat(formActions(html(get("/catalog/topic-groups/new")))).contains("/catalog/topic-groups/new");
    }

    @Test
    void createTopicGroup_valid_redirectsToTheListWithANotice() throws Exception {
        asAdmin(post("/catalog/topic-groups/new").param("label", "  Sports  ").param("displayOrder", "9999"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("notice"));

        assertThat(topicGroupRepository.findAll()).anyMatch(g -> g.getLabel().equals("Sports")
                && g.getDisplayOrder() == 9999 && g.isActive());
    }

    @Test
    void createTopicGroup_invalid_reRendersWithAnErrorPerField_andSavesNothing() throws Exception {
        long before = topicGroupRepository.count();

        String html = asAdmin(post("/catalog/topic-groups/new").param("label", "   ").param("displayOrder", "10000"))
                .andExpect(status().isOk())
                .andExpect(view().name("catalogadmin/topic-group-form"))
                .andExpect(model().attributeHasFieldErrors("form", "label", "displayOrder"))
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldErrors(html)).containsExactlyInAnyOrder("must not be blank", "must be between 0 and 9999");
        assertThat(html).contains("value=\"10000\"");
        assertThat(topicGroupRepository.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"first", "1.5", "99999999999"})
    void createTopicGroup_nonNumericOrder_showsAReadableMessage(String order) throws Exception {
        String html = asAdmin(post("/catalog/topic-groups/new").param("label", "X").param("displayOrder", order))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "displayOrder"))
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldErrors(html)).containsExactly("must be a whole number");
        assertThat(html).doesNotContain("NumberFormatException").doesNotContain("java.lang");
    }

    @Test
    void createTopicGroup_labelOf120CharactersWithSurroundingSpaces_isAccepted_121IsNot() throws Exception {
        String label120 = "g".repeat(120);
        asAdmin(post("/catalog/topic-groups/new").param("label", "  " + label120 + "  ").param("displayOrder", "1"))
                .andExpect(redirectedUrl("/catalog/topics"));
        assertThat(topicGroupRepository.findAll()).anyMatch(g -> g.getLabel().equals(label120));

        asAdmin(post("/catalog/topic-groups/new").param("label", label120 + "g").param("displayOrder", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "label"));
    }

    @Test
    void editTopicGroupForm_isPrefilled() throws Exception {
        TopicGroup group = fixtures.group("Prefilled", 42, false);

        String html = html(get("/catalog/topic-groups/" + group.getId()));

        assertThat(html).contains("value=\"Prefilled\"").contains("value=\"42\"");
        assertThat(formActions(html)).contains("/catalog/topic-groups/" + group.getId());
    }

    @Test
    void editTopicGroup_valid_savesAndKeepsTheActiveFlag() throws Exception {
        TopicGroup group = fixtures.group("Old", 1, false);

        asAdmin(post("/catalog/topic-groups/" + group.getId()).param("label", "New").param("displayOrder", "2"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("notice"));

        TopicGroup stored = topicGroupRepository.findById(group.getId()).orElseThrow();
        assertThat(stored.getLabel()).isEqualTo("New");
        assertThat(stored.getDisplayOrder()).isEqualTo(2);
        assertThat(stored.isActive()).isFalse();
    }

    @Test
    void editTopicGroup_invalid_reRendersTheEditForm() throws Exception {
        TopicGroup group = fixtures.group("Keep", 1, true);

        String html = asAdmin(post("/catalog/topic-groups/" + group.getId())
                        .param("label", "")
                        .param("displayOrder", "-1"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "label", "displayOrder"))
                .andReturn().getResponse().getContentAsString();

        assertThat(formActions(html)).contains("/catalog/topic-groups/" + group.getId());
        assertThat(topicGroupRepository.findById(group.getId()).orElseThrow().getLabel()).isEqualTo("Keep");
    }

    @Test
    void unknownTopicGroup_editAndSave_redirectToTheListWithAnError() throws Exception {
        asAdmin(get("/catalog/topic-groups/987654"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(post("/catalog/topic-groups/987654").param("label", "X").param("displayOrder", "1"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
    }

    // -- topic form --

    @Test
    void newTopicForm_offersStandaloneAndEveryGroup_inactiveOnesMarked() throws Exception {
        TopicGroup off = fixtures.group("Dormant", 1, false);

        String html = html(get("/catalog/topics/new"));

        List<String> options = elements(html, "option");
        assertThat(options.get(0)).contains("value=\"\"").contains("Standalone");
        assertThat(options)
                .anyMatch(o -> o.contains("value=\"" + off.getId() + "\"") && o.contains("Dormant (inactive)"));
        assertThat(options).anyMatch(o -> o.contains("value=\"1\"") && o.contains("Academics"));
        assertThat(formActions(html)).contains("/catalog/topics/new");
    }

    @Test
    void createTopic_inAGroup_redirectsAndSaves() throws Exception {
        TopicGroup group = fixtures.group("G", 1, true);

        asAdmin(post("/catalog/topics/new")
                        .param("slug", " page_topic_x ")
                        .param("label", "Page topic")
                        .param("guidingPrompt", "Why?")
                        .param("displayOrder", "0")
                        .param("topicGroupId", group.getId().toString()))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("notice"));

        Topic stored = topicRepository.findBySlug("page_topic_x").orElseThrow();
        assertThat(stored.getTopicGroup().getId()).isEqualTo(group.getId());
    }

    @Test
    void createTopic_standalone_whenTheEmptyOptionIsChosen() throws Exception {
        asAdmin(post("/catalog/topics/new")
                        .param("slug", "page_standalone_x")
                        .param("label", "L")
                        .param("guidingPrompt", "P")
                        .param("displayOrder", "0")
                        .param("topicGroupId", ""))
                .andExpect(redirectedUrl("/catalog/topics"));

        assertThat(topicRepository.findBySlug("page_standalone_x").orElseThrow().getTopicGroup()).isNull();
    }

    @Test
    void createTopic_duplicateSlug_reRendersWithTheErrorAtTheSlug() throws Exception {
        String html = asAdmin(post("/catalog/topics/new")
                        .param("slug", "academics_teaching")
                        .param("label", "Dup")
                        .param("guidingPrompt", "P")
                        .param("displayOrder", "0"))
                .andExpect(status().isOk())
                .andExpect(view().name("catalogadmin/topic-form"))
                .andExpect(model().attributeHasFieldErrors("form", "slug"))
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldErrors(html)).singleElement().asString().contains("already used");
        assertThat(html).contains("value=\"Dup\"");
    }

    @Test
    void createTopic_unknownGroup_reRendersWithTheErrorAtTheGroup() throws Exception {
        asAdmin(post("/catalog/topics/new")
                        .param("slug", "page_orphan_x")
                        .param("label", "L")
                        .param("guidingPrompt", "P")
                        .param("displayOrder", "0")
                        .param("topicGroupId", "987654"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "topicGroupId"));

        assertThat(topicRepository.findBySlug("page_orphan_x")).isEmpty();
    }

    @Test
    void createTopic_everyFieldBroken_reportsEveryField() throws Exception {
        String html = asAdmin(post("/catalog/topics/new")
                        .param("slug", "Bad Slug")
                        .param("label", " ")
                        .param("guidingPrompt", "p".repeat(501))
                        .param("displayOrder", ""))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "slug", "label", "guidingPrompt", "displayOrder"))
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldErrors(html)).containsExactlyInAnyOrder(
                "may contain only lowercase letters a-z, digits and underscores",
                "must not be blank",
                "must be at most 500 characters",
                "is required");
    }

    @Test
    void editTopic_movesItToStandalone() throws Exception {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(group, "T", 1, true);

        String form = html(get("/catalog/topics/" + topic.getId()));
        assertThat(elements(form, "option")).anyMatch(o -> o.contains("value=\"" + group.getId() + "\"")
                && o.contains("selected"));

        asAdmin(post("/catalog/topics/" + topic.getId())
                        .param("slug", topic.getSlug())
                        .param("label", "T")
                        .param("guidingPrompt", "P")
                        .param("displayOrder", "1")
                        .param("topicGroupId", ""))
                .andExpect(redirectedUrl("/catalog/topics"));

        assertThat(topicRepository.findById(topic.getId()).orElseThrow().getTopicGroup()).isNull();
    }

    @Test
    void editTopic_keepsItsActiveFlag() throws Exception {
        Topic topic = fixtures.topic(null, "Off", 1, false);

        asAdmin(post("/catalog/topics/" + topic.getId())
                        .param("slug", topic.getSlug())
                        .param("label", "Still off")
                        .param("guidingPrompt", "P")
                        .param("displayOrder", "1"))
                .andExpect(redirectedUrl("/catalog/topics"));

        Topic stored = topicRepository.findById(topic.getId()).orElseThrow();
        assertThat(stored.getLabel()).isEqualTo("Still off");
        assertThat(stored.isActive()).isFalse();
    }

    @Test
    void editGeneralForm_showsTheSlugAndGroupReadOnly() throws Exception {
        Topic general = fixtures.general();

        String html = html(get("/catalog/topics/" + general.getId()));

        assertThat(openingTags(html, "input")).anyMatch(i -> i.contains("name=\"slug\"") && i.contains("readonly"));
        assertThat(openingTags(html, "select")).isEmpty();
        assertThat(openingTags(html, "input")).anyMatch(i -> i.contains("type=\"hidden\"")
                && i.contains("name=\"topicGroupId\"") && i.contains("value=\"\""));
        assertThat(html).contains("Standalone");
    }

    @Test
    void editGeneral_labelPromptAndOrder_save() throws Exception {
        Topic general = fixtures.general();

        asAdmin(post("/catalog/topics/" + general.getId())
                        .param("slug", "general")
                        .param("label", "Anything else")
                        .param("guidingPrompt", "Add anything.")
                        .param("displayOrder", "99")
                        .param("topicGroupId", ""))
                .andExpect(redirectedUrl("/catalog/topics"));

        Topic stored = topicRepository.findById(general.getId()).orElseThrow();
        assertThat(stored.getLabel()).isEqualTo("Anything else");
        assertThat(stored.getDisplayOrder()).isEqualTo(99);
    }

    @Test
    void editGeneral_aForgedSlugOrGroup_reRendersWithAFormLevelError() throws Exception {
        Topic general = fixtures.general();
        TopicGroup group = fixtures.group("G", 1, true);

        String html = asAdmin(post("/catalog/topics/" + general.getId())
                        .param("slug", "general_two")
                        .param("label", "L")
                        .param("guidingPrompt", "P")
                        .param("displayOrder", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasErrors("form"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("The general topic&#39;s slug can&#39;t be changed.");

        asAdmin(post("/catalog/topics/" + general.getId())
                        .param("slug", "general")
                        .param("label", "L")
                        .param("guidingPrompt", "P")
                        .param("displayOrder", "1")
                        .param("topicGroupId", group.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasErrors("form"));

        assertThat(topicRepository.findBySlug("general").orElseThrow().getTopicGroup()).isNull();
    }

    @Test
    void unknownTopic_editAndSave_redirectToTheListWithAnError() throws Exception {
        asAdmin(get("/catalog/topics/987654"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(post("/catalog/topics/987654")
                        .param("slug", "x")
                        .param("label", "X")
                        .param("guidingPrompt", "P")
                        .param("displayOrder", "1"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
    }

    // -- achievement form --

    @Test
    void createAchievement_valid_andDuplicate() throws Exception {
        asAdmin(post("/catalog/achievements/new")
                        .param("slug", "page_ach_x")
                        .param("label", "A")
                        .param("displayOrder", "5"))
                .andExpect(redirectedUrl("/catalog/achievements"))
                .andExpect(flash().attributeExists("notice"));
        assertThat(achievementRepository.findBySlug("page_ach_x")).isPresent();

        asAdmin(post("/catalog/achievements/new")
                        .param("slug", "page_ach_x")
                        .param("label", "B")
                        .param("displayOrder", "5"))
                .andExpect(status().isOk())
                .andExpect(view().name("catalogadmin/achievement-form"))
                .andExpect(model().attributeHasFieldErrors("form", "slug"));
    }

    @Test
    void editAchievement_prefilledThenSaved_slugToItsOwnValueIsFine() throws Exception {
        Achievement a = fixtures.achievement("Old", 3, true);

        String html = html(get("/catalog/achievements/" + a.getId()));
        assertThat(html).contains("value=\"" + a.getSlug() + "\"").contains("value=\"Old\"").contains("value=\"3\"");

        asAdmin(post("/catalog/achievements/" + a.getId())
                        .param("slug", a.getSlug())
                        .param("label", "New")
                        .param("displayOrder", "4"))
                .andExpect(redirectedUrl("/catalog/achievements"));
        assertThat(achievementRepository.findById(a.getId()).orElseThrow().getLabel()).isEqualTo("New");
    }

    @Test
    void unknownAchievement_editAndSave_redirectToTheListWithAnError() throws Exception {
        asAdmin(get("/catalog/achievements/987654"))
                .andExpect(redirectedUrl("/catalog/achievements"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(post("/catalog/achievements/987654").param("slug", "x").param("label", "X").param("displayOrder", "1"))
                .andExpect(redirectedUrl("/catalog/achievements"))
                .andExpect(flash().attributeExists("error"));
    }

    // -- Active toggle --

    @Test
    void toggle_setsTheRequestedState_andReturnsToTheList() throws Exception {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(null, "T", 1, true);
        Achievement achievement = fixtures.achievement("A", 1, false);

        asAdmin(post("/catalog/topic-groups/" + group.getId() + "/active").param("active", "false"))
                .andExpect(redirectedUrl("/catalog/topics"));
        asAdmin(post("/catalog/topics/" + topic.getId() + "/active").param("active", "false"))
                .andExpect(redirectedUrl("/catalog/topics"));
        asAdmin(post("/catalog/achievements/" + achievement.getId() + "/active").param("active", "true"))
                .andExpect(redirectedUrl("/catalog/achievements"));

        assertThat(topicGroupRepository.findById(group.getId()).orElseThrow().isActive()).isFalse();
        assertThat(topicRepository.findById(topic.getId()).orElseThrow().isActive()).isFalse();
        assertThat(achievementRepository.findById(achievement.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void toggle_sentTwice_staysInTheRequestedState() throws Exception {
        // A double click must not flip it back.
        Topic topic = fixtures.topic(null, "T", 1, true);

        asAdmin(post("/catalog/topics/" + topic.getId() + "/active").param("active", "false"));
        asAdmin(post("/catalog/topics/" + topic.getId() + "/active").param("active", "false"));

        assertThat(topicRepository.findById(topic.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void toggle_unknownId_redirectsWithAnError() throws Exception {
        asAdmin(post("/catalog/topic-groups/987654/active").param("active", "false"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(post("/catalog/topics/987654/active").param("active", "false"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(post("/catalog/achievements/987654/active").param("active", "false"))
                .andExpect(redirectedUrl("/catalog/achievements"))
                .andExpect(flash().attributeExists("error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "maybe"})
    void toggle_withoutAUsableState_changesNothing(String value) throws Exception {
        Topic topic = fixtures.topic(null, "T", 1, true);

        asAdmin(post("/catalog/topics/" + topic.getId() + "/active").param("active", value))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(post("/catalog/topics/" + topic.getId() + "/active"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));

        assertThat(topicRepository.findById(topic.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void toggle_deactivatingGeneral_isRefusedWithAnError() throws Exception {
        Topic general = fixtures.general();

        asAdmin(post("/catalog/topics/" + general.getId() + "/active").param("active", "false"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attribute("error", "The general topic can't be deactivated."));

        assertThat(topicRepository.findById(general.getId()).orElseThrow().isActive()).isTrue();
    }

    // -- delete --

    @Test
    void deleteTopicPage_showsTheLabelAndTheAffectedTestimonials() throws Exception {
        Topic topic = fixtures.topic(null, "Doomed topic", 1, true);
        fixtures.testimonial(TestimonialStatus.APPROVED, List.of(topic));
        fixtures.testimonial(TestimonialStatus.PENDING, List.of(topic));

        String html = asAdmin(get("/catalog/topics/" + topic.getId() + "/delete"))
                .andExpect(status().isOk())
                .andExpect(view().name("catalogadmin/delete-confirm"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Doomed topic").contains("2 testimonials affected");
        assertThat(formActions(html)).contains("/catalog/topics/" + topic.getId() + "/delete");
        assertThat(html).contains("href=\"/catalog/topics\"");
        assertThat(topicRepository.findById(topic.getId())).isPresent();
    }

    @Test
    void deletePage_singularAndZeroCounts() throws Exception {
        Topic once = fixtures.topic(null, "Once", 1, true);
        fixtures.testimonial(TestimonialStatus.APPROVED, List.of(once));
        Achievement never = fixtures.achievement("Never ticked", 1, true);

        assertThat(html(get("/catalog/topics/" + once.getId() + "/delete"))).contains("1 testimonial affected");
        assertThat(html(get("/catalog/achievements/" + never.getId() + "/delete")))
                .contains("Never ticked")
                .contains("0 testimonials affected");
    }

    @Test
    void deleteGroupPage_saysHowManyTopicsGoWithIt() throws Exception {
        TopicGroup group = fixtures.group("Doomed group", 1, true);
        fixtures.topic(group, "A", 1, true);
        fixtures.topic(group, "B", 2, true);

        String html = html(get("/catalog/topic-groups/" + group.getId() + "/delete"));

        assertThat(html).contains("Doomed group").contains("its 2 topics").contains("0 testimonials affected");
        assertThat(formActions(html)).contains("/catalog/topic-groups/" + group.getId() + "/delete");
    }

    @Test
    void deletePage_unknownIdOrGeneral_redirectsWithAnError() throws Exception {
        asAdmin(get("/catalog/topics/987654/delete"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(get("/catalog/topic-groups/987654/delete"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(get("/catalog/achievements/987654/delete"))
                .andExpect(redirectedUrl("/catalog/achievements"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(get("/catalog/topics/" + fixtures.general().getId() + "/delete"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attribute("error", "The general topic can't be deleted."));
    }

    @Test
    void confirmDelete_deletesAndReturnsToTheListWithANotice() throws Exception {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic grouped = fixtures.topic(group, "In group", 1, true);
        Topic topic = fixtures.topic(null, "Standalone doomed", 1, true);
        Achievement achievement = fixtures.achievement("A", 1, true);
        var testimonial = fixtures.testimonial(
                TestimonialStatus.APPROVED, List.of(topic, fixtures.general()), List.of(), List.of(achievement));

        asAdmin(post("/catalog/topics/" + topic.getId() + "/delete"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("notice"));
        asAdmin(post("/catalog/topic-groups/" + group.getId() + "/delete"))
                .andExpect(redirectedUrl("/catalog/topics"));
        asAdmin(post("/catalog/achievements/" + achievement.getId() + "/delete"))
                .andExpect(redirectedUrl("/catalog/achievements"))
                .andExpect(flash().attributeExists("notice"));

        assertThat(topicRepository.findById(topic.getId())).isEmpty();
        assertThat(topicRepository.findById(grouped.getId())).isEmpty();
        assertThat(topicGroupRepository.findById(group.getId())).isEmpty();
        assertThat(achievementRepository.findById(achievement.getId())).isEmpty();
        var reloaded = testimonialRepository.findById(testimonial.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
        assertThat(reloaded.getSections()).hasSize(1);
    }

    @Test
    void confirmDelete_unknownIdOrGeneral_redirectsWithAnError() throws Exception {
        asAdmin(post("/catalog/topics/987654/delete"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(post("/catalog/topic-groups/987654/delete"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attributeExists("error"));
        asAdmin(post("/catalog/achievements/987654/delete"))
                .andExpect(redirectedUrl("/catalog/achievements"))
                .andExpect(flash().attributeExists("error"));
        Topic general = fixtures.general();
        asAdmin(post("/catalog/topics/" + general.getId() + "/delete"))
                .andExpect(redirectedUrl("/catalog/topics"))
                .andExpect(flash().attribute("error", "The general topic can't be deleted."));
        assertThat(topicRepository.findById(general.getId())).isPresent();
    }

    @Test
    void nonNumericIds_matchNoPage() throws Exception {
        asAdmin(get("/catalog/topics/abc")).andExpect(status().isNotFound());
        asAdmin(post("/catalog/achievements/abc/delete")).andExpect(status().isNotFound());
    }

    @Test
    void reParentingIntoAnInactiveGroup_isAllowedFromTheForm() throws Exception {
        TopicGroup off = fixtures.group("Off", 1, false);
        Topic topic = fixtures.topic(null, "T", 1, true);

        asAdmin(post("/catalog/topics/" + topic.getId())
                        .param("slug", topic.getSlug())
                        .param("label", "T")
                        .param("guidingPrompt", "P")
                        .param("displayOrder", "1")
                        .param("topicGroupId", off.getId().toString()))
                .andExpect(redirectedUrl("/catalog/topics"));

        assertThat(Optional.ofNullable(topicRepository.findById(topic.getId()).orElseThrow().getTopicGroup())
                .map(TopicGroup::getId)).contains(off.getId());
    }
}
