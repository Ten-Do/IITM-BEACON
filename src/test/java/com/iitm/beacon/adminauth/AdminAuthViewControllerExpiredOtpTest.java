package com.iitm.beacon.adminauth;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Own top-level test class (not a {@code @Nested} inner class of {@link
 * AdminAuthViewControllerTest}), for the same reason as {@code
 * AdminAuthControllerExpiredOtpTest}: a {@code @TestConfiguration} nested
 * anywhere in a test class file is auto-detected by Spring Boot for that
 * whole file's context, so isolating it in its own file keeps the {@link
 * MutableClock} override from leaking into sibling test classes/methods.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(AdminAuthViewControllerExpiredOtpTest.MutableClockTestConfig.class)
class AdminAuthViewControllerExpiredOtpTest {

    private static final String ADMIN_EMAIL = "admin@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock mutableClock;

    @MockitoBean
    private OtpMailer otpMailer;

    @Test
    void verifyAfterTtlElapses_rerendersLoginWithError() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", ADMIN_EMAIL))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(ADMIN_EMAIL), captor.capture());
        String code = captor.getValue();

        mutableClock.advanceBy(Duration.ofMinutes(5).plusSeconds(1));

        mockMvc.perform(post("/admin/login/verify")
                        .param("email", ADMIN_EMAIL)
                        .param("code", code))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attributeExists("error"));
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
