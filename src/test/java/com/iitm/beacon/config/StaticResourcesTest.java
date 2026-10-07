package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Confirms the shared view-layer stylesheet at {@code
 * src/main/resources/static/css/beacon.css} is actually reachable through
 * the real application, not just present on disk — relying on Spring
 * Boot's default static-resource handling (files under {@code
 * src/main/resources/static/} are served at their path minus {@code
 * static/}) plus {@code SecurityConfig}'s {@code permitAll()} on {@code
 * /css/**}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class StaticResourcesTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void beaconCssIsServedWithCssContentType() throws Exception {
        mockMvc.perform(get("/css/beacon.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    /**
     * Confirms the fullscreen photo-viewer module (loaded by {@code
     * gallery/detail.html} and {@code moderation/queue.html}) is actually
     * reachable through the real application, mirroring {@link
     * #beaconCssIsServedWithCssContentType()}. What it does in a browser is
     * the e2e suite's job.
     */
    @Test
    void photoViewerJsIsServedWithJavascriptContentType() throws Exception {
        mockMvc.perform(get("/js/photo-viewer.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"));
    }

    /**
     * The viewer is an ES module ({@code <script type="module">}): a browser
     * refuses to run one served with a non-JavaScript content type.
     */
    @ParameterizedTest
    @CsvSource({
        "/webjars/photoswipe/dist/photoswipe-lightbox.esm.min.js, text/javascript",
        "/webjars/photoswipe/dist/photoswipe.esm.min.js, text/javascript",
        "/webjars/photoswipe/dist/photoswipe.css, text/css"
    })
    void photoSwipeWebJar_isServedToAnonymousVisitorsAtItsVersionlessUrls(String path, String contentType)
            throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(contentType));
    }

    /**
     * Pins the contract between the viewer module and the markup of {@code
     * layout/photo-viewer.html} (a browser runs the module itself in the e2e
     * suite): it builds on PhotoSwipe, loaded from the version-less WebJar
     * URLs — the core only on first open — and finds galleries, photos and
     * tag chips by the same attribute and class names the templates render.
     */
    @Test
    void photoViewerJs_isAPhotoSwipeModule_readingTheMarkupTheTemplatesRender() throws Exception {
        String js = mockMvc.perform(get("/js/photo-viewer.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(js)
                .contains("from '/webjars/photoswipe/dist/photoswipe-lightbox.esm.min.js'")
                .contains("import('/webjars/photoswipe/dist/photoswipe.esm.min.js')")
                .contains("'[data-photo-gallery]'", "'a[data-photo-viewer-item]'", "'.photo-tag-chip'")
                .doesNotContain("photo-viewer-template");
    }

    private String beaconCss() throws Exception {
        return mockMvc.perform(get("/css/beacon.css"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Body of the top-level rule whose selector list is exactly {@code
     * selectors} — not one that merely ends with it after a comma.
     */
    private static String topLevelRule(String css, String selectors) {
        Matcher rule = Pattern.compile("(?m)^" + Pattern.quote(selectors) + "\\s*\\{([^}]*)}").matcher(css);
        while (rule.find()) {
            if (!css.substring(0, rule.start()).stripTrailing().endsWith(",")) {
                return rule.group(1);
            }
        }
        throw new AssertionError("No top-level rule " + selectors);
    }

    /** Everything inside the {@code @media <query> { … }} block. */
    private static String mediaBlock(String css, String query) {
        int start = css.indexOf("@media " + query + " {");
        assertThat(start).as("@media " + query).isNotNegative();
        int depth = 0;
        for (int i = css.indexOf('{', start); i < css.length(); i++) {
            if (css.charAt(i) == '{') {
                depth++;
            } else if (css.charAt(i) == '}' && --depth == 0) {
                return css.substring(css.indexOf('{', start) + 1, i);
            }
        }
        throw new AssertionError("Unclosed @media " + query);
    }

    /** Body of the rule for {@code selectors} inside a block (indented, unlike {@link #topLevelRule}). */
    private static String nestedRule(String block, String selectors) {
        Matcher rule = Pattern.compile("(?m)^\\s+" + Pattern.quote(selectors) + "\\s*\\{([^}]*)}").matcher(block);
        assertThat(rule.find()).as("rule " + selectors).isTrue();
        return rule.group(1);
    }

    @Test
    void beaconCss_showsBiggerCroppedThumbnails_onTheArticleTheQueueAndTheGalleryCards() throws Exception {
        String css = beaconCss();

        assertThat(topLevelRule(css, ".gallery-photo-thumb"))
                .contains("width: 200px;", "height: 150px;", "object-fit: cover;");
        assertThat(topLevelRule(css, ".moderation-section-photo"))
                .contains("width: 160px;", "height: 120px;", "object-fit: cover;");
        assertThat(topLevelRule(css, ".gallery-card-photo,\n.gallery-card-photo-placeholder"))
                .contains("height: 200px;");
    }

    @Test
    void beaconCss_onNarrowScreens_laysArticleAndQueuePhotosOutInTwoFullWidth4by3Columns() throws Exception {
        String narrow = mediaBlock(beaconCss(), "(max-width: 720px)");

        assertThat(nestedRule(narrow, ".gallery-photo-row,\n  .moderation-section-photos"))
                .contains("display: grid;", "grid-template-columns: repeat(2, minmax(0, 1fr));");
        assertThat(nestedRule(narrow, ".gallery-photo-thumb,\n  .moderation-section-photo"))
                .contains("width: 100%;", "height: auto;", "aspect-ratio: 4 / 3;");
    }

    @Test
    void beaconCss_stylesPhotoTagChips_andALightVariantForTheViewersDarkBackdrop() throws Exception {
        String css = beaconCss();

        assertThat(topLevelRule(css, ".photo-tag-list")).contains("flex-wrap: wrap;");
        // Under a thumbnail a chip stays one line (its title has the full tag) ...
        assertThat(topLevelRule(css, ".photo-tag-chip"))
                .contains("border-radius: var(--radius-full);", "white-space: nowrap;", "text-overflow: ellipsis;");
        // ... in the viewer's caption it shows the whole tag, wrapping if it must.
        assertThat(topLevelRule(css, ".photo-viewer-caption .photo-tag-chip"))
                .contains("color: #fff;", "white-space: normal;");
    }

    /**
     * PhotoSwipe centres its arrows vertically and hides them on touch
     * screens; here they sit in the bottom corners, level with the caption,
     * on every device.
     */
    @Test
    void beaconCss_pinsTheViewerArrowsToTheBottomCorners_andShowsThemOnTouchScreens() throws Exception {
        String arrows = topLevelRule(beaconCss(), ".pswp.beacon-pswp .pswp__button--arrow");

        assertThat(arrows)
                .contains("top: auto;", "bottom: var(--photo-viewer-edge);", "visibility: visible;");
    }

    @Test
    void beaconCss_dropsTheOldHandRolledViewer() throws Exception {
        assertThat(beaconCss()).doesNotContain(".photo-viewer-overlay", ".photo-viewer-nav", ".photo-viewer-row");
    }

    /**
     * Alpine.js ships as a WebJar; {@code webjars-locator-lite} maps the
     * version-less URL used by the templates onto the jar's versioned path,
     * so a dependency bump never has to touch a template.
     */
    @Test
    void alpineJsWebJar_isServedToAnonymousVisitorsAtItsVersionlessUrl() throws Exception {
        mockMvc.perform(get("/webjars/alpinejs/dist/cdn.min.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"));
    }

    @Test
    void submissionFormJs_isServedWithJavascriptContentType() throws Exception {
        mockMvc.perform(get("/js/submission-form.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                // Components must be registered on alpine:init, i.e. before
                // Alpine walks the DOM, or x-data="topicPicker" is undefined.
                .andExpect(content().string(containsString("alpine:init")));
    }

    @Test
    void submissionFormJs_registersTheFieldRuleComponentsTheFormUses() throws Exception {
        String js = submissionFormJs();

        assertThat(js).contains(
                "Alpine.data('submissionForm'",
                "Alpine.data('topicBlock'",
                "Alpine.data('photoPicker'",
                "Alpine.data('contactRow'");
        // The three fixed upload slots are gone.
        assertThat(js).doesNotContain("uploadSlot");
    }

    private String submissionFormJs() throws Exception {
        return mockMvc.perform(get("/js/submission-form.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** One {@code Alpine.data('<name>', …)} registration, up to the next one. */
    private static String alpineComponent(String js, String name) {
        int start = js.indexOf("Alpine.data('" + name + "'");
        assertThat(start).as("component " + name).isNotNegative();
        int next = js.indexOf("Alpine.data(", start + 1);
        return js.substring(start, next < 0 ? js.length() : next);
    }

    /**
     * The picker's behaviour runs in a real browser (e2e); this pins the
     * contract with the markup and the server: the limits come from the
     * data attributes the server renders — never numbers of its own — and
     * the input's file list is rebuilt, not replaced, so every file stays
     * paired with its {@code photoTags[j]} field.
     */
    @Test
    void submissionFormJs_photoPicker_takesItsLimitsFromTheMarkup_andNeverHardCodesThem() throws Exception {
        String picker = alpineComponent(submissionFormJs(), "photoPicker");

        assertThat(picker).contains(
                "dataset.maxPerTopic", "dataset.maxTotal", "dataset.maxBytes", "dataset.maxSizeLabel",
                "dataset.tagsName");
        assertThat(withoutComments(picker)).doesNotContainPattern("\\b(5|20|50|20971520)\\b")
                .doesNotContain("MB");
    }

    @Test
    void submissionFormJs_photoPicker_appendsThroughADataTransfer_andRevokesEveryPreviewUrl() throws Exception {
        String picker = alpineComponent(submissionFormJs(), "photoPicker");

        assertThat(picker).contains("new DataTransfer()", "URL.createObjectURL(", "URL.revokeObjectURL(");
        assertThat(picker).contains(
                "is larger than", "You can add at most", "to this topic.", "in total.", "is not an image.");
    }

    /**
     * The row's behaviour runs in a browser (e2e); this pins the fix for
     * BL-030. {@code update()} also runs from the value input's {@code
     * x-on:input}, where Alpine's {@code $el} is that input, not the row —
     * so looking the checkbox up through {@code this.$el} found nothing and
     * left it ticked. The row is captured once, in {@code init()}, and
     * {@code update()} finds the checkbox through it.
     */
    @Test
    void submissionFormJs_contactRow_findsItsCheckboxThroughTheRowCapturedInInit_neverThroughThisEl()
            throws Exception {
        String row = withoutComments(alpineComponent(submissionFormJs(), "contactRow"))
                .replaceAll("(?m)//.*$", "");

        assertThat(jsMethodBody(row, "init()")).containsPattern("\\b(\\w+) = this\\.\\$el;");
        String rowVariable = jsMethodBody(row, "init()").replaceAll("(?s).*?\\b(\\w+) = this\\.\\$el;.*", "$1");
        assertThat(jsMethodBody(row, "update(value)"))
                .doesNotContain("$el")
                .contains(rowVariable + ".querySelector('input[type=\"checkbox\"]').checked = false;");
        assertThat(row.split("this\\.\\$el", -1)).as("this.$el used only in init()").hasSize(2);
    }

    /** The body of the JS method declared as {@code signature {…}}, braces matched. */
    private static String jsMethodBody(String js, String signature) {
        int start = js.indexOf(signature + " {");
        assertThat(start).as("method " + signature).isNotNegative();
        int open = js.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < js.length(); i++) {
            if (js.charAt(i) == '{') {
                depth++;
            } else if (js.charAt(i) == '}' && --depth == 0) {
                return js.substring(open + 1, i);
            }
        }
        throw new AssertionError("Unclosed method " + signature);
    }

    @Test
    void submissionFormJs_formStillLeavesEmptyPhotoInputsOutOfTheSubmission_andRestoresThemOnPageShow()
            throws Exception {
        String form = alpineComponent(submissionFormJs(), "submissionForm");

        assertThat(form).contains("disableEmptyUploads()", "restoreUploads()", "input[type=\"file\"]");
    }

    /**
     * The slider's own fallback (used only if the server-rendered {@code
     * data-initial-score} is missing or not a number) must match the
     * server's default for a form without a score yet: 10.
     */
    @Test
    void submissionFormJs_scoreSliderFallsBackToTheSameDefaultScoreAsTheServer() throws Exception {
        String js = mockMvc.perform(get("/js/submission-form.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String slider = js.substring(js.indexOf("Alpine.data('scoreSlider'"));
        assertThat(slider).containsPattern("score:\\s*10,").doesNotContainPattern("score:\\s*5,");
    }

    /**
     * The login pages put the field and the submit button inside a {@code
     * <form>} within {@code .auth-card}; the card's own gap doesn't reach
     * inside the form, so the form must lay its children out with the same
     * gap, or the button sticks to the input.
     */
    @Test
    void beaconCss_spacesTheChildrenOfEveryFormInsideAnAuthCard() throws Exception {
        String css = mockMvc.perform(get("/css/beacon.css"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Matcher rule = Pattern.compile("\\.auth-card form\\s*\\{([^}]*)}").matcher(css);
        assertThat(rule.find()).as(".auth-card form rule").isTrue();
        assertThat(rule.group(1))
                .contains("display: flex;")
                .contains("flex-direction: column;")
                .contains("gap: var(--space-4);");
    }

    @Test
    void sessionCheckJs_isServedToAnonymousVisitorsWithJavascriptContentType() throws Exception {
        // Anonymous too: the script loads while the session may already be gone.
        mockMvc.perform(get("/js/session-check.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"));
    }

    /**
     * The script's behaviour is exercised in a real browser by the e2e
     * suite; this only pins down the contract a browser can't be asked
     * about cheaply: it reacts to the tab becoming visible again and to
     * nothing else (no focus or page-show handlers), reads its ping URL
     * from its own tag, and stays inert on the login pages.
     */
    @Test
    void sessionCheckJs_listensOnlyForTheTabBecomingVisibleAgain() throws Exception {
        String js = mockMvc.perform(get("/js/session-check.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(js).containsOnlyOnce("addEventListener(");
        assertThat(js).contains("document.addEventListener('visibilitychange'");
        assertThat(js).contains("document.visibilityState === 'visible'");
        assertThat(js).doesNotContain("onfocus", "onpageshow", "onvisibilitychange");
        assertThat(js).contains("data-session-url");
        assertThat(js).contains("'/admin/login'", "'/submissions/login'");
    }

    @Test
    void beaconCss_stylesPerFieldErrorsAndInvalidInputs() throws Exception {
        mockMvc.perform(get("/css/beacon.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".submission-field-error")))
                .andExpect(content().string(containsString(".submission-field-input--invalid")));
    }

    /** Linked from a {@code <noscript>} on the submission form: without JS, nothing stays cloaked. */
    @Test
    void noscriptCss_showsWhatAlpineWouldHaveUncloaked() throws Exception {
        String css = mockMvc.perform(get("/css/noscript.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(topLevelRule(css, "[x-cloak]")).contains("display: block !important;");
    }

    /** One class per score, 0 to 10, in that score's colour: the pages' score badges use them (no inline style). */
    @Test
    void beaconCss_hasAColourClassForEveryScore() throws Exception {
        String css = beaconCss();

        for (int score = 0; score <= 10; score++) {
            assertThat(css).contains(".score-" + score + " { color: var(--score-" + score + "); }");
        }
    }

    @Test
    void beaconCss_hidesAlpineCloakedElementsUntilAlpineStarts() throws Exception {
        mockMvc.perform(get("/css/beacon.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("[x-cloak]")));
    }

    // -- responsive layout and the burger menu --

    /** {@code css} without its comments. */
    private static String withoutComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /** {@code css} (comments removed) with every {@code @media} block cut out: only its top-level rules. */
    private static String topLevelRules(String css) {
        String rest = withoutComments(css);
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (int at = rest.indexOf("@media"); at >= 0; at = rest.indexOf("@media", from)) {
            out.append(rest, from, at);
            int depth = 0;
            int i = rest.indexOf('{', at);
            for (; i < rest.length(); i++) {
                if (rest.charAt(i) == '{') {
                    depth++;
                } else if (rest.charAt(i) == '}' && --depth == 0) {
                    break;
                }
            }
            from = i + 1;
        }
        return out.append(rest.substring(from)).toString();
    }

    /**
     * Bodies of the rules in {@code flatCss} (plain rules, no {@code @media})
     * whose comma-separated selector list includes {@code selector} exactly.
     */
    private static List<String> ruleBodiesFor(String flatCss, String selector) {
        Matcher rule = Pattern.compile("([^{}]+)\\{([^{}]*)}").matcher(withoutComments(flatCss));
        List<String> bodies = new ArrayList<>();
        while (rule.find()) {
            for (String each : rule.group(1).split(",")) {
                if (each.strip().equals(selector)) {
                    bodies.add(rule.group(2));
                }
            }
        }
        return bodies;
    }

    /** All the rule bodies for {@code selector} in {@code flatCss}, joined — fails if there is none. */
    private static String declarationsFor(String flatCss, String selector) {
        List<String> bodies = ruleBodiesFor(flatCss, selector);
        assertThat(bodies).as("rules for " + selector).isNotEmpty();
        return String.join("\n", bodies);
    }

    @Test
    void navToggleJs_isServedToAnonymousVisitorsWithJavascriptContentType() throws Exception {
        // Anonymous: it is loaded on every page, the login pages included.
        mockMvc.perform(get("/js/nav-toggle.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"));
    }

    private String navToggleJs() throws Exception {
        return mockMvc.perform(get("/js/nav-toggle.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * The script's behaviour is exercised in a real browser by the e2e
     * suite; this pins the contract with the markup of {@code
     * layout/nav-toggle.html}: it is driven by data/ARIA attributes only
     * (any header can reuse it), closes on Escape and on a click outside,
     * and is plain JS — no framework, no module.
     */
    @Test
    void navToggleJs_isAPlainScriptDrivenByTheToggleButtonsDataAndAriaAttributes() throws Exception {
        String js = navToggleJs();

        assertThat(js).contains("'[data-nav-toggle]'", "'aria-controls'", "'aria-expanded'");
        assertThat(js).contains("'keydown'", "'Escape'", "'click'");
        assertThat(js).doesNotContain("Alpine", "import ", "export ", "x-data", "window.location");
    }

    /**
     * Every class the script puts on a toggle or its menu is styled — the
     * stylesheet can't hide the menu (or show the button) on a class the
     * script never sets, nor the other way round.
     */
    @ParameterizedTest
    @ValueSource(strings = {"nav-toggle--ready", "nav-menu--collapsible", "nav-menu--open"})
    void navToggleJs_classesItSets_areTheOnesTheStylesheetUses(String className) throws Exception {
        assertThat(navToggleJs()).contains("'" + className + "'");
        assertThat(withoutComments(beaconCss())).contains("." + className);
    }

    /**
     * Without JavaScript nothing is collapsed: the button is hidden
     * everywhere by default and shown on phones only once the script has
     * marked it ready; the menu is hidden only through the class the script
     * adds, never through the header's own nav class, so on a phone without
     * JS the header just wraps its links onto a second row.
     */
    @Test
    void beaconCss_collapsesTheHeaderNavOnPhonesOnlyOnceTheScriptIsReady() throws Exception {
        String css = beaconCss();
        String narrow = mediaBlock(css, "(max-width: 720px)");

        assertThat(declarationsFor(topLevelRules(css), ".nav-toggle")).contains("display: none;");
        assertThat(declarationsFor(narrow, ".nav-toggle--ready")).contains("display: inline-flex;");
        assertThat(declarationsFor(narrow, ".nav-menu--collapsible:not(.nav-menu--open)"))
                .contains("display: none;");

        assertThat(declarationsFor(narrow, ".header-bar")).contains("flex-wrap: wrap;");
        assertThat(declarationsFor(narrow, ".header-nav")).contains("flex-wrap: wrap;").doesNotContain("display: none");
        assertThat(ruleBodiesFor(topLevelRules(css), ".header-nav"))
                .allSatisfy(body -> assertThat(body).doesNotContain("display: none"));
    }

    /** Explicit decision: 4 columns at 1200px and up, 3 from 900px, 2 from 560px, 1 below. */
    @Test
    void beaconCss_galleryGrid_hasFourThreeTwoOneColumnsAtTheAgreedBoundaries() throws Exception {
        String css = beaconCss();

        assertThat(declarationsFor(topLevelRules(css), ".gallery-grid"))
                .contains("grid-template-columns: repeat(4, minmax(0, 1fr));");
        assertThat(declarationsFor(mediaBlock(css, "(max-width: 1199px)"), ".gallery-grid"))
                .contains("grid-template-columns: repeat(3, minmax(0, 1fr));");
        assertThat(declarationsFor(mediaBlock(css, "(max-width: 899px)"), ".gallery-grid"))
                .contains("grid-template-columns: repeat(2, minmax(0, 1fr));");
        assertThat(declarationsFor(mediaBlock(css, "(max-width: 559px)"), ".gallery-grid"))
                .contains("grid-template-columns: minmax(0, 1fr);");
    }

    /**
     * Same specificity, so the cascade order decides: each narrower query
     * must come after the wider one (and all after the base rule), and no
     * other breakpoint may change the grid's columns in between.
     */
    @Test
    void beaconCss_galleryGridBreakpoints_comeWidestFirst_andNoOtherQueryOverridesThem() throws Exception {
        String css = beaconCss();
        int base = css.indexOf("\n.gallery-grid {");
        int wide = css.indexOf("@media (max-width: 1199px) {");
        int medium = css.indexOf("@media (max-width: 899px) {");
        int narrow = css.indexOf("@media (max-width: 559px) {");

        assertThat(base).isNotNegative();
        assertThat(wide).isGreaterThan(base);
        assertThat(medium).isGreaterThan(wide);
        assertThat(narrow).isGreaterThan(medium);
        assertThat(ruleBodiesFor(mediaBlock(css, "(max-width: 720px)"), ".gallery-grid")).isEmpty();
        assertThat(ruleBodiesFor(mediaBlock(css, "(max-width: 480px)"), ".gallery-grid")).isEmpty();
    }

    // -- homepage dashboard (analytics/dashboard.html, decision 30) --

    /** Explicit decision: 4 stat cards and 2 lists side by side; on phones 2 stat columns and 1 list column. */
    @Test
    void beaconCss_dashboardGrids_haveFourAndTwoColumns_andTwoAndOneOnPhones() throws Exception {
        String css = beaconCss();
        String narrow = mediaBlock(css, "(max-width: 720px)");

        assertThat(declarationsFor(topLevelRules(css), ".dashboard-stat-grid"))
                .contains("display: grid;", "grid-template-columns: repeat(4, minmax(0, 1fr));");
        assertThat(declarationsFor(topLevelRules(css), ".dashboard-list-grid"))
                .contains("display: grid;", "grid-template-columns: repeat(2, minmax(0, 1fr));");
        assertThat(declarationsFor(narrow, ".dashboard-stat-grid"))
                .contains("grid-template-columns: repeat(2, minmax(0, 1fr));");
        assertThat(declarationsFor(narrow, ".dashboard-list-grid")).contains("grid-template-columns: minmax(0, 1fr);");
    }

    /** Only the phone block steps the dashboard grids: no other breakpoint changes their columns in between. */
    @Test
    void beaconCss_dashboardGrids_areSteppedOnlyByThePhoneBlock() throws Exception {
        String css = beaconCss();

        for (String grid : List.of(".dashboard-stat-grid", ".dashboard-list-grid")) {
            for (String query : List.of("(max-width: 1199px)", "(max-width: 899px)", "(max-width: 559px)",
                    "(max-width: 480px)")) {
                assertThat(ruleBodiesFor(mediaBlock(css, query), grid)).as(grid + " in " + query).isEmpty();
            }
        }
    }

    /** The choropleth's five steps, light gold to maroon (decision 30). */
    @Test
    void beaconCss_declaresTheFiveMapShadeTokens_andFillsEachShadeWithIt() throws Exception {
        String css = beaconCss();
        String flat = topLevelRules(css);

        assertThat(topLevelRule(css, ":root"))
                .contains(
                        "--map-shade-1: #f5e3bd;",
                        "--map-shade-2: #e6c07a;",
                        "--map-shade-3: #d6a64f;",
                        "--map-shade-4: #c2603a;",
                        "--map-shade-5: #ae152d;");
        for (int shade = 1; shade <= 5; shade++) {
            assertThat(declarationsFor(flat, ".map-shade-" + shade)).contains("fill: var(--map-shade-" + shade + ");");
            assertThat(declarationsFor(flat, ".dashboard-map-swatch--" + shade))
                    .contains("background: var(--map-shade-" + shade + ");");
        }
    }

    /** Land is paper with the line colour for borders, on the sand-coloured sea of the mockup. */
    @Test
    void beaconCss_mapLandIsPaperOnSand_andALinkedCountryShowsHoverAndKeyboardFocus() throws Exception {
        String flat = topLevelRules(beaconCss());

        assertThat(declarationsFor(flat, ".dashboard-map")).contains("background: var(--sand);");
        assertThat(declarationsFor(flat, ".map-region")).contains("fill: var(--paper);", "stroke: var(--line);");
        assertThat(declarationsFor(flat, ".dashboard-map a:hover .map-region")).contains("stroke: var(--ink);");
        assertThat(declarationsFor(flat, ".dashboard-map a:focus-visible .map-region")).contains("stroke: var(--ink);");
    }

    /**
     * The wordmark is a link home now (decision 30) but must look exactly as
     * before: the global {@code a:hover} would otherwise underline it and
     * darken its colour.
     */
    @Test
    void beaconCss_wordmarkLink_keepsItsColourAndNoUnderlineOnHover() throws Exception {
        String flat = topLevelRules(beaconCss());

        assertThat(declarationsFor(flat, "a.wordmark:hover"))
                .contains("color: var(--maroon);", "text-decoration: none;");
    }

    /** The small-phone block refines the phone block, so it must come after it. */
    @Test
    void beaconCss_smallPhoneBlockComesAfterThePhoneBlock() throws Exception {
        String css = beaconCss();

        assertThat(mediaBlock(css, "(max-width: 480px)")).isNotBlank();
        assertThat(css.indexOf("@media (max-width: 480px) {"))
                .isGreaterThan(css.indexOf("@media (max-width: 720px) {"));
    }

    /**
     * iOS Safari zooms the page into any focused form control whose text is
     * under 16px — and the page's own control classes (14px, 15px, 11px)
     * are more specific than a plain element rule, so it must win anyway.
     */
    @Test
    void beaconCss_onPhones_setsEveryFormControlTo16px() throws Exception {
        String narrow = mediaBlock(beaconCss(), "(max-width: 720px)");

        assertThat(nestedRule(narrow, "input,\n  select,\n  textarea")).contains("font-size: 16px !important;");
    }

    @Test
    void beaconCss_onPhones_putsTheArticleSidebarUnderTheArticle() throws Exception {
        String narrow = mediaBlock(beaconCss(), "(max-width: 720px)");

        assertThat(declarationsFor(narrow, ".gallery-detail-body")).contains("grid-template-columns: minmax(0, 1fr);");
    }

    @Test
    void beaconCss_onPhones_stacksTheFormsSideBySideFields_andDropsTheContactLabelColumn() throws Exception {
        String narrow = mediaBlock(beaconCss(), "(max-width: 720px)");

        assertThat(declarationsFor(narrow, ".submission-field-row")).contains("flex-direction: column;");
        assertThat(declarationsFor(narrow, ".submission-contact-label")).contains("width: 100%;");
        assertThat(declarationsFor(narrow, ".submission-contact-error")).contains("padding-left: 0;");
        assertThat(declarationsFor(narrow, ".submission-photo-tag-input")).contains("width: 100%;");
    }

    // -- the submission form's photo picker --

    @Test
    void beaconCss_photoGrid_autoFillsTilesOfAtLeast120px_twoColumnsOnAPhoneFourOnTheDesktopForm()
            throws Exception {
        String css = topLevelRules(beaconCss());

        assertThat(declarationsFor(css, ".submission-photo-grid"))
                .contains("display: grid;", "grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));");
        assertThat(declarationsFor(css, ".submission-photo-frame")).contains("aspect-ratio: 4 / 3;");
        assertThat(declarationsFor(css, ".submission-photo-tile-image")).contains("object-fit: cover;");
        assertThat(declarationsFor(css, ".submission-photo-tile")).contains("min-width: 0;");
    }

    @Test
    void beaconCss_addPhotosControl_isADashedDropZone_markedWhileAFileIsDraggedOverIt() throws Exception {
        String css = topLevelRules(beaconCss());

        assertThat(declarationsFor(css, ".submission-photo-add")).contains("border: 1.5px dashed");
        assertThat(ruleBodiesFor(css, ".submission-photo-add--dragover")).isNotEmpty();
        assertThat(ruleBodiesFor(css, ".submission-photo-add--disabled")).isNotEmpty();
    }

    @Test
    void beaconCss_withoutTheScript_theNativeInputAndRemoveCheckboxShow_withItTheDropZoneAndTheCrossDo()
            throws Exception {
        String css = topLevelRules(beaconCss());

        assertThat(declarationsFor(css, ".submission-photo-remove")).contains("display: none;");
        assertThat(declarationsFor(css, ".submission-photos--js .submission-photo-remove")).contains("display: flex;");
        assertThat(declarationsFor(css, ".submission-photos--js .submission-photo-remove-check"))
                .contains("display: none;");
        assertThat(declarationsFor(css, ".submission-photos--js .submission-photo-add-input"))
                .contains("position: absolute;", "opacity: 0;");
    }

    @Test
    void beaconCss_onPhones_photoRemoveButtonsHaveA44pxTapArea() throws Exception {
        String narrow = mediaBlock(beaconCss(), "(max-width: 720px)");

        assertThat(declarationsFor(narrow, ".submission-photo-remove")).contains("width: 36px;", "height: 36px;");
        assertThat(declarationsFor(narrow, ".submission-photo-remove::after")).contains("inset: -4px;");
    }

    /** Their own display (grid, flex) would otherwise beat the [hidden] attribute Alpine toggles. */
    @Test
    void beaconCss_aRemovedSavedPhotoAndAnEmptyPhotoGrid_areReallyHidden() throws Exception {
        String css = topLevelRules(beaconCss());

        assertThat(declarationsFor(css, ".submission-photo-grid[hidden]")).contains("display: none;");
        assertThat(declarationsFor(css, ".submission-photo-tile[hidden]")).contains("display: none;");
    }

    @Test
    void beaconCss_newPhotosOfATopicWithoutText_areDimmed() throws Exception {
        assertThat(declarationsFor(topLevelRules(beaconCss()), ".submission-photo-tile--inactive"))
                .contains("opacity: 0.5;");
    }

    @Test
    void beaconCss_withoutTheScript_theDropZoneNamesItselfBeforeTheNativeInput() throws Exception {
        assertThat(declarationsFor(topLevelRules(beaconCss()), ".submission-photo-add-input")).contains("order: 1;");
    }

    @Test
    void beaconCss_dropsTheFixedUploadSlotRules() throws Exception {
        assertThat(beaconCss()).doesNotContain(".submission-photo-upload-");
    }

    @Test
    void beaconCss_onPhones_wrapsTheQueueItemHeader_andStacksTheRejectForm() throws Exception {
        String narrow = mediaBlock(beaconCss(), "(max-width: 720px)");

        assertThat(declarationsFor(narrow, ".moderation-item-header")).contains("flex-wrap: wrap;");
        assertThat(declarationsFor(narrow, ".moderation-reject-form"))
                .contains("min-width: 0;", "flex-direction: column;");
    }

    // -- page background and height --

    /**
     * The body's background also paints the whole viewport below it, so a
     * page shorter than the screen is beige to the bottom — no white band
     * under the content. The header keeps its own white.
     */
    @Test
    void beaconCss_pageBackgroundIsSand_soShortPagesAreBeigeToTheBottom() throws Exception {
        String css = topLevelRules(beaconCss());

        assertThat(declarationsFor(css, "body")).contains("background: var(--sand);").doesNotContain("var(--paper)");
        assertThat(declarationsFor(css, ".header-bar")).contains("background: var(--paper);");
    }

    /**
     * Header on top, then {@code main} stretched over the rest of the screen:
     * the login and confirmation pages centre their card in it without
     * guessing the header's height (a hard-coded 60px left a strip on desktop
     * and a 1px scroll on phones, where the header is 61px).
     */
    @Test
    void beaconCss_bodyIsAFullHeightColumn_whoseMainFillsTheRest() throws Exception {
        String css = topLevelRules(beaconCss());

        assertThat(declarationsFor(css, "body"))
                .contains("display: flex;", "flex-direction: column;", "min-height: 100vh;", "min-height: 100dvh;");
        assertThat(declarationsFor(css, "body > main")).contains("flex: 1 0 auto;");
        assertThat(withoutComments(beaconCss())).doesNotContain("100vh - 60px");
    }

    // -- chips on phones --

    /**
     * Compact on phones — 32px tall, 13px text, 10px side padding, 6px apart
     * in a row — but still easy to tap: an invisible area 4px above and below
     * each chip makes it a 40px target, and the rows' 8px gap keeps two
     * rows' targets from overlapping.
     */
    @ParameterizedTest
    @ValueSource(strings = {".gallery-chip-toggle", ".submission-chip-toggle"})
    void beaconCss_onPhones_topicChipsAreCompact_withATallerInvisibleTapArea(String chip) throws Exception {
        String narrow = mediaBlock(beaconCss(), "(max-width: 720px)");

        assertThat(declarationsFor(narrow, chip))
                .contains("height: 32px;", "padding: 0 10px;", "font-size: 13px;", "position: relative;")
                .doesNotContain("44px");
        assertThat(declarationsFor(narrow, chip + "::after"))
                .contains("content: \"\";", "position: absolute;", "inset: -4px 0;");
    }

    @ParameterizedTest
    @ValueSource(strings = {".gallery-topic-filter-row", ".submission-chip-toggle-row"})
    void beaconCss_onPhones_chipRowsKeepAnEightPixelRowGap_andASixPixelColumnGap(String row) throws Exception {
        String narrow = mediaBlock(beaconCss(), "(max-width: 720px)");

        assertThat(declarationsFor(narrow, row)).contains("gap: var(--space-2) 6px;");
    }

    // -- contact reveal (gallery/contact-card.html, static/js/contact-reveal.js) --

    private String contactRevealJs() throws Exception {
        return mockMvc.perform(get("/js/contact-reveal.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * The script's behaviour is exercised in a real browser by the e2e
     * suite; this pins its contract with the page and the server: it takes
     * over the reveal form's submit, POSTs to the form's own action on the
     * same origin with the header that asks for the card alone, and swaps the
     * card in place — never navigating or scrolling. Plain JS, no framework.
     */
    @Test
    void contactRevealJs_postsTheFormItselfAskingForTheCard_andSwapsItInPlace() throws Exception {
        String js = contactRevealJs();

        assertThat(js).contains("'.gallery-reveal-form'", "'submit'", "preventDefault()");
        assertThat(js).contains("fetch(form.action", "method: 'POST'", "credentials: 'same-origin'");
        assertThat(js).contains("'X-Requested-With': 'fetch'");
        assertThat(js).contains("'[data-contact-card]'", "replaceWith(", "preventScroll: true");
        assertThat(js).doesNotContain(
                "window.location", "location.href", "scrollTo", "scrollIntoView", "Alpine", "import ", "export ");
    }

    /** While the request runs the button is busy and inert; a failure shows the error line and gives it back. */
    @Test
    void contactRevealJs_marksTheButtonBusy_andShowsTheErrorLineOnFailure() throws Exception {
        String js = contactRevealJs();

        assertThat(js).contains("'aria-busy'", "'aria-disabled'", "'[data-contact-reveal-error]'");
        assertThat(js).contains(".catch(");
    }

    @Test
    void beaconCss_stylesTheRevealButtonsSpinner_busyState_andErrorLine() throws Exception {
        String css = beaconCss();
        String flat = topLevelRules(css);

        assertThat(declarationsFor(flat, ".gallery-reveal-spinner")).contains("display: none;", "position: absolute;");
        assertThat(declarationsFor(flat, ".gallery-reveal-btn[aria-busy=\"true\"] .gallery-reveal-spinner"))
                .contains("display: block;");
        assertThat(declarationsFor(flat, ".gallery-reveal-btn[aria-disabled=\"true\"]")).contains("cursor: progress;");
        assertThat(declarationsFor(flat, ".gallery-reveal-btn")).contains("position: relative;");
        assertThat(declarationsFor(flat, ".gallery-reveal-error")).contains("color: var(--red);");
        assertThat(css).contains("@keyframes gallery-reveal-spin");
    }

    /** Without JavaScript the reveal lands on #contact: the card stops short of the screen's top edge. */
    @Test
    void beaconCss_leavesRoomAboveTheContactCard_whenTheBrowserJumpsToIt() throws Exception {
        assertThat(declarationsFor(topLevelRules(beaconCss()), ".gallery-side-card[data-contact-card]"))
                .contains("scroll-margin-top: var(--space-5);");
    }

    /**
     * Names, answers, emails and contact values are typed by users: a single
     * long word (a URL, say) must break inside its box instead of pushing
     * the page wider than a phone screen.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        ".gallery-card-excerpt",
        ".gallery-article-title",
        ".gallery-answer-text",
        ".gallery-contact-chip",
        ".moderation-identity-line",
        ".moderation-meta-line",
        ".moderation-section-row-body",
        ".moderation-contact-chip",
        ".auth-subtitle strong",
        ".confirmation-note strong",
        ".submission-error-text",
        ".dashboard-list-label",
        ".dashboard-chip-name",
    })
    void beaconCss_breaksLongWordsInUserContent(String selector) throws Exception {
        assertThat(declarationsFor(topLevelRules(beaconCss()), selector)).contains("overflow-wrap: anywhere;");
    }
}
