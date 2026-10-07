package com.iitm.beacon.catalogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link CatalogAdminController} — the {@code
 * /api/catalog/**} contract in api-spec.yaml (decision 28): status codes,
 * response shapes, PATCH semantics (incl. {@code topicGroupId} absent vs
 * explicit {@code null}), and the admin-only access enforced by {@code
 * SecurityConfig}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class CatalogAdminControllerTest {

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

    private ResultActions asAdmin(MockHttpServletRequestBuilder request, String json) throws Exception {
        return asAdmin(request.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // -- security --

    static Stream<MockHttpServletRequestBuilder> everyEndpoint() {
        return Stream.of(
                get("/api/catalog/topic-groups"),
                post("/api/catalog/topic-groups").contentType(MediaType.APPLICATION_JSON).content("{}"),
                patch("/api/catalog/topic-groups/1").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/catalog/topic-groups/1"),
                get("/api/catalog/topics"),
                post("/api/catalog/topics").contentType(MediaType.APPLICATION_JSON).content("{}"),
                patch("/api/catalog/topics/1").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/catalog/topics/1"),
                get("/api/catalog/achievements"),
                post("/api/catalog/achievements").contentType(MediaType.APPLICATION_JSON).content("{}"),
                patch("/api/catalog/achievements/1").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/catalog/achievements/1"));
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void withoutASession_isAJson401(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void withAVisitorSession_isAJson403(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.with(authentication(visitor())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // -- topic groups --

    @Test
    void listTopicGroups_returnsAllIncludingInactive_withExactlyTheContractsFields() throws Exception {
        TopicGroup inactive = fixtures.group("Hidden group", 0, false);

        asAdmin(get("/api/catalog/topic-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value((int) topicGroupRepository.count()))
                .andExpect(jsonPath("$[0].id").value(inactive.getId()))
                .andExpect(jsonPath("$[0].label").value("Hidden group"))
                .andExpect(jsonPath("$[0].displayOrder").value(0))
                .andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[0].length()").value(4));
    }

    @Test
    void createTopicGroup_returns201WithTheTrimmedEntry() throws Exception {
        asAdmin(post("/api/catalog/topic-groups"), "{\"label\":\"  Sports  \",\"displayOrder\":9999}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.label").value("Sports"))
                .andExpect(jsonPath("$.displayOrder").value(9999))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void createTopicGroup_breakingSeveralRules_is400ListingEveryField() throws Exception {
        asAdmin(post("/api/catalog/topic-groups"), "{\"label\":\"   \",\"displayOrder\":10000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("label: must not be blank")))
                .andExpect(jsonPath("$.message").value(containsString("displayOrder: must be between 0 and 9999")));
    }

    @Test
    void patchTopicGroup_changesOnlyThePresentFields() throws Exception {
        TopicGroup group = fixtures.group("Old", 5, true);

        asAdmin(patch("/api/catalog/topic-groups/" + group.getId()), "{\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Old"))
                .andExpect(jsonPath("$.displayOrder").value(5))
                .andExpect(jsonPath("$.active").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"label\":null,\"displayOrder\":null,\"active\":null}"})
    void patchTopicGroup_withNoFieldToChange_isA200NoOp(String body) throws Exception {
        TopicGroup group = fixtures.group("Same", 5, false);

        asAdmin(patch("/api/catalog/topic-groups/" + group.getId()), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Same"))
                .andExpect(jsonPath("$.displayOrder").value(5))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void patchTopicGroup_blankLabel_is400() throws Exception {
        TopicGroup group = fixtures.group("Keep", 5, true);

        asAdmin(patch("/api/catalog/topic-groups/" + group.getId()), "{\"label\":\" \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("label: must not be blank"));
    }

    @Test
    void deleteTopicGroup_is204_andItIsGoneWithItsTopics() throws Exception {
        TopicGroup group = fixtures.group("Doomed", 5, true);
        Topic topic = fixtures.topic(group, "Doomed topic", 1, true);

        asAdmin(delete("/api/catalog/topic-groups/" + group.getId())).andExpect(status().isNoContent());

        assertThat(topicGroupRepository.findById(group.getId())).isEmpty();
        assertThat(topicRepository.findById(topic.getId())).isEmpty();
    }

    // -- topics --

    @Test
    void listTopics_includesInactiveAndStandaloneWithNullGroup() throws Exception {
        Topic inactive = fixtures.topic(null, "Hidden", 0, false);

        asAdmin(get("/api/catalog/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value((int) topicRepository.count()))
                .andExpect(jsonPath("$[0].id").value(inactive.getId()))
                .andExpect(jsonPath("$[0].topicGroupId").value(nullValue()))
                .andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[0].length()").value(7))
                .andExpect(jsonPath("$[*].slug").value(hasItem("general")));
    }

    @Test
    void createTopic_inAGroup_returns201() throws Exception {
        TopicGroup group = fixtures.group("G", 1, true);

        asAdmin(post("/api/catalog/topics"), "{\"slug\":\" new_topic_x \",\"label\":\"New\","
                + "\"guidingPrompt\":\"Why?\",\"displayOrder\":0,\"topicGroupId\":" + group.getId() + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("new_topic_x"))
                .andExpect(jsonPath("$.topicGroupId").value(group.getId()))
                .andExpect(jsonPath("$.guidingPrompt").value("Why?"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void createTopic_withoutTopicGroupId_isStandalone() throws Exception {
        asAdmin(post("/api/catalog/topics"),
                "{\"slug\":\"standalone_x\",\"label\":\"S\",\"guidingPrompt\":\"P\",\"displayOrder\":1}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.topicGroupId").value(nullValue()));
    }

    @Test
    void createTopic_unknownGroup_is400OnTopicGroupId() throws Exception {
        asAdmin(post("/api/catalog/topics"), "{\"slug\":\"orphan_x\",\"label\":\"S\",\"guidingPrompt\":\"P\","
                + "\"displayOrder\":1,\"topicGroupId\":987654}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("topicGroupId: must name an existing topic group"));
    }

    @Test
    void createTopic_duplicateSlug_is409() throws Exception {
        asAdmin(post("/api/catalog/topics"),
                "{\"slug\":\"academics_teaching\",\"label\":\"S\",\"guidingPrompt\":\"P\",\"displayOrder\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("academics_teaching")));
    }

    @Test
    void createTopic_badSlug_is400() throws Exception {
        asAdmin(post("/api/catalog/topics"),
                "{\"slug\":\"Bad-Slug\",\"label\":\"S\",\"guidingPrompt\":\"P\",\"displayOrder\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("slug: may contain only lowercase letters a-z, digits and underscores"));
    }

    @Test
    void patchTopic_topicGroupIdAbsent_leavesTheGroup() throws Exception {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(group, "T", 1, true);

        asAdmin(patch("/api/catalog/topics/" + topic.getId()), "{\"label\":\"Renamed\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Renamed"))
                .andExpect(jsonPath("$.topicGroupId").value(group.getId()));
    }

    @Test
    void patchTopic_topicGroupIdExplicitNull_makesItStandalone() throws Exception {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(group, "T", 1, true);

        asAdmin(patch("/api/catalog/topics/" + topic.getId()), "{\"topicGroupId\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topicGroupId").value(nullValue()));

        assertThat(topicRepository.findById(topic.getId()).orElseThrow().getTopicGroup()).isNull();
    }

    @Test
    void patchTopic_topicGroupIdValue_reparentsIt() throws Exception {
        TopicGroup target = fixtures.group("Target", 1, false);
        Topic topic = fixtures.topic(null, "T", 1, true);

        asAdmin(patch("/api/catalog/topics/" + topic.getId()), "{\"topicGroupId\":" + target.getId() + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topicGroupId").value(target.getId()));
    }

    @Test
    void patchTopic_unknownGroup_is400() throws Exception {
        Topic topic = fixtures.topic(null, "T", 1, true);

        asAdmin(patch("/api/catalog/topics/" + topic.getId()), "{\"topicGroupId\":987654}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("topicGroupId: must name an existing topic group"));
    }

    @Test
    void patchTopic_emptyBody_isA200NoOp() throws Exception {
        TopicGroup group = fixtures.group("G", 1, true);
        Topic topic = fixtures.topic(group, "T", 4, false);

        asAdmin(patch("/api/catalog/topics/" + topic.getId()), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topicGroupId").value(group.getId()))
                .andExpect(jsonPath("$.slug").value(topic.getSlug()))
                .andExpect(jsonPath("$.displayOrder").value(4))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void patchTopic_general_protectedChangesAre409_restatementsAre200() throws Exception {
        Topic general = fixtures.general();
        TopicGroup group = fixtures.group("G", 1, true);
        String url = "/api/catalog/topics/" + general.getId();

        asAdmin(patch(url), "{\"active\":false}").andExpect(status().isConflict());
        asAdmin(patch(url), "{\"slug\":\"general_two\"}").andExpect(status().isConflict());
        asAdmin(patch(url), "{\"topicGroupId\":" + group.getId() + "}").andExpect(status().isConflict());
        asAdmin(patch(url), "{\"slug\":\"general\",\"active\":true,\"topicGroupId\":null}")
                .andExpect(status().isOk());
        asAdmin(patch(url), "{\"label\":\"General\",\"guidingPrompt\":\"Anything else?\",\"displayOrder\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("General"));
    }

    @Test
    void patchTopic_boundThroughItsBuilder_isStillValidatedAndTrimmed() throws Exception {
        Topic topic = fixtures.topic(null, "T", 1, true);
        String url = "/api/catalog/topics/" + topic.getId();

        asAdmin(patch(url), "{\"slug\":\"Bad Slug\",\"guidingPrompt\":\"  \",\"displayOrder\":10000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("slug: may contain only")))
                .andExpect(jsonPath("$.message").value(containsString("guidingPrompt: must not be blank")))
                .andExpect(jsonPath("$.message").value(containsString("displayOrder: must be between 0 and 9999")));
        asAdmin(patch(url), "{\"label\":\"  Padded  \",\"slug\":\" padded_slug_x \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Padded"))
                .andExpect(jsonPath("$.slug").value("padded_slug_x"));
    }

    @Test
    void deleteTopic_general_is409() throws Exception {
        asAdmin(delete("/api/catalog/topics/" + fixtures.general().getId())).andExpect(status().isConflict());
    }

    @Test
    void deleteTopic_is204_withItsSections_testimonialKeepsItsStatus() throws Exception {
        Topic topic = fixtures.topic(null, "Doomed", 1, true);
        var testimonial = fixtures.testimonial(TestimonialStatus.APPROVED, List.of(topic, fixtures.general()));

        asAdmin(delete("/api/catalog/topics/" + topic.getId())).andExpect(status().isNoContent());

        assertThat(topicRepository.findById(topic.getId())).isEmpty();
        var reloaded = testimonialRepository.findById(testimonial.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TestimonialStatus.APPROVED);
        assertThat(reloaded.getSections()).hasSize(1);
    }

    // -- achievements --

    @Test
    void achievements_listCreatePatchDelete() throws Exception {
        Achievement inactive = fixtures.achievement("Hidden", 0, false);

        asAdmin(get("/api/catalog/achievements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(inactive.getId()))
                .andExpect(jsonPath("$[0].length()").value(5));

        asAdmin(post("/api/catalog/achievements"), "{\"slug\":\"kayak_x\",\"label\":\"Kayak\",\"displayOrder\":3}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("kayak_x"))
                .andExpect(jsonPath("$.active").value(true));

        asAdmin(patch("/api/catalog/achievements/" + inactive.getId()), "{\"active\":true,\"slug\":\"kayak_x\"}")
                .andExpect(status().isConflict());
        asAdmin(patch("/api/catalog/achievements/" + inactive.getId()), "{\"active\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        asAdmin(delete("/api/catalog/achievements/" + inactive.getId())).andExpect(status().isNoContent());
        assertThat(achievementRepository.findById(inactive.getId())).isEmpty();
    }

    @Test
    void createAchievement_duplicateSlug_is409_brokenRules_are400() throws Exception {
        String existing = achievementRepository.findAll().get(0).getSlug();
        asAdmin(post("/api/catalog/achievements"), "{\"slug\":\"" + existing + "\",\"label\":\"L\",\"displayOrder\":1}")
                .andExpect(status().isConflict());
        asAdmin(post("/api/catalog/achievements"), "{\"slug\":\"x\",\"label\":\"" + "a".repeat(121)
                + "\",\"displayOrder\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("label: must be at most 120 characters")))
                .andExpect(jsonPath("$.message").value(containsString("displayOrder: must be between 0 and 9999")));
    }

    // -- unknown ids --

    static Stream<MockHttpServletRequestBuilder> idEndpoints() {
        return Stream.of(
                patch("/api/catalog/topic-groups/987654").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/catalog/topic-groups/987654"),
                patch("/api/catalog/topics/987654").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/catalog/topics/987654"),
                patch("/api/catalog/achievements/987654").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/catalog/achievements/987654"));
    }

    @ParameterizedTest
    @MethodSource("idEndpoints")
    void unknownId_is404(MockHttpServletRequestBuilder request) throws Exception {
        asAdmin(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    // -- unreadable bodies --

    static Stream<MockHttpServletRequestBuilder> unreadableBodies() {
        return Stream.of(
                patch("/api/catalog/topic-groups/1").contentType(MediaType.APPLICATION_JSON).content("{\"label\": "),
                post("/api/catalog/topics").contentType(MediaType.APPLICATION_JSON).content("not json at all"),
                post("/api/catalog/topic-groups").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"X\",\"displayOrder\":\"first\"}"),
                patch("/api/catalog/topics/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicGroupId\":\"abc\"}"),
                post("/api/catalog/achievements").contentType(MediaType.APPLICATION_JSON),
                patch("/api/catalog/achievements/1").contentType(MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @MethodSource("unreadableBodies")
    void unreadableBody_is400WithoutParserDetails(MockHttpServletRequestBuilder request) throws Exception {
        asAdmin(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(not(containsString("JSON parse error"))))
                .andExpect(jsonPath("$.message").value(not(containsString("jackson"))));
    }
}
