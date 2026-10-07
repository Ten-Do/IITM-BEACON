package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.testsupport.Csrf;
import com.iitm.beacon.testsupport.LoginCodeSteps;
import com.iitm.beacon.testsupport.MutableClock;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * NFR-VISITOR-OTP-BRUTEFORCE on the visitor's page flow ({@code
 * /submissions/login} → {@code /submissions/login/code}), the one browsers
 * actually use: a code entered after the 5-minute TTL, or after 5 wrong
 * guesses, re-renders the code step with an error and logs nobody in. Own
 * top-level class for its {@link MutableClock} override, for the same reason
 * as {@code VisitorAuthControllerExpiredOtpTest}. The clock is shared by the
 * whole context, so each test measures from the instant its code was issued.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(SubmissionLoginOtpBoundsTest.MutableClockTestConfig.class)
class SubmissionLoginOtpBoundsTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;
    private static final String CODE_VIEW = "submission/login-code";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock mutableClock;

    @MockitoBean
    private OtpMailer otpMailer;

    /** Each email's own browser session, which carries it from the email step to the code step. */
    private final Map<String, MockHttpSession> sessions = new HashMap<>();

    private String requestCode(String email) throws Exception {
        sessions.put(email, LoginCodeSteps.visitorAskedForACode(mockMvc, email));
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), captor.capture());
        return captor.getValue();
    }

    private ResultActions postCode(String email, String code) throws Exception {
        return mockMvc.perform(post("/submissions/login/code")
                .param("code", code)
                .session(sessions.get(email))
                .with(Csrf.csrfField()));
    }

    private MvcResult enterCode(String email, String code) throws Exception {
        return postCode(email, code).andReturn();
    }

    private static boolean loggedIn(MvcResult result) {
        HttpSession session = result.getRequest().getSession(false);
        return session != null
                && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
    }

    private static String wrongCodeFor(String code) {
        return "ZZZZZZ".equals(code) ? "222222" : "ZZZZZZ";
    }

    @Test
    void codeEnteredOneSecondAfterTheTtl_rerendersTheCodeStepWithAnError_andLogsNobodyIn() throws Exception {
        String email = "page-ttl-expired@example.com";
        Instant issued = mutableClock.instant();
        String code = requestCode(email);

        mutableClock.advanceTo(issued.plus(TTL).plusSeconds(1));

        MvcResult result = postCode(email, code)
                .andExpect(status().isOk())
                .andExpect(view().name(CODE_VIEW))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("email", email))
                .andReturn();
        assertThat(loggedIn(result)).isFalse();
    }

    @Test
    void codeEnteredExactlyAtTheTtl_stillLogsTheVisitorIn() throws Exception {
        String email = "page-ttl-boundary@example.com";
        Instant issued = mutableClock.instant();
        String code = requestCode(email);

        mutableClock.advanceTo(issued.plus(TTL));

        MvcResult result = postCode(email, code)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/form"))
                .andReturn();
        assertThat(loggedIn(result)).isTrue();
    }

    @Test
    void correctCodeAfterFiveWrongOnes_rerendersTheCodeStepWithAnError_andLogsNobodyIn() throws Exception {
        String email = "page-attempts-exhausted@example.com";
        String code = requestCode(email);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThat(enterCode(email, wrongCodeFor(code)).getModelAndView().getViewName()).isEqualTo(CODE_VIEW);
        }

        MvcResult result = enterCode(email, code);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getModelAndView().getViewName()).isEqualTo(CODE_VIEW);
        assertThat(result.getModelAndView().getModel()).containsKey("error");
        assertThat(loggedIn(result)).isFalse();
    }

    @TestConfiguration
    static class MutableClockTestConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }
}
