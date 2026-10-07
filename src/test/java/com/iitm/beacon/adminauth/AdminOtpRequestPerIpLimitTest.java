package com.iitm.beacon.adminauth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.testsupport.Csrf;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * NFR-ADMIN-OTP-BRUTEFORCE: the admin OTP request endpoint is rate-limited
 * per IP as well as per email, so cycling through emails from one address
 * doesn't escape the limit. Its own context, with a per-IP limit of 1 and
 * the per-email limit left generous, so only the per-IP limit can answer
 * 429. Every test uses its own remote addresses: the limiter's state lives
 * as long as the context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "admin.otp.request-limit-per-ip=1",
            "admin.otp.request-window-per-ip=PT1M",
            "admin.otp.request-limit-per-email=1000"
        })
class AdminOtpRequestPerIpLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OtpMailer otpMailer;

    private ResultActions requestFrom(String remoteAddress, String email) throws Exception {
        return mockMvc.perform(post("/api/admin/auth/otp/request")
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
                .with(Csrf.csrfHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new OtpRequestRequest(email))));
    }

    @Test
    void twoDifferentEmailsFromTheSameAddress_theSecondIs429() throws Exception {
        requestFrom("198.51.100.1", "admin@example.com").andExpect(status().isAccepted());

        requestFrom("198.51.100.1", "someone-else@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.path").value("/api/admin/auth/otp/request"));
    }

    @Test
    void anotherAddress_isNotHeldBackByALimitedOne() throws Exception {
        requestFrom("198.51.100.2", "admin@example.com").andExpect(status().isAccepted());
        requestFrom("198.51.100.2", "someone-else@example.com").andExpect(status().isTooManyRequests());

        requestFrom("198.51.100.3", "admin@example.com").andExpect(status().isAccepted());
    }
}
