package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every response carries a {@code Content-Security-Policy} — pages, the JSON
 * API, error pages, static files — that lets a page load scripts, styles,
 * fonts and data only from this site: no inline script or style, no event
 * handler attribute, no plugin, no foreign {@code <base>}, form target or
 * frame parent. Images may also be {@code data:} URLs (the pages' empty
 * icon). Only the submission form, the one page running Alpine.js, may
 * evaluate script text ({@code 'unsafe-eval'}: Alpine's standard build
 * evaluates its attribute expressions) and show {@code blob:} images (the
 * photo picker's previews). And {@code Referrer-Policy: same-origin}: a
 * link to another site doesn't learn which page it was followed from.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class SecurityHeadersTest {

    static final String POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'self'";

    static final String SUBMISSION_FORM_POLICY = "default-src 'self'; script-src 'self' 'unsafe-eval'; "
            + "style-src 'self'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'self'";

    @Autowired
    private MockMvc mockMvc;

    private static Authentication visitor() {
        return new UsernamePasswordAuthenticationToken(
                "security-headers@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static void assertHeaders(MockHttpServletResponse response, String policy) {
        assertThat(response.getHeaders("Content-Security-Policy")).containsExactly(policy);
        assertThat(response.getHeaders("Referrer-Policy")).containsExactly("same-origin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/gallery", "/submissions/login", "/admin/login"})
    void page_getsTheStrictPolicy(String path) throws Exception {
        MockHttpServletResponse response = perform(get(path));

        assertThat(response.getStatus()).isEqualTo(200);
        assertHeaders(response, POLICY);
    }

    @Test
    void apiResponse_getsTheStrictPolicy() throws Exception {
        MockHttpServletResponse response = perform(get("/api/gallery/countries"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertHeaders(response, POLICY);
    }

    @Test
    void htmlErrorPage_getsTheStrictPolicy() throws Exception {
        MockHttpServletResponse response = perform(get("/admin/login/nothing-here"));

        assertThat(response.getStatus()).isEqualTo(404);
        assertHeaders(response, POLICY);
    }

    @Test
    void jsonError_getsTheStrictPolicy() throws Exception {
        MockHttpServletResponse response = perform(get("/api/moderation/testimonials/pending"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertHeaders(response, POLICY);
    }

    /** Refused in the security filter chain (no CSRF token), before Spring MVC: the policy is there all the same. */
    @Test
    void csrfRefusalPage_getsTheStrictPolicy() throws Exception {
        MockHttpServletResponse response = perform(post("/admin/login/request").param("email", "a@example.com"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertHeaders(response, POLICY);
    }

    @Test
    void staticFile_getsTheStrictPolicy() throws Exception {
        assertHeaders(perform(get("/css/beacon.css")), POLICY);
    }

    @Test
    void submissionForm_alone_mayEvaluateScriptAndShowBlobImages() throws Exception {
        MockHttpServletResponse response = perform(get("/submissions/form").with(authentication(visitor())));

        assertThat(response.getStatus()).isEqualTo(200);
        assertHeaders(response, SUBMISSION_FORM_POLICY);
    }

    /** A form sent back with its errors is the same page again: Alpine runs on it too. */
    @Test
    void submissionFormReRenderedWithItsErrors_getsTheFormsPolicy() throws Exception {
        MockHttpServletResponse response = perform(multipart("/submissions/form")
                .param("firstName", "")
                .with(csrfField())
                .with(authentication(visitor())));

        assertThat(response.getStatus()).isEqualTo(200);
        assertHeaders(response, SUBMISSION_FORM_POLICY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/submissions/confirmation", "/submissions/form/x", "/submissions/formx"})
    void theFormsNeighbours_getTheStrictPolicy(String path) throws Exception {
        assertHeaders(perform(get(path).with(authentication(visitor()))), POLICY);
    }
}
