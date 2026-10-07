package com.iitm.beacon.adminauth;

import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.ClientErrors.assertJsonError;
import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.iitm.beacon.testsupport.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Client errors on the admin login routes (BL-029): a method a URL doesn't
 * take is a 405 with {@code Allow} — the JSON one for the API, the HTML
 * error page for the login pages, which used to answer a JSON 500 — and a
 * body in another format than JSON is a 415. Nothing is logged as a server
 * error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AdminAuthClientErrorsTest {

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void nothingWasLoggedAsAServerError() {
        assertThat(logs.errors()).isEmpty();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    // -- REST --

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/auth/otp/request", "/api/admin/auth/otp/verify"})
    void apiOtpEndpoints_takeOnlyPost_soAGetIsA405(String path) throws Exception {
        MockHttpServletResponse response = perform(get(path));

        assertJsonError(response, 405, "This method is not supported for this resource.", path);
        assertThat(response.getHeader("Allow")).isEqualTo("POST");
    }

    @Test
    void apiOtpRequest_withAnotherMethod_isA405_too() throws Exception {
        assertJsonError(perform(put("/api/admin/auth/otp/request").with(csrfHeader())),
                405, "This method is not supported for this resource.", "/api/admin/auth/otp/request");
    }

    @ParameterizedTest
    @ValueSource(strings = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE,
        MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    void apiOtpRequest_inAnotherFormatThanJson_isA415_listingJson(String contentType) throws Exception {
        MockHttpServletResponse response = perform(post("/api/admin/auth/otp/request").with(csrfHeader())
                .contentType(contentType).content("email=admin@example.com"));

        assertJsonError(response, 415, "This content type is not supported for this resource.",
                "/api/admin/auth/otp/request");
        assertThat(response.getHeader("Accept")).contains(MediaType.APPLICATION_JSON_VALUE);
    }

    // -- the login pages --

    @Test
    void loginPage_withAnUnsupportedMethod_isTheHtml405Page_withAllow() throws Exception {
        MockHttpServletResponse response = perform(put("/admin/login").with(csrfField()));

        assertHtmlErrorPage(response, 405);
        assertThat(response.getHeader("Allow")).contains("GET").doesNotContain("PUT");
    }

    @Test
    void codePage_withAnUnsupportedMethod_isTheHtml405Page() throws Exception {
        assertHtmlErrorPage(perform(delete("/admin/login/code").with(csrfField())), 405);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/login/request", "/admin/login/resend", "/admin/login/verify"})
    void postOnlyLoginSteps_openedWithAGet_areTheHtml405Page_allowingPost(String path) throws Exception {
        MockHttpServletResponse response = perform(get(path));

        assertHtmlErrorPage(response, 405);
        assertThat(response.getHeader("Allow")).isEqualTo("POST");
    }

    @Test
    void unknownLoginPage_isTheHtml404Page() throws Exception {
        assertHtmlErrorPage(perform(get("/admin/login/nothing-here")), 404);
    }
}
