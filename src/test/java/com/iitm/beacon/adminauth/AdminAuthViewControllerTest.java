package com.iitm.beacon.adminauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.config.OtpMailer;
import java.net.URI;
import java.util.ArrayList;
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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtpMailer otpMailer;

    private String requestAndCaptureCode() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", ADMIN_EMAIL))
                .andExpect(status().is3xxRedirection());

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
    void requestOtp_post_adminEmail_sendsCodeAndRedirectsToTheCodeStep() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", ADMIN_EMAIL))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login/code?email=admin%40example.com"));

        verify(otpMailer, atLeastOnce()).sendOtp(eq(ADMIN_EMAIL), anyString());
    }

    @Test
    void requestOtp_post_nonAdminEmail_redirectsExactlyLikeTheAdminEmailButSendsNothing() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", "not-the-admin@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login/code?email=not-the-admin%40example.com"));

        verify(otpMailer, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void requestOtp_post_emailWithPlus_isUrlEncodedInTheRedirectAndRoundTripsToTheCodeStep() throws Exception {
        // An unencoded '+' in a query string decodes to a space, silently
        // changing the email the code page then submits.
        String redirect = mockMvc.perform(post("/admin/login/request").param("email", "admin+ops@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login/code?email=admin%2Bops%40example.com"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mockMvc.perform(get(URI.create(redirect)))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attribute("email", "admin+ops@example.com"));
    }

    @Test
    void requestOtp_post_blankEmail_rerendersTheEmailStepWithErrorAndSendsNothing() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attribute("error", BLANK_EMAIL_MESSAGE));

        verify(otpMailer, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void requestOtp_post_whitespaceOnlyEmail_rerendersTheEmailStepWithError() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", "   "))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attribute("error", BLANK_EMAIL_MESSAGE));
    }

    @Test
    void requestOtp_post_missingEmailParam_rerendersTheEmailStepWithErrorInsteadOfCrashing() throws Exception {
        mockMvc.perform(post("/admin/login/request"))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attribute("error", BLANK_EMAIL_MESSAGE));
    }

    // -- GET /admin/login/code (step 2: code) --

    @Test
    void loginCodeForm_get_anonymous_rendersTheCodeStepWithEmail() throws Exception {
        mockMvc.perform(get("/admin/login/code").param("email", ADMIN_EMAIL))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attribute("email", ADMIN_EMAIL));
    }

    @Test
    void loginCodeForm_get_rendersASingleOtpFieldPostingToVerifyWithTheEmailHidden() throws Exception {
        String html = html(get("/admin/login/code").param("email", ADMIN_EMAIL));

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
                .containsPattern("<input type=\"hidden\" name=\"email\" value=\"admin@example.com\"");
        assertThat(html).doesNotContain("type=\"email\"");
    }

    @Test
    void loginCodeForm_get_offersAResendFormAndAUseADifferentEmailLink() throws Exception {
        String html = html(get("/admin/login/code").param("email", ADMIN_EMAIL));

        String resendForm = formPostingTo(html, "/admin/login/request");
        assertThat(resendForm)
                .contains("method=\"post\"")
                .contains("Resend code")
                .containsPattern("<input type=\"hidden\" name=\"email\" value=\"admin@example.com\"")
                .doesNotContain("name=\"code\"");
        assertThat(html).containsPattern("<a\\b[^>]*href=\"/admin/login\"[^>]*>\\s*Use a different email\\s*</a>");
        assertThat(tags(html, "form")).hasSize(2);
    }

    @Test
    void loginCodeForm_get_subtitleDoesNotConfirmTheEmailIsTheAdmins() throws Exception {
        // OtpService answers identically for any email, so the page must not
        // claim a code was definitely sent to whatever was typed.
        String html = html(get("/admin/login/code").param("email", "not-the-admin@example.com"));

        assertThat(html.replaceAll("\\s+", " "))
                .contains("If <strong>not-the-admin@example.com</strong> is the admin email,"
                        + " a one-time code has been sent to it.");
    }

    @Test
    void loginCodeForm_get_markupInTheEmailParam_isEscapedNotRendered() throws Exception {
        String html = html(get("/admin/login/code").param("email", "<script>alert(1)</script>@example.com"));

        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
    }

    @Test
    void loginCodeForm_get_missingEmail_redirectsToTheEmailStep() throws Exception {
        mockMvc.perform(get("/admin/login/code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void loginCodeForm_get_blankEmail_redirectsToTheEmailStep() throws Exception {
        mockMvc.perform(get("/admin/login/code").param("email", "   "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    // -- POST /admin/login/verify --

    @Test
    void verify_post_correctCode_redirectsToModerationQueueAndEstablishesAdminSession() throws Exception {
        String code = requestAndCaptureCode();

        MvcResult result = mockMvc.perform(post("/admin/login/verify")
                        .param("email", ADMIN_EMAIL)
                        .param("code", code))
                .andExpect(status().is3xxRedirection())
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

    @Test
    void verify_post_wrongCode_rerendersTheCodeStepWithErrorAndPreservesEmail() throws Exception {
        requestAndCaptureCode();

        mockMvc.perform(post("/admin/login/verify")
                        .param("email", ADMIN_EMAIL)
                        .param("code", "ZZZZZZ"))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("email", ADMIN_EMAIL));
    }

    @Test
    void verify_post_wrongCode_establishesNoSession() throws Exception {
        requestAndCaptureCode();

        MvcResult result = mockMvc.perform(post("/admin/login/verify")
                        .param("email", ADMIN_EMAIL)
                        .param("code", "ZZZZZZ"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/moderation/queue").session(session != null ? session : new MockHttpSession()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void verify_post_missingCodeParam_rerendersTheCodeStepWithErrorInsteadOfCrashing() throws Exception {
        requestAndCaptureCode();

        mockMvc.perform(post("/admin/login/verify").param("email", ADMIN_EMAIL))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("email", ADMIN_EMAIL));
    }

    @Test
    void verify_post_blankEmail_redirectsToTheEmailStep() throws Exception {
        mockMvc.perform(post("/admin/login/verify")
                        .param("email", "")
                        .param("code", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void verify_post_missingEmailParam_redirectsToTheEmailStep() throws Exception {
        mockMvc.perform(post("/admin/login/verify").param("code", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }
}
