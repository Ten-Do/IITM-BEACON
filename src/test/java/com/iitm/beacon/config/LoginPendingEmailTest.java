package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitm.beacon.testsupport.LoginCodeSteps;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The email typed at a login's email step travels to its code step in the
 * HTTP session, not in the URL — and the admin's and the visitor's logins
 * keep theirs apart: one browser in the middle of both shows each code page
 * its own email, and verifies each code against it. (Within one login, the
 * last email asked for wins: see the two view controllers' tests.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class LoginPendingEmailTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String VISITOR_EMAIL = "both-flows@example.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtpMailer otpMailer;

    private String codeMailedTo(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), code.capture());
        return code.getValue();
    }

    /** One browser that asked for a visitor code, then for an admin code. */
    private MockHttpSession inTheMiddleOfBothLogins() throws Exception {
        MockHttpSession session = LoginCodeSteps.visitorAskedForACode(mockMvc, VISITOR_EMAIL);
        return LoginCodeSteps.askedForACode(mockMvc, "/admin/login/request", ADMIN_EMAIL, session);
    }

    @Test
    void eachCodePage_showsItsOwnLoginsEmail() throws Exception {
        MockHttpSession session = inTheMiddleOfBothLogins();

        mockMvc.perform(get(LoginCodeSteps.VISITOR_CODE_PAGE).session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("email", VISITOR_EMAIL));
        mockMvc.perform(get(LoginCodeSteps.ADMIN_CODE_PAGE).session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("email", ADMIN_EMAIL));
    }

    @Test
    void theAdminLogin_leavesTheVisitorsPendingEmail_andTheVisitorCodeStillLogsIn() throws Exception {
        MockHttpSession session = inTheMiddleOfBothLogins();

        mockMvc.perform(post("/admin/login/verify").with(csrfField())
                        .param("code", codeMailedTo(ADMIN_EMAIL))
                        .session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/moderation/queue"));

        mockMvc.perform(get(LoginCodeSteps.VISITOR_CODE_PAGE).session(session))
                .andExpect(model().attribute("email", VISITOR_EMAIL));
        mockMvc.perform(post(LoginCodeSteps.VISITOR_CODE_PAGE).with(csrfField())
                        .param("code", codeMailedTo(VISITOR_EMAIL))
                        .session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/form"));
    }

    @Test
    void leavingOneLoginsEmailStep_forgetsOnlyThatLoginsEmail() throws Exception {
        MockHttpSession session = inTheMiddleOfBothLogins();

        mockMvc.perform(get("/admin/login").session(session)).andExpect(status().isOk());

        mockMvc.perform(get(LoginCodeSteps.ADMIN_CODE_PAGE).session(session))
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(get(LoginCodeSteps.VISITOR_CODE_PAGE).session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("email", VISITOR_EMAIL));

        mockMvc.perform(get("/submissions/login").session(session)).andExpect(status().isOk());

        mockMvc.perform(get(LoginCodeSteps.VISITOR_CODE_PAGE).session(session))
                .andExpect(redirectedUrl("/submissions/login"));
    }

    @Test
    void aVisitorCodeIsNeverCheckedAgainstTheAdminsEmail() throws Exception {
        MockHttpSession session = inTheMiddleOfBothLogins();

        mockMvc.perform(post("/admin/login/verify").with(csrfField())
                        .param("code", codeMailedTo(VISITOR_EMAIL))
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"));
    }
}
