package com.iitm.beacon.adminauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.testsupport.Csrf;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * NFR-ADMIN-OTP-BRUTEFORCE over HTTP, with the configured bound of 5
 * attempts per issued OTP: {@code POST /api/admin/auth/otp/verify} accepts
 * at most 5 guesses, after which even the correct code is refused (401, no
 * session) until a new OTP is requested (UC-ADMIN-OTP-VERIFY alternate
 * flow). {@link OtpServiceTest} covers the same bound on the service alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AdminOtpBruteForceOverHttpTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OtpMailer otpMailer;

    private String requestCode() throws Exception {
        mockMvc.perform(post("/api/admin/auth/otp/request")
                        .with(Csrf.csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(ADMIN_EMAIL))))
                .andExpect(status().isAccepted());
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(ADMIN_EMAIL), captor.capture());
        return captor.getValue();
    }

    private MvcResult verifyCode(String code) throws Exception {
        return mockMvc.perform(post("/api/admin/auth/otp/verify")
                        .with(Csrf.csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyRequest(ADMIN_EMAIL, code))))
                .andReturn();
    }

    private void guessWrong(String code, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mockMvc.perform(post("/api/admin/auth/otp/verify")
                            .with(Csrf.csrfHeader())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new OtpVerifyRequest(ADMIN_EMAIL, wrongCodeFor(code)))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    private static String wrongCodeFor(String code) {
        return "ZZZZZZ".equals(code) ? "222222" : "ZZZZZZ";
    }

    private static boolean loggedIn(MvcResult result) {
        HttpSession session = result.getRequest().getSession(false);
        return session != null
                && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
    }

    @Test
    void fiveWrongCodes_thenTheCorrectOne_is401_andNoSessionIsEstablished() throws Exception {
        String code = requestCode();
        guessWrong(code, MAX_ATTEMPTS);

        MvcResult result = verifyCode(code);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(loggedIn(result)).isFalse();
    }

    @Test
    void fourWrongCodes_thenTheCorrectOne_is200_theFifthAttemptIsStillAllowed() throws Exception {
        String code = requestCode();
        guessWrong(code, MAX_ATTEMPTS - 1);

        MvcResult result = verifyCode(code);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(loggedIn(result)).isTrue();
    }

    @Test
    void afterTheAttemptsAreExhausted_aNewlyRequestedCode_verifies() throws Exception {
        String exhausted = requestCode();
        guessWrong(exhausted, MAX_ATTEMPTS);

        String fresh = requestCode();

        assertThat(verifyCode(fresh).getResponse().getStatus()).isEqualTo(200);
    }
}
