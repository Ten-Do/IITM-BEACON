package com.iitm.beacon.adminauth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.config.OtpMailer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Narrowly-scoped, separately-cached Spring context proving {@link
 * AdminAuthViewController} re-renders the login page with a friendly
 * model-bound error (not a JSON body from {@code GlobalExceptionHandler}, the
 * wrong response shape for a plain browser form POST) when {@code OtpService}
 * rejects a request for exceeding its rate limit — mirrors {@code
 * AdminAuthControllerRateLimitTest} for the REST layer.
 *
 * <p>Uses a probe email distinct from {@code AdminAuthControllerRateLimitTest}'s
 * ("admin@example.com") deliberately: this class shares the exact same
 * {@code @TestPropertySource} properties, so Spring's test-context cache
 * reuses the very same {@code ApplicationContext} (and therefore the same
 * {@code RateLimiterService} singleton/bucket state) for both classes.
 * {@code RateLimiterService} buckets are keyed per-email, and the rate limit
 * is enforced before {@code OtpService} even checks whether the email
 * matches the configured admin account, so a non-matching probe email still
 * exercises the exact same rate-limit-rejection path without touching the
 * other test's "admin@example.com" bucket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "admin.otp.request-limit-per-email=1",
            "admin.otp.request-window-per-email=PT1M"
        })
class AdminAuthViewControllerRateLimitTest {

    private static final String ADMIN_EMAIL = "admin-view-ratelimit-probe@example.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtpMailer otpMailer;

    @Test
    void secondRequestWithinWindow_rerendersLoginWithErrorInsteadOfJson() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", ADMIN_EMAIL))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/admin/login/request").param("email", ADMIN_EMAIL))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("email", ADMIN_EMAIL));
    }
}
