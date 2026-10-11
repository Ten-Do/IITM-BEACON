package com.iitm.beacon.analytics;

import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTagsWithAttribute;
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
import java.util.ArrayList;
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
 * chips, stat cards and lists linking into the gallery's filters; the
 * "show more" disclosures under the chips and the lists; and no script
 * beyond the shared head's and {@code collapsible.js}. The shading and split
 * arithmetic itself is {@code DashboardViewTest}'s job.
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

    /**
     * A "show more" disclosure (decision 30): {@code rest}, the list of the
     * hidden rows, and {@code button}, the {@code <button>} element that
     * opens and closes them.
     */
    private record Disclosure(String rest, String button) {

        /** The button's opening tag. */
        String buttonTag() {
            return openingTags(button, "button").get(0);
        }

        /** The button's label while the rows are hidden. */
        String collapsedLabel() {
            Matcher label = Pattern.compile("<span class=\"when-collapsed\">([^<]*)</span>").matcher(button);
            assertThat(label.find()).as("collapsed label in " + button).isTrue();
            return label.group(1);
        }
    }

    /**
     * The disclosure whose container has id {@code id}: the container holds
     * just the list of the hidden rows, and the button comes right after it
     * — under the rows, so it glides down as they open. The button is a
     * plain button (never a submit) that controls the container, collapsed
     * at first, with a label for each state. Fails unless the container and
     * its id are on the page exactly once.
     */
    private static Disclosure disclosure(String html, String id) {
        Matcher found = Pattern.compile(
                        "<div class=\"collapsible\" id=\"" + Pattern.quote(id) + "\">\\s*"
                                + "<div class=\"collapsible-body\">\\s*(<ul\\b[^>]*>.*?</ul>)\\s*</div>\\s*</div>\\s*"
                                + "(<button\\b[^>]*>.*?</button>)",
                        Pattern.DOTALL)
                .matcher(html);
        assertThat(found.find()).as("#" + id + " holding a list, then its button").isTrue();
        Disclosure disclosure = new Disclosure(found.group(1), found.group(2));
        assertThat(html.split("\\sid=\"" + Pattern.quote(id) + "\"", -1)).as("id " + id + " on the page").hasSize(2);

        String tag = disclosure.buttonTag();
        assertThat(attribute(tag, "type")).contains("button");
        assertThat(attribute(tag, "data-collapsible-toggle")).isPresent();
        assertThat(attribute(tag, "aria-controls")).contains(id);
        assertThat(attribute(tag, "aria-expanded")).contains("false");
        assertThat(disclosure.button()).contains("<span class=\"when-expanded\">Show less</span>");
        return disclosure;
    }

    /** No disclosure on the page at all, nor the {@code <details>} that used to be one. */
    private static void assertNoDisclosure(String html) {
        assertThat(html).doesNotContain("data-collapsible-toggle", "class=\"collapsible\"", "<details", "<summary");
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
                "Testimonials by topic", "Most common achievements");
        assertNoDisclosure(html);
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
    void sixCountries_allShowAsChips_withNoMoreCountriesButton() throws Exception {
        approvedFromEach("DE", "FR", "US", "BR", "JP", "PT");

        String html = home();

        assertThat(html).doesNotContain("more countr", "countries-rest");
        assertNoDisclosure(html);
    }

    @Test
    void sevenCountries_putTheSeventhBehindOneMoreCountry_aChipButtonUnderIt() throws Exception {
        data.approved().from("DE").save();
        approvedFromEach("DE", "FR", "US", "BR", "JP", "PT", "SE");

        String html = home();
        Disclosure more = disclosure(html, "countries-rest");

        assertThat(more.collapsedLabel()).isEqualTo("+ 1 more country");
        assertThat(attribute(more.buttonTag(), "class"))
                .hasValueSatisfying(css -> assertThat(css).contains("dashboard-chip"));
        // Germany (2) first, then the six ties by name: the United States come last — hidden, and only there.
        assertThat(more.rest()).contains("href=\"/gallery?country=US\"", ">United States of America</span>");
        assertThat(openingTags(more.rest(), "a")).hasSize(1);
        assertThat(linksTo(html.replace(map(html), ""), "/gallery?country=US")).hasSize(1);
    }

    @Test
    void eightCountries_putTwoBehindTwoMoreCountries() throws Exception {
        data.approved().from("DE").save();
        data.approved().from("FR").save();
        approvedFromEach("DE", "FR", "US", "BR", "JP", "PT", "SE", "AU");

        Disclosure more = disclosure(home(), "countries-rest");

        assertThat(more.collapsedLabel()).isEqualTo("+ 2 more countries");
        assertThat(openingTags(more.rest(), "a")).hasSize(2);
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
                ">67%</span>\\s*<span class=\"dashboard-stat-label\">Recommend the exchange<");
    }

    /** "Other" (decision 1) shows as a chip, off the map, but isn't counted as a country represented. */
    @Test
    void other_isAChipButNotOnTheMap_andNotACountryRepresented() throws Exception {
        data.approved().from("ZZ").save();
        data.approved().from("ZZ").save();
        data.approved().from("DE").save();

        String html = home();
        String outsideTheMap = html.replace(map(html), "");

        assertThat(map(html)).doesNotContain("country=ZZ", "Other");
        List<String> chips = elements(outsideTheMap, "a").stream()
                .filter(link -> link.contains("href=\"/gallery?country="))
                .toList();
        assertThat(chips).hasSize(2);
        assertThat(chips.get(0)).contains("href=\"/gallery?country=ZZ\"", ">Other</span>", ">2</span>");
        assertThat(chips.get(1)).contains("href=\"/gallery?country=DE\"", ">Germany</span>", ">1</span>");
        assertThat(html).containsPattern(">1</span>\\s*<span class=\"dashboard-stat-label\">Countries represented<");
    }

    @Test
    void onlyOther_showsItsChip_andZeroCountriesRepresented() throws Exception {
        data.approved().from("ZZ").save();

        String html = home();

        assertThat(linksTo(html, "/gallery?country=ZZ")).hasSize(1);
        assertThat(html).containsPattern(">1</span>\\s*<span class=\"dashboard-stat-label\">Approved testimonials<");
        assertThat(html).containsPattern(">0</span>\\s*<span class=\"dashboard-stat-label\">Countries represented<");
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

        assertThat(html).doesNotContain("Show all", "topics-rest");
        assertNoDisclosure(html);
    }

    @Test
    void sixTopicEntries_putTheSixthBehindShowAll_aButtonUnderTheList() throws Exception {
        data.approved().from("DE")
                .sections("academics_teaching", "housing_food", "travel_did", "campus_events", "oge_support")
                .save();
        data.approved().from("DE").sections("academics_teaching", "housing_food", "travel_did", "campus_events",
                        "oge_support", "general")
                .save();
        Long general = data.topic("general").getId();

        Disclosure more = disclosure(home(), "topics-rest");

        assertThat(more.collapsedLabel()).isEqualTo("Show all (1 more)");
        assertThat(openingTags(more.rest(), "a")).singleElement()
                .satisfies(link -> assertThat(attribute(link, "href")).contains("/gallery?topicIds=" + general));
        assertThat(more.rest()).contains(">General</span>");
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
    void fiveAchievements_allShow_withNoShowAll() throws Exception {
        data.approved().from("DE")
                .ticks("made_new_friends", "traveled_within_india", "enjoyed_spicy_indian_food",
                        "keeping_in_touch", "missed_home")
                .save();

        String html = home();

        assertThat(html).doesNotContain("Show all", "achievements-rest");
        assertNoDisclosure(html);
    }

    @Test
    void sixAchievements_putTheSixthBehindShowAll_aButtonUnderTheList() throws Exception {
        data.approved().from("DE")
                .ticks("made_new_friends", "traveled_within_india", "enjoyed_spicy_indian_food",
                        "keeping_in_touch", "explored_the_city", "missed_home")
                .save();

        Disclosure more = disclosure(home(), "achievements-rest");

        assertThat(more.collapsedLabel()).isEqualTo("Show all (1 more)");
        // All tied at 1, so by display order: "Explored the city beyond campus" (22) comes last.
        assertThat(more.rest()).contains(">Explored the city beyond campus</span>");
        assertThat(openingTags(more.rest(), "a")).isEmpty();
    }

    /**
     * Chips and both lists overflowing at once: three disclosures, each
     * button controlling its own rows — the two "Show all (1 more)" buttons
     * included — and no id twice on the page.
     */
    @Test
    void everythingOverflowing_hasThreeDisclosures_eachControllingItsOwnRows() throws Exception {
        approvedFromEach("FR", "US", "BR", "JP", "PT", "SE");
        data.approved().from("DE")
                .sections("academics_teaching", "housing_food", "travel_did", "campus_events", "oge_support")
                .ticks("made_new_friends", "traveled_within_india", "enjoyed_spicy_indian_food",
                        "keeping_in_touch", "explored_the_city", "missed_home")
                .save();
        data.approved().from("DE").sections("academics_teaching", "housing_food", "travel_did", "campus_events",
                        "oge_support", "general")
                .ticks("made_new_friends", "traveled_within_india", "enjoyed_spicy_indian_food",
                        "keeping_in_touch", "missed_home")
                .save();

        String html = home();

        assertThat(disclosure(html, "countries-rest").collapsedLabel()).isEqualTo("+ 1 more country");
        assertThat(disclosure(html, "topics-rest").rest()).contains(">General</span>")
                .doesNotContain("Explored the city");
        assertThat(disclosure(html, "achievements-rest").rest()).contains(">Explored the city beyond campus</span>")
                .doesNotContain(">General</span>");
        assertThat(openingTagsWithAttribute(html, "data-collapsible-toggle")).hasSize(3);
        assertThat(openingTagsWithAttribute(html, "id").stream().map(tag -> attribute(tag, "id").orElseThrow()))
                .doesNotHaveDuplicates();
        assertThat(html).doesNotContain("<details", "<summary");
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

    /** The shared head's scripts, on every page ({@code config.LayoutShellTest}). */
    private static final List<String> SHARED_SCRIPTS = List.of("/js/nav-toggle.js", "/js/form-submit.js");

    private static List<String> scriptSources(String html) {
        return openingTags(html, "script").stream()
                .map(tag -> attribute(tag, "src").map(AssetUrls::plain).orElse("(inline)"))
                .toList();
    }

    /**
     * The map is pure SVG: no Alpine, no PhotoSwipe. Beyond the shared
     * head's scripts only the disclosures' script, once, deferred and a
     * classic script.
     */
    @Test
    void fullPage_loadsOnlyTheSharedScriptsAndTheDisclosureScript() throws Exception {
        approvedFromEach("DE", "FR", "US", "BR", "JP", "PT", "SE");

        String html = home();

        List<String> expected = new ArrayList<>(SHARED_SCRIPTS);
        expected.add("/js/collapsible.js");
        assertThat(scriptSources(html)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(openingTags(html, "script"))
                .filteredOn(tag -> attribute(tag, "src").map(AssetUrls::plain).orElse("").equals("/js/collapsible.js"))
                .singleElement()
                .satisfies(tag -> {
                    assertThat(attribute(tag, "defer")).isPresent();
                    assertThat(attribute(tag, "async")).isEmpty();
                    assertThat(attribute(tag, "type")).isEmpty();
                });
    }

    /** Nothing to open on the empty state: not even the disclosures' script. */
    @Test
    void emptyPage_loadsOnlyTheSharedScripts() throws Exception {
        assertThat(scriptSources(home())).containsExactlyInAnyOrderElementsOf(SHARED_SCRIPTS);
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
