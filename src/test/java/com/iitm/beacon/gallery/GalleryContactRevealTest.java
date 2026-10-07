package com.iitm.beacon.gallery;

import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTag;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTagsWithAttribute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.ContactMethod;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.AssetUrls;
import com.iitm.beacon.testsupport.Csrf;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-REVEAL-CONTACT on the article page: {@code POST /gallery/{id}/contact},
 * accepted only from this site's own pages (the {@code Origin} header,
 * decision 27) and, like every POST, only with the CSRF token (BL-004). The
 * page's script asks for it with {@code X-Requested-With: fetch} and the
 * token from its cookie in the {@code X-XSRF-TOKEN} header, and gets just
 * the contact card to swap in; a plain form POST (no JavaScript) sends the
 * form's hidden token field and gets the whole article back with the card in
 * place of the button. MockMvc's requests go to {@code http://localhost}
 * (port 80), so that is this site's origin here. Real {@code
 * SecurityConfig}, filters on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class GalleryContactRevealTest {

    private static final String SITE = "http://localhost";
    private static final String PUBLIC_VALUE = "reachme@example.com";
    private static final String PRIVATE_VALUE = "+91 98765 00000";
    private static final String UNAVAILABLE = "Contact info isn't available.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ContactTypeRepository contactTypeRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    // -- fixtures --

    private Testimonial testimonial(TestimonialStatus status) {
        String email = UUID.randomUUID() + "@example.com";
        Testimonial t = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .status(status)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        t.getSections().add(TestimonialSection.builder()
                .testimonial(t)
                .topic(topicRepository.findBySlug("general").orElseThrow())
                .answerText("An unforgettable semester.")
                .modified(false)
                .build());
        return t;
    }

    private void addContact(Testimonial t, String typeSlug, String value, boolean isPublic) {
        t.getContactMethods().add(ContactMethod.builder()
                .testimonial(t)
                .contactType(contactTypeRepository.findBySlug(typeSlug).orElseThrow())
                .value(value)
                .isPublic(isPublic)
                .displayOrder(t.getContactMethods().size())
                .build());
    }

    /** One public email and one private WhatsApp number. */
    private Testimonial withPublicAndPrivateContact(TestimonialStatus status) {
        Testimonial t = testimonial(status);
        addContact(t, "email", PUBLIC_VALUE, true);
        addContact(t, "whatsapp", PRIVATE_VALUE, false);
        return testimonialRepository.saveAndFlush(t);
    }

    private Testimonial withOnlyPrivateContact() {
        Testimonial t = testimonial(TestimonialStatus.APPROVED);
        addContact(t, "whatsapp", PRIVATE_VALUE, false);
        return testimonialRepository.saveAndFlush(t);
    }

    // -- requests --

    /** What the page's script sends: the CSRF token from its cookie in the header. */
    private static MockHttpServletRequestBuilder fetchContact(Object id) {
        return post("/gallery/{id}/contact", id).with(csrfHeader())
                .header("Origin", SITE)
                .header("Sec-Fetch-Site", "same-origin")
                .header("X-Requested-With", "fetch");
    }

    /** What a browser without JavaScript sends when the button's form is submitted. */
    private static MockHttpServletRequestBuilder submitForm(Object id) {
        return post("/gallery/{id}/contact", id).with(csrfField())
                .header("Origin", SITE)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static String collapseWhitespace(String html) {
        return html.replaceAll("\\s+", " ");
    }

    private String detailHtml(Long id) throws Exception {
        return mockMvc.perform(get("/gallery/{id}", id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    // -- the script's request: just the card --

    @Test
    void fetch_fromThisSite_returnsOnlyTheContactCard_withThePublicContactsOnly() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        String card = mockMvc.perform(fetchContact(saved.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(card).contains("email: " + PUBLIC_VALUE).doesNotContain(PRIVATE_VALUE);
        assertThat(card).doesNotContain("<html", "<main", "gallery-detail-main", "An unforgettable semester.");
        String root = openingTag(card.strip(), "div");
        assertThat(card.strip()).startsWith(root);
        assertThat(attribute(root, "id")).contains("contact");
        assertThat(attribute(root, "data-contact-card")).isPresent();
        // Focusable by the script (not by Tab), so focus can move to it once swapped in.
        assertThat(attribute(root, "tabindex")).contains("-1");
        assertThat(card).doesNotContain("gallery-reveal-form");
    }

    @Test
    void fetch_everyPublicContactIsListed_eachAsOneChip() throws Exception {
        Testimonial t = testimonial(TestimonialStatus.APPROVED);
        addContact(t, "email", PUBLIC_VALUE, true);
        addContact(t, "telegram", "@david_j", true);
        addContact(t, "whatsapp", PRIVATE_VALUE, false);
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        String card = perform(fetchContact(saved.getId())).getContentAsString();

        assertThat(elements(card, "span"))
                .filteredOn(chip -> attribute(openingTag(chip, "span"), "class")
                        .orElse("")
                        .contains("gallery-contact-chip"))
                .extracting(chip -> chip.replaceAll("<[^>]+>", ""))
                .containsExactlyInAnyOrder("email: " + PUBLIC_VALUE, "telegram: @david_j");
    }

    @Test
    void fetch_contactValueWithMarkup_isEscaped() throws Exception {
        Testimonial t = testimonial(TestimonialStatus.APPROVED);
        addContact(t, "email", "<img src=x onerror=alert(1)>&me@example.com", true);
        Testimonial saved = testimonialRepository.saveAndFlush(t);

        String card = perform(fetchContact(saved.getId())).getContentAsString();

        assertThat(card).doesNotContain("<img", "onerror=alert(1)>");
        assertThat(card).contains("&lt;img src=x onerror=alert(1)&gt;&amp;me@example.com");
    }

    @Test
    void fetch_unknownTestimonial_is404_withTheUnavailableMessageOnly() throws Exception {
        MockHttpServletResponse response = perform(fetchContact(999_999L));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_HTML_VALUE);
        assertThat(response.getContentAsString()).contains(UNAVAILABLE).doesNotContain("<html", "<main");
    }

    @ParameterizedTest
    @EnumSource(value = TestimonialStatus.class, names = {"PENDING", "REJECTED"})
    void fetch_testimonialNotApproved_is404_andNeverShowsItsContacts(TestimonialStatus status) throws Exception {
        Testimonial saved = withPublicAndPrivateContact(status);

        MockHttpServletResponse response = perform(fetchContact(saved.getId()));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains(UNAVAILABLE).doesNotContain(PUBLIC_VALUE, PRIVATE_VALUE);
    }

    @Test
    void fetch_approvedWithOnlyPrivateContacts_is404_andNeverShowsThem() throws Exception {
        Testimonial saved = withOnlyPrivateContact();

        MockHttpServletResponse response = perform(fetchContact(saved.getId()));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains(UNAVAILABLE).doesNotContain(PRIVATE_VALUE);
    }

    @Test
    void fetch_approvedWithNoContactsAtAll_is404() throws Exception {
        Testimonial saved = testimonialRepository.saveAndFlush(testimonial(TestimonialStatus.APPROVED));

        MockHttpServletResponse response = perform(fetchContact(saved.getId()));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains(UNAVAILABLE);
    }

    /** A double click that got through: nothing changes between the two, so both get the same card. */
    @Test
    void fetch_twiceInARow_answersBothTheSame() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        MockHttpServletResponse first = perform(fetchContact(saved.getId()));
        MockHttpServletResponse second = perform(fetchContact(saved.getId()));

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
    }

    // -- the same-origin check --

    /** Header pairs for requests that must be refused: another site, none, the opaque origin, a cross-site fetch. */
    static Stream<Arguments> foreignRequests() {
        return Stream.of(
                        new String[] {},
                        new String[] {"Origin", "http://evil.example"},
                        new String[] {"Origin", "https://localhost"},
                        new String[] {"Origin", "http://localhost:8080"},
                        new String[] {"Origin", "null"},
                        new String[] {"Origin", SITE, "Sec-Fetch-Site", "cross-site"},
                        new String[] {"Origin", SITE, "Sec-Fetch-Site", "same-site"},
                        new String[] {"Origin", SITE, "Sec-Fetch-Site", "none"})
                .map(headers -> Arguments.of((Object) headers));
    }

    @ParameterizedTest
    @MethodSource("foreignRequests")
    void requestNotFromThisSite_is403_withAShortPlainTextBody_andNoContacts(String[] headers) throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);
        for (boolean asFetch : new boolean[] {true, false}) {
            MockHttpServletRequestBuilder request = post("/gallery/{id}/contact", saved.getId()).with(csrfField());
            for (int i = 0; i < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            if (asFetch) {
                request.header("X-Requested-With", "fetch");
            }

            MockHttpServletResponse response = perform(request);

            assertThat(response.getStatus()).as("fetch=" + asFetch).isEqualTo(403);
            assertThat(response.getContentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
            assertThat(response.getContentAsString())
                    .isNotBlank()
                    .hasSizeLessThan(200)
                    .doesNotContain(PUBLIC_VALUE, PRIVATE_VALUE, "Exception", "<");
        }
    }

    /** The check runs before anything else: a refused request learns nothing, not even whether the id exists. */
    @Test
    void requestNotFromThisSite_forAnUnknownTestimonial_isAlso403() throws Exception {
        MockHttpServletResponse response =
                perform(post("/gallery/{id}/contact", 999_999L).with(csrfField()).header("X-Requested-With", "fetch"));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void fetchFromThisSite_withoutSecFetchSite_isAccepted() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        mockMvc.perform(post("/gallery/{id}/contact", saved.getId()).with(csrfField())
                        .header("Origin", SITE)
                        .header("X-Requested-With", "fetch"))
                .andExpect(status().isOk());
    }

    // -- the plain form POST (no JavaScript): the whole article --

    @Test
    void formPost_fromThisSite_rendersTheWholeArticle_withTheContactCardInPlaceOfTheButton() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        String html = mockMvc.perform(submitForm(saved.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("gallery/detail"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("<html", "David J.", "An unforgettable semester.");
        assertThat(html).contains("email: " + PUBLIC_VALUE).doesNotContain(PRIVATE_VALUE);
        assertThat(html).doesNotContain("gallery-reveal-form", "Reveal contact info");
        List<String> cards = openingTagsWithAttribute(html, "data-contact-card");
        assertThat(cards).singleElement().satisfies(card -> assertThat(attribute(card, "id")).contains("contact"));
    }

    /** The card is the very same markup the script swaps in. */
    @Test
    void formPost_andFetch_renderTheSameCard() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        String fragment = perform(fetchContact(saved.getId())).getContentAsString();
        String page = perform(submitForm(saved.getId())).getContentAsString();

        assertThat(collapseWhitespace(page)).contains(collapseWhitespace(fragment).strip());
    }

    @Test
    void formPost_unknownTestimonial_rendersTheNotFoundPage() throws Exception {
        mockMvc.perform(submitForm(999_999L))
                .andExpect(status().isNotFound())
                .andExpect(view().name("gallery/not-found"));
    }

    @ParameterizedTest
    @EnumSource(value = TestimonialStatus.class, names = {"PENDING", "REJECTED"})
    void formPost_testimonialNotApproved_rendersTheNotFoundPage_withoutItsContacts(TestimonialStatus status)
            throws Exception {
        Testimonial saved = withPublicAndPrivateContact(status);

        MockHttpServletResponse response = perform(submitForm(saved.getId()));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("Testimonial not found")
                .doesNotContain(PUBLIC_VALUE, PRIVATE_VALUE);
    }

    @Test
    void formPost_approvedWithOnlyPrivateContacts_is404_showingTheArticleWithTheUnavailableMessage() throws Exception {
        Testimonial saved = withOnlyPrivateContact();

        String html = mockMvc.perform(submitForm(saved.getId()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("gallery/detail"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("David J.", UNAVAILABLE).doesNotContain(PRIVATE_VALUE, "gallery-reveal-form");
    }

    // -- the article page (GET) --

    @Test
    void detail_withAPublicContact_rendersAFormPostingToTheContactEndpoint_landingOnTheCard() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        String html = detailHtml(saved.getId());

        List<String> forms = elements(html, "form");
        assertThat(forms).hasSize(1);
        String form = forms.get(0);
        String formTag = openingTag(form, "form");
        assertThat(attribute(formTag, "method")).contains("post");
        assertThat(attribute(formTag, "action")).contains("/gallery/" + saved.getId() + "/contact#contact");
        assertThat(attribute(formTag, "class")).contains("gallery-reveal-form");
        String button = openingTag(form, "button");
        assertThat(attribute(button, "type")).contains("submit");
        assertThat(attribute(button, "class")).contains("gallery-reveal-btn");
        assertThat(elements(form, "button").get(0).replaceAll("<[^>]+>", "").strip()).isEqualTo("Reveal contact info");
        // The card the form sits in is the one the script replaces, and the one #contact lands on.
        List<String> cards = openingTagsWithAttribute(html, "data-contact-card");
        assertThat(cards).singleElement().satisfies(card -> assertThat(attribute(card, "id")).contains("contact"));
        assertThat(html.indexOf(cards.get(0))).isLessThan(html.indexOf(formTag));
        assertThat(html).doesNotContain(PUBLIC_VALUE, PRIVATE_VALUE, "reveal=true");
    }

    @Test
    void detail_withAPublicContact_hasAnInitiallyHiddenErrorLine_forTheScript() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        String form = elements(detailHtml(saved.getId()), "form").get(0);

        assertThat(openingTagsWithAttribute(form, "data-contact-reveal-error"))
                .singleElement()
                .satisfies(tag -> {
                    assertThat(attribute(tag, "hidden")).isPresent();
                    assertThat(attribute(tag, "role")).contains("alert");
                });
    }

    @Test
    void detail_withOnlyPrivateContacts_hasNoRevealForm_andNoContactCard() throws Exception {
        Testimonial saved = withOnlyPrivateContact();

        String html = detailHtml(saved.getId());

        assertThat(elements(html, "form")).isEmpty();
        assertThat(openingTagsWithAttribute(html, "data-contact-card")).isEmpty();
        assertThat(html).doesNotContain(PRIVATE_VALUE);
    }

    /** The old reveal link is gone: the query parameter is simply ignored now. */
    @Test
    void detail_withRevealTrue_noLongerShowsTheContacts() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        String html = mockMvc.perform(get("/gallery/{id}", saved.getId()).param("reveal", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).doesNotContain(PUBLIC_VALUE, PRIVATE_VALUE).contains("gallery-reveal-form");
    }

    // -- the script: loaded on the article only, and only when there is a button for it --

    private static List<String> contactRevealScripts(String html) {
        return openingTags(html, "script").stream()
                .filter(tag -> attribute(tag, "src").map(AssetUrls::plain).orElse("").equals("/js/contact-reveal.js"))
                .toList();
    }

    @Test
    void detail_withAPublicContact_loadsTheRevealScriptOnce_deferred() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        assertThat(contactRevealScripts(detailHtml(saved.getId())))
                .singleElement()
                .satisfies(tag -> assertThat(attribute(tag, "defer")).isPresent());
    }

    @Test
    void detail_withoutAPublicContact_doesNotLoadTheRevealScript() throws Exception {
        assertThat(contactRevealScripts(detailHtml(withOnlyPrivateContact().getId()))).isEmpty();
    }

    @Test
    void formPostResult_doesNotLoadTheRevealScript_thereIsNoButtonLeft() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        assertThat(contactRevealScripts(perform(submitForm(saved.getId())).getContentAsString())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/gallery", "/gallery/999999", "/submissions/login", "/admin/login"})
    void otherPages_doNotLoadTheRevealScript(String path) throws Exception {
        assertThat(contactRevealScripts(perform(get(path)).getContentAsString())).isEmpty();
    }

    @Test
    void revealScript_isServedToAnonymousVisitorsAsJavascript() throws Exception {
        mockMvc.perform(get("/js/contact-reveal.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"));
    }

    // -- nothing else reveals contacts, and nothing else under /gallery takes a POST --

    @Test
    void getOnTheContactEndpoint_neverRevealsContacts() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        MockHttpServletResponse response = perform(get("/gallery/{id}/contact", saved.getId()).header("Origin", SITE));

        assertThat(response.getStatus()).isBetween(400, 499);
        assertThat(response.getContentAsString()).doesNotContain(PUBLIC_VALUE, PRIVATE_VALUE);
    }

    @Test
    void putOnTheContactEndpoint_isBlocked() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        MockHttpServletResponse response = perform(put("/gallery/{id}/contact", saved.getId()).with(csrfField())
                .header("Origin", SITE)
                .header("X-Requested-With", "fetch"));

        assertHtmlErrorPage(response, 404);
        assertThat(response.getContentAsString()).doesNotContain(PUBLIC_VALUE, PRIVATE_VALUE);
    }

    /** Refused by the security chain's {@code denyAll()} tail before any handler: the HTML 404 page. */
    @ParameterizedTest
    @ValueSource(strings = {"/gallery", "/gallery/1", "/gallery/1/contact/extra", "/gallery/1/other"})
    void postToAnyOtherGalleryPath_isBlockedBySecurity(String path) throws Exception {
        assertHtmlErrorPage(perform(post(path).with(csrfField()).header("Origin", SITE)), 404);
    }

    @Test
    void headOfTheGalleryList_isStillAllowed() throws Exception {
        assertThat(perform(head("/gallery")).getStatus()).isEqualTo(200);
    }

    /** Only a plain number (that fits an id) is a testimonial id: anything else is no such page, not an error. */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "-1", "99999999999999999999"})
    void nonNumericOrOversizedId_is404_notAServerError(String id) throws Exception {
        MockHttpServletResponse response = perform(fetchContact(id));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).doesNotContain("Exception", "at com.");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, Long.MAX_VALUE})
    void idAtTheBoundaries_is404(long id) throws Exception {
        assertThat(perform(fetchContact(id)).getStatus()).isEqualTo(404);
    }

    // -- CSRF (BL-004) --

    @Test
    void detail_revealForm_carriesTheCsrfToken() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        Csrf.assertEveryPostFormCarriesTheToken(detailHtml(saved.getId()), 1);
    }

    @Test
    void fetchFromThisSite_withoutTheCsrfToken_is403_andShowsNoContacts() throws Exception {
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        MockHttpServletResponse response = perform(post("/gallery/{id}/contact", saved.getId())
                .header("Origin", SITE)
                .header("Sec-Fetch-Site", "same-origin")
                .header("X-Requested-With", "fetch"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).doesNotContain(PUBLIC_VALUE, PRIVATE_VALUE);
    }

    @Test
    void fetchFromThisSite_withTheTokenOnlyInTheFormFields_isAccepted() throws Exception {
        // The script's fallback when it can't read the cookie: the form's own fields as the body.
        Testimonial saved = withPublicAndPrivateContact(TestimonialStatus.APPROVED);

        MockHttpServletResponse response = perform(post("/gallery/{id}/contact", saved.getId()).with(csrfField())
                .header("Origin", SITE)
                .header("Sec-Fetch-Site", "same-origin")
                .header("X-Requested-With", "fetch")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(PUBLIC_VALUE).doesNotContain(PRIVATE_VALUE);
    }
}
