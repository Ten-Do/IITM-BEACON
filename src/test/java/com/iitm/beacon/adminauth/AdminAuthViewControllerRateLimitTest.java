package com.iitm.beacon.adminauth;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.testsupport.LoginCodeSteps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Narrowly-scoped, separately-cached Spring context proving {@link
 * AdminAuthViewController} renders the code step with a friendly
 * model-bound error (not a JSON body from {@code GlobalExceptionHandler}, the
 * wrong response shape for a plain browser form POST) when {@code OtpService}
 * rejects a request for exceeding its rate limit — mirrors {@code
 * AdminAuthControllerRateLimitTest} for the REST layer. The code step, not
 * the email step, because a code may already be on its way: the same
 * response is expected whether the rejected request was the first one from
 * the email page or a "Resend code" from the code page.
 *
 * <p>Uses probe emails distinct from {@code AdminAuthControllerRateLimitTest}'s
 * ("admin@example.com") deliberately: this class shares the exact same
 * {@code @TestPropertySource} properties, so Spring's test-context cache
 * reuses the very same {@code ApplicationContext} (and therefore the same
 * {@code RateLimiterService} singleton/bucket state) for both classes.
 * {@code RateLimiterService} buckets are keyed per-email, and the rate limit
 * is enforced before {@code OtpService} even checks whether the email
 * matches the configured admin account, so a non-matching probe email still
 * exercises the exact same rate-limit-rejection path without touching the
 * other test's "admin@example.com" bucket. Each test uses its own probe email
 * for the same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "admin.otp.request-limit-per-email=1",
            "admin.otp.request-window-per-email=PT1M"
        })
class AdminAuthViewControllerRateLimitTest {

    private static final String STILL_ENTER_IT_HINT = "if you already received a code, you can still enter it below";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtpMailer otpMailer;

    @Test
    void resendWithinWindow_rendersTheCodeStepWithErrorAndKeepsEmail() throws Exception {
        String email = "admin-view-ratelimit-resend-probe@example.com";
        MockHttpSession session = LoginCodeSteps.adminAskedForACode(mockMvc, email);

        String html = mockMvc.perform(post("/admin/login/resend").with(csrfField()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attribute("error", containsString(STILL_ENTER_IT_HINT)))
                .andExpect(model().attribute("email", email))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html).contains("name=\"code\"");
    }

    @Test
    void sameEmailAgainFromTheEmailStepWithinWindow_rendersTheCodeStepWithError() throws Exception {
        String email = "admin-view-ratelimit-again-probe@example.com";
        MockHttpSession session = LoginCodeSteps.adminAskedForACode(mockMvc, email);

        mockMvc.perform(post("/admin/login/request").with(csrfField()).param("email", email).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attribute("error", containsString(STILL_ENTER_IT_HINT)))
                .andExpect(model().attribute("email", email));
    }

    @Test
    void firstRequestFromThisBrowserAlreadyOverTheLimit_stillRendersTheCodeStepNotTheEmailStep() throws Exception {
        // The per-email budget was already spent elsewhere (another device),
        // so this browser's very first request is the one rejected.
        String email = "admin-view-ratelimit-first-probe@example.com";
        mockMvc.perform(post("/admin/login/request").with(csrfField()).param("email", email).with(request -> {
                    request.setRemoteAddr("203.0.113.7");
                    return request;
                }))
                .andExpect(status().is3xxRedirection());

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/admin/login/request").with(csrfField()).param("email", email).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"))
                .andExpect(model().attribute("error", containsString(STILL_ENTER_IT_HINT)))
                .andExpect(model().attribute("email", email));

        // The page's own forms carry no email: the session already holds this one for them.
        mockMvc.perform(get("/admin/login/code").session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("email", email));
    }
}
