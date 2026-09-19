package com.iitm.beacon.adminauth;

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
 * Narrowly-scoped, separately-cached Spring context proving
 * {@code AdminAuthController} correctly maps a {@code RateLimiterService}
 * rejection into the documented 429 — the unit-level {@code
 * RateLimiterServiceTest} only proves the limiter's own internal logic, not
 * this wiring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "admin.otp.request-limit-per-email=1",
            "admin.otp.request-window-per-email=PT1M"
        })
class AdminAuthControllerRateLimitTest {

    private static final String ADMIN_EMAIL = "admin@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OtpMailer otpMailer;

    @Test
    void secondRequestWithinWindow_returns429WithErrorResponseBody() throws Exception {
        mockMvc.perform(post("/api/admin/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(ADMIN_EMAIL))))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/admin/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(ADMIN_EMAIL))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/admin/auth/otp/request"));
    }
}
