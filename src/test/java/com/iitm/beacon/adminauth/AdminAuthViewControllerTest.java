package com.iitm.beacon.adminauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.iitm.beacon.config.OtpMailer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc tests for {@link AdminAuthViewController}'s single combined
 * email+code login page (per {@code ui-design/AdminLogin.dc.html}, unlike the
 * visitor flow's two separate pages). Role enforcement for
 * {@code /moderation/**} is the same {@code SecurityConfig} matcher already
 * covering the JSON API and the moderation view routes — these tests confirm
 * a successful verify here actually establishes a session that satisfies it.
 *
 * <p>Rate-limit and expired-OTP scenarios live in their own top-level test
 * classes ({@link AdminAuthViewControllerRateLimitTest}, {@link
 * AdminAuthViewControllerExpiredOtpTest}) — same separately-cached-context
 * convention as the REST layer's {@code AdminAuthControllerRateLimitTest}/
 * {@code AdminAuthControllerExpiredOtpTest}, since both need a distinct
 * Spring context (custom properties / a mutable clock bean) that must not
 * leak into this class's tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AdminAuthViewControllerTest {

    private static final String ADMIN_EMAIL = "admin@example.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtpMailer otpMailer;

    private String requestAndCaptureCode() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", ADMIN_EMAIL))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(ADMIN_EMAIL), captor.capture());
        return captor.getValue();
    }

    // -- GET /admin/login --

    @Test
    void loginForm_get_returns200AndRendersViewWithEmptyDefaults() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attribute("email", ""))
                .andExpect(model().attribute("sent", false));
    }

    @Test
    void loginForm_get_withEmailAndSentParams_reflectsThemInModel() throws Exception {
        mockMvc.perform(get("/admin/login").param("email", ADMIN_EMAIL).param("sent", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attribute("email", ADMIN_EMAIL))
                .andExpect(model().attribute("sent", true));
    }

    // -- POST /admin/login/request --

    @Test
    void requestOtp_post_happyPath_redirectsBackToLoginWithSentFlag() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", ADMIN_EMAIL))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/login?email=*&sent=true"));

        verify(otpMailer, atLeastOnce()).sendOtp(eq(ADMIN_EMAIL), anyString());
    }

    @Test
    void requestOtp_post_blankEmail_rerendersWithErrorInsteadOfCrashing() throws Exception {
        mockMvc.perform(post("/admin/login/request").param("email", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attributeExists("error"));
    }

    @Test
    void requestOtp_post_missingEmailParam_rerendersWithErrorInsteadOfCrashing() throws Exception {
        mockMvc.perform(post("/admin/login/request"))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attributeExists("error"));
    }

    // -- POST /admin/login/verify --

    @Test
    void verify_post_correctCode_redirectsToModerationQueueAndEstablishesAdminSession() throws Exception {
        String code = requestAndCaptureCode();

        MvcResult result = mockMvc.perform(post("/admin/login/verify")
                        .param("email", ADMIN_EMAIL)
                        .param("code", code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/moderation/queue"))
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

        // Prove the session actually satisfies the ADMIN-gated route, not just
        // that a SecurityContext object with the right shape was stored.
        mockMvc.perform(get("/moderation/queue").session(session)).andExpect(status().isOk());
    }

    @Test
    void verify_post_wrongCode_rerendersLoginWithErrorAndPreservesEmail() throws Exception {
        requestAndCaptureCode();

        mockMvc.perform(post("/admin/login/verify")
                        .param("email", ADMIN_EMAIL)
                        .param("code", "ZZZZZZ"))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("email", ADMIN_EMAIL));
    }

    @Test
    void verify_post_blankEmail_rerendersWithErrorInsteadOfCrashing() throws Exception {
        mockMvc.perform(post("/admin/login/verify")
                        .param("email", "")
                        .param("code", "123456"))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login"))
                .andExpect(model().attributeExists("error"));
    }
}
