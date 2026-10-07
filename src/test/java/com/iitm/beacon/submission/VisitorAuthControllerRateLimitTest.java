package com.iitm.beacon.submission;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.testsupport.ClientErrors;
import com.iitm.beacon.testsupport.LoginCodeSteps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Narrowly-scoped, separately-cached Spring context proving {@code
 * VisitorAuthController} correctly maps a {@code RateLimiterService}
 * rejection into the documented 429 — the visitor analogue of {@code
 * AdminAuthControllerRateLimitTest} — and that the login page's "Resend
 * code" ({@code SubmissionViewController}) is held to the same limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "visitor.otp.request-limit-per-email=1",
            "visitor.otp.request-window-per-email=PT1M"
        })
class VisitorAuthControllerRateLimitTest {

    private static final String EMAIL = "rate-limited-visitor@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OtpMailer otpMailer;

    @Test
    void secondRequestWithinWindow_returns429WithErrorResponseBody() throws Exception {
        mockMvc.perform(post("/api/submissions/otp/request").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(EMAIL))))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/submissions/otp/request").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(EMAIL))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/submissions/otp/request"));
    }

    /** "Resend code" on the page draws on the same per-email budget: it can't be used to bypass the limit. */
    @Test
    void pageResendWithinWindow_isTheHtml429Page_andSendsNoSecondCode() throws Exception {
        String email = "rate-limited-resend@example.com";
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, email);

        ClientErrors.assertHtmlErrorPage(
                mockMvc.perform(post("/submissions/login/resend").with(csrfField()).session(session))
                        .andReturn()
                        .getResponse(),
                429);

        verify(otpMailer, times(1)).sendOtp(eq(email), anyString());
    }
}
