package com.iitm.beacon.submission;

import static com.iitm.beacon.testsupport.ClientErrors.INTERNALS;
import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.ClientErrors.assertJsonError;
import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.iitm.beacon.testsupport.LogCapture;
import com.iitm.beacon.testsupport.LoginCodeSteps;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/**
 * Client errors on the submission routes (BL-014, BL-029): a submission
 * without its {@code payload} part, or not multipart at all, is a 400/415;
 * a method a URL doesn't take is a 405 with {@code Allow}; the login code
 * step without its fields is the HTML 400 page. A form field the data
 * binder can't bind — an index past its auto-grow limit of 256, a negative
 * or non-numeric index — sends the visitor back to the form with an inline
 * error, like an unreadable upload, and saves nothing. None is a 500 or is
 * logged as a server error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class SubmissionClientErrorsTest {

    private static final String UNREADABLE_FORM =
            "The form couldn't be read, so nothing was saved. Please check it and send it again.";

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubmissionService submissionService;

    @AfterEach
    void nothingWasLoggedAsAServerError() {
        assertThat(logs.errors()).isEmpty();
    }

    private static Authentication visitor(String email) {
        return new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private int generalIndex() {
        List<String> slugs = new ArrayList<>();
        for (TopicCatalogEntryDto entry : submissionService.listTopicCatalog()) {
            if ("GROUP".equals(entry.kind())) {
                entry.subtopics().forEach(t -> slugs.add(t.slug()));
            } else {
                slugs.add(entry.slug());
            }
        }
        return slugs.indexOf("general");
    }

    /** A complete, valid form, with "general" answered under {@code sections[generalIndex]}. */
    private static MockMultipartHttpServletRequestBuilder validForm(String email, int generalIndex) {
        MockMultipartHttpServletRequestBuilder form = multipart("/submissions/form");
        form.with(csrfField())
                .with(authentication(visitor(email)))
                .param("firstName", "David")
                .param("lastName", "Jones")
                .param("rollNumber", "CS21B001")
                .param("admissionYear", "2024")
                .param("countryCode", "IN")
                .param("recommendationScore", "8")
                .param("sections[" + generalIndex + "].topicSlug", "general")
                .param("sections[" + generalIndex + "].answerText", "Great time overall.")
                .param("dataProcessingConsent", "true");
        return form;
    }

    private boolean hasTestimonial(String email) {
        return submissionService.determineMode(email).mode() == SubmissionMode.EDIT;
    }

    // -- REST --

    @Test
    void apiCreate_withoutThePayloadPart_isA400NamingIt() throws Exception {
        MockHttpServletResponse response = perform(multipart("/api/submissions")
                .file(new MockMultipartFile("photo-1", "cat.png", "image/png", new byte[] {1}))
                .with(csrfHeader())
                .with(authentication(visitor("no-payload@example.com"))));

        assertJsonError(response, 400, "Required part 'payload' is missing.", "/api/submissions");
        assertThat(hasTestimonial("no-payload@example.com")).isFalse();
    }

    @Test
    void apiEdit_withoutThePayloadPart_isA400NamingIt() throws Exception {
        MockHttpServletResponse response = perform(multipart(HttpMethod.PUT, "/api/submissions/mine")
                .with(csrfHeader())
                .with(authentication(visitor("no-payload-edit@example.com"))));

        assertJsonError(response, 400, "Required part 'payload' is missing.", "/api/submissions/mine");
    }

    @Test
    void apiCreate_sentAsJsonInsteadOfMultipart_isA415_listingMultipart() throws Exception {
        MockHttpServletResponse response = perform(post("/api/submissions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(csrfHeader())
                .with(authentication(visitor("json-create@example.com"))));

        assertJsonError(response, 415, "This content type is not supported for this resource.", "/api/submissions");
        assertThat(response.getHeader("Accept")).contains(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/submissions/otp/request", "/api/submissions/otp/verify"})
    void apiOtpEndpoints_takeOnlyPost_soAGetIsA405(String path) throws Exception {
        MockHttpServletResponse response = perform(get(path));

        assertJsonError(response, 405, "This method is not supported for this resource.", path);
        assertThat(response.getHeader("Allow")).isEqualTo("POST");
    }

    @Test
    void apiOtpRequest_inAnotherFormatThanJson_isA415() throws Exception {
        assertJsonError(perform(post("/api/submissions/otp/request").with(csrfHeader())
                        .contentType(MediaType.TEXT_PLAIN).content("visitor@example.com")),
                415, "This content type is not supported for this resource.", "/api/submissions/otp/request");
    }

    // -- the login pages --

    /** The code is the step's one field; its email comes from the session (the email step put it there). */
    @Test
    void loginCodeStep_withoutACode_isTheHtml400Page() throws Exception {
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, "visitor@example.com");

        assertHtmlErrorPage(perform(post("/submissions/login/code").with(csrfField()).session(session)), 400);
    }

    @Test
    void loginCodeStep_withoutACodeOrAPendingEmail_isTheHtml400Page() throws Exception {
        assertHtmlErrorPage(perform(post("/submissions/login/code").with(csrfField())), 400);
    }

    @Test
    void loginResendStep_openedWithAGet_isTheHtml405Page_allowingPost() throws Exception {
        MockHttpServletResponse response = perform(get("/submissions/login/resend"));

        assertHtmlErrorPage(response, 405);
        assertThat(response.getHeader("Allow")).isEqualTo("POST");
    }

    @Test
    void loginPage_withAnUnsupportedMethod_isTheHtml405Page_withAllow() throws Exception {
        MockHttpServletResponse response = perform(put("/submissions/login").with(csrfField()));

        assertHtmlErrorPage(response, 405);
        assertThat(response.getHeader("Allow")).contains("GET", "POST").doesNotContain("PUT");
    }

    @Test
    void loginCodePage_withAnUnsupportedMethod_isTheHtml405Page() throws Exception {
        assertHtmlErrorPage(perform(delete("/submissions/login/code").with(csrfField())), 405);
    }

    @Test
    void unknownLoginPage_isTheHtml404Page() throws Exception {
        assertHtmlErrorPage(perform(get("/submissions/login/nothing-here")), 404);
    }

    // -- the form: fields the data binder can't bind (BL-014) --

    @ParameterizedTest
    @ValueSource(strings = {
        "sections[256].answerText",
        "sections[256].topicSlug",
        "contactMethods[256].value",
        "achievementSlugs[256]",
        "sections[0].photoTags[256]",
        "sections[1000].answerText",
        "sections[2147483647].answerText",
        "sections[2147483648].answerText",
        "sections[-1].answerText",
        "sections[abc].answerText",
        "sections[].answerText"
    })
    void formWithAFieldTheBinderCantBind_goesBackToTheFormWithAnInlineError_andSavesNothing(String field)
            throws Exception {
        String email = "unbindable-" + Math.abs(field.hashCode()) + "@example.com";

        MvcResult result = mockMvc.perform(validForm(email, generalIndex()).param(field, "x")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/submissions/form");
        assertThat(result.getFlashMap().get("error")).isEqualTo(UNREADABLE_FORM);
        assertThat(hasTestimonial(email)).isFalse();
    }

    @Test
    void formWithAPhotoPastTheLimit_goesBackToTheFormWithTheInlineError_too() throws Exception {
        String email = "unbindable-photo@example.com";

        MvcResult result = mockMvc.perform(validForm(email, generalIndex())
                        .file(new MockMultipartFile("sections[0].photos[256]", "cat.png", "image/png", new byte[] {1})))
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/submissions/form");
        assertThat(result.getFlashMap().get("error")).isEqualTo(UNREADABLE_FORM);
        assertThat(hasTestimonial(email)).isFalse();
    }

    @Test
    void theInlineError_isShownInTheFormsBanner_withoutInternals() throws Exception {
        String email = "unbindable-banner@example.com";
        MvcResult redirect = mockMvc.perform(validForm(email, generalIndex()).param("sections[256].answerText", "x"))
                .andReturn();

        String html = mockMvc.perform(get("/submissions/form")
                        .flashAttrs(redirect.getFlashMap())
                        .with(authentication(visitor(email))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains(HtmlUtils.htmlEscape(UNREADABLE_FORM)).doesNotContain(INTERNALS);
    }

    /** Off by one: the last index the binder grows a list to (255) is an ordinary field. */
    @Test
    void formWithItsTopicAtIndex255_isStillAccepted() throws Exception {
        String email = "index-255@example.com";

        MvcResult result = mockMvc.perform(validForm(email, 255)).andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/submissions/confirmation");
        assertThat(hasTestimonial(email)).isTrue();
    }
}
