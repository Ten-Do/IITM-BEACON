package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

    /** Linked from a {@code <noscript>} in every page's head: without JS, nothing stays cloaked. */
    @Test
    void noscriptCss_showsWhatAlpineWouldHaveUncloaked() throws Exception {
        assertThat(topLevelRule(noscriptCss(), "[x-cloak]")).contains("display: block !important;");
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
     * The script's one class, the open menu's, is the one the stylesheet
     * shows the menu by; the classes it used to set before the stylesheet
     * hid the menu on its own (until it ran, a phone showed the whole nav for a moment)
     * are gone from both.
     */
    @Test
    void navToggleJs_setsOnlyTheOpenClass_whichTheStylesheetShowsTheMenuBy() throws Exception {
        String js = withoutComments(navToggleJs());
        String css = withoutComments(beaconCss());

        assertThat(js).contains("'nav-menu--open'");
        assertThat(css).contains(".nav-menu--open");
        assertThat(js + css).doesNotContain("nav-toggle--ready", "nav-menu--collapsible");
    }

    /**
     * On phones the burger shows and the nav is hidden by the stylesheet
     * alone, from the first paint — no class from the script needed, so
     * nothing flashes while the deferred script loads. Hidden, not removed:
     * {@code visibility: hidden} keeps its links out of the tab order and
     * lets it fade; {@code display: none} could not be transitioned. On a
     * desktop the burger stays hidden and the nav is never hidden.
     */
    @Test
    void beaconCss_onPhones_showsTheBurgerAndHidesTheNav_fromTheFirstPaint() throws Exception {
        String css = beaconCss();
        String narrow = mediaBlock(css, "(max-width: 720px)");

        assertThat(declarationsFor(topLevelRules(css), ".nav-toggle")).contains("display: none;");
        assertThat(declarationsFor(narrow, ".nav-toggle")).contains("display: inline-flex;");

        assertThat(declarationsFor(narrow, ".header-nav"))
                .contains(
                        "position: absolute;",
                        "visibility: hidden;",
                        "opacity: 0;",
                        "transform: translateY(-8px);",
                        "pointer-events: none;")
                .doesNotContain("display: none");
        assertThat(ruleBodiesFor(topLevelRules(css), ".header-nav"))
                .allSatisfy(body -> assertThat(body).doesNotContain("display: none", "visibility", "opacity"));
        // Kept for the no-JavaScript look (noscript.css puts the nav back in the header's flow).
        assertThat(declarationsFor(narrow, ".header-bar")).contains("position: relative;", "flex-wrap: wrap;");
    }

    /** Opened by the script, the panel slides and fades in — and out again when closed. */
    @Test
    void beaconCss_onPhones_theOpenMenuShows_slidingAndFading() throws Exception {
        String narrow = mediaBlock(beaconCss(), "(max-width: 720px)");

        assertThat(declarationsFor(narrow, ".header-nav.nav-menu--open"))
                .contains("visibility: visible;", "opacity: 1;", "transform: none;", "pointer-events: auto;");
        assertThat(declarationsFor(narrow, ".header-nav"))
                .contains(
                        "opacity var(--motion-medium) var(--ease-standard)",
                        "transform var(--motion-medium) var(--ease-standard)",
                        "visibility var(--motion-medium)");
    }

    private String noscriptCss() throws Exception {
        return mockMvc.perform(get("/css/noscript.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Decision 26: without JavaScript nothing is collapsed. On phones the
     * no-JS stylesheet (linked from every page's head) hides the burger —
     * nothing would open the menu — and puts the nav back in the header's
     * flow, wrapping onto a second row, visible and reachable.
     */
    @Test
    void noscriptCss_onPhones_hidesTheBurger_andShowsTheNavInTheHeader() throws Exception {
        String narrow = mediaBlock(noscriptCss(), "(max-width: 720px)");

        assertThat(declarationsFor(narrow, ".nav-toggle")).contains("display: none;");
        assertThat(declarationsFor(narrow, ".header-nav"))
                .contains(
                        "position: static;",
                        "flex-basis: 100%;",
                        "flex-direction: row;",
                        "flex-wrap: wrap;",
                        "visibility: visible;",
                        "opacity: 1;",
                        "transform: none;",
                        "pointer-events: auto;");
    }

    /** On a desktop the header needs nothing from it: the nav is never hidden there. */
    @Test
    void noscriptCss_leavesTheDesktopHeaderAlone() throws Exception {
        String flat = topLevelRules(noscriptCss());

        assertThat(ruleBodiesFor(flat, ".header-nav")).isEmpty();
        assertThat(ruleBodiesFor(flat, ".nav-toggle")).isEmpty();
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

    // -- submit buttons: busy while their form is being sent (static/js/form-submit.js), hover and press --

    private String formSubmitJs() throws Exception {
        return mockMvc.perform(get("/js/form-submit.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Its behaviour runs in a browser (e2e); this pins the contract: one
     * submit listener, on the document and in the bubble phase (no capture
     * argument) — so a form's own listener runs first, and a submit it has
     * taken over ({@code defaultPrevented}) is left alone; a second submit
     * of a form being sent is prevented; the submitter is marked busy with
     * ARIA attributes, never the {@code disabled} attribute (which would drop
     * the keyboard focus and leave its name and value out of the
     * submission); a page restored from the back/forward cache is reset.
     */
    @Test
    void formSubmitJs_marksTheSubmitterBusy_blocksASecondSubmit_andResetsARestoredPage() throws Exception {
        String js = withoutComments(formSubmitJs());

        assertThat(js).containsOnlyOnce("addEventListener('submit'");
        assertThat(js).containsPattern("document\\.addEventListener\\('submit', \\w+\\);");
        assertThat(js).contains("event.defaultPrevented", "event.preventDefault()", "event.submitter");
        assertThat(js).contains("'aria-busy'", "'aria-disabled'");
        assertThat(js).doesNotContain("'disabled'").doesNotContainPattern("\\.disabled\\s*=");
        assertThat(js).contains("'pageshow'", ".persisted");
        assertThat(js).doesNotContain("Alpine", "import ", "export ", "fetch(", "window.location");
    }

    /**
     * Each family of buttons that submit a form (with the links styled like
     * them), as {@code base selector, selector of the element it styles}:
     * the pill is also a plain locked {@code <span>}, which gets no feedback.
     */
    private static final String[] SUBMIT_BUTTON_FAMILIES = {
        ".auth-btn-primary, .auth-btn-primary",
        ".auth-btn-ghost, .auth-btn-ghost",
        ".submission-btn-primary, .submission-btn-primary",
        ".submission-btn-ghost, .submission-btn-ghost",
        ".moderation-btn-approve, .moderation-btn-approve",
        ".moderation-btn-reject, .moderation-btn-reject",
        ".catalog-btn-primary, .catalog-btn-primary",
        ".catalog-btn-ghost, .catalog-btn-ghost",
        ".catalog-btn-danger, .catalog-btn-danger",
        ".catalog-pill, button.catalog-pill",
        ".gallery-filter-submit, .gallery-filter-submit",
        ".header-nav-button, .header-nav-button",
        ".confirmation-btn, .confirmation-btn",
        ".gallery-reveal-btn, .gallery-reveal-btn",
    };

    private static final String NOT_BUSY = ":not([aria-busy=\"true\"])";

    /** {@code property → value} of the declarations in {@code body}, a later one winning. */
    private static Map<String, String> declarationMap(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String declaration : body.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon > 0) {
                map.put(declaration.substring(0, colon).strip(), declaration.substring(colon + 1).strip());
            }
        }
        return map;
    }

    /**
     * Under a real pointer (a touch screen's {@code :hover} sticks after a
     * tap) every family's hover visibly changes its background, border or
     * text colour from the resting look — the catalog's used to repeat it —
     * but not while the button is busy.
     */
    @ParameterizedTest
    @MethodSource("submitButtonFamilies")
    void beaconCss_submitButtons_changeColourOnHover_unlessBusy(String base, String element) throws Exception {
        String css = withoutComments(beaconCss());

        String hover = declarationsFor(mediaBlocks(css, "(hover: hover)"), element + NOT_BUSY + ":hover");
        Map<String, String> hovered = declarationMap(hover);
        Map<String, String> resting = declarationMap(declarationsFor(topLevelRules(css), base));

        List<String> colours = List.of("background", "background-color", "border-color", "color");
        assertThat(hovered.keySet()).as(element + ":hover").containsAnyElementsOf(colours);
        assertThat(colours.stream().filter(hovered::containsKey))
                .as(element + ":hover changes something from " + resting)
                .anySatisfy(property -> assertThat(resting.get(property)).isNotEqualTo(hovered.get(property)));
    }

    static Stream<Arguments> submitButtonFamilies() {
        return Arrays.stream(SUBMIT_BUTTON_FAMILIES)
                .map(pair -> pair.split(", "))
                .map(pair -> Arguments.of(pair[0], pair[1]));
    }

    /**
     * Pressed, it sinks a pixel; its colours ease in and out with the motion
     * tokens (switched off for reduced motion — see the transitions test).
     * Pressing must win over hovering: same specificity, so the {@code
     * :active} rules come after the hover ones.
     */
    @ParameterizedTest
    @MethodSource("submitButtonFamilies")
    void beaconCss_submitButtons_sinkWhenPressed_unlessBusy_andEaseTheirColours(String base, String element)
            throws Exception {
        String css = withoutComments(beaconCss());
        String flat = topLevelRules(css);

        assertThat(declarationsFor(flat, element + NOT_BUSY + ":active")).contains("transform: translateY(1px);");
        assertThat(declarationsFor(flat, base))
                .contains(
                        "background-color var(--motion-fast) var(--ease-standard)",
                        "border-color var(--motion-fast) var(--ease-standard)",
                        "color var(--motion-fast) var(--ease-standard)",
                        "transform var(--motion-fast) var(--ease-standard)");
        int lastHover = css.lastIndexOf(element + NOT_BUSY + ":hover");
        assertThat(css.indexOf(element + NOT_BUSY + ":active")).isGreaterThan(lastHover);
    }

    /**
     * Busy (form-submit.js), a submit button shows a progress cursor and a
     * spinner after its label, turning like the reveal button's — which
     * keeps its own spinner, so it gets no second one. Reduced motion slows
     * it (see the animations test).
     */
    @Test
    void beaconCss_aBusySubmitButton_showsAProgressCursorAndASpinner() throws Exception {
        String flat = topLevelRules(beaconCss());

        assertThat(declarationsFor(flat, "button[aria-busy=\"true\"]")).contains("cursor: progress;");
        assertThat(declarationsFor(flat, "button[aria-busy=\"true\"]:not(.gallery-reveal-btn)::after"))
                .contains(
                        "content: \"\";",
                        "border: 2px solid currentcolor;",
                        "border-radius: var(--radius-full);",
                        "animation: gallery-reveal-spin 0.7s linear infinite;");
    }

    // -- "Resend code" waiting out the code request limit (static/js/resend-cooldown.js) --

    private String resendCooldownJs() throws Exception {
        return mockMvc.perform(get("/js/resend-cooldown.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Its behaviour runs in a browser (e2e); this pins the contract with the
     * markup of the two login code pages: driven by the button's data
     * attribute only, it disables the button itself (the real attribute: the
     * button has sent nothing yet, so nothing is lost, and a disabled button
     * can't send its form), starts over on a page restored from the
     * back/forward cache, and is plain JS — no framework, no module, no
     * inline style (the Content-Security-Policy blocks them).
     */
    @Test
    void resendCooldownJs_isAPlainScriptDrivenByTheButtonsWaitAttribute() throws Exception {
        String js = withoutComments(resendCooldownJs());

        assertThat(js).contains("'use strict'", "data-resend-wait-seconds", ".disabled");
        assertThat(js).contains("'pageshow'", ".persisted");
        assertThat(js).doesNotContain(
                "Alpine", "import ", "export ", "fetch(", "window.location", ".style", "innerHTML");
    }

    /**
     * Disabled while it waits, "Resend code" looks it — dimmed, with a
     * not-allowed cursor — and gets no hover or press effect. The hover and
     * press rules have the same specificity, so this one comes after them.
     */
    @Test
    void beaconCss_aDisabledResendButton_looksDisabled_withoutHoverOrPressEffect() throws Exception {
        String css = withoutComments(beaconCss());
        String flat = topLevelRules(css);

        for (String selector : new String[] {
            ".auth-btn-ghost:disabled", ".auth-btn-ghost:disabled:hover", ".auth-btn-ghost:disabled:active"
        }) {
            assertThat(declarationsFor(flat, selector)).as(selector).contains(
                    "background: transparent;",
                    "color: var(--ink-soft);",
                    "opacity: 0.6;",
                    "cursor: not-allowed;",
                    "transform: none;");
        }
        assertThat(css.indexOf(".auth-btn-ghost:disabled:hover"))
                .isGreaterThan(css.lastIndexOf(".auth-btn-ghost" + NOT_BUSY + ":hover"))
                .isGreaterThan(css.lastIndexOf(".auth-btn-ghost" + NOT_BUSY + ":active"));
    }

    // -- gallery cards (gallery/list.html): the whole card is the link to its article --

    /**
     * A link that still reads like a card: its text keeps its own colours
     * and gets no underline — not even from the global {@code a:hover},
     * which would underline every line of it and, on a touch screen, sticks
     * after a tap; hence outside any hover-only query.
     */
    @Test
    void beaconCss_galleryCard_isALinkThatReadsLikeACard() throws Exception {
        String flat = topLevelRules(beaconCss());

        assertThat(declarationsFor(flat, ".gallery-card")).contains("color: inherit;", "text-decoration: none;");
        assertThat(declarationsFor(flat, ".gallery-card:hover")).contains("color: inherit;", "text-decoration: none;");
    }

    /**
     * Feedback for the whole card: under a pointer it lifts with a stronger
     * shadow (smoothly), pressed it sinks, and keyboard focus rings it.
     * Pressing must win over hovering: same specificity, so the {@code
     * :active} rule comes after the hover one.
     */
    @Test
    void beaconCss_galleryCard_liftsOnHover_sinksWhenPressed_andShowsAFocusRing() throws Exception {
        String css = withoutComments(beaconCss());
        String flat = topLevelRules(css);

        assertThat(declarationsFor(mediaBlocks(css, "(hover: hover)"), ".gallery-card:hover"))
                .contains("transform: translateY(-2px);", "box-shadow: var(--shadow-card-hover);");
        assertThat(topLevelRule(css, ":root")).contains("--shadow-card-hover:");
        assertThat(declarationsFor(flat, ".gallery-card:active")).contains("transform: translateY(1px);");
        assertThat(declarationsFor(flat, ".gallery-card:focus-visible")).contains("outline: 2px solid var(--maroon);");
        assertThat(declarationsFor(flat, ".gallery-card"))
                .contains(
                        "transform var(--motion-fast) var(--ease-standard)",
                        "box-shadow var(--motion-fast) var(--ease-standard)");
        assertThat(css.indexOf("\n.gallery-card:active {")).isGreaterThan(css.indexOf("  .gallery-card:hover {"));
    }

    /** The whole card is the link: the "Read more" link and its rules (the phone tap target too) are gone. */
    @Test
    void beaconCss_dropsTheReadMoreLink() throws Exception {
        assertThat(withoutComments(beaconCss())).doesNotContain("gallery-read-more");
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

    /**
     * The choropleth's five steps, light gold to maroon (decision 30). A
     * shade names the region's fill colour ({@code --region-fill}) rather
     * than setting {@code fill} itself, so the hover rule can darken
     * whichever shade the region has.
     */
    @Test
    void beaconCss_declaresTheFiveMapShadeTokens_andMakesEachShadeTheRegionsFill() throws Exception {
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
            assertThat(declarationsFor(flat, ".map-shade-" + shade))
                    .contains("--region-fill: var(--map-shade-" + shade + ");")
                    .doesNotContainPattern("(?m)^\\s*fill:");
            assertThat(declarationsFor(flat, ".dashboard-map-swatch--" + shade))
                    .contains("background: var(--map-shade-" + shade + ");");
        }
    }

    /**
     * Land is paper with the line colour for borders, on the sand-coloured
     * sea of the mockup: a region without a shade falls back to paper.
     */
    @Test
    void beaconCss_mapLandIsPaperOnSand_unlessAShadeSetsTheRegionFill() throws Exception {
        String flat = topLevelRules(beaconCss());

        assertThat(declarationsFor(flat, ".dashboard-map")).contains("background: var(--sand);");
        assertThat(declarationsFor(flat, ".map-region"))
                .contains("fill: var(--region-fill, var(--paper));", "stroke: var(--line);", "stroke-width: 0.5;");
    }

    /**
     * Hovering or keyboard-focusing a linked country darkens its own shade
     * (a quarter ink mixed in), fading in quickly; its outline stays as it
     * is — the old thick ink outline is gone.
     */
    @ParameterizedTest
    @ValueSource(strings = {".dashboard-map a:hover .map-region", ".dashboard-map a:focus-visible .map-region"})
    void beaconCss_aLinkedCountryDarkensOnHoverAndKeyboardFocus_withoutAnOutline(String selector) throws Exception {
        String flat = topLevelRules(beaconCss());

        assertThat(declarationsFor(flat, selector))
                .contains("fill: color-mix(in srgb, var(--region-fill) 75%, var(--ink));")
                .doesNotContain("stroke");
        assertThat(declarationsFor(flat, ".map-region"))
                .contains("transition: fill var(--motion-fast) var(--ease-standard);");
    }

    // -- motion --

    /** The durations and the easing every transition uses. */
    @Test
    void beaconCss_declaresTheMotionTokens() throws Exception {
        assertThat(topLevelRule(beaconCss(), ":root"))
                .contains(
                        "--motion-fast: 150ms;",
                        "--motion-medium: 250ms;",
                        "--ease-standard: cubic-bezier(0.2, 0, 0, 1);");
    }

    private static final Pattern CSS_RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");

    /** {@code [start, end)} of the content of every {@code @media <query> { … }} block, nested ones included. */
    private static List<int[]> mediaBlockRanges(String css, String query) {
        List<int[]> ranges = new ArrayList<>();
        String opening = "@media " + query + " {";
        for (int at = css.indexOf(opening); at >= 0; at = css.indexOf(opening, at + 1)) {
            int open = css.indexOf('{', at);
            int depth = 0;
            for (int i = open; i < css.length(); i++) {
                if (css.charAt(i) == '{') {
                    depth++;
                } else if (css.charAt(i) == '}' && --depth == 0) {
                    ranges.add(new int[] {open + 1, i});
                    break;
                }
            }
        }
        return ranges;
    }

    /** Everything inside every {@code @media <query> { … }} block, joined; fails if there is none. */
    private static String mediaBlocks(String css, String query) {
        List<int[]> ranges = mediaBlockRanges(css, query);
        assertThat(ranges).as("@media " + query).isNotEmpty();
        StringBuilder out = new StringBuilder();
        for (int[] range : ranges) {
            out.append(css, range[0], range[1]).append('\n');
        }
        return out.toString();
    }

    /** One selector of a rule that declares a property: where the rule ends, and its body. */
    private record Declared(String selector, int end, String body) {
    }

    /**
     * Every selector of every rule in {@code css} that declares {@code
     * property} (other than {@code property: none}) outside a reduced-motion
     * block.
     */
    private static List<Declared> selectorsDeclaring(String css, String property) {
        List<int[]> reduced = mediaBlockRanges(css, "(prefers-reduced-motion: reduce)");
        List<Declared> found = new ArrayList<>();
        Matcher rule = CSS_RULE.matcher(css);
        while (rule.find()) {
            String body = rule.group(2);
            int start = rule.start(2);
            boolean declares = body.contains(property + ":") && !body.contains(property + ": none");
            boolean inReduced = reduced.stream().anyMatch(r -> start >= r[0] && start < r[1]);
            if (declares && !inReduced) {
                for (String selector : rule.group(1).split(",")) {
                    found.add(new Declared(selector.strip(), rule.end(), body));
                }
            }
        }
        return found;
    }

    /** Whether a reduced-motion block after {@code position} has a rule for {@code selector} declaring {@code text}. */
    private static boolean reducedLater(String css, String selector, int position, String text) {
        return mediaBlockRanges(css, "(prefers-reduced-motion: reduce)").stream()
                .filter(r -> r[0] > position)
                .anyMatch(r -> ruleBodiesFor(css.substring(r[0], r[1]), selector).stream()
                        .anyMatch(body -> body.contains(text)));
    }

    /**
     * Every transition takes its durations from the motion tokens (no raw
     * seconds) and is switched off for visitors who ask for reduced motion —
     * by a rule for the same selector that comes later in the file, since at
     * the same specificity an earlier one would lose.
     */
    @Test
    void beaconCss_everyTransitionUsesTheMotionTokens_andIsSwitchedOffForReducedMotion() throws Exception {
        String css = withoutComments(beaconCss());
        List<Declared> transitions = selectorsDeclaring(css, "transition");

        assertThat(transitions).as("transitions").isNotEmpty();
        assertThat(transitions).allSatisfy(each -> {
            assertThat(reducedLater(css, each.selector(), each.end(), "transition: none;"))
                    .as("transition of %s switched off under reduced motion, after it", each.selector())
                    .isTrue();
            Matcher value = Pattern.compile("transition:([^;]*);").matcher(each.body());
            assertThat(value.find()).isTrue();
            assertThat(value.group(1))
                    .as("transition of %s", each.selector())
                    .contains("var(--motion-")
                    .doesNotContainPattern("\\b\\d*\\.?\\d+m?s\\b");
        });
    }

    /** A spinner that doesn't turn no longer says "loading": under reduced motion it turns slower instead. */
    @Test
    void beaconCss_everyAnimationIsSlowedForReducedMotion() throws Exception {
        String css = withoutComments(beaconCss());
        List<Declared> animations = selectorsDeclaring(css, "animation");

        assertThat(animations).as("animations").isNotEmpty();
        assertThat(animations).allSatisfy(each ->
                assertThat(reducedLater(css, each.selector(), each.end(), "animation-duration:"))
                        .as("animation of %s slowed under reduced motion, after it", each.selector())
                        .isTrue());
    }

    // -- the OTP code field --

    /** A code is the same in either case (the server normalises it): the field shows it in capitals as it's typed. */
    @Test
    void beaconCss_showsTheOtpCodeInCapitals() throws Exception {
        assertThat(declarationsFor(topLevelRules(beaconCss()), ".auth-code-input"))
                .contains("text-transform: uppercase;");
    }

    // -- the dashboard's "show more" disclosures (static/js/collapsible.js) --

    @Test
    void collapsibleJs_isServedToAnonymousVisitorsWithJavascriptContentType() throws Exception {
        mockMvc.perform(get("/js/collapsible.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"));
    }

    /**
     * Its behaviour runs in a browser (e2e); this pins the contract with the
     * markup of {@code analytics/dashboard.html}: driven by data/ARIA
     * attributes only — a toggle opens the element its {@code
     * aria-controls} names with the one class the stylesheet opens it by —
     * on a click, as plain JS with no framework or module.
     */
    @Test
    void collapsibleJs_isAPlainScriptDrivenByTheTogglesDataAndAriaAttributes() throws Exception {
        String js = withoutComments(mockMvc.perform(get("/js/collapsible.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(js).contains("'[data-collapsible-toggle]'", "'aria-controls'", "'aria-expanded'", "'click'");
        assertThat(js).contains("'collapsible--open'");
        assertThat(withoutComments(beaconCss())).contains(".collapsible--open");
        assertThat(js).doesNotContain("Alpine", "import ", "export ", "window.location", ".style");
    }

    /**
     * Rows open and close by animating the grid row from 0fr to 1fr, which
     * a browser can transition, unlike {@code height: auto}. Closed, the
     * body is clipped to nothing and hidden ({@code visibility}, so its
     * links leave the tab order once it has finished closing). The opening
     * class comes after the base rule: same specificity, so order decides.
     */
    @Test
    void beaconCss_collapsible_animatesItsGridRowFromZeroToOneFr() throws Exception {
        String css = withoutComments(beaconCss());
        String flat = topLevelRules(css);

        assertThat(declarationsFor(flat, ".collapsible"))
                .contains(
                        "display: grid;",
                        "grid-template-rows: 0fr;",
                        "visibility: hidden;",
                        "grid-template-rows var(--motion-medium) var(--ease-standard)",
                        "visibility var(--motion-medium)");
        assertThat(declarationsFor(flat, ".collapsible--open"))
                .contains("grid-template-rows: 1fr;", "visibility: visible;");
        assertThat(declarationsFor(flat, ".collapsible-body")).contains("min-height: 0;", "overflow: hidden;");
        assertThat(css.indexOf("\n.collapsible--open {")).isGreaterThan(css.indexOf("\n.collapsible {"));
    }

    /**
     * Collapsed, a disclosure must take no room at all: the container itself
     * has no padding, margin, border or gap (any spacing lives inside its
     * clipped body), and the elements that hold one lay their children out
     * without a gap — a gap would still open around the empty container.
     */
    @Test
    void beaconCss_aCollapsedDisclosureTakesNoRoom() throws Exception {
        String flat = topLevelRules(beaconCss());

        assertThat(declarationsFor(flat, ".collapsible"))
                .doesNotContainPattern("(?m)^\\s*(padding|margin|border|gap|row-gap)\\b");
        for (String parent : List.of(".dashboard-chips", ".dashboard-list-card")) {
            assertThat(declarationsFor(flat, parent)).as(parent).doesNotContainPattern("(?m)^\\s*(gap|row-gap):");
        }
    }

    /** One label per state, picked by the toggle's own aria-expanded (which the script keeps in step). */
    @Test
    void beaconCss_aDisclosureToggleShowsTheLabelOfItsState() throws Exception {
        String flat = topLevelRules(beaconCss());

        assertThat(declarationsFor(flat, "[data-collapsible-toggle][aria-expanded=\"true\"] .when-collapsed"))
                .contains("display: none;");
        assertThat(declarationsFor(flat, "[data-collapsible-toggle][aria-expanded=\"false\"] .when-expanded"))
                .contains("display: none;");
    }

    /** The native {@code <details>} disclosures are gone, and so are their rules. */
    @Test
    void beaconCss_dropsTheOldDetailsDisclosureRules() throws Exception {
        assertThat(withoutComments(beaconCss()))
                .doesNotContain("details", "summary", ".dashboard-chip-more", ".dashboard-list-more",
                        ".dashboard-chip--summary");
    }

    /** Without JavaScript nothing could open them: every hidden row is shown, and the buttons are not. */
    @Test
    void noscriptCss_showsEveryCollapsedRow_andHidesTheToggles() throws Exception {
        String flat = topLevelRules(noscriptCss());

        assertThat(declarationsFor(flat, ".collapsible")).contains("grid-template-rows: 1fr;", "visibility: visible;");
        assertThat(declarationsFor(flat, "[data-collapsible-toggle]")).contains("display: none;");
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

    /**
     * The reveal form's submit is taken over on the form itself, first thing:
     * by the time the event bubbles up to the document, the shared busy
     * script ({@code form-submit.js}) sees it prevented and leaves the
     * button — which has its own busy state and spinner — alone.
     */
    @Test
    void contactRevealJs_takesTheSubmitOverOnTheFormItself_beforeTheSharedBusyScriptSeesIt() throws Exception {
        assertThat(contactRevealJs())
                .containsPattern("form\\.addEventListener\\('submit', function \\(event\\) \\{"
                        + "\\s*event\\.preventDefault\\(\\);");
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
