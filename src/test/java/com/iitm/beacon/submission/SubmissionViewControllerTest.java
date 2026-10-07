package com.iitm.beacon.submission;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.common.score.RecommendationScoreLabels;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.config.PhotoStorageProperties;
import com.iitm.beacon.domain.testimonial.Photo;
import com.iitm.beacon.domain.testimonial.PhotoTag;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialSection;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.testsupport.Csrf;
import com.iitm.beacon.testsupport.HtmlSnippets;
import com.iitm.beacon.testsupport.LoginCodeSteps;
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * MockMvc tests for {@link SubmissionViewController}'s Thymeleaf pages — the
 * two-step visitor OTP login flow, the create/edit submission form, and the
 * confirmation page. Role enforcement for {@code /submissions/form} and
 * {@code /submissions/confirmation} comes from {@code SecurityConfig} —
 * these tests confirm it applies to the view routes: without a session (or
 * with an expired one) they redirect to the visitor login page, same as
 * {@code ModerationViewControllerTest} for {@code /moderation/**} ({@code
 * config.SecurityConfigLoginRedirectTest} covers the entry point itself),
 * while an admin session still gets the JSON 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class SubmissionViewControllerTest {

    private static final String CODE_PAGE = LoginCodeSteps.VISITOR_CODE_PAGE;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private PhotoStorageProperties photoStorageProperties;

    @Autowired
    private EntityManager entityManager;

    @Value("${server.tomcat.max-part-count}")
    private int maxPartCount;

    @MockitoBean
    private OtpMailer otpMailer;

    private static Authentication visitor(String email) {
        return new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    /** The code last mailed to {@code email}. */
    private String codeMailedTo(String email) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), captor.capture());
        return captor.getValue();
    }

    private TestimonialSubmissionRequest.SectionInput section(String topicSlug, String answer) {
        return new TestimonialSubmissionRequest.SectionInput(topicSlug, answer, List.of());
    }

    private void persistTestimonialFor(String email) {
        submissionService.create(
                email,
                new TestimonialSubmissionRequest(
                        "David",
                        "Jones",
                        "GE26Z001",
                        2024,
                        "IN",
                        8,
                        List.of(section("general", "Great time overall.")),
                        List.of(),
                        List.of(),
                        true),
                Map.of());
    }

    // -- GET/POST /submissions/login --

    @Test
    void loginEmailForm_get_returns200AndRendersView() throws Exception {
        mockMvc.perform(get("/submissions/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-email"));
    }

    @Test
    void loginEmailForm_get_alreadyLoggedInVisitor_redirectsStraightToForm() throws Exception {
        mockMvc.perform(get("/submissions/login").with(authentication(visitor("login-again@example.com"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/form"));
    }

    @Test
    void loginEmailForm_get_loggedInAdmin_stillRendersVisitorLogin() throws Exception {
        // An admin session carries no visitor identity, so there is no form
        // to skip ahead to.
        mockMvc.perform(get("/submissions/login").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-email"));
    }

    /** Back at the email step — the header link, the browser's back button — the pending email is forgotten. */
    @Test
    void loginEmailForm_get_forgetsThePendingEmail() throws Exception {
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, "forget-me@example.com");

        mockMvc.perform(get("/submissions/login").session(session)).andExpect(status().isOk());

        mockMvc.perform(get(CODE_PAGE).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));
    }

    @Test
    void loginEmailForm_post_redirectsToTheCodePage_withoutTheEmailInTheUrl() throws Exception {
        mockMvc.perform(post("/submissions/login").with(csrfField()).param("email", "new-visitor@example.com"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CODE_PAGE));
    }

    @Test
    void loginEmailForm_post_blankEmail_rerendersWithErrorInsteadOfCrashing() throws Exception {
        mockMvc.perform(post("/submissions/login").with(csrfField()).param("email", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-email"))
                .andExpect(model().attributeExists("error"));
    }

    @Test
    void loginEmailForm_post_missingEmail_rerendersWithErrorAndSendsNothing() throws Exception {
        mockMvc.perform(post("/submissions/login").with(csrfField()))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-email"))
                .andExpect(model().attributeExists("error"));

        verify(otpMailer, never()).sendOtp(anyString(), anyString());
    }

    /** Two requests in one browser (two tabs, say): the last email wins — the page and the verify use it. */
    @Test
    void loginEmailForm_post_secondEmailInTheSameSession_replacesThePendingOne() throws Exception {
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, "first-tab@example.com");
        String firstCode = codeMailedTo("first-tab@example.com");
        LoginCodeSteps.askedForACode(mockMvc, "/submissions/login", "second-tab@example.com", session);

        mockMvc.perform(get(CODE_PAGE).session(session))
                .andExpect(model().attribute("email", "second-tab@example.com"));
        mockMvc.perform(post(CODE_PAGE).with(csrfField()).param("code", firstCode).session(session))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));
        mockMvc.perform(post(CODE_PAGE).with(csrfField())
                        .param("code", codeMailedTo("second-tab@example.com"))
                        .session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/form"));
    }

    // -- GET/POST /submissions/login/code --

    @Test
    void loginCodeForm_get_afterTheEmailStep_rendersTheCodePageWithTheEmail() throws Exception {
        mockMvc.perform(get(CODE_PAGE).session(LoginCodeSteps.visitorAskedForACode(mockMvc, "someone@example.com")))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-code"))
                .andExpect(model().attribute("email", "someone@example.com"));
    }

    @Test
    void loginCodeForm_get_showsTheEmail_butNoFormActionOrLinkCarriesIt() throws Exception {
        String html = okHtml(get(CODE_PAGE)
                .session(LoginCodeSteps.visitorAskedForACode(mockMvc, "jane+tab@example.com")));

        assertThat(html).contains("<strong>jane+tab@example.com</strong>");
        List<String> urls = new ArrayList<>();
        HtmlSnippets.openingTags(html, "form")
                .forEach(tag -> urls.add(HtmlSnippets.attribute(tag, "action").orElse("")));
        HtmlSnippets.openingTags(html, "a")
                .forEach(tag -> urls.add(HtmlSnippets.attribute(tag, "href").orElse("")));
        assertThat(urls).isNotEmpty().allSatisfy(url -> assertThat(url)
                .doesNotContain("jane", "example.com", "email="));
        assertThat(HtmlSnippets.openingTags(html, "input"))
                .noneMatch(input -> input.contains("name=\"email\""))
                .noneMatch(input -> input.contains("jane+tab@example.com"));
    }

    @Test
    void loginCodeForm_get_offersVerifyAndAResendThatPostsToItsOwnStep() throws Exception {
        String html = okHtml(get(CODE_PAGE)
                .session(LoginCodeSteps.visitorAskedForACode(mockMvc, "resend-form@example.com")));

        List<String> actions = HtmlSnippets.openingTags(html, "form").stream()
                .map(tag -> HtmlSnippets.attribute(tag, "action").orElse(""))
                .toList();
        assertThat(actions).contains("/submissions/login/code", "/submissions/login/resend");
        assertThat(html).contains("Resend code");
    }

    /** A fresh browser — no session at all, e.g. a bookmarked or shared code-page URL — starts at the email step. */
    @Test
    void loginCodeForm_get_withoutASession_redirectsBackToLoginEmail() throws Exception {
        mockMvc.perform(get(CODE_PAGE))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));
    }

    @Test
    void loginCodeForm_get_aSessionThatAskedForNoCode_redirectsBackToLoginEmail() throws Exception {
        mockMvc.perform(get(CODE_PAGE).session(new MockHttpSession()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));
    }

    /** An old link with the email in its query is ignored: it neither fills the page nor replaces the pending email. */
    @Test
    void loginCodeForm_get_legacyEmailQueryParameter_isIgnored() throws Exception {
        mockMvc.perform(get(CODE_PAGE).param("email", "someone@example.com"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));
        mockMvc.perform(get(CODE_PAGE).param("email", "other@example.com")
                        .session(LoginCodeSteps.visitorAskedForACode(mockMvc, "pending@example.com")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("email", "pending@example.com"));
    }

    @Test
    void loginCodeForm_get_alreadyLoggedInVisitor_redirectsStraightToForm() throws Exception {
        mockMvc.perform(get(CODE_PAGE)
                        .session(LoginCodeSteps.visitorAskedForACode(mockMvc, "someone@example.com"))
                        .with(authentication(visitor("code-again@example.com"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/form"));
    }

    @Test
    void loginCodeForm_get_alreadyLoggedInVisitorWithoutAPendingEmail_redirectsToFormNotBackToLogin()
            throws Exception {
        mockMvc.perform(get(CODE_PAGE).with(authentication(visitor("code-again-no-email@example.com"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/form"));
    }

    @Test
    void loginCodeForm_get_loggedInAdmin_stillRendersCodePage() throws Exception {
        mockMvc.perform(get(CODE_PAGE)
                        .session(LoginCodeSteps.visitorAskedForACode(mockMvc, "someone@example.com"))
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-code"))
                .andExpect(model().attribute("email", "someone@example.com"));
    }

    @Test
    void loginCodeForm_post_correctCode_redirectsToFormAndEstablishesVisitorSession() throws Exception {
        String email = "view-login-success@example.com";
        MockHttpSession requested = LoginCodeSteps.visitorAskedForACode(mockMvc, email);

        var result = mockMvc.perform(post(CODE_PAGE).with(csrfField())
                        .param("code", codeMailedTo(email))
                        .session(requested))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/form"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        SecurityContext securityContext = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(securityContext).isNotNull();
        assertThat(securityContext.getAuthentication().getPrincipal()).isEqualTo(email);
    }

    /** Once logged in, the email is no longer held as a pending login: no session attribute has it as its value. */
    @Test
    void loginCodeForm_post_correctCode_forgetsThePendingEmail() throws Exception {
        String email = "view-login-forget@example.com";
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, email);

        mockMvc.perform(post(CODE_PAGE).with(csrfField()).param("code", codeMailedTo(email)).session(session))
                .andExpect(status().isFound());

        assertThat(Collections.list(session.getAttributeNames()))
                .noneMatch(name -> email.equals(session.getAttribute(name)));
    }

    @Test
    void loginCodeForm_post_wrongCode_rerendersWithErrorAndTheEmail() throws Exception {
        String email = "view-login-wrong-code@example.com";
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, email);

        mockMvc.perform(post(CODE_PAGE).with(csrfField()).param("code", "ZZZZZZ").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-code"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("email", email));
    }

    /**
     * The code is checked against the session's email only. An email posted
     * along — say, someone else's, with a code mailed to them — is ignored:
     * nobody is logged in as that other address.
     */
    @Test
    void loginCodeForm_post_anEmailPostedAlong_isIgnored() throws Exception {
        String victim = "view-login-victim@example.com";
        LoginCodeSteps.visitorAskedForACode(mockMvc, victim);
        String victimsCode = codeMailedTo(victim);
        MockHttpSession attacker = LoginCodeSteps.visitorAskedForACode(mockMvc, "view-login-attacker@example.com");

        MvcResult result = mockMvc.perform(post(CODE_PAGE).with(csrfField())
                        .param("email", victim)
                        .param("code", victimsCode)
                        .session(attacker))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-code"))
                .andExpect(model().attribute("email", "view-login-attacker@example.com"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
    }

    @Test
    void loginCodeForm_post_withoutAPendingEmail_redirectsToTheEmailStep() throws Exception {
        String email = "view-login-no-session@example.com";
        LoginCodeSteps.visitorAskedForACode(mockMvc, email);

        mockMvc.perform(post(CODE_PAGE).with(csrfField()).param("email", email).param("code", codeMailedTo(email)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));
    }

    // -- POST /submissions/login/resend --

    @Test
    void resend_post_sendsANewCodeToThePendingEmail_whichLogsIn() throws Exception {
        String email = "view-resend@example.com";
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, email);

        mockMvc.perform(post("/submissions/login/resend").with(csrfField()).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CODE_PAGE));

        verify(otpMailer, times(2)).sendOtp(eq(email), anyString());
        mockMvc.perform(post(CODE_PAGE).with(csrfField()).param("code", codeMailedTo(email)).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/form"));
    }

    @Test
    void resend_post_anEmailPostedAlong_isIgnored() throws Exception {
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, "view-resend-pending@example.com");

        mockMvc.perform(post("/submissions/login/resend").with(csrfField())
                        .param("email", "view-resend-other@example.com")
                        .session(session))
                .andExpect(status().isFound());

        verify(otpMailer, never()).sendOtp(eq("view-resend-other@example.com"), anyString());
        verify(otpMailer, times(2)).sendOtp(eq("view-resend-pending@example.com"), anyString());
    }

    @Test
    void resend_post_withoutAPendingEmail_redirectsToTheEmailStepAndSendsNothing() throws Exception {
        mockMvc.perform(post("/submissions/login/resend").with(csrfField()).param("email", "view-resend@example.com"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));

        verify(otpMailer, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void resend_post_withoutTheCsrfToken_isRefusedAndSendsNothing() throws Exception {
        String email = "view-resend-no-csrf@example.com";
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, email);

        mockMvc.perform(post("/submissions/login/resend").session(session)).andExpect(status().isForbidden());

        verify(otpMailer, times(1)).sendOtp(eq(email), anyString());
    }

    // -- GET /submissions/form --

    @Test
    void form_get_newVisitor_rendersEmptyFormForCreateMode() throws Exception {
        mockMvc.perform(get("/submissions/form").with(authentication(visitor("view-form-new@example.com"))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(model().attribute("editing", false));
    }

    @Test
    void form_get_existingVisitor_prefillsFormForEditMode() throws Exception {
        String email = "view-form-existing@example.com";
        persistTestimonialFor(email);

        mockMvc.perform(get("/submissions/form").with(authentication(visitor(email))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(model().attribute("editing", true));
    }

    @Test
    void form_get_existingVisitorWithPhoto_rendersExistingPhotoTileWithoutError() throws Exception {
        // Exercises the template's nested existingPhotoUrls/existingPhotoTags
        // indexed-list rendering branch, which the plain (photo-less)
        // pre-fill test above never reaches.
        String email = "view-form-existing-photo@example.com";
        MockMultipartFile photo = new MockMultipartFile("photo", "cat.png", "image/png", realPngBytes());
        submissionService.create(
                email,
                new TestimonialSubmissionRequest(
                        "David",
                        "Jones",
                        "GE26Z001",
                        2024,
                        "IN",
                        8,
                        List.of(new TestimonialSubmissionRequest.SectionInput(
                                "general",
                                "Great time overall.",
                                List.of(new TestimonialSubmissionRequest.PhotoInput("photo-0", List.of("sunset"))))),
                        List.of(),
                        List.of(),
                        true),
                Map.of("photo-0", photo));

        mockMvc.perform(get("/submissions/form").with(authentication(visitor(email))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(content().string(containsString("sunset")));
    }

    @Test
    void form_get_editMode_savedPhotoIsATileWithItsImageRemoveControlsAndTags_beforeThePhotoInput()
            throws Exception {
        String email = "view-photos-saved-tile@example.com";
        int general = catalogSlugsInFormOrder().indexOf("general");
        submissionService.create(
                email,
                new TestimonialSubmissionRequest("David", "Jones", "GE26Z001", 2024, "IN", 8,
                        List.of(new TestimonialSubmissionRequest.SectionInput("general", "Great time overall.",
                                List.of(new TestimonialSubmissionRequest.PhotoInput("p", List.of("sunset", "beach"))))),
                        List.of(), List.of(), true),
                Map.of("p", new MockMultipartFile("p", "cat.png", "image/png", realPngBytes())));
        String url = submissionService.loadMine(email).sections().get(0).photos().get(0).url();

        String html = formHtml(visitor(email));

        Matcher tile = Pattern.compile("<div[^>]*data-saved-photo[^>]*>[\\s\\S]*?name=\"sections\\[" + general
                + "]\\.existingPhotoTags\\[0]\"[^>]*>").matcher(html);
        assertThat(tile.find()).as("saved photo tile").isTrue();
        String tileHtml = tile.group();
        assertThat(tileHtml)
                .containsPattern("<img[^>]*src=\"" + Pattern.quote(url) + "\"")
                .containsPattern("<input type=\"hidden\"[^>]*name=\"sections\\[" + general
                        + "]\\.existingPhotoUrls\\[0]\"[^>]*value=\"" + Pattern.quote(url) + "\"")
                .containsPattern("<button type=\"button\"[^>]*aria-label=\"Remove photo\"")
                .containsPattern("<input type=\"checkbox\" name=\"sections\\[" + general
                        + "]\\.removedPhotoUrls\"\\s+value=\"" + Pattern.quote(url) + "\"/?>")
                .containsPattern("value=\"sunset, beach\"");
        assertThat(html.indexOf(tileHtml))
                .isLessThan(html.indexOf("name=\"sections[" + general + "].photos\""));
    }

    private static byte[] realPngBytes() throws Exception {
        return realPngBytes(1, 1);
    }

    private static byte[] realPngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void form_get_loadsItsAlpineComponentsBeforeAlpineItselfBothDeferred() throws Exception {
        String html = formHtml(visitor("view-form-js@example.com"));

        // At their versioned URLs (config.StaticAssetsConfig).
        int components = firstIndexOf(html, "<script defer src=\"/js/submission-form-[0-9a-f]{32}\\.js\"></script>");
        int alpine = firstIndexOf(html,
                "<script defer src=\"/webjars/alpinejs/\\d[^/\"]*/dist/cdn\\.min\\.js\"></script>");
        assertThat(components).as("component script").isNotNegative();
        assertThat(alpine)
                .as("Alpine must load after the alpine:init listener is registered")
                .isGreaterThan(components);
        // Without JS nothing may stay cloaked: the whole form is shown instead (static/css/noscript.css;
        // a stylesheet, as the Content-Security-Policy blocks inline styles).
        assertThat(html).containsPattern(
                "<noscript><link rel=\"stylesheet\" href=\"/css/noscript(-[0-9a-f]{32})?\\.css\"/></noscript>");
    }

    private static int firstIndexOf(String html, String regex) {
        Matcher m = Pattern.compile(regex).matcher(html);
        return m.find() ? m.start() : -1;
    }

    // -- recommendation score slider --

    private String formHtml(Authentication who) throws Exception {
        return mockMvc.perform(get("/submissions/form").with(authentication(who)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** The opening tag of the slider label rendered for {@code score}. */
    private static String scoreLabelTag(String html, int score) {
        Matcher m = Pattern.compile("<span[^>]*\\bdata-score=\"" + score + "\"[^>]*>").matcher(html);
        assertThat(m.find()).as("label tag for score " + score).isTrue();
        return m.group();
    }

    private static String scoreInputTag(String html) {
        Matcher m = Pattern.compile("<input[^>]*name=\"recommendationScore\"[^>]*>").matcher(html);
        assertThat(m.find()).as("score range input").isTrue();
        return m.group();
    }

    /** Only {@code visibleScore}'s label is visible before (or without) JS; the other ten carry {@code hidden}. */
    private static void assertOnlyLabelVisibleFor(String html, int visibleScore) {
        for (int score = 0; score <= 10; score++) {
            String tag = scoreLabelTag(html, score);
            if (score == visibleScore) {
                assertThat(tag).as("label " + score).doesNotContain(" hidden=");
            } else {
                assertThat(tag).as("label " + score).contains(" hidden=");
            }
        }
        assertThat(html).contains(RecommendationScoreLabels.forScore(visibleScore));
        assertThat(html).contains("class=\"submission-score-value-row score-" + visibleScore + "\"");
        assertThat(scoreInputTag(html)).contains("value=\"" + visibleScore + "\"");
    }

    @Test
    void form_get_modelOffersAllElevenScoreLabels() throws Exception {
        mockMvc.perform(get("/submissions/form").with(authentication(visitor("view-score-model@example.com"))))
                .andExpect(status().isOk())
                .andExpect(model().attribute("scoreLabels", RecommendationScoreLabels.all()));
    }

    @Test
    void form_get_editMode_showsTheSavedScoresLabelAndColour() throws Exception {
        String email = "view-score-edit@example.com";
        persistTestimonialFor(email); // score 8

        String html = formHtml(visitor(email));

        assertOnlyLabelVisibleFor(html, 8);
        assertThat(html).contains("A great experience, a lot to remember");
        assertThat(html).contains("0 — Terrible").contains("5 — Mixed").contains("10 — Unforgettable");
    }

    @Test
    void form_get_createMode_startsTheSliderAtTenWithItsLabel() throws Exception {
        // No score yet: the slider starts at the top of the scale, and the
        // label shown (and the value Alpine starts from) must match it.
        String html = formHtml(visitor("view-score-create@example.com"));

        assertOnlyLabelVisibleFor(html, 10);
        assertThat(html).contains("data-initial-score=\"10\"");
    }

    @Test
    void form_get_editModeWithASavedScoreOfZero_showsZeroNotTheDefault() throws Exception {
        // 0 is a real score, not "no score yet".
        String email = "view-score-edit-zero@example.com";
        persistTestimonialWithScore(email, 0);

        String html = formHtml(visitor(email));

        assertOnlyLabelVisibleFor(html, 0);
        assertThat(html).contains("data-initial-score=\"0\"");
    }

    @Test
    void form_get_editModeWithASavedScoreOfFive_showsFiveNotTheDefault() throws Exception {
        String email = "view-score-edit-five@example.com";
        persistTestimonialWithScore(email, 5);

        assertOnlyLabelVisibleFor(formHtml(visitor(email)), 5);
    }

    private void persistTestimonialWithScore(String email, int score) {
        submissionService.create(
                email,
                new TestimonialSubmissionRequest("David", "Jones", "GE26Z001", 2024, "IN", score,
                        List.of(section("general", "Great time overall.")), List.of(), List.of(), true),
                Map.of());
    }

    @Test
    void form_post_scoreMissingAltogether_rerendersWithTheSliderAtTen() throws Exception {
        String html = mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("sections[0].topicSlug", "general")
                        .param("sections[0].answerText", "Great time overall.")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor("view-score-missing@example.com"))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(model().attributeExists("error"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertOnlyLabelVisibleFor(html, 10);
    }

    @Test
    void form_post_unparseableScore_rerendersWithTheSliderAtTenInsteadOfCrashing() throws Exception {
        String html = mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("recommendationScore", "not-a-number")
                        .with(authentication(visitor("view-score-garbage@example.com"))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(model().attributeExists("error"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertOnlyLabelVisibleFor(html, 10);
    }

    @Test
    void form_post_scoreAboveTen_rerendersWithTheSliderAtTen() throws Exception {
        assertOnlyLabelVisibleFor(postValidFormWithScore("view-score-eleven@example.com", "11"), 10);
    }

    @Test
    void form_post_negativeScore_rerendersWithTheSliderAtZero() throws Exception {
        assertOnlyLabelVisibleFor(postValidFormWithScore("view-score-negative@example.com", "-3"), 0);
    }

    private String postValidFormWithScore(String email, String score) throws Exception {
        return mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", score)
                        .param("sections[0].topicSlug", "general")
                        .param("sections[0].answerText", "Great time overall.")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(model().attributeExists("error"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    // -- topic picker (chips + one fieldset per pick) --

    /** Pick key per the form contract: {@code group-{groupId}} or {@code topic-{topicId}}. */
    private static String pickKey(TopicCatalogEntryDto entry) {
        return "GROUP".equals(entry.kind()) ? "group-" + entry.groupId() : "topic-" + entry.topicId();
    }

    private String pickKeyOf(String topicSlug) {
        for (TopicCatalogEntryDto entry : submissionService.listTopicCatalog()) {
            if ("GROUP".equals(entry.kind())
                    ? entry.subtopics().stream().anyMatch(t -> t.slug().equals(topicSlug))
                    : entry.slug().equals(topicSlug)) {
                return pickKey(entry);
            }
        }
        throw new AssertionError("no pick for " + topicSlug);
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

    private static List<String> initialPicked(String html) {
        Matcher m = Pattern.compile("data-initial-picked=\"([^\"]*)\"").matcher(html);
        assertThat(m.find()).as("data-initial-picked").isTrue();
        return Arrays.stream(m.group(1).split(" ")).filter(k -> !k.isBlank()).toList();
    }

    private static String openingTag(String html, String element, String pickKey) {
        Matcher m = Pattern.compile("<" + element + "[^>]*\\bdata-pick=\"" + pickKey + "\"[^>]*>").matcher(html);
        assertThat(m.find()).as(element + " for " + pickKey).isTrue();
        return m.group();
    }

    /** The server-rendered {@code class} attribute's classes (not Alpine's {@code x-bind:class} expression). */
    private static List<String> staticClasses(String tag) {
        Matcher m = Pattern.compile("\\sclass=\"([^\"]*)\"").matcher(tag);
        assertThat(m.find()).as("class attribute").isTrue();
        return Arrays.stream(m.group(1).trim().split("\\s+")).toList();
    }

    private static int count(String html, String needle) {
        return html.split(Pattern.quote(needle), -1).length - 1;
    }

    private void persistTestimonialWithSections(String email, String... topicSlugs) {
        List<TestimonialSubmissionRequest.SectionInput> sections = Arrays.stream(topicSlugs)
                .map(slug -> section(slug, "Saved answer for " + slug + "."))
                .toList();
        submissionService.create(
                email,
                new TestimonialSubmissionRequest(
                        "David", "Jones", "GE26Z001", 2024, "IN", 8, sections, List.of(), List.of(), true),
                Map.of());
    }

    @Test
    void form_get_rendersOneChipAndOneFieldsetPerTopLevelTopic() throws Exception {
        List<TopicCatalogEntryDto> catalog = submissionService.listTopicCatalog();

        String html = formHtml(visitor("view-picker-chips@example.com"));

        assertThat(count(html, "<button type=\"button\" class=\"submission-chip-toggle")).isEqualTo(catalog.size());
        assertThat(count(html, "<fieldset")).isEqualTo(catalog.size());
        for (TopicCatalogEntryDto entry : catalog) {
            assertThat(count(html, "data-pick=\"" + pickKey(entry) + "\"")).as(pickKey(entry)).isEqualTo(2);
        }
        assertThat(html.replaceAll("\\s+", " "))
                .contains("Pick at least one topic below — picking a group reveals all of its subtopics,"
                        + " each still optional to fill in.");
    }

    @Test
    void form_get_createMode_opensWithOnlyTheGeneralPickSelected() throws Exception {
        String general = pickKeyOf("general");
        String academics = pickKeyOf("academics_teaching");

        String html = formHtml(visitor("view-picker-create@example.com"));

        assertThat(initialPicked(html)).containsExactly(general);
        String generalChip = openingTag(html, "button", general);
        assertThat(staticClasses(generalChip))
                .contains(
                        "submission-chip-toggle",
                        "submission-chip-toggle--general",
                        "submission-chip-toggle--selected");
        assertThat(generalChip).contains("aria-pressed=\"true\"");
        String academicsChip = openingTag(html, "button", academics);
        assertThat(staticClasses(academicsChip))
                .containsExactly("submission-chip-toggle");
        assertThat(academicsChip).contains("aria-pressed=\"false\"");
        assertThat(openingTag(html, "fieldset", general)).doesNotContain("x-cloak");
        assertThat(openingTag(html, "fieldset", academics)).contains("x-cloak");
        // Picked or not, nothing is disabled server-side: without JS every block must be submittable.
        assertThat(openingTag(html, "fieldset", academics)).doesNotContain(" disabled=");
    }

    @Test
    void form_get_generalChip_hasNoRequiredAsterisk() throws Exception {
        String html = formHtml(visitor("view-picker-no-asterisk@example.com"));

        Matcher chip = Pattern.compile("data-pick=\"" + pickKeyOf("general") + "\"[^>]*>([^<]*)</button>")
                .matcher(html);
        assertThat(chip.find()).isTrue();
        assertThat(chip.group(1).strip()).isEqualTo("General");
    }

    @Test
    void form_get_editMode_preselectsEveryPickWithASavedSectionPlusGeneral() throws Exception {
        String email = "view-picker-edit@example.com";
        persistTestimonialWithSections(email, "academics_standout", "academics_workload", "networking");

        String html = formHtml(visitor(email));

        assertThat(initialPicked(html)).containsExactlyInAnyOrder(
                pickKeyOf("academics_standout"), pickKeyOf("networking"), pickKeyOf("general"));
        assertThat(openingTag(html, "fieldset", pickKeyOf("networking"))).doesNotContain("x-cloak");
        assertThat(openingTag(html, "fieldset", pickKeyOf("travel_did"))).contains("x-cloak");
        assertThat(html).contains("Saved answer for academics_workload.");
    }

    // -- photo fields --

    private static List<String> fileInputs(String html) {
        Matcher m = Pattern.compile("<input[^>]*type=\"file\"[^>]*>").matcher(html);
        List<String> inputs = new ArrayList<>();
        while (m.find()) {
            inputs.add(m.group());
        }
        return inputs;
    }

    @Test
    void form_get_everyTopicBlockHasOneMultiplePhotoInputNamedAfterItsSection() throws Exception {
        List<String> slugs = catalogSlugsInFormOrder();

        List<String> inputs = fileInputs(formHtml(visitor("view-photos-inputs@example.com")));

        assertThat(inputs).hasSize(slugs.size());
        for (int i = 0; i < slugs.size(); i++) {
            assertThat(inputs.get(i)).as("photo input of sections[" + i + "]")
                    .contains("name=\"sections[" + i + "].photos\"")
                    .contains(" multiple")
                    .contains("accept=\"image/*\"")
                    .contains("aria-describedby=\"photo-limits-" + i + "\"");
        }
    }

    @Test
    void form_get_createMode_hasNoFixedUploadSlotsAndNoTagsFieldsForNewPhotos() throws Exception {
        // New photos' tags fields are added by the script, one per chosen file.
        String html = formHtml(visitor("view-photos-no-slots@example.com"));

        assertThat(html).doesNotContainPattern("name=\"sections\\[\\d+]\\.photos\\[")
                .doesNotContain(".photoTags[")
                .doesNotContain("uploadSlot");
    }

    @Test
    void form_get_everyPhotoFieldStatesTheConfiguredLimits() throws Exception {
        String limits = "Up to " + photoStorageProperties.maxPhotosPerSection() + " photos per topic, "
                + photoStorageProperties.maxPhotosPerTestimonial() + " in total, up to 20 MB each."
                + " JPEG, PNG, WebP, GIF, TIFF or BMP.";
        assertThat(photoStorageProperties.maxPhotoSizeBytes()).isEqualTo(20L * 1024 * 1024);
        List<String> slugs = catalogSlugsInFormOrder();

        String html = formHtml(visitor("view-photos-limits@example.com"));

        assertThat(count(html, limits)).isEqualTo(slugs.size());
        for (int i = 0; i < slugs.size(); i++) {
            assertThat(html).containsPattern("id=\"photo-limits-" + i + "\"[^>]*>" + Pattern.quote(limits) + "<");
        }
    }

    @Test
    void form_get_photoPickerHandsTheConfiguredLimitsAndItsTagsFieldNameToTheScript() throws Exception {
        int general = catalogSlugsInFormOrder().indexOf("general");

        String html = formHtml(visitor("view-photos-data@example.com"));

        Matcher pickers = Pattern.compile("<div[^>]*x-data=\"photoPicker\"[^>]*>").matcher(html);
        List<String> tags = new ArrayList<>();
        while (pickers.find()) {
            tags.add(pickers.group());
        }
        assertThat(tags).hasSize(catalogSlugsInFormOrder().size()).allSatisfy(tag -> assertThat(tag)
                .contains("data-max-per-topic=\"" + photoStorageProperties.maxPhotosPerSection() + "\"")
                .contains("data-max-total=\"" + photoStorageProperties.maxPhotosPerTestimonial() + "\"")
                .contains("data-max-bytes=\"" + photoStorageProperties.maxPhotoSizeBytes() + "\"")
                .contains("data-max-size-label=\"20 MB\""));
        assertThat(tags.get(general)).contains("data-tags-name=\"sections[" + general + "].photoTags\"");
    }

    @Test
    void form_get_worstCaseSubmission_staysWithinTheConfiguredMultipartPartCount() throws Exception {
        // Without JS every topic block is submitted, so every rendered named
        // control can become a part (a file input with no file still posts
        // one). On top: 3 parts per saved photo in edit mode (url, remove
        // checkbox, tags) — a removed one too — and 2 per new photo (its file
        // and its tags); at most max-photos-per-testimonial of each.
        String html = formHtml(visitor("view-picker-part-count@example.com"));
        String form = html.substring(html.indexOf("<form"), html.indexOf("</form>"));

        int worstCaseParts = count(form, " name=\"") + 5 * photoStorageProperties.maxPhotosPerTestimonial();

        assertThat(worstCaseParts).isLessThanOrEqualTo(maxPartCount);
    }

    @Test
    void form_post_onlyOneFieldsetSubmittedFromTheMiddleOfTheCatalog_succeeds() throws Exception {
        String email = "view-picker-sparse-post@example.com";
        List<String> slugs = catalogSlugsInFormOrder();
        int index = slugs.indexOf("travel_recommend");

        mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[" + index + "].topicSlug", "travel_recommend")
                        .param("sections[" + index + "].answerText", "Pondicherry.")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        Testimonial saved = savedTestimonial(email);
        assertThat(saved.getSections()).singleElement().satisfies(section -> {
            assertThat(section.getTopic().getSlug()).isEqualTo("travel_recommend");
            assertThat(section.getAnswerText()).isEqualTo("Pondicherry.");
        });
    }

    @Test
    void form_post_onlyTheLastTopicSubmitted_succeeds() throws Exception {
        String email = "view-picker-last-index@example.com";
        List<String> slugs = catalogSlugsInFormOrder();
        int last = slugs.size() - 1;

        mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[" + last + "].topicSlug", slugs.get(last))
                        .param("sections[" + last + "].answerText", "Last one.")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        assertThat(savedTestimonial(email).getSections()).extracting(s -> s.getTopic().getSlug())
                .containsExactly(slugs.get(last));
    }

    @Test
    void form_post_editMode_unpickedTopicIsNotSubmittedAndItsSavedSectionIsRemoved() throws Exception {
        String email = "view-picker-unpick@example.com";
        persistTestimonialWithSections(email, "networking", "general");
        int generalIndex = catalogSlugsInFormOrder().indexOf("general");

        mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[" + generalIndex + "].topicSlug", "general")
                        .param("sections[" + generalIndex + "].answerText", "Saved answer for general.")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        assertThat(savedTestimonial(email).getSections()).extracting(s -> s.getTopic().getSlug())
                .containsExactly("general");
    }

    @Test
    void form_post_failedSparseSubmission_rerendersEveryTopicUnderItsOwnSlugAndKeepsTheAnswer() throws Exception {
        List<String> slugs = catalogSlugsInFormOrder();
        int index = slugs.indexOf("travel_recommend");

        String html = mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[" + index + "].topicSlug", "travel_recommend")
                        .param("sections[" + index + "].answerText", "Pondicherry.")
                        // no dataProcessingConsent -> rejected, form re-rendered
                        .with(authentication(visitor("view-picker-rerender@example.com"))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(model().attributeExists("error"))
                .andExpect(result -> {
                    SubmissionFormCommand command =
                            (SubmissionFormCommand) result.getModelAndView().getModel().get("command");
                    assertThat(command.getSections()).extracting(SectionFormEntry::getTopicSlug)
                            .containsExactlyElementsOf(slugs);
                    assertThat(command.getSections().get(index).getAnswerText()).isEqualTo("Pondicherry.");
                })
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("name=\"sections[" + (slugs.size() - 1) + "].topicSlug\" value=\"general\"");
        assertThat(initialPicked(html)).containsExactlyInAnyOrder(pickKeyOf("travel_recommend"), pickKeyOf("general"));
    }

    @Test
    void form_post_answerPostedUnderAStaleIndex_staysWithItsTopicOnRerender() throws Exception {
        List<String> slugs = catalogSlugsInFormOrder();
        assertThat(slugs.get(0)).isNotEqualTo("networking");

        mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("sections[0].topicSlug", "networking")
                        .param("sections[0].answerText", "Met great people.")
                        .with(authentication(visitor("view-picker-stale-index@example.com"))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"))
                .andExpect(result -> {
                    SubmissionFormCommand command =
                            (SubmissionFormCommand) result.getModelAndView().getModel().get("command");
                    assertThat(command.getSections().get(0).getTopicSlug()).isEqualTo(slugs.get(0));
                    assertThat(command.getSections().get(0).getAnswerText()).isNull();
                    assertThat(command.getSections().get(slugs.indexOf("networking")).getAnswerText())
                            .isEqualTo("Met great people.");
                });
    }

    /** A valid create POST with only "general" filled in, at its catalog index. */
    private MockMultipartHttpServletRequestBuilder generalPost(
            int generalIndex, String email) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/submissions/form");
        builder.with(csrfField());
        builder.param("firstName", "David")
                .param("lastName", "Jones")
                .param("rollNumber", "GE26Z001")
                .param("admissionYear", "2024")
                .param("countryCode", "IN")
                .param("recommendationScore", "8")
                .param("sections[" + generalIndex + "].topicSlug", "general")
                .param("sections[" + generalIndex + "].answerText", "Great time overall.")
                .param("dataProcessingConsent", "true")
                .with(authentication(visitor(email)));
        return builder;
    }

    @Test
    void form_post_threeFilesOfOneMultipleInput_areSavedInOrderEachWithTheTagsAtItsIndex() throws Exception {
        String email = "view-photos-three@example.com";
        int generalIndex = catalogSlugsInFormOrder().indexOf("general");
        String name = "sections[" + generalIndex + "].photos";

        mockMvc.perform(generalPost(generalIndex, email)
                        .file(new MockMultipartFile(name, "a.png", "image/png", realPngBytes(3, 1)))
                        .file(new MockMultipartFile(name, "b.png", "image/png", realPngBytes(1, 3)))
                        .file(new MockMultipartFile(name, "c.png", "image/png", realPngBytes(2, 2)))
                        .param("sections[" + generalIndex + "].photoTags[0]", "beach")
                        .param("sections[" + generalIndex + "].photoTags[1]", "")
                        .param("sections[" + generalIndex + "].photoTags[2]", "sunset, dunes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        List<Photo> photos = savedTestimonial(email).getSections().get(0).getPhotos().stream()
                .sorted(Comparator.comparingInt(Photo::getDisplayOrder))
                .toList();
        assertThat(photos).extracting(p -> p.getWidth() + "x" + p.getHeight()).containsExactly("3x1", "1x3", "2x2");
        assertThat(photos).extracting(p -> p.getTags().stream().map(PhotoTag::getTagText).sorted().toList())
                .containsExactly(List.of("beach"), List.of(), List.of("dunes", "sunset"));
    }

    @Test
    void form_post_aSingleFileUnderTheListName_isSaved() throws Exception {
        String email = "view-photos-single@example.com";
        int generalIndex = catalogSlugsInFormOrder().indexOf("general");

        mockMvc.perform(generalPost(generalIndex, email)
                        .file(new MockMultipartFile(
                                "sections[" + generalIndex + "].photos", "cat.png", "image/png", realPngBytes()))
                        .param("sections[" + generalIndex + "].photoTags[0]", "cat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        assertThat(savedTestimonial(email).getSections().get(0).getPhotos()).singleElement().satisfies(saved ->
                assertThat(saved.getTags()).extracting(PhotoTag::getTagText).containsExactly("cat"));
    }

    @Test
    void form_post_untouchedPhotoInputOfEveryTopic_savesNoPhoto() throws Exception {
        String email = "view-photos-untouched@example.com";
        List<String> slugs = catalogSlugsInFormOrder();
        int generalIndex = slugs.indexOf("general");
        var request = generalPost(generalIndex, email);
        for (int i = 0; i < slugs.size(); i++) {
            request.file(new MockMultipartFile(
                    "sections[" + i + "].photos", "", "application/octet-stream", new byte[0]));
        }

        mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        assertThat(savedTestimonial(email).getSections()).singleElement()
                .satisfies(section -> assertThat(section.getPhotos()).isEmpty());
    }

    private Testimonial savedTestimonial(String email) {
        return testimonialRepository.findAll().stream()
                .filter(t -> email.equals(t.getEmail()))
                .findFirst()
                .orElseThrow();
    }

    // -- contact methods --

    @Test
    void form_get_contactRowsAreLabelledWithTheNetworkNameAndUseTheLabelAsPlaceholder() throws Exception {
        String html = formHtml(visitor("view-contact-names@example.com"));

        Matcher whatsappRow = Pattern.compile(
                        "<label class=\"submission-contact-label\"[^>]*>WhatsApp</label>[\\s\\S]*?"
                                + "<input type=\"text\"[^>]*>")
                .matcher(html);
        assertThat(whatsappRow.find()).as("WhatsApp contact row").isTrue();
        assertThat(whatsappRow.group()).contains("placeholder=\"phone number, or a wa.me link\"");
        for (String name : List.of("Email", "Telegram", "Instagram", "X (Twitter)")) {
            assertThat(html).contains("\">" + name + "</label>");
        }
    }

    @Test
    void form_get_unauthenticated_redirectsToVisitorLogin() throws Exception {
        mockMvc.perform(get("/submissions/form"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));
    }

    @Test
    void form_get_adminRole_redirectsToVisitorLogin() throws Exception {
        mockMvc.perform(get("/submissions/form").with(authentication(admin())))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));
    }

    // -- POST /submissions/form --

    @Test
    void form_post_createMode_persistsAndRedirectsToConfirmation() throws Exception {
        String email = "view-form-post-create@example.com";

        mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[0].topicSlug", "general")
                        .param("sections[0].answerText", "Great time overall.")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        Testimonial saved = testimonialRepository
                .findAll()
                .stream()
                .filter(t -> email.equals(t.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(saved.getSections().get(0).getAnswerText()).isEqualTo("Great time overall.");
    }

    @Test
    void form_post_editMode_updatesAndRedirectsToConfirmation() throws Exception {
        String email = "view-form-post-edit@example.com";
        persistTestimonialFor(email);

        mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[0].topicSlug", "general")
                        .param("sections[0].answerText", "Updated via form.")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        Testimonial saved = testimonialRepository
                .findAll()
                .stream()
                .filter(t -> email.equals(t.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(saved.getSections().get(0).getAnswerText()).isEqualTo("Updated via form.");
    }

    @Test
    void form_post_editOfApprovedWithSameAchievementsPlusNewSectionWithPhoto_persistsThePhotoAndGoesPending()
            throws Exception {
        // Regression: re-selecting achievements the testimonial already had
        // used to re-insert their join rows under the same composite id,
        // failing the whole edit with a 500 and rolling back the new
        // section's photo along with it.
        String email = "view-form-edit-approved-photo@example.com";
        List<String> slugs = catalogSlugsInFormOrder();
        int generalIndex = slugs.indexOf("general");
        int clubsIndex = slugs.indexOf("campus_clubs");

        mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[" + generalIndex + "].topicSlug", "general")
                        .param("sections[" + generalIndex + "].answerText", "Great time overall.")
                        .param("achievementSlugs", "made_new_friends", "traveled_nearby_countries")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));
        Testimonial created = savedTestimonial(email);
        created.setStatus(TestimonialStatus.APPROVED);
        testimonialRepository.saveAndFlush(created);

        MockMultipartFile photo = new MockMultipartFile(
                "sections[" + clubsIndex + "].photos", "club.png", "image/png", realPngBytes());
        mockMvc.perform(multipart("/submissions/form")
                        .file(photo).with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[" + generalIndex + "].topicSlug", "general")
                        .param("sections[" + generalIndex + "].answerText", "Great time overall.")
                        .param("sections[" + clubsIndex + "].topicSlug", "campus_clubs")
                        .param("sections[" + clubsIndex + "].answerText", "Joined the chess club.")
                        .param("sections[" + clubsIndex + "].photoTags[0]", "chess")
                        .param("achievementSlugs", "made_new_friends", "traveled_nearby_countries")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/confirmation"));

        // Flush-time failures must surface here, and the reload must hit the
        // database rather than the persistence context.
        entityManager.flush();
        entityManager.clear();
        Testimonial edited = savedTestimonial(email);
        assertThat(edited.getStatus()).isEqualTo(TestimonialStatus.PENDING);
        assertThat(edited.getAchievements()).extracting(ta -> ta.getAchievement().getSlug())
                .containsExactlyInAnyOrder("made_new_friends", "traveled_nearby_countries");
        TestimonialSection clubs = edited.getSections().stream()
                .filter(s -> "campus_clubs".equals(s.getTopic().getSlug()))
                .findFirst()
                .orElseThrow();
        assertThat(clubs.getPhotos()).singleElement().satisfies(saved -> {
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getFilePath()).endsWith(".webp");
            assertThat(saved.getThumbnailPath()).endsWith("-thumb.webp");
            assertThat(saved.getTags()).extracting(PhotoTag::getTagText).containsExactly("chess");
        });
    }

    @Test
    void form_post_unauthenticated_redirectsToVisitorLogin() throws Exception {
        mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[0].topicSlug", "general")
                        .param("sections[0].answerText", "Text.")
                        .param("dataProcessingConsent", "true"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));
    }

    // -- GET /submissions/confirmation --

    @Test
    void confirmation_get_authenticatedVisitor_returns200() throws Exception {
        mockMvc.perform(
                        get("/submissions/confirmation").with(authentication(visitor("view-confirm@example.com"))))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/confirmation"));
    }

    /** The homepage is the dashboard at {@code /} now (decision 30), no longer the gallery. */
    @Test
    void confirmation_get_backToHomepage_linksToTheDashboard() throws Exception {
        String html = mockMvc.perform(
                        get("/submissions/confirmation").with(authentication(visitor("view-confirm-home@example.com"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("<a class=\"confirmation-btn\" href=\"/\">Back to homepage</a>");
    }

    @Test
    void confirmation_get_unauthenticated_redirectsToVisitorLogin() throws Exception {
        mockMvc.perform(get("/submissions/confirmation"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));
    }

    // -- CSRF (BL-004) --

    private String okHtml(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void loginPages_carryTheCsrfTokenInEveryForm() throws Exception {
        Csrf.assertEveryPostFormCarriesTheToken(okHtml(get("/submissions/login")), 1);
        Csrf.assertEveryPostFormCarriesTheToken(
                okHtml(get(CODE_PAGE).session(LoginCodeSteps.visitorAskedForACode(mockMvc, "csrf-pages@example.com"))),
                2);
    }

    @Test
    void submissionForm_carriesTheCsrfToken_alsoWhenRerenderedWithErrors() throws Exception {
        Authentication visitor = visitor("view-csrf-form@example.com");
        Csrf.assertEveryPostFormCarriesTheToken(okHtml(get("/submissions/form").with(authentication(visitor))), 1);

        String rerendered = okHtml(multipart("/submissions/form").with(csrfField())
                .param("firstName", "")
                .param("sections[0].topicSlug", "general")
                .param("sections[0].answerText", "Great time overall.")
                .with(authentication(visitor)));

        assertThat(rerendered).contains("submission-error");
        Csrf.assertEveryPostFormCarriesTheToken(rerendered, 1);
    }

    @Test
    void loginEmail_post_withoutTheCsrfToken_isRefusedAndSendsNothing() throws Exception {
        mockMvc.perform(post("/submissions/login").param("email", "no-csrf-login@example.com"))
                .andExpect(status().isForbidden());

        verify(otpMailer, never()).sendOtp(eq("no-csrf-login@example.com"), anyString());
    }

    @Test
    void form_post_withoutTheCsrfToken_isRefusedAndSavesNothing() throws Exception {
        String email = "view-form-post-no-csrf@example.com";

        mockMvc.perform(multipart("/submissions/form")
                        .param("firstName", "David")
                        .param("lastName", "Jones")
                        .param("rollNumber", "GE26Z001")
                        .param("admissionYear", "2024")
                        .param("countryCode", "IN")
                        .param("recommendationScore", "8")
                        .param("sections[0].topicSlug", "general")
                        .param("sections[0].answerText", "Great time overall.")
                        .param("dataProcessingConsent", "true")
                        .with(authentication(visitor(email))))
                .andExpect(status().isForbidden());

        assertThat(testimonialRepository.findAll()).noneMatch(t -> email.equals(t.getEmail()));
    }
}
