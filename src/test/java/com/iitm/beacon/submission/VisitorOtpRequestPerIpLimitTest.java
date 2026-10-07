package com.iitm.beacon.submission;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * NFR-VISITOR-OTP-BRUTEFORCE: the visitor OTP request endpoint is
 * rate-limited per IP as well as per email, "to blunt spraying across many
 * emails". Its own context, with a per-IP limit of 1 and the per-email limit
 * left generous, so only the per-IP limit can answer 429. Every test uses
 * its own remote addresses: the limiter's state lives as long as the
 * context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "visitor.otp.request-limit-per-ip=1",
            "visitor.otp.request-window-per-ip=PT1M",
            "visitor.otp.request-limit-per-email=1000"
        })
class VisitorOtpRequestPerIpLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OtpMailer otpMailer;

    private ResultActions requestFrom(String remoteAddress, String email) throws Exception {
        return mockMvc.perform(post("/api/submissions/otp/request")
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
                .with(Csrf.csrfHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new OtpRequestRequest(email))));
    }

    @Test
    void twoDifferentEmailsFromTheSameAddress_theSecondIs429_andGetsNoCode() throws Exception {
        requestFrom("198.51.100.11", "per-ip-first@example.com").andExpect(status().isAccepted());

        requestFrom("198.51.100.11", "per-ip-second@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.path").value("/api/submissions/otp/request"));
        verify(otpMailer, never()).sendOtp(eq("per-ip-second@example.com"), anyString());
    }

    @Test
    void anotherAddress_isNotHeldBackByALimitedOne() throws Exception {
        requestFrom("198.51.100.12", "per-ip-third@example.com").andExpect(status().isAccepted());
        requestFrom("198.51.100.12", "per-ip-fourth@example.com").andExpect(status().isTooManyRequests());

        requestFrom("198.51.100.13", "per-ip-fifth@example.com").andExpect(status().isAccepted());
    }
}
