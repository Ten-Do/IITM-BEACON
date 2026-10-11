package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTag;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTagsWithAttribute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.testsupport.AssetUrls;
import com.iitm.beacon.testsupport.LoginCodeSteps;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * "Resend code" on the two login code pages waits out the per-email code
 * request limit in the browser (decision 36): the server renders the button
 * enabled — without JavaScript it works as before — and tells {@code
 * static/js/resend-cooldown.js}, which only these pages load, how many
 * seconds to keep it disabled: the per-email window over the per-email limit,
 * rounded up. The server's own limits and answers are unchanged (their
 * tests: {@code AdminAuthViewControllerRateLimitTest}, {@code
 * VisitorAuthControllerRateLimitTest}, {@code
 * VisitorOtpEnumerationResistanceTest}).
 *
 * <p>Non-default limits, a different one per actor, so a page showing the
 * shipped 60 s, a number of its own or the other actor's would fail: the
 * visitor's 3 minutes over 4 requests is 45 s; the admin's 100 s over 3 is
 * 33.3 s, so 34 s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "visitor.otp.request-limit-per-email=4",
            "visitor.otp.request-window-per-email=PT3M",
            "admin.otp.request-limit-per-email=3",
            "admin.otp.request-window-per-email=PT100S"
        })
class ResendCooldownTest {

    private static final String WAIT_ATTRIBUTE = "data-resend-wait-seconds";
    private static final String SCRIPT = "/js/resend-cooldown.js";
    private static final String VISITOR_WAIT = "45";
    private static final String ADMIN_WAIT = "34";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtpMailer otpMailer;

    private String html(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String visitorCodePage(String email) throws Exception {
        return html(get(LoginCodeSteps.VISITOR_CODE_PAGE)
                .session(LoginCodeSteps.visitorAskedForACode(mockMvc, email)), 200);
    }

    private String adminCodePage(String email) throws Exception {
        return html(get(LoginCodeSteps.ADMIN_CODE_PAGE)
                .session(LoginCodeSteps.adminAskedForACode(mockMvc, email)), 200);
    }

    /** The submit button of the page's one form posting to {@code action}. */
    private static String buttonOfTheFormPostingTo(String html, String action) {
        List<String> forms = elements(html, "form").stream()
                .filter(form -> attribute(openingTag(form, "form"), "action").filter(action::equals).isPresent())
                .toList();
        assertThat(forms).as("forms posting to " + action).hasSize(1);
        return openingTag(forms.get(0), "button");
    }

    /**
     * The resend button carries the wait, and is the only element that does
     * (not "Verify"), and is rendered enabled: the script disables it.
     */
    private static void assertResendButtonWaits(String html, String resendAction, String expectedSeconds) {
        String resend = buttonOfTheFormPostingTo(html, resendAction);
        assertThat(attribute(resend, WAIT_ATTRIBUTE)).contains(expectedSeconds);
        assertThat(attribute(resend, "disabled")).as("disabled").isEmpty();
        assertThat(attribute(resend, "aria-disabled")).as("aria-disabled").isEmpty();
        assertThat(openingTagsWithAttribute(html, WAIT_ATTRIBUTE)).containsExactly(resend);
    }

    /** {@code <script>} tags of {@code html} whose source, unversioned, is the cooldown script. */
    private static List<String> cooldownScripts(String html) {
        return openingTags(html, "script").stream()
                .filter(tag -> attribute(tag, "src").map(AssetUrls::plain).filter(SCRIPT::equals).isPresent())
                .toList();
    }

    /** Exactly once, deferred (it needs the parsed page) and a classic script, not a module. */
    private static void assertLoadsTheScriptOnceDeferred(String html) {
        assertThat(cooldownScripts(html)).singleElement().satisfies(tag -> {
            assertThat(attribute(tag, "defer")).as("defer").isPresent();
            assertThat(attribute(tag, "async")).as("async").isEmpty();
            assertThat(attribute(tag, "type")).as("type").isEmpty();
        });
    }

    // -- the wait the pages render --

    @Test
    void visitorCodePage_resendButtonWaitsTheVisitorsPerEmailWindowOverItsLimit() throws Exception {
        String html = visitorCodePage("resend-wait-visitor@example.com");

        assertResendButtonWaits(html, "/submissions/login/resend", VISITOR_WAIT);
    }

    @Test
    void adminCodePage_resendButtonWaitsTheAdminsPerEmailWindowOverItsLimit_roundedUp() throws Exception {
        String html = adminCodePage("resend-wait-admin@example.com");

        assertResendButtonWaits(html, "/admin/login/resend", ADMIN_WAIT);
    }

    /**
     * A rate-limited admin request renders the code page itself, with its
     * error (not a redirect to the GET handler): the full wait again, since
     * the limit has just been met.
     */
    @Test
    void adminCodePageRenderedForARateLimitedResend_stillCarriesTheWait() throws Exception {
        MockHttpSession session = LoginCodeSteps.adminAskedForACode(mockMvc, "resend-wait-admin-limited@example.com");
        for (int request = 2; request <= 3; request++) {
            mockMvc.perform(post("/admin/login/resend").with(csrfField()).session(session))
                    .andExpect(status().isFound());
        }

        String html = mockMvc.perform(post("/admin/login/resend").with(csrfField()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attributeExists("error"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertResendButtonWaits(html, "/admin/login/resend", ADMIN_WAIT);
        assertLoadsTheScriptOnceDeferred(html);
    }

    /**
     * A wrong code re-renders the code page itself (not a redirect to the
     * GET handler): it carries the full wait again, so the countdown starts
     * over. {@code 000000} is never a code: the alphabet has no {@code 0}.
     */
    @Test
    void visitorCodePageRenderedForAWrongCode_stillCarriesTheWait() throws Exception {
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, "resend-wait-visitor-wrong@example.com");

        String html = mockMvc.perform(post(LoginCodeSteps.VISITOR_CODE_PAGE).with(csrfField())
                        .param("code", "000000")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-code"))
                .andExpect(model().attributeExists("error"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertResendButtonWaits(html, "/submissions/login/resend", VISITOR_WAIT);
        assertLoadsTheScriptOnceDeferred(html);
    }

    @Test
    void adminCodePageRenderedForAWrongCode_stillCarriesTheWait() throws Exception {
        MockHttpSession session = LoginCodeSteps.adminAskedForACode(mockMvc, "resend-wait-admin-wrong@example.com");

        String html = mockMvc.perform(post("/admin/login/verify").with(csrfField())
                        .param("code", "000000")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attributeExists("error"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertResendButtonWaits(html, "/admin/login/resend", ADMIN_WAIT);
        assertLoadsTheScriptOnceDeferred(html);
    }

    // -- which pages load the script --

    @ParameterizedTest
    @ValueSource(strings = {LoginCodeSteps.VISITOR_CODE_PAGE, LoginCodeSteps.ADMIN_CODE_PAGE})
    void codePages_loadTheScriptOnceDeferred(String path) throws Exception {
        assertLoadsTheScriptOnceDeferred(html(LoginCodeSteps.page(mockMvc, path), 200));
    }

    @ParameterizedTest
    @CsvSource({"/, 200", "/gallery, 200", "/submissions/login, 200", "/admin/login, 200", "/no-such-page, 404",
        "/gallery/999999, 404"})
    void anonymousPagesOtherThanTheCodePages_doNotLoadTheScript(String path, int expectedStatus) throws Exception {
        String html = html(get(path), expectedStatus);

        assertThat(cooldownScripts(html)).isEmpty();
        assertThat(openingTagsWithAttribute(html, WAIT_ATTRIBUTE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/submissions/form", "/submissions/confirmation"})
    void visitorPages_doNotLoadTheScript(String path) throws Exception {
        assertThat(cooldownScripts(html(get(path).with(authentication(user("ROLE_VISITOR"))), 200))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/moderation/queue", "/catalog/topics", "/catalog/achievements"})
    void adminPages_doNotLoadTheScript(String path) throws Exception {
        assertThat(cooldownScripts(html(get(path).with(authentication(user("ROLE_ADMIN"))), 200))).isEmpty();
    }

    private static Authentication user(String role) {
        return new UsernamePasswordAuthenticationToken(
                "resend-wait-pages@example.com", null, List.of(new SimpleGrantedAuthority(role)));
    }
}
