package com.iitm.beacon.adminauth;

import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.config.OtpMailer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AdminAuthControllerTest {

    private static final String ADMIN_EMAIL = "admin@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OtpMailer otpMailer;

    private String requestAndCaptureCode() throws Exception {
        mockMvc.perform(post("/api/admin/auth/otp/request").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(ADMIN_EMAIL))))
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, org.mockito.Mockito.atLeastOnce()).sendOtp(eq(ADMIN_EMAIL), captor.capture());
        return captor.getValue();
    }

    @Test
    void requestReturns202WithEmptyBody() throws Exception {
        mockMvc.perform(post("/api/admin/auth/otp/request").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(ADMIN_EMAIL))))
                .andExpect(status().isAccepted())
                .andExpect(content().string(emptyOrNullString()));
    }

    @Test
    void verifyWithCorrectCodeReturns200() throws Exception {
        String code = requestAndCaptureCode();

        mockMvc.perform(post("/api/admin/auth/otp/verify").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyRequest(ADMIN_EMAIL, code))))
                .andExpect(status().isOk())
                .andExpect(content().string(emptyOrNullString()));
    }

    @Test
    void verifySuccessEstablishesSessionWithAdminRoleAndPrincipal() throws Exception {
        String code = requestAndCaptureCode();

        MvcResult result = mockMvc.perform(post("/api/admin/auth/otp/verify").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyRequest(ADMIN_EMAIL, code))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        SecurityContext securityContext = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(securityContext).isNotNull();
        Authentication authentication = securityContext.getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo(ADMIN_EMAIL);
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void verifyWithWrongCodeReturns401WithErrorResponseBody() throws Exception {
        requestAndCaptureCode();

        mockMvc.perform(post("/api/admin/auth/otp/verify").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyRequest(ADMIN_EMAIL, "ZZZZZZ"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/admin/auth/otp/verify"));
    }

    @Test
    void requestWithBlankEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/auth/otp/request").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestWithMalformedEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/auth/otp/request").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyWithWrongLengthCodeReturns400() throws Exception {
        mockMvc.perform(post("/api/admin/auth/otp/verify").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"code\":\"123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentVerifyWithSameCorrectCode_exactlyOneSucceeds() throws Exception {
        String code = requestAndCaptureCode();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    int status = mockMvc.perform(post("/api/admin/auth/otp/verify").with(csrfHeader())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new OtpVerifyRequest(ADMIN_EMAIL, code))))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (status == 200) {
                        successes.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(successes.get()).isEqualTo(1);
    }

    // -- CSRF (BL-004); the token's other variants are in config.CsrfProtectionTest --

    @Test
    void verify_withoutTheCsrfHeader_isAJson403_logsNobodyIn_andLeavesTheCodeUsable() throws Exception {
        String code = requestAndCaptureCode();
        String body = objectMapper.writeValueAsString(new OtpVerifyRequest(ADMIN_EMAIL, code));

        MvcResult refused = mockMvc.perform(post("/api/admin/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andReturn();

        assertThat(refused.getRequest().getSession(false)).isNull();
        mockMvc.perform(post("/api/admin/auth/otp/verify").with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
