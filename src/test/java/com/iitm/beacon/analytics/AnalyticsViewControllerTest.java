package com.iitm.beacon.analytics;

import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.achievement.Achievement;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.AssetUrls;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture.IdClash;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link AnalyticsViewController}, the homepage dashboard
 * at {@code /} (UC-VIEW-DASHBOARD, decision 30), with the real {@code
 * SecurityConfig} filters: public for GET and HEAD, refused otherwise; the
 * empty state until something is approved; the server-rendered SVG map, the
 * chips, stat cards and lists linking into the gallery's filters; and no
 * script beyond the shared burger script. The shading and split arithmetic
 * itself is {@code DashboardViewTest}'s job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class AnalyticsViewControllerTest {

    private static final String HOME = "/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @Autowired
    private WorldMap worldMap;

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
                "dashboard-visitor@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private String html(RequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String home() throws Exception {
        return html(get(HOME));
    }

    private String pathOf(String code) {
        return worldMap.regions().stream()
                .filter(region -> region.code().equals(code))
                .findFirst()
                .orElseThrow()
                .path();
    }

    /** The page's one inline {@code <svg>}: the map. */
    private static String map(String html) {
        List<String> svgs = elements(html, "svg");
        assertThat(svgs).as("<svg> elements").hasSize(1);
        return svgs.get(0);
    }

    /** The {@code <details>} whose summary reads {@code summaryText}; fails unless there is exactly one. */
    private static String details(String html, String summaryText) {
        List<String> found = elements(html, "details").stream()
                .filter(d -> d.contains(">" + summaryText + "</summary>"))
                .toList();
        assertThat(found).as("<details> with summary " + summaryText).hasSize(1);
        return found.get(0);
    }

    /** Opening {@code <a>} tags linking to exactly {@code href}. */
    private static List<String> linksTo(String html, String href) {
        return openingTags(html, "a").stream()
                .filter(tag -> attribute(tag, "href").filter(href::equals).isPresent())
                .toList();
    }

    // -- access: public for GET and HEAD, nothing else --

    @Test
    void anonymousGet_rendersTheDashboardView() throws Exception {
        mockMvc.perform(get(HOME))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(view().name("analytics/dashboard"))
                .andExpect(model().attributeExists("dashboard"));
    }

    /** {@code /} used to redirect to the gallery; it is the dashboard itself now (decision 30). */
    @Test
    void anonymousGet_isNoLongerARedirect() throws Exception {
        mockMvc.perform(get(HOME))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("See where IITM Beacon alumni have been")));
    }

    @Test
    void anonymousHead_isAllowed() throws Exception {
        mockMvc.perform(head(HOME)).andExpect(status().isOk());
    }

    @Test
    void visitorSessionGetAndHead_areAllowed() throws Exception {
        mockMvc.perform(get(HOME).with(authentication(visitor())))
                .andExpect(status().isOk())
                .andExpect(view().name("analytics/dashboard"));
        mockMvc.perform(head(HOME).with(authentication(visitor()))).andExpect(status().isOk());
    }

    @Test
    void adminSessionGet_isAllowed() throws Exception {
        mockMvc.perform(get(HOME).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("analytics/dashboard"));
    }

    /** Only GET and HEAD are served here: any other method is the security chain's HTML 404 page, never JSON. */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void anonymousWrite_isRefusedWithTheHtml404Page(String method) throws Exception {
        assertHtmlErrorPage(
                mockMvc.perform(request(HttpMethod.valueOf(method), HOME).with(csrfField())).andReturn().getResponse(),
                404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void visitorWrite_isRefusedWithTheHtml404Page(String method) throws Exception {
        assertHtmlErrorPage(
                mockMvc.perform(request(HttpMethod.valueOf(method), HOME).with(csrfField())
                                .with(authentication(visitor())))
                        .andReturn()
                        .getResponse(),
                404);
    }

    // -- hero and heading levels --

    /** The page body, without the header (whose nav has its own "Browse testimonials" link). */
    private static String main(String html) {
        List<String> mains = elements(html, "main");
        assertThat(mains).as("<main> elements").hasSize(1);
        return mains.get(0);
    }

    @Test
    void hero_hasTheTitleTheIntroAndAButtonToTheGallery() throws Exception {
        String main = main(home());

        assertThat(elements(main, "h1")).singleElement().satisfies(h1 ->
                assertThat(h1).contains(">See where IITM Beacon alumni have been</h1>"));
        assertThat(main).contains("Real testimonials and photos from exchange students who studied at IIT Madras");
        assertThat(elements(main, "a")).anySatisfy(link ->
                assertThat(link).contains("href=\"/gallery\"", ">Browse testimonials</a>"));
    }

    @Test
    void emptyPage_hasExactlyOneH1_andNoHeadingBelowH2() throws Exception {
        String html = home();

        assertThat(openingTags(html, "h1")).hasSize(1);
        assertThat(openingTags(html, "h2")).isNotEmpty();
        assertThat(openingTags(html, "h[3-6]")).isEmpty();
    }

    @Test
    void fullPage_hasExactlyOneH1_andNoHeadingBelowH2() throws Exception {
        data.approved().from("DE").sections("general").ticks("made_new_friends").save();

        String html = home();

        assertThat(openingTags(html, "h1")).hasSize(1);
        assertThat(openingTags(html, "h2")).hasSizeGreaterThanOrEqualTo(4);
        assertThat(openingTags(html, "h[3-6]")).isEmpty();
    }

    // -- empty state (UC-VIEW-DASHBOARD alternate flow) --

    @Test
    void noTestimonialAtAll_showsTheEmptyState_andNoMapStatsOrLists() throws Exception {
        String html = home();

        assertThat(html).contains(">No testimonials yet</h2>");
        assertThat(html).contains("Be the first to share your exchange experience at IIT Madras.");
        assertThat(elements(html, "a")).anySatisfy(link ->
                assertThat(link).contains("href=\"/submissions/login\"").contains(">Write your testimonial</a>"));
        assertThat(html).doesNotContain("<svg", "Where our alumni came from", "By the numbers",
                "Testimonials by topic", "Most common achievements", "<details");
    }

    @Test
    void onlyPendingAndRejectedTestimonials_stillShowTheEmptyState() throws Exception {
        data.pending().from("DE").sections("general").ticks("made_new_friends").save();
        data.rejected().from("FR").sections("general").ticks("made_new_friends").save();

        String html = home();

        assertThat(html).contains(">No testimonials yet</h2>").doesNotContain("<svg", "By the numbers");
    }

    @Test
    void oneApprovedTestimonial_replacesTheEmptyStateWithTheDashboard() throws Exception {
        data.approved().from("DE").sections("general").save();

        String html = home();

        assertThat(html).doesNotContain("No testimonials yet", "Write your testimonial");
        assertThat(html).contains(">Where our alumni came from</h2>", ">By the numbers</h2>");
        assertThat(html).contains("<svg");
    }

    // -- the map --

    @Test
    void map_hasTheMapsViewBox_andALabel_butNoImgRoleThatWouldHideItsLinks() throws Exception {
        data.approved().from("DE").save();

        String svg = openingTags(map(home()), "svg").get(0);

        assertThat(attribute(svg, "viewBox")).contains("0 0 900 440.71");
        assertThat(attribute(svg, "aria-label")).hasValueSatisfying(label -> assertThat(label).isNotBlank());
        assertThat(attribute(svg, "role")).isEmpty();
    }

    @Test
    void map_drawsOnePathPerRegion() throws Exception {
        data.approved().from("DE").save();

        assertThat(openingTags(map(home()), "path")).hasSize(173);
    }

    /**
     * Region {@code code}'s path inside its gallery link, with the tooltip as
     * its only child: group 1 is the shade, group 2 the tooltip.
     */
    private Matcher linkedRegion(String svg, String code) {
        return Pattern.compile("<a href=\"/gallery\\?country=" + code + "\">\\s*"
                        + "<path class=\"map-region map-shade-(\\d)\" d=\"" + Pattern.quote(pathOf(code)) + "\">\\s*"
                        + "<title>([^<]*)</title>\\s*</path>\\s*</a>")
                .matcher(svg);
    }

    @Test
    void aRepresentedCountry_isAShadedPathInsideAGalleryLink_withATooltip() throws Exception {
        data.approved().from("DE").save();
        data.approved().from("DE").save();
        data.approved().from("FR").save();

        String svg = map(home());

        Matcher germany = linkedRegion(svg, "DE");
        assertThat(germany.find()).as("Germany's linked path").isTrue();
        assertThat(germany.group(1)).isEqualTo("5");
        assertThat(germany.group(2)).isEqualTo("Germany — 2 testimonials");

        Matcher france = linkedRegion(svg, "FR");
        assertThat(france.find()).as("France's linked path").isTrue();
        assertThat(france.group(1)).isEqualTo("3");
        assertThat(france.group(2)).isEqualTo("France — 1 testimonial");
    }

    @Test
    void aCountryWithoutApprovedTestimonials_isAPlainPath_withNoLinkAndNoTooltip() throws Exception {
        data.approved().from("DE").save();
        data.pending().from("ES").save();

        String svg = map(home());

        assertThat(svg).containsPattern("<path class=\"map-region\" d=\"" + Pattern.quote(pathOf("ES")) + "\"\\s*/?>");
        assertThat(svg).doesNotContain("country=ES", "Spain");
        assertThat(openingTags(svg, "a")).hasSize(1);
        assertThat(elements(svg, "title")).hasSize(1);
    }

    /** Singapore is too small for the map (decision 30): it shows only as a chip. */
    @Test
    void aCountryTooSmallForTheMap_appearsOnlyAsAChip() throws Exception {
        data.approved().from("SG").save();

        String html = home();

        assertThat(map(html)).doesNotContain("country=SG");
        assertThat(linksTo(html, "/gallery?country=SG")).hasSize(1);
        assertThat(html).contains(">Singapore</span>");
    }

    @Test
    void theMapScale_runsFromFewerToMore() throws Exception {
        data.approved().from("DE").save();

        String html = home();

        assertThat(html).contains(">Fewer</span>", ">More</span>");
        assertThat(html.indexOf(">Fewer</span>")).isLessThan(html.indexOf(">More</span>"));
    }

    // -- country chips --

    @Test
    void countryChips_linkToTheGalleryCountryFilter_withNameAndCount() throws Exception {
        data.approved().from("DE").save();
        data.approved().from("DE").save();
        data.approved().from("IN").save();

        String html = home();
        String outsideTheMap = html.replace(map(html), "");

        List<String> chips = elements(outsideTheMap, "a").stream()
                .filter(link -> link.contains("href=\"/gallery?country="))
                .toList();
        assertThat(chips).hasSize(2);
        assertThat(chips.get(0)).contains("href=\"/gallery?country=DE\"", ">Germany</span>", ">2</span>");
        assertThat(chips.get(1)).contains("href=\"/gallery?country=IN\"", ">India</span>", ">1</span>");
    }

    private void approvedFromEach(String... codes) {
        for (String code : codes) {
            data.approved().from(code).save();
        }
    }

    @Test
    void sixCountries_allShowAsChips_withNoMoreCountriesDetails() throws Exception {
        approvedFromEach("DE", "FR", "US", "BR", "JP", "PT");

        String html = home();

        assertThat(html).doesNotContain("more countr", "<details");
    }

    @Test
    void sevenCountries_putTheSeventhBehindOneMoreCountry() throws Exception {
        data.approved().from("DE").save();
        approvedFromEach("DE", "FR", "US", "BR", "JP", "PT", "SE");

        String more = details(home(), "+ 1 more country");

        // Germany (2) first, then the six ties by name: the United States come last.
        assertThat(more).contains("href=\"/gallery?country=US\"", ">United States of America</span>");
        assertThat(openingTags(more, "a")).hasSize(1);
    }

    @Test
    void eightCountries_putTwoBehindTwoMoreCountries() throws Exception {
        data.approved().from("DE").save();
        data.approved().from("FR").save();
        approvedFromEach("DE", "FR", "US", "BR", "JP", "PT", "SE", "AU");

        String more = details(home(), "+ 2 more countries");

        assertThat(openingTags(more, "a")).hasSize(2);
    }

    // -- stat cards --

    @Test
    void statCards_showTotalAverageCountriesAndRecommendingShare() throws Exception {
        data.approved().from("DE").score(8).save();
        data.approved().from("DE").score(9).save();
        data.approved().from("FR").score(5).save();
        data.pending().from("US").score(0).save();

        String html = home();

        // 3 approved; (8 + 9 + 5) / 3 = 7.33 -> "7.3" in score-7's colour; 2 countries; 2 of 3 score 6+ -> 67 %.
        assertThat(html).containsPattern(">3</span>\\s*<span class=\"dashboard-stat-label\">Approved testimonials<");
        assertThat(html).containsPattern(
                "<span class=\"dashboard-stat-number score-7\">7\\.3</span>\\s*"
                        + "<span class=\"dashboard-stat-label\">Average recommendation score<");
        assertThat(html).containsPattern(">2</span>\\s*<span class=\"dashboard-stat-label\">Countries represented<");
        assertThat(html).containsPattern(
                ">67%</span>\\s*<span class=\"dashboard-stat-label\">Recommend the exchange \\(score 6\\+\\)<");
    }

    @Test
    void nobodyRecommending_showsZeroPercent() throws Exception {
        data.approved().from("DE").score(5).save();

        assertThat(home()).containsPattern(">0%</span>\\s*<span class=\"dashboard-stat-label\">Recommend the exchange");
    }

    // -- testimonials by topic --

    @Test
    void topicRows_linkAGroupByGroupIds_andAStandaloneTopicByTopicIds() throws Exception {
        data.approved().from("DE").sections("academics_teaching", "academics_style", "general").save();
        Long academics = data.topic("academics_teaching").getTopicGroup().getId();
        Long general = data.topic("general").getId();

        String html = home();

        assertThat(html).contains(">Testimonials by topic</h2>");
        assertThat(elements(html, "a").stream().filter(a -> a.contains("href=\"/gallery?groupIds=" + academics + "\"")))
                .singleElement()
                .satisfies(row -> assertThat(row).contains(">Academics</span>", ">1</span>"));
        assertThat(linksTo(html, "/gallery?topicIds=" + general)).hasSize(1);
    }

    /** Groups and topics have separate id sequences (decision 29): an equal id must still pick the right filter. */
    @Test
    void aGroupAndAStandaloneTopicSharingAnId_linkToDifferentFilters() throws Exception {
        IdClash clash = CatalogVisibilityFixture.forceIdClash(topicGroupRepository, topicRepository);
        data.approved().from("DE").sections(clash.member(), clash.standalone()).save();

        String html = home();

        assertThat(linksTo(html, "/gallery?groupIds=" + clash.sharedId())).hasSize(1);
        assertThat(linksTo(html, "/gallery?topicIds=" + clash.sharedId())).hasSize(1);
    }

    @Test
    void fiveTopicEntries_allShow_withNoShowAll() throws Exception {
        data.approved().from("DE")
                .sections("academics_teaching", "housing_food", "travel_did", "campus_events", "general")
                .save();

        String html = home();

        assertThat(html).doesNotContain("Show all", "<details");
    }

    @Test
    void sixTopicEntries_putTheSixthBehindShowAll() throws Exception {
        data.approved().from("DE")
                .sections("academics_teaching", "housing_food", "travel_did", "campus_events", "oge_support")
                .save();
        data.approved().from("DE").sections("academics_teaching", "housing_food", "travel_did", "campus_events",
                        "oge_support", "general")
                .save();
        Long general = data.topic("general").getId();

        String more = details(home(), "Show all (1 more)");

        assertThat(more).contains("href=\"/gallery?topicIds=" + general + "\"", ">General</span>");
    }

    // -- most common achievements --

    @Test
    void achievementRows_arePlainText_neverLinks() throws Exception {
        data.approved().from("DE").ticks("made_new_friends").save();

        String html = home();

        assertThat(html).contains(">Most common achievements</h2>", ">Made new friends here</span>");
        assertThat(elements(html, "a")).noneSatisfy(link -> assertThat(link).contains("Made new friends here"));
    }

    @Test
    void sixAchievements_putTheSixthBehindShowAll() throws Exception {
        data.approved().from("DE")
                .ticks("made_new_friends", "traveled_within_india", "enjoyed_spicy_indian_food",
                        "keeping_in_touch", "explored_the_city", "missed_home")
                .save();

        String more = details(home(), "Show all (1 more)");

        // All tied at 1, so by display order: "Explored the city beyond campus" (22) comes last.
        assertThat(more).contains(">Explored the city beyond campus</span>");
        assertThat(openingTags(more, "a")).isEmpty();
    }

    @Test
    void listsWithNoRows_areNotRendered() throws Exception {
        data.approved().from("DE").save();

        String html = home();

        assertThat(html).doesNotContain("Testimonials by topic", "Most common achievements");
        assertThat(html).contains(">By the numbers</h2>");
    }

    @Test
    void onlyAchievements_rendersOnlyTheAchievementList() throws Exception {
        data.approved().from("DE").ticks("made_new_friends").save();

        String html = home();

        assertThat(html).doesNotContain("Testimonials by topic").contains("Most common achievements");
    }

    /** Topic and achievement labels are typed by an admin: they are escaped, never rendered as markup. */
    @Test
    void labelsAreEscaped() throws Exception {
        Topic topic = topicRepository.saveAndFlush(
                CatalogVisibilityFixture.topic("dashboard_markup", "<b>Q&A</b>", null, 60, true));
        Achievement feat = achievementRepository.saveAndFlush(
                Achievement.builder().slug("dashboard_markup").label("<i>Feat</i>").displayOrder(300).build());
        data.approved().from("DE").sections(topic).ticks(feat).save();

        String html = home();

        assertThat(html).contains("&lt;b&gt;Q&amp;A&lt;/b&gt;", "&lt;i&gt;Feat&lt;/i&gt;");
        assertThat(html).doesNotContain("<b>Q&A</b>", "<i>Feat</i>");
    }

    // -- scripts and header --

    /** The map is pure SVG: no Alpine, no PhotoSwipe, nothing but the shared burger script. */
    @Test
    void fullPage_loadsNoScriptButTheNavToggle() throws Exception {
        approvedFromEach("DE", "FR", "US", "BR", "JP", "PT", "SE");

        List<String> scripts = openingTags(home(), "script");

        assertThat(scripts).singleElement().satisfies(tag ->
                assertThat(attribute(tag, "src").map(AssetUrls::plain)).contains("/js/nav-toggle.js"));
    }

    @Test
    void emptyPage_loadsNoScriptButTheNavToggle() throws Exception {
        List<String> scripts = openingTags(home(), "script");

        assertThat(scripts).singleElement().satisfies(tag ->
                assertThat(attribute(tag, "src").map(AssetUrls::plain)).contains("/js/nav-toggle.js"));
    }

    @Test
    void header_marksHomepageActive_andNothingElse() throws Exception {
        String nav = elements(elements(home(), "header").get(0), "nav").get(0);

        List<String> links = elements(nav, "a");
        assertThat(links.get(0)).contains("href=\"/\"", ">Homepage</a>");
        assertThat(attribute(openingTags(links.get(0), "a").get(0), "class"))
                .hasValueSatisfying(css -> assertThat(css).contains("active"));
        assertThat(links.subList(1, links.size())).allSatisfy(link ->
                assertThat(attribute(openingTags(link, "a").get(0), "class").orElse("")).doesNotContain("active"));
    }
}
