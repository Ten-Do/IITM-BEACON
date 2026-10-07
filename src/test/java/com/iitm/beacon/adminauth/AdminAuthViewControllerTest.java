package com.iitm.beacon.adminauth;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.testsupport.Csrf;
import com.iitm.beacon.testsupport.LoginCodeSteps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MockMvc tests for {@link AdminAuthViewController}'s two-step login flow —
 * an email page ({@code adminauth/login}) that requests a code, then a
 * separate code page ({@code adminauth/login-code}) with a single OTP field,
 * mirroring the visitor flow. Every route here is anonymous ({@code
 * SecurityConfig} permits {@code /admin/login/**}), which these tests confirm
 * by never authenticating. Role enforcement for {@code /moderation/**} is the
 * same {@code SecurityConfig} matcher already covering the JSON API and the
 * moderation view routes — these tests confirm a successful verify here
 * actually establishes a session that satisfies it.
 *
 * <p>Rate-limit and expired-OTP scenarios live in their own top-level test
 * classes ({@link AdminAuthViewControllerRateLimitTest}, {@link
 * AdminAuthViewControllerExpiredOtpTest}) — same separately-cached-context
 * convention as the REST layer's {@code AdminAuthControllerRateLimitTest}/
 * {@code AdminAuthControllerExpiredOtpTest}, since both need a distinct
 * Spring context (custom properties / a mutable clock bean) that must not
 * leak into this class's tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AdminAuthViewControllerTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String BLANK_EMAIL_MESSAGE = "Please enter the admin email address.";
    private static final String CODE_PAGE = LoginCodeSteps.ADMIN_CODE_PAGE;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtpMailer otpMailer;

    /** A new browser session that has just asked for a code for {@code email} at the email step. */
    private MockHttpSession askedForACode(String email) throws Exception {
        return LoginCodeSteps.adminAskedForACode(mockMvc, email);
    }

    /** The code last mailed to the admin. */
    private String lastCode() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(ADMIN_EMAIL), captor.capture());
        return captor.getValue();
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** Every opening tag of {@code element} in document order. */
    private static List<String> tags(String html, String element) {
        Matcher m = Pattern.compile("<" + element + "\\b[^>]*>").matcher(html);
        List<String> result = new ArrayList<>();
        while (m.find()) {
            result.add(m.group());
        }
        return result;
    }

    /** The markup of the {@code <form>} posting to {@code action}, up to its closing tag. */
    private static String formPostingTo(String html, String action) {
        Matcher m = Pattern.compile("<form\\b[^>]*\\baction=\"" + Pattern.quote(action) + "\"[^>]*>[\\s\\S]*?</form>")
                .matcher(html);
        assertThat(m.find()).as("form posting to " + action).isTrue();
        return m.group();
    }

    private static List<String> visibleInputs(String html) {
        return tags(html, "input").stream().filter(t -> !t.contains("type=\"hidden\"")).toList();
    }

    // -- GET /admin/login (step 1: email) --

    @Test
    void loginForm_get_rendersOnlyTheEmailStepPostingToRequest() throws Exception {
        String html = html(get("/admin/login"));

        assertThat(tags(html, "form")).singleElement().satisfies(form -> assertThat(form)
                .contains("method=\"post\"")
                .contains("action=\"/admin/login/request\""));
        assertThat(visibleInputs(html)).singleElement().satisfies(input -> assertThat(input)
                .contains("name=\"email\"")
                .contains("type=\"email\"")
                .contains("required"));
        assertThat(formPostingTo(html, "/admin/login/request")).contains("Send code");
        assertThat(html).doesNotContain("name=\"code\"").doesNotContain("/admin/login/verify");
    }

    @Test
    void loginForm_get_rendersTheLoginView() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"));
    }

    @Test
    void loginForm_get_legacyEmailAndSentParams_areIgnored() throws Exception {
        // Old single-screen links (?email=...&sent=true) must not resurrect
        // the combined layout or a "code sent" claim on the email step.
        MvcResult result = mockMvc.perform(get("/admin/login").param("email", ADMIN_EMAIL).param("sent", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attributeDoesNotExist("sent", "email"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("name=\"code\"")
                .doesNotContain("Code sent");
    }

    // -- POST /admin/login/request --

    @Test
    void requestOtp_post_adminEmail_sendsCodeAndRedirectsToTheCodeStepWithoutTheEmailInTheUrl() throws Exception {
        mockMvc.perform(post("/admin/login/request").with(csrfField()).param("email", ADMIN_EMAIL))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CODE_PAGE));

        verify(otpMailer, atLeastOnce()).sendOtp(eq(ADMIN_EMAIL), anyString());
    }

    @Test
    void requestOtp_post_nonAdminEmail_redirectsExactlyLikeTheAdminEmailButSendsNothing() throws Exception {
        mockMvc.perform(post("/admin/login/request").with(csrfField()).param("email", "not-the-admin@example.com"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CODE_PAGE));

        verify(otpMailer, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void requestOtp_post_theTypedEmailReachesTheCodeStepThroughTheSession_asTyped() throws Exception {
        // A '+' once had to survive a query string; now nothing is encoded at all.
        MockHttpSession session = askedForACode("admin+ops@example.com");

        mockMvc.perform(get(CODE_PAGE).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attribute("email", "admin+ops@example.com"));
    }

    @Test
    void requestOtp_post_blankEmail_rerendersTheEmailStepWithErrorAndSendsNothing() throws Exception {
        mockMvc.perform(post("/admin/login/request").with(csrfField()).param("email", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attribute("error", BLANK_EMAIL_MESSAGE));

        verify(otpMailer, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void requestOtp_post_whitespaceOnlyEmail_rerendersTheEmailStepWithError() throws Exception {
        mockMvc.perform(post("/admin/login/request").with(csrfField()).param("email", "   "))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attribute("error", BLANK_EMAIL_MESSAGE));
    }

    @Test
    void requestOtp_post_missingEmailParam_rerendersTheEmailStepWithErrorInsteadOfCrashing() throws Exception {
        mockMvc.perform(post("/admin/login/request").with(csrfField()))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attribute("error", BLANK_EMAIL_MESSAGE));
    }

    /** A blank email is no email: the one asked for before is still the pending one. */
    @Test
    void requestOtp_post_blankEmailAfterARealOne_keepsTheEarlierEmailPending() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);

        mockMvc.perform(post("/admin/login/request").with(csrfField()).param("email", " ").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get(CODE_PAGE).session(session)).andExpect(model().attribute("email", ADMIN_EMAIL));
    }

    /** Two requests in one browser (two tabs, say): the last email wins. */
    @Test
    void requestOtp_post_secondEmailInTheSameSession_replacesThePendingOne() throws Exception {
        MockHttpSession session = askedForACode("first@example.com");

        LoginCodeSteps.askedForACode(mockMvc, "/admin/login/request", "second@example.com", session);

        mockMvc.perform(get(CODE_PAGE).session(session)).andExpect(model().attribute("email", "second@example.com"));
    }

    // -- GET /admin/login/code (step 2: code) --

    @Test
    void loginCodeForm_get_afterTheEmailStep_rendersTheCodeStepWithTheEmail() throws Exception {
        mockMvc.perform(get(CODE_PAGE).session(askedForACode(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attribute("email", ADMIN_EMAIL));
    }

    @Test
    void loginCodeForm_get_rendersASingleOtpFieldPostingToVerify_withNoEmailField() throws Exception {
        String html = html(get(CODE_PAGE).session(askedForACode(ADMIN_EMAIL)));

        assertThat(visibleInputs(html)).singleElement().satisfies(input -> assertThat(input)
                .contains("name=\"code\"")
                .contains("maxlength=\"6\"")
                .contains("autocomplete=\"one-time-code\"")
                .contains("required"));
        String verifyForm = formPostingTo(html, "/admin/login/verify");
        assertThat(verifyForm)
                .contains("method=\"post\"")
                .contains("name=\"code\"")
                .contains("Verify")
                .doesNotContain("name=\"email\"");
        assertThat(html).doesNotContain("type=\"email\"");
    }

    @Test
    void loginCodeForm_get_offersAResendFormWithoutTheEmail_andAUseADifferentEmailLink() throws Exception {
        String html = html(get(CODE_PAGE).session(askedForACode(ADMIN_EMAIL)));

        String resendForm = formPostingTo(html, "/admin/login/resend");
        assertThat(resendForm)
                .contains("method=\"post\"")
                .contains("Resend code")
                .doesNotContain("name=\"email\"")
                .doesNotContain("name=\"code\"");
        assertThat(html).containsPattern("<a\\b[^>]*href=\"/admin/login\"[^>]*>\\s*Use a different email\\s*</a>");
        assertThat(tags(html, "form")).hasSize(2);
    }

    /** Shown on the page, but in no URL: no form action and no link carries it. */
    @Test
    void loginCodeForm_get_noFormActionOrLinkCarriesTheEmail() throws Exception {
        String html = html(get(CODE_PAGE).session(askedForACode("admin+ops@example.com")));

        List<String> urls = new ArrayList<>();
        tags(html, "form").forEach(tag -> urls.add(attribute(tag, "action").orElse("")));
        tags(html, "a").forEach(tag -> urls.add(attribute(tag, "href").orElse("")));
        assertThat(urls).isNotEmpty().allSatisfy(url -> assertThat(url)
                .doesNotContain("admin+ops", "admin%2Bops", "example.com", "email="));
    }

    @Test
    void loginCodeForm_get_subtitleDoesNotConfirmTheEmailIsTheAdmins() throws Exception {
        // OtpService answers identically for any email, so the page must not
        // claim a code was definitely sent to whatever was typed.
        String html = html(get(CODE_PAGE).session(askedForACode("not-the-admin@example.com")));

        assertThat(html.replaceAll("\\s+", " "))
                .contains("If <strong>not-the-admin@example.com</strong> is the admin email,"
                        + " a one-time code has been sent to it.");
    }

    @Test
    void loginCodeForm_get_markupInTheTypedEmail_isEscapedNotRendered() throws Exception {
        String html = html(get(CODE_PAGE).session(askedForACode("<script>alert(1)</script>@example.com")));

        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
    }

    /** A fresh browser — no session at all, e.g. a bookmarked or shared code-step URL — starts at the email step. */
    @Test
    void loginCodeForm_get_withoutASession_redirectsToTheEmailStep() throws Exception {
        mockMvc.perform(get(CODE_PAGE))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void loginCodeForm_get_aSessionThatAskedForNoCode_redirectsToTheEmailStep() throws Exception {
        mockMvc.perform(get(CODE_PAGE).session(new MockHttpSession()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    /** An old link with the email in its query: ignored — it neither fills the page nor replaces the pending email. */
    @Test
    void loginCodeForm_get_legacyEmailQueryParameter_isIgnored() throws Exception {
        mockMvc.perform(get(CODE_PAGE).param("email", ADMIN_EMAIL))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get(CODE_PAGE).param("email", "other@example.com").session(askedForACode(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("email", ADMIN_EMAIL));
    }

    /** "Use a different email" links to the email step, which forgets the pending email. */
    @Test
    void emailStep_get_forgetsThePendingEmail() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);

        mockMvc.perform(get("/admin/login").session(session)).andExpect(status().isOk());

        mockMvc.perform(get(CODE_PAGE).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void emailStep_get_withoutASession_createsNone() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/login")).andExpect(status().isOk()).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    // -- POST /admin/login/resend --

    @Test
    void resend_post_sendsANewCodeToThePendingEmail_andReturnsToTheCodeStep() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);

        mockMvc.perform(post("/admin/login/resend").with(csrfField()).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CODE_PAGE));

        verify(otpMailer, times(2)).sendOtp(eq(ADMIN_EMAIL), anyString());
    }

    @Test
    void resend_post_theNewCodeLogsIn() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);
        mockMvc.perform(post("/admin/login/resend").with(csrfField()).session(session))
                .andExpect(status().isFound());

        mockMvc.perform(post("/admin/login/verify").with(csrfField()).param("code", lastCode()).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/moderation/queue"));
    }

    /** The email comes from the session only: one posted alongside is ignored. */
    @Test
    void resend_post_anEmailPostedAlong_isIgnored() throws Exception {
        MockHttpSession session = askedForACode("not-the-admin@example.com");

        mockMvc.perform(post("/admin/login/resend").with(csrfField()).param("email", ADMIN_EMAIL).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CODE_PAGE));

        verify(otpMailer, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void resend_post_withoutAPendingEmail_redirectsToTheEmailStepAndSendsNothing() throws Exception {
        mockMvc.perform(post("/admin/login/resend").with(csrfField()).param("email", ADMIN_EMAIL))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));

        verify(otpMailer, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void resend_post_withoutTheCsrfToken_isRefusedAndSendsNothing() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);

        mockMvc.perform(post("/admin/login/resend").session(session)).andExpect(status().isForbidden());

        verify(otpMailer, times(1)).sendOtp(eq(ADMIN_EMAIL), anyString());
    }

    // -- POST /admin/login/verify --

    @Test
    void verify_post_correctCode_redirectsToModerationQueueAndEstablishesAdminSession() throws Exception {
        MockHttpSession requested = askedForACode(ADMIN_EMAIL);

        MvcResult result = mockMvc.perform(post("/admin/login/verify").with(csrfField())
                        .param("code", lastCode())
                        .session(requested))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/moderation/queue"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        SecurityContext securityContext = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(securityContext).isNotNull();
        Authentication authentication = securityContext.getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo(ADMIN_EMAIL);
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");

        // Prove the session actually satisfies the ADMIN-gated route, not just
        // that a SecurityContext object with the right shape was stored.
        mockMvc.perform(get("/moderation/queue").session(session)).andExpect(status().isOk());
    }

    /** Once logged in, the email is no longer held: nothing in the session has it as its value any more. */
    @Test
    void verify_post_correctCode_forgetsThePendingEmail() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);

        mockMvc.perform(post("/admin/login/verify").with(csrfField()).param("code", lastCode()).session(session))
                .andExpect(status().isFound());

        assertThat(Collections.list(session.getAttributeNames()))
                .noneMatch(name -> ADMIN_EMAIL.equals(session.getAttribute(name)));
        mockMvc.perform(get(CODE_PAGE).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void verify_post_wrongCode_rerendersTheCodeStepWithErrorAndKeepsTheEmail() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);

        mockMvc.perform(post("/admin/login/verify").with(csrfField()).param("code", "ZZZZZZ").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("email", ADMIN_EMAIL));

        mockMvc.perform(post("/admin/login/verify").with(csrfField()).param("code", lastCode()).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void verify_post_wrongCode_establishesNoSession() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);

        mockMvc.perform(post("/admin/login/verify").with(csrfField()).param("code", "ZZZZZZ").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/moderation/queue").session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void verify_post_missingCodeParam_rerendersTheCodeStepWithErrorInsteadOfCrashing() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);

        mockMvc.perform(post("/admin/login/verify").with(csrfField()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("email", ADMIN_EMAIL));
    }

    @Test
    void verify_post_withoutAPendingEmail_redirectsToTheEmailStep_evenWithAnEmailPosted() throws Exception {
        askedForACode(ADMIN_EMAIL);

        mockMvc.perform(post("/admin/login/verify").with(csrfField())
                        .param("email", ADMIN_EMAIL)
                        .param("code", lastCode()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    // -- CSRF (BL-004) --

    @Test
    void emailAndCodePages_carryTheCsrfTokenInEveryForm() throws Exception {
        Csrf.assertEveryPostFormCarriesTheToken(html(get("/admin/login")), 1);
        Csrf.assertEveryPostFormCarriesTheToken(html(get(CODE_PAGE).session(askedForACode(ADMIN_EMAIL))), 2);
    }

    @Test
    void codePageRerenderedAfterAWrongCode_stillCarriesTheToken() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);

        String html = html(post("/admin/login/verify").with(csrfField()).param("code", "ZZZZZZ").session(session));

        Csrf.assertEveryPostFormCarriesTheToken(html, 2);
    }

    @Test
    void requestOtp_post_withoutTheCsrfToken_isRefusedAndSendsNothing() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", ADMIN_EMAIL)).andExpect(status().isForbidden());

        verify(otpMailer, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void verify_post_withoutTheCsrfToken_isRefused_logsNobodyIn_andLeavesTheCodeUsable() throws Exception {
        MockHttpSession session = askedForACode(ADMIN_EMAIL);
        String code = lastCode();

        mockMvc.perform(post("/admin/login/verify").param("code", code).session(session))
                .andExpect(status().isForbidden());

        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        mockMvc.perform(post("/admin/login/verify").with(csrfField()).param("code", code).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/moderation/queue"));
    }
}
