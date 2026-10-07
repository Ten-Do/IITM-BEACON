package com.iitm.beacon.submission;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.domain.contacttype.ContactType;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.util.HtmlUtils;

/**
 * The submission form's per-field error rendering (decision 21, BL-007):
 * every violation of a rejected POST is added to the form's {@code
 * BindingResult} and shown next to its own field, the top banner is only a
 * summary, and the browser-side rules (decision 5, 10) are rendered as
 * attributes. MockMvc can only see the rendered HTML, not run Alpine — the
 * in-browser behaviour itself is covered by the browser e2e tests
 * ({@code e2e.SubmissionFormE2eTest}, {@code e2e.SubmissionPhotoPickerE2eTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class SubmissionViewControllerFieldErrorsTest {

    private static final String SUMMARY = "Please fix the highlighted fields below.";
    private static final String REATTACH = "Photos you attached were not saved — please attach them again.";
    private static final String PHOTOS_NEED_TEXT =
            "Photos need some text — write something here, or remove the photos.";
    private static final String TAGS_NEED_A_PHOTO = "Tags need a photo — attach one or clear the tags.";
    private static final String PUBLIC_NEEDS_A_VALUE = "Enter a contact before making it public.";
    private static final String ROLL_NUMBER_FORMAT =
            "must look like CS21B001 (two letters, two digits, a letter, three digits)";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    private static Authentication visitor(String email) {
        return new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private static MockMultipartFile png(String partName) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile(partName, "cat.png", "image/png", out.toByteArray());
    }

    /** Every topic slug in form order, i.e. {@code sections[i]}'s slug. */
    private List<String> catalogSlugsInFormOrder() {
        List<String> slugs = new ArrayList<>();
        for (TopicCatalogEntryDto entry : submissionService.listTopicCatalog()) {
            if ("GROUP".equals(entry.kind())) {
                entry.subtopics().forEach(t -> slugs.add(t.slug()));
            } else {
                slugs.add(entry.slug());
            }
        }
        return slugs;
    }

    private int indexOf(String slug) {
        int index = catalogSlugsInFormOrder().indexOf(slug);
        assertThat(index).as(slug).isNotNegative();
        return index;
    }

    /** Contact rows are rendered in contact-type display order: email, whatsapp, telegram, instagram, twitter. */
    private int contactRow(String typeSlug) {
        List<String> slugs = submissionService.listActiveContactTypes().stream().map(ContactTypeView::slug).toList();
        return slugs.indexOf(typeSlug);
    }

    /** A valid create-mode POST ("general" filled in); {@code overrides} replace or add parameters. */
    private MockMultipartHttpServletRequestBuilder formPost(String email, Map<String, String> overrides) {
        int general = indexOf("general");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("firstName", "David");
        params.put("lastName", "Jones");
        params.put("rollNumber", "CS21B001");
        params.put("admissionYear", "2024");
        params.put("countryCode", "IN");
        params.put("recommendationScore", "8");
        params.put("sections[" + general + "].topicSlug", "general");
        params.put("sections[" + general + "].answerText", "Great time overall.");
        params.put("dataProcessingConsent", "true");
        params.putAll(overrides);
        MockMultipartHttpServletRequestBuilder builder = multipart("/submissions/form");
        builder.with(csrfField());
        params.forEach((name, value) -> {
            if (value != null) {
                builder.param(name, value);
            }
        });
        builder.with(authentication(visitor(email)));
        return builder;
    }

    private MvcResult rerendered(MockMultipartHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andReturn();
    }

    private static String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static List<String> fieldErrorFields(MvcResult result) {
        BindingResult bindingResult = (BindingResult) result.getModelAndView().getModel()
                .get(BindingResult.MODEL_KEY_PREFIX + "command");
        return bindingResult.getFieldErrors().stream().map(FieldError::getField).toList();
    }

    /** The HTML from {@code startMarker} up to the first {@code endMarker} after it, unescaped. */
    private static String segment(String html, String startMarker, String... endMarkers) {
        int start = html.indexOf(startMarker);
        assertThat(start).as("marker " + startMarker).isNotNegative();
        int end = html.length();
        for (String endMarker : endMarkers) {
            int candidate = html.indexOf(endMarker, start + startMarker.length());
            if (candidate >= 0 && candidate < end) {
                end = candidate;
            }
        }
        return HtmlUtils.htmlUnescape(html.substring(start, end));
    }

    /** One topic block: from its textarea up to the next topic block or the end of its fieldset. */
    private static String topicBlock(String html, int index) {
        return segment(html, "id=\"answer-" + index + "\"", "class=\"submission-topic-block\"", "</fieldset>");
    }

    private static String contactRowHtml(String html, int row) {
        return segment(html, "id=\"contact-" + row + "\"", "class=\"submission-contact-row\"", "</section>");
    }

    /** A field-error span (with any modifier classes) holding exactly {@code message}. */
    private static String errorSpan(String message) {
        return "class=\"submission-field-error[^\"]*\">" + Pattern.quote(message) + "</span>";
    }

    private static String banner(String html) {
        return segment(html, "class=\"submission-error-banner\"", "<form");
    }

    private static String openingTag(String html, String attributeMarker) {
        Matcher m = Pattern.compile("<(input|textarea|select)[^>]*" + Pattern.quote(attributeMarker) + "[^>]*>")
                .matcher(html);
        assertThat(m.find()).as("tag with " + attributeMarker).isTrue();
        return m.group();
    }

    // -- several violations in one POST --

    @Test
    void post_severalViolations_eachIsRenderedNextToItsOwnFieldAndTheBannerSummarizes() throws Exception {
        int general = indexOf("general");
        List<String> slugs = catalogSlugsInFormOrder();
        int last = slugs.size() - 1;
        if (last == general) {
            last--; // the highest-index topic other than the pre-filled "general"
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("rollNumber", "cs21b0");
        overrides.put("admissionYear", "1958");
        overrides.put("dataProcessingConsent", null);
        overrides.put("sections[" + last + "].topicSlug", slugs.get(last));
        overrides.put("sections[" + last + "].answerText", "  ");
        overrides.put("sections[" + general + "].photoTags[1]", "orphan");
        overrides.put("contactMethods[" + contactRow("email") + "].typeSlug", "email");
        overrides.put("contactMethods[" + contactRow("email") + "].value", "d@example.com");
        overrides.put("contactMethods[" + contactRow("instagram") + "].typeSlug", "instagram");
        overrides.put("contactMethods[" + contactRow("instagram") + "].value", "john!doe");
        overrides.put("contactMethods[" + contactRow("twitter") + "].typeSlug", "twitter");
        overrides.put("contactMethods[" + contactRow("twitter") + "].value", "");
        overrides.put("contactMethods[" + contactRow("twitter") + "].publicContact", "true");

        MvcResult result = rerendered(formPost("view-errors-many@example.com", overrides)
                .file(png("sections[" + last + "].photos")));
        String html = html(result);

        assertThat(fieldErrorFields(result)).containsExactlyInAnyOrder(
                "rollNumber",
                "admissionYear",
                "dataProcessingConsent",
                "sections[" + last + "].answerText",
                "sections[" + general + "].photoTags[1]",
                "contactMethods[" + contactRow("instagram") + "].value",
                "contactMethods[" + contactRow("twitter") + "].publicContact");
        assertThat(segment(html, "id=\"rollNumber\"", "id=\"admissionYear\""))
                .containsPattern(errorSpan(ROLL_NUMBER_FORMAT));
        assertThat(segment(html, "id=\"admissionYear\"", "id=\"emailDisplay\""))
                .containsPattern(errorSpan("must be between 1959 and the current year"));
        assertThat(topicBlock(html, last)).containsPattern(errorSpan(PHOTOS_NEED_TEXT));
        assertThat(topicBlock(html, general)).containsPattern(errorSpan(TAGS_NEED_A_PHOTO));
        assertThat(contactRowHtml(html, contactRow("instagram")))
                .containsPattern(errorSpan(
                        "doesn't look like a valid Instagram contact — expected: @username or a profile link"));
        assertThat(contactRowHtml(html, contactRow("twitter"))).containsPattern(errorSpan(PUBLIC_NEEDS_A_VALUE));
        assertThat(segment(html, "class=\"submission-consent-block\"", "class=\"submission-btn-row\""))
                .containsPattern("class=\"submission-field-error[^\"]*\">");
        // Only once each: next to the field, not repeated in the banner.
        assertThat(banner(html)).contains(SUMMARY).contains(REATTACH).doesNotContain(ROLL_NUMBER_FORMAT);
    }

    @Test
    void post_invalidInputsKeepWhatTheVisitorTypedAndAreMarkedInvalid() throws Exception {
        int general = indexOf("general");
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("rollNumber", "cs21b0");
        overrides.put("contactMethods[" + contactRow("instagram") + "].typeSlug", "instagram");
        overrides.put("contactMethods[" + contactRow("instagram") + "].value", "john!doe");

        String html = html(rerendered(formPost("view-errors-values@example.com", overrides)));

        assertThat(openingTag(html, "id=\"rollNumber\""))
                .contains("value=\"cs21b0\"").contains("submission-field-input--invalid");
        assertThat(openingTag(html, "id=\"contact-" + contactRow("instagram") + "\""))
                .contains("value=\"john!doe\"").contains("submission-field-input--invalid");
        assertThat(openingTag(html, "id=\"answer-" + general + "\"")).doesNotContain("--invalid");
        assertThat(openingTag(html, "id=\"lastName\"")).doesNotContain("--invalid");
    }

    // -- banner --

    @Test
    void post_fieldViolationWithoutAnyPhoto_bannerIsJustTheSummary() throws Exception {
        String html = html(rerendered(formPost("view-errors-no-photo@example.com", Map.of("rollNumber", "X"))));

        assertThat(banner(html)).contains(SUMMARY).doesNotContain(REATTACH);
    }

    @Test
    void post_emptyFileInputsFromANoJsBrowser_doNotCountAsAttachedPhotos() throws Exception {
        int general = indexOf("general");
        MockMultipartFile emptyInput = new MockMultipartFile(
                "sections[" + general + "].photos", "", "application/octet-stream", new byte[0]);

        String html = html(rerendered(formPost("view-errors-empty-file@example.com", Map.of("rollNumber", "X"))
                .file(emptyInput)));

        assertThat(banner(html)).doesNotContain(REATTACH);
    }

    @Test
    void post_validPhotoButAnotherFieldRejected_bannerAsksToReattachPhotos() throws Exception {
        int general = indexOf("general");

        String html = html(rerendered(formPost("view-errors-reattach@example.com", Map.of("rollNumber", "X"))
                .file(png("sections[" + general + "].photos"))));

        assertThat(banner(html)).contains(SUMMARY).contains(REATTACH);
    }

    @Test
    void post_onlyAGlobalViolation_bannerShowsItWithoutClaimingFieldsAreHighlighted() throws Exception {
        // A tampered hidden contact-type field: nothing on the page to highlight.
        MvcResult result = rerendered(formPost("view-errors-global@example.com", Map.of(
                "contactMethods[0].typeSlug", "myspace",
                "contactMethods[0].value", "tom")));

        assertThat(fieldErrorFields(result)).isEmpty();
        assertThat(banner(html(result)))
                .contains("Unknown or inactive contact type: myspace")
                .doesNotContain(SUMMARY);
    }

    // -- binding (type-mismatch) errors --

    @Test
    void post_nonNumericAdmissionYear_rendersAReadableMessageNextToItWithoutInternals() throws Exception {
        String html = html(rerendered(
                formPost("view-errors-year-garbage@example.com", Map.of("admissionYear", "twenty"))));

        assertThat(segment(html, "id=\"admissionYear\"", "id=\"emailDisplay\""))
                .containsPattern(errorSpan("must be a year, e.g. 2021"));
        assertThat(html).doesNotContain("Failed to convert").doesNotContain("NumberFormatException");
        assertThat(banner(html)).contains(SUMMARY);
    }

    @Test
    void post_nonNumericScore_rendersAReadableMessageNextToTheSlider() throws Exception {
        String html = html(rerendered(
                formPost("view-errors-score-garbage@example.com", Map.of("recommendationScore", "lots"))));

        assertThat(segment(html, "name=\"recommendationScore\"", "class=\"submission-section\""))
                .containsPattern(errorSpan("must be a whole number from 0 to 10"));
        assertThat(html).doesNotContain("Failed to convert");
    }

    // -- section anchors survive the re-render's re-alignment --

    @Test
    void post_violationInTheLastCatalogTopic_isRenderedUnderThatTopicOnly() throws Exception {
        List<String> slugs = catalogSlugsInFormOrder();
        int last = slugs.size() - 1;

        String html = html(rerendered(formPost("view-errors-last-topic@example.com", Map.of(
                        "sections[" + last + "].topicSlug", slugs.get(last),
                        "sections[" + last + "].answerText", ""))
                .file(png("sections[" + last + "].photos"))));

        assertThat(topicBlock(html, last)).containsPattern(errorSpan(PHOTOS_NEED_TEXT));
        assertThat(html.indexOf(PHOTOS_NEED_TEXT)).isEqualTo(html.lastIndexOf(PHOTOS_NEED_TEXT));
        assertThat(openingTag(html, "id=\"answer-" + last + "\"")).contains("submission-field-input--invalid");
    }

    @Test
    void post_answerPostedUnderAStaleIndex_itsErrorFollowsItsTopicNotTheIndex() throws Exception {
        int networking = indexOf("networking");
        assertThat(networking).isNotZero();

        String html = html(rerendered(formPost("view-errors-stale-index@example.com", Map.of(
                        "sections[0].topicSlug", "networking",
                        "sections[0].answerText", " "))
                .file(png("sections[0].photos"))));

        assertThat(topicBlock(html, networking)).containsPattern(errorSpan(PHOTOS_NEED_TEXT));
        assertThat(topicBlock(html, 0)).doesNotContain(PHOTOS_NEED_TEXT);
    }

    @Test
    void post_topicHoldingOnlyAnError_isOpenedSoTheMessageIsVisible() throws Exception {
        int networking = indexOf("networking");

        String html = html(rerendered(formPost("view-errors-open-pick@example.com", Map.of(
                "sections[" + networking + "].topicSlug", "networking",
                "sections[" + networking + "].photoTags[0]", "orphan"))));

        Matcher fieldset = Pattern.compile("<fieldset[^>]*>(?:(?!</fieldset>)[\\s\\S])*id=\"answer-" + networking
                + "\"").matcher(html);
        assertThat(fieldset.find()).isTrue();
        String openingFieldsetTag = fieldset.group().substring(0, fieldset.group().indexOf('>') + 1);
        assertThat(openingFieldsetTag).doesNotContain("x-cloak");
    }

    @Test
    void post_noTopicFilledInAtAll_isShownNextToTheTopicPicker() throws Exception {
        int general = indexOf("general");
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("sections[" + general + "].topicSlug", null);
        overrides.put("sections[" + general + "].answerText", null);

        String html = html(rerendered(formPost("view-errors-no-topic@example.com", overrides)));

        assertThat(segment(html, "class=\"submission-chip-toggle-row\"", "<fieldset"))
                .containsPattern(errorSpan("At least one section must be filled in."));
    }

    // -- country select --

    private static final String SELECT_COUNTRY = "Please select your country.";

    /** One {@code <option>} of the country select: its opening tag's attributes and its text. */
    private record Option(String attributes, String text) {

        String value() {
            Matcher m = Pattern.compile("\\bvalue=\"([^\"]*)\"").matcher(attributes);
            return m.find() ? m.group(1) : text;
        }

        boolean hasAttribute(String name) {
            return Pattern.compile("(^|\\s)" + name + "(=|\\s|$)").matcher(attributes).find();
        }
    }

    private static List<Option> countryOptions(String html) {
        String select = segment(html, "id=\"countryCode\"", "</select>");
        Matcher m = Pattern.compile("<option([^>]*)>([^<]*)</option>").matcher(select);
        List<Option> options = new ArrayList<>();
        while (m.find()) {
            options.add(new Option(m.group(1), m.group(2)));
        }
        assertThat(options).as("country options").isNotEmpty();
        return options;
    }

    /**
     * The option a browser shows for this single-choice select, per the
     * HTML selectedness rules: the last one marked {@code selected}, else
     * the first one that isn't disabled.
     */
    private static Option shownCountryOption(String html) {
        List<Option> options = countryOptions(html);
        List<Option> marked = options.stream().filter(o -> o.hasAttribute("selected")).toList();
        if (!marked.isEmpty()) {
            return marked.get(marked.size() - 1);
        }
        return options.stream().filter(o -> !o.hasAttribute("disabled")).findFirst().orElseThrow();
    }

    /** From the select up to the next field block, i.e. the select plus its own error span, if any. */
    private static String countryField(String html) {
        return segment(html, "id=\"countryCode\"", "</section>");
    }

    @Test
    void get_createMode_countrySelectIsRequiredAndStartsOnAnEmptyPlaceholder() throws Exception {
        String html = formHtml("view-country-create@example.com");

        assertThat(openingTag(html, "id=\"countryCode\"")).contains("required");
        List<Option> options = countryOptions(html);
        // First, empty-valued and not disabled: the HTML "placeholder label
        // option", which makes `required` block submitting it.
        Option placeholder = options.get(0);
        assertThat(placeholder.value()).isEmpty();
        assertThat(placeholder.text()).isEqualTo("Select your country");
        assertThat(placeholder.hasAttribute("disabled")).isFalse();
        assertThat(shownCountryOption(html)).isEqualTo(placeholder);
        assertThat(options.subList(1, options.size()))
                .as("real countries only after the placeholder")
                .allSatisfy(o -> assertThat(o.value()).isNotEmpty())
                .noneMatch(o -> o.hasAttribute("selected"));
    }

    @Test
    void get_editMode_countrySelectShowsTheSavedCountryNotThePlaceholder() throws Exception {
        String email = "view-country-edit@example.com";
        submissionService.create(email, new TestimonialSubmissionRequest("David", "Jones", "CS21B001", 2024, "DE", 8,
                List.of(new TestimonialSubmissionRequest.SectionInput("general", "Text.", List.of())),
                List.of(), List.of(), true), Map.of());

        String html = formHtml(email);

        assertThat(shownCountryOption(html).value()).isEqualTo("DE");
        assertThat(countryOptions(html).stream().filter(o -> o.hasAttribute("selected")))
                .as("exactly one option marked selected")
                .hasSize(1);
        assertThat(countryOptions(html).get(0).hasAttribute("selected")).as("placeholder").isFalse();
    }

    static List<String> blankCountryCodes() {
        return Arrays.asList(null, ""); // not posted at all; posted with the placeholder chosen
    }

    @ParameterizedTest(name = "countryCode {0}")
    @MethodSource("blankCountryCodes")
    void post_noCountryChosen_isOneErrorNextToTheSelectWhichIsMarkedInvalidAndBackOnThePlaceholder(
            String countryCode) throws Exception {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("countryCode", countryCode);

        MvcResult result = rerendered(formPost("view-country-blank@example.com", overrides));
        String html = html(result);

        assertThat(fieldErrorFields(result)).containsExactly("countryCode");
        assertThat(countryField(html))
                .containsPattern(errorSpan(SELECT_COUNTRY))
                .containsOnlyOnce("submission-field-error");
        assertThat(openingTag(html, "id=\"countryCode\"")).contains("submission-field-input--invalid");
        assertThat(shownCountryOption(html).value()).isEmpty();
        assertThat(banner(html)).contains(SUMMARY).doesNotContain(SELECT_COUNTRY);
    }

    @Test
    void post_anotherFieldRejected_keepsTheChosenCountrySelectedAndValid() throws Exception {
        String html = html(rerendered(formPost("view-country-kept@example.com", Map.of(
                "countryCode", "DE",
                "rollNumber", "X"))));

        assertThat(shownCountryOption(html).value()).isEqualTo("DE");
        assertThat(countryOptions(html).get(0).hasAttribute("selected")).as("placeholder").isFalse();
        assertThat(openingTag(html, "id=\"countryCode\"")).doesNotContain("--invalid");
        assertThat(countryField(html)).doesNotContain("submission-field-error");
    }

    @Test
    void post_unknownCountryCode_isStillReportedByNameNextToTheSelect() throws Exception {
        MvcResult result = rerendered(formPost("view-country-unknown@example.com", Map.of("countryCode", "ZZ")));
        String html = html(result);

        assertThat(fieldErrorFields(result)).containsExactly("countryCode");
        assertThat(countryField(html))
                .containsPattern(errorSpan("Unknown country code: ZZ"))
                .containsOnlyOnce("submission-field-error");
    }

    // -- browser-side rules rendered as attributes (GET) --

    private String formHtml(String email) throws Exception {
        return mockMvc.perform(get("/submissions/form").with(authentication(visitor(email))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void get_rollNumberInput_carriesTheFormatRulesAndItsHelpText() throws Exception {
        String html = formHtml("view-rules-roll@example.com");

        assertThat(openingTag(html, "id=\"rollNumber\""))
                .contains("pattern=\"[A-Za-z]{2}[0-9]{2}[A-Za-z][0-9]{3}\"")
                .contains("maxlength=\"8\"")
                .contains("required")
                .contains("autocapitalize=\"characters\"");
        assertThat(segment(html, "id=\"rollNumber\"", "id=\"admissionYear\""))
                .contains("Format: AA00A000, e.g. CS21B001");
    }

    @Test
    void get_admissionYearInput_isBoundedByTheFoundingYearAndTheClocksCurrentYear() throws Exception {
        String html = formHtml("view-rules-year@example.com");

        assertThat(openingTag(html, "id=\"admissionYear\""))
                .contains("min=\"1959\"")
                .contains("max=\"" + submissionService.latestAdmissionYear() + "\"")
                .contains("required");
    }

    @Test
    void get_namesAreRequired() throws Exception {
        String html = formHtml("view-rules-names@example.com");

        assertThat(openingTag(html, "id=\"firstName\"")).contains("required");
        assertThat(openingTag(html, "id=\"lastName\"")).contains("required");
    }

    @Test
    void get_contactInputs_carryTheirTypesPatternWithTheLabelAsTitle() throws Exception {
        String html = formHtml("view-rules-contact@example.com");
        ContactTypeView whatsapp = submissionService.listActiveContactTypes().get(contactRow("whatsapp"));

        String input = HtmlUtils.htmlUnescape(openingTag(html, "id=\"contact-" + contactRow("whatsapp") + "\""));

        assertThat(input)
                .contains("pattern=\"" + whatsapp.valuePattern() + "\"")
                .contains("title=\"phone number, or a wa.me link\"");
    }

    @Test
    void get_contactTypeWithoutAPattern_rendersNoPatternAttribute() throws Exception {
        contactTypeRepository.saveAndFlush(ContactType.builder()
                .slug("fixture_signal").name("Signal").label("anything").displayOrder(99).build());
        int row = contactRow("fixture_signal");

        String input = openingTag(formHtml("view-rules-no-pattern@example.com"), "id=\"contact-" + row + "\"");

        assertThat(input).doesNotContain("pattern=").contains("title=\"anything\"");
    }

    @Test
    void get_showPubliclyCheckbox_isDisabledByTheRowWhileItsValueIsBlank() throws Exception {
        String html = formHtml("view-rules-public@example.com");

        String row = segment(html, "class=\"submission-contact-row\"", "</section>");
        assertThat(row).contains("x-data=\"contactRow\"");
        Matcher checkbox = Pattern.compile("<input type=\"checkbox\"[^>]*publicContact[^>]*>").matcher(row);
        assertThat(checkbox.find()).isTrue();
        assertThat(checkbox.group()).contains("x-bind:disabled=\"!hasValue\"").doesNotContain(" disabled=");
    }

    @Test
    void get_editMode_prefilledPublicContactIsRenderedWithItsValueAndChecked() throws Exception {
        String email = "view-rules-public-edit@example.com";
        SubmissionFormCommand command = new SubmissionFormCommand();
        command.setFirstName("David");
        command.setLastName("Jones");
        command.setRollNumber("CS21B001");
        command.setAdmissionYear(2024);
        command.setCountryCode("IN");
        command.setRecommendationScore(8);
        command.setDataProcessingConsent(true);
        SectionFormEntry general = new SectionFormEntry();
        general.setTopicSlug("general");
        general.setAnswerText("Text.");
        command.setSections(new ArrayList<>(List.of(general)));
        ContactFormEntry telegram = new ContactFormEntry();
        telegram.setTypeSlug("telegram");
        telegram.setValue("@david_jones");
        telegram.setPublicContact(true);
        command.setContactMethods(new ArrayList<>(List.of(telegram)));
        submissionService.createFromForm(email, command);

        String row = contactRowHtml(formHtml(email), contactRow("telegram"));

        assertThat(row).contains("value=\"@david_jones\"");
        Matcher checkbox = Pattern.compile("<input type=\"checkbox\"[^>]*>").matcher(row);
        assertThat(checkbox.find()).isTrue();
        assertThat(checkbox.group()).contains("checked").doesNotContain(" disabled=");
    }

    @Test
    void get_topicBlocks_keepThePhotoInputDisabledUntilTheBlockHasText() throws Exception {
        int general = indexOf("general");
        String html = formHtml("view-rules-uploads@example.com");
        String block = topicBlock(html, general);

        Matcher blockTag = Pattern.compile("<div class=\"submission-topic-block\"[^>]*>").matcher(html);
        assertThat(blockTag.find()).isTrue();
        assertThat(blockTag.group()).contains("x-data=\"topicBlock\"");
        assertThat(block).contains("x-data=\"photoPicker\"");
        Matcher hint = Pattern.compile("<[a-z]+[^>]*>Add some text first to attach photos").matcher(block);
        assertThat(hint.find()).isTrue();
        assertThat(hint.group()).contains("hidden").contains("x-bind:hidden=\"hasText\"");
        // Disabled only by the script: without JS the input must stay usable.
        assertThat(openingTag(block, "name=\"sections[" + general + "].photos\""))
                .contains("x-bind:disabled=\"!hasText\"")
                .doesNotContain(" disabled=");
        // New photos of a topic whose text was cleared won't be sent: they look it.
        Matcher newTile = Pattern.compile("<div class=\"submission-photo-tile\"[^>]*data-new-photo[^>]*>")
                .matcher(block);
        assertThat(newTile.find()).as("new photo tile template").isTrue();
        assertThat(newTile.group()).contains("'submission-photo-tile--inactive': !hasText");
        assertThat(openingTag(block, "x-bind:name=\"tagsName(index)\"")).contains("x-bind:disabled=\"!hasText\"");
    }

    // -- photo fields on a rejected POST --

    @Test
    void post_tagsWithoutAPhoto_areShownInThatTopicsPhotoBlock_withoutRenderingATagsFieldForThem()
            throws Exception {
        int general = indexOf("general");

        MvcResult result = rerendered(formPost("view-errors-orphan-tags@example.com", Map.of(
                "sections[" + general + "].photoTags[2]", "orphan")));
        String html = html(result);

        assertThat(fieldErrorFields(result)).containsExactly("sections[" + general + "].photoTags[2]");
        assertThat(topicBlock(html, general)).containsPattern(errorSpan(TAGS_NEED_A_PHOTO));
        assertThat(html.indexOf(TAGS_NEED_A_PHOTO)).isEqualTo(html.lastIndexOf(TAGS_NEED_A_PHOTO));
        assertThat(html).doesNotContain(".photoTags[");
        assertThat(banner(html)).contains(SUMMARY).doesNotContain(REATTACH);
    }

    @Test
    void post_sixPhotosInOneTopic_isShownInThatTopicsPhotoBlock_andTheBannerAsksToReattach() throws Exception {
        int general = indexOf("general");
        var request = formPost("view-errors-six-photos@example.com", Map.of());
        for (int n = 0; n < 6; n++) {
            request.file(png("sections[" + general + "].photos"));
        }

        MvcResult result = rerendered(request);
        String html = html(result);

        assertThat(fieldErrorFields(result)).containsExactly("sections[" + general + "].photos");
        assertThat(topicBlock(html, general)).containsPattern(errorSpan("At most 5 photos per topic."));
        assertThat(banner(html)).contains(SUMMARY).contains(REATTACH);
    }

    @Test
    void post_editMode_savedPhotoMarkedForRemoval_staysMarkedWhenTheFormComesBack() throws Exception {
        String email = "view-errors-removal-kept@example.com";
        int general = indexOf("general");
        MockMultipartFile saved = png("p");
        submissionService.create(email, new TestimonialSubmissionRequest("David", "Jones", "CS21B001", 2024, "IN", 8,
                List.of(new TestimonialSubmissionRequest.SectionInput("general", "Text.",
                        List.of(new TestimonialSubmissionRequest.PhotoInput("p", List.of()),
                                new TestimonialSubmissionRequest.PhotoInput("q", List.of())))),
                List.of(), List.of(), true), Map.of("p", saved, "q", png("q")));
        List<String> urls = submissionService.loadMine(email).sections().get(0).photos().stream()
                .map(TestimonialSubmissionView.PhotoRef::url)
                .toList();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("rollNumber", "X");
        overrides.put("sections[" + general + "].existingPhotoUrls[0]", urls.get(0));
        overrides.put("sections[" + general + "].existingPhotoUrls[1]", urls.get(1));
        overrides.put("sections[" + general + "].removedPhotoUrls", urls.get(1));

        String html = html(rerendered(formPost(email, overrides)));

        assertThat(removeCheckbox(html, urls.get(0))).doesNotContain("checked");
        assertThat(removeCheckbox(html, urls.get(1))).contains("checked=\"checked\"");
    }

    private static String removeCheckbox(String html, String url) {
        Matcher m = Pattern.compile("<input type=\"checkbox\"[^>]*removedPhotoUrls[^>]*value=\""
                + Pattern.quote(url) + "\"[^>]*>").matcher(html);
        assertThat(m.find()).as("remove checkbox for " + url).isTrue();
        return m.group();
    }
}
