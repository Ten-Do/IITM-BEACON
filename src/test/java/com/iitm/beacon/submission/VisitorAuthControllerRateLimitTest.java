package com.iitm.beacon.submission;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.config.OtpMailer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Narrowly-scoped, separately-cached Spring context proving {@code
 * VisitorAuthController} correctly maps a {@code RateLimiterService}
 * rejection into the documented 429 — the visitor analogue of {@code
 * AdminAuthControllerRateLimitTest}.
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
        mockMvc.perform(post("/api/submissions/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(EMAIL))))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/submissions/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(EMAIL))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/submissions/otp/request"));
    }
}
