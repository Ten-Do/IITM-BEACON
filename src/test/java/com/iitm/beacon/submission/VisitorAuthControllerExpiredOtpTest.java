package com.iitm.beacon.submission;

import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Own top-level test class (not a {@code @Nested} inner class of {@link
 * VisitorAuthControllerTest}) for the same reason as {@code
 * AdminAuthControllerExpiredOtpTest}: a {@code @TestConfiguration} nested
 * anywhere in a test class file — even inside a {@code @Nested} class — gets
 * auto-detected by Spring Boot and applied to the whole file's context,
 * which would otherwise leak the {@link MutableClock} override into sibling
 * tests in {@code VisitorAuthControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(VisitorAuthControllerExpiredOtpTest.MutableClockTestConfig.class)
class VisitorAuthControllerExpiredOtpTest {

    private static final String EMAIL = "expired-otp-visitor@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock mutableClock;

    @MockitoBean
    private OtpMailer otpMailer;

    @Test
    void verifyAfterTtlElapses_returns401() throws Exception {
        mockMvc.perform(post("/api/submissions/otp/request").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(EMAIL))))
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(EMAIL), captor.capture());
        String code = captor.getValue();

        mutableClock.advanceBy(Duration.ofMinutes(5).plusSeconds(1));

        mockMvc.perform(post("/api/submissions/otp/verify").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyRequest(EMAIL, code))))
                .andExpect(status().isUnauthorized());
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
