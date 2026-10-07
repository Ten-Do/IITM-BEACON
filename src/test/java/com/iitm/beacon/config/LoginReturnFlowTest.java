package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The whole return trip with one real session, as a browser does it: an
 * unauthenticated page request — or one made with the other role's session
 * (BL-033) — is sent to the login page, the OTP login runs through the real
 * view controllers, and the verify step redirects back to the page
 * originally asked for — or to the role's default page when there is
 * nothing (usable) to return to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class LoginReturnFlowTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String VISITOR_EMAIL = "return-trip@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestCache requestCache;

    @MockitoBean
    private OtpMailer otpMailer;

    private String requestCode(String loginRequestPath, String email, MockHttpSession session) throws Exception {
        mockMvc.perform(post(loginRequestPath).with(csrfField()).param("email", email).session(session))
                .andExpect(status().isFound());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), code.capture());
        return code.getValue();
    }

    private ResultActions adminLogin(MockHttpSession session) throws Exception {
        String code = requestCode("/admin/login/request", ADMIN_EMAIL, session);
        return mockMvc.perform(post("/admin/login/verify").with(csrfField())
                .param("code", code)
                .session(session));
    }

    private ResultActions visitorLogin(MockHttpSession session) throws Exception {
        String code = requestCode("/submissions/login", VISITOR_EMAIL, session);
        return mockMvc.perform(post("/submissions/login/code").with(csrfField())
                .param("code", code)
                .session(session));
    }

    private void expectRedirectToLogin(MockHttpSession session, String page, String loginPage) throws Exception {
        mockMvc.perform(get(page).session(session)).andExpect(status().isFound()).andExpect(redirectedUrl(loginPage));
    }

    private boolean hasSavedRequest(MockHttpSession session) {
        MockHttpServletRequest probe = new MockHttpServletRequest();
        probe.setSession(session);
        return requestCache.getRequest(probe, new MockHttpServletResponse()) != null;
    }

    // -- admin --

    @Test
    void adminLogin_afterAnUnauthenticatedQueuePage_returnsToItWithItsQuery() throws Exception {
        MockHttpSession session = new MockHttpSession();
        expectRedirectToLogin(session, "/moderation/queue?page=2", "/admin/login");

        adminLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/moderation/queue?page=2"));

        // Consumed by the login itself, so following the redirect is a plain page load.
        assertThat(hasSavedRequest(session)).isFalse();
        mockMvc.perform(get("/moderation/queue?page=2").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("moderation/queue"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/catalog/topics", "/catalog/achievements", "/catalog/topics/5",
        "/catalog/topic-groups/new", "/catalog/achievements/3/delete"})
    void adminLogin_afterAnUnauthenticatedCatalogPage_returnsToIt(String page) throws Exception {
        MockHttpSession session = new MockHttpSession();
        expectRedirectToLogin(session, page, "/admin/login");

        adminLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl(page));

        assertThat(hasSavedRequest(session)).isFalse();
    }

    @Test
    void adminLogin_secondTimeInTheSameSession_landsOnTheQueue() throws Exception {
        MockHttpSession session = new MockHttpSession();
        expectRedirectToLogin(session, "/moderation/queue?page=2", "/admin/login");
        adminLogin(session).andExpect(redirectedUrl("/moderation/queue?page=2"));

        adminLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void adminLogin_afterAWrongCode_stillReturnsToTheSavedPage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        expectRedirectToLogin(session, "/moderation/queue?page=4", "/admin/login");
        String code = requestCode("/admin/login/request", ADMIN_EMAIL, session);

        mockMvc.perform(post("/admin/login/verify").with(csrfField())
                        .param("code", "ZZZZZZ")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("adminauth/login-code"));
        assertThat(hasSavedRequest(session)).isTrue();

        mockMvc.perform(post("/admin/login/verify").with(csrfField())
                        .param("code", code)
                        .session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/moderation/queue?page=4"));
    }

    @Test
    void adminLogin_afterAnUnauthenticatedApprovePost_landsOnTheQueue() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/moderation/queue/1/approve").with(csrfField()).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/login"));

        adminLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void adminLogin_afterAnUnauthenticatedApiRequest_landsOnTheQueue() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/api/moderation/testimonials/pending").session(session))
                .andExpect(status().isUnauthorized());

        adminLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/moderation/queue"));
    }

    @Test
    void adminLogin_withTheVisitorsPageSaved_landsOnTheQueueAndDiscardsIt() throws Exception {
        MockHttpSession session = new MockHttpSession();
        expectRedirectToLogin(session, "/submissions/confirmation", "/submissions/login");

        adminLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/moderation/queue"));

        // Not kept for a later visitor login in the same browser either.
        assertThat(hasSavedRequest(session)).isFalse();
        visitorLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/submissions/form"));
    }

    @Test
    void adminLogin_afterAVisitorSessionOpenedTheQueue_returnsToItAsTheAdmin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        visitorLogin(session).andExpect(status().isFound());
        expectRedirectToLogin(session, "/moderation/queue?page=2", "/admin/login");
        mockMvc.perform(get("/admin/login").session(session)).andExpect(status().isOk());

        adminLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/moderation/queue?page=2"));

        mockMvc.perform(get("/moderation/queue?page=2").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("moderation/queue"));
        // The admin login replaced the visitor's: the visitor's pages want their own login again.
        expectRedirectToLogin(session, "/submissions/form", "/submissions/login");
    }

    @Test
    void adminLogin_afterAVisitorSessionOpenedACatalogPage_returnsToIt() throws Exception {
        MockHttpSession session = new MockHttpSession();
        visitorLogin(session).andExpect(status().isFound());
        expectRedirectToLogin(session, "/catalog/achievements", "/admin/login");

        adminLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/catalog/achievements"));

        assertThat(hasSavedRequest(session)).isFalse();
    }

    // -- visitor --

    @Test
    void visitorLogin_afterAnUnauthenticatedConfirmationPage_returnsToIt() throws Exception {
        MockHttpSession session = new MockHttpSession();
        expectRedirectToLogin(session, "/submissions/confirmation", "/submissions/login");

        visitorLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/submissions/confirmation"));

        assertThat(hasSavedRequest(session)).isFalse();
        mockMvc.perform(get("/submissions/confirmation").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/confirmation"));
    }

    @Test
    void visitorLogin_afterAWrongCode_stillReturnsToTheSavedPage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        expectRedirectToLogin(session, "/submissions/confirmation", "/submissions/login");
        String code = requestCode("/submissions/login", VISITOR_EMAIL, session);

        mockMvc.perform(post("/submissions/login/code").with(csrfField())
                        .param("code", "ZZZZZZ")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-code"));
        assertThat(hasSavedRequest(session)).isTrue();

        mockMvc.perform(post("/submissions/login/code").with(csrfField())
                        .param("code", code)
                        .session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/confirmation"));
    }

    @Test
    void visitorLogin_afterAnUnauthenticatedFormPost_landsOnTheForm() throws Exception {
        // The form's content can't survive a redirect; the visitor fills it in again.
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(multipart("/submissions/form").with(csrfField())
                        .param("firstName", "David")
                        .param("sections[0].topicSlug", "general")
                        .param("sections[0].answerText", "Text.")
                        .session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/submissions/login"));

        visitorLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/submissions/form"));
    }

    @Test
    void visitorLogin_afterAnAdminSessionOpenedTheForm_returnsToItAsTheVisitor() throws Exception {
        MockHttpSession session = new MockHttpSession();
        adminLogin(session).andExpect(status().isFound());
        expectRedirectToLogin(session, "/submissions/form", "/submissions/login");
        mockMvc.perform(get("/submissions/login").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/login-email"));

        visitorLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/submissions/form"));

        mockMvc.perform(get("/submissions/form").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/form"));
        // The visitor login replaced the admin's: the admin's pages want their own login again.
        expectRedirectToLogin(session, "/moderation/queue", "/admin/login");
    }

    @Test
    void visitorLogin_afterAnAdminSessionOpenedTheConfirmationPage_returnsToIt() throws Exception {
        MockHttpSession session = new MockHttpSession();
        adminLogin(session).andExpect(status().isFound());
        expectRedirectToLogin(session, "/submissions/confirmation", "/submissions/login");

        visitorLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/submissions/confirmation"));

        assertThat(hasSavedRequest(session)).isFalse();
    }

    @Test
    void visitorLogin_withTheAdminsPageSaved_landsOnTheForm() throws Exception {
        MockHttpSession session = new MockHttpSession();
        expectRedirectToLogin(session, "/moderation/queue?page=2", "/admin/login");

        visitorLogin(session).andExpect(status().isFound()).andExpect(redirectedUrl("/submissions/form"));

        assertThat(hasSavedRequest(session)).isFalse();
    }
}
