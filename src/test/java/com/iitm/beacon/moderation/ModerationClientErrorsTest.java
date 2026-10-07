package com.iitm.beacon.moderation;

import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.ClientErrors.assertJsonError;
import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.iitm.beacon.testsupport.LogCapture;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Client errors on the moderation routes, for a logged-in admin (BL-029,
 * BL-037): a testimonial id that isn't a number — or doesn't fit one — is a
 * 404 like an unknown id; the pending list's {@code page} and {@code size}
 * outside their documented bounds (page ≥ 0, size 1–100) or not numbers are
 * a 400 (they used to reach {@code PageRequest.of} and fail as a 500); an
 * unsupported method is a 405 with {@code Allow}. The API answers JSON, the
 * queue page the HTML error page; nothing is logged as a server error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ModerationClientErrorsTest {

    private static final String PENDING = "/api/moderation/testimonials/pending";

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void nothingWasLoggedAsAServerError() {
        assertThat(logs.errors()).isEmpty();
    }

    private static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private MockHttpServletResponse asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(admin())).andReturn().getResponse();
    }

    // -- REST --

    @ParameterizedTest
    @CsvSource({
        "abc, approve",
        "abc, reject",
        "1.5, approve",
        "99999999999999999999, approve",
        "9223372036854775808, reject"
    })
    void apiApproveOrReject_idThatIsNoLong_isA404_likeAnUnknownId(String id, String action) throws Exception {
        String path = "/api/moderation/testimonials/" + id + "/" + action;

        assertJsonError(asAdmin(post(path).with(csrfHeader())), 404, "Resource not found", path);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "page | -1          | page: must be greater than or equal to 0",
        "page | -2147483648 | page: must be greater than or equal to 0",
        "size | 0           | size: must be greater than or equal to 1",
        "size | -1          | size: must be greater than or equal to 1",
        "size | 101         | size: must be less than or equal to 100",
        "size | 2147483647  | size: must be less than or equal to 100"
    })
    void apiPending_pagingOutsideItsDocumentedBounds_isA400(String parameter, String value, String message)
            throws Exception {
        assertJsonError(asAdmin(get(PENDING).param(parameter, value)), 400, message, PENDING);
    }

    @Test
    void apiPending_negativePageAndZeroSize_areBothReported() throws Exception {
        assertJsonError(asAdmin(get(PENDING).param("page", "-1").param("size", "0")), 400,
                "page: must be greater than or equal to 0, size: must be greater than or equal to 1", PENDING);
    }

    @ParameterizedTest
    @CsvSource({"page, abc", "page, 1.5", "page, 2147483648", "size, abc", "size, ''"})
    void apiPending_pagingThatIsNoNumber_isA400NamingTheParameter(String parameter, String value)
            throws Exception {
        MockHttpServletResponse response = asAdmin(get(PENDING).param(parameter, value));

        if (value.isEmpty()) {
            // An empty value falls back to the default, like an absent one.
            assertThat(response.getStatus()).isEqualTo(200);
        } else {
            assertJsonError(response, 400, "Invalid value for parameter '" + parameter + "'.", PENDING);
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "0, 100", "2147483647, 1"})
    void apiPending_pagingAtItsBounds_isAccepted(String page, String size) throws Exception {
        assertThat(asAdmin(get(PENDING).param("page", page).param("size", size)).getStatus()).isEqualTo(200);
    }

    /** The largest page whose offset, page × size, still fits an {@code int}: an empty page. */
    @ParameterizedTest
    @CsvSource({"21474836, 100", "107374182, 20"})
    void apiPending_largestPageWhoseOffsetFitsAnInt_isAnEmptyPage(String page, String size) throws Exception {
        MockHttpServletResponse response = asAdmin(get(PENDING).param("page", page).param("size", size));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"content\":[]", "\"page\":" + page);
    }

    /** One page further used to fail in the query as a 500; without a size, the default size of 20 counts. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "107374183  |     | page: must be less than or equal to 107374182",
        "21474837   | 100 | page: must be less than or equal to 21474836",
        "107374183  | 20  | page: must be less than or equal to 107374182",
        "2147483647 | 2   | page: must be less than or equal to 1073741823"
    })
    void apiPending_pageOneBeyondThat_isA400NamingTheLargestPage(String page, String size, String message)
            throws Exception {
        MockHttpServletRequestBuilder request = get(PENDING).param("page", page);
        if (size != null) {
            request.param("size", size);
        }
        assertJsonError(asAdmin(request), 400, message, PENDING);
    }

    @Test
    void apiApprove_withAnUnsupportedMethod_isA405_allowingOnlyPost() throws Exception {
        String path = "/api/moderation/testimonials/1/approve";

        MockHttpServletResponse get = asAdmin(get(path));
        MockHttpServletResponse put = asAdmin(put(path).with(csrfHeader()));

        assertJsonError(get, 405, "This method is not supported for this resource.", path);
        assertJsonError(put, 405, "This method is not supported for this resource.", path);
        assertThat(get.getHeader("Allow")).isEqualTo(HttpMethod.POST.name());
        assertThat(put.getHeader("Allow")).isEqualTo(HttpMethod.POST.name());
    }

    // -- the queue page --

    /** The queue shows 20 cards a page: page 107374182 is the last whose offset fits an int. */
    @Test
    void queuePage_largestAddressablePage_isAnEmptyQueue() throws Exception {
        assertThat(asAdmin(get("/moderation/queue").param("page", "107374182")).getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"107374183", "2147483647"})
    void queuePage_pageBeyondThat_isTheHtml400Page(String page) throws Exception {
        assertHtmlErrorPage(asAdmin(get("/moderation/queue").param("page", page)), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "2147483648"})
    void queuePage_pageThatIsNoNumber_isTheHtml400Page(String page) throws Exception {
        assertHtmlErrorPage(asAdmin(get("/moderation/queue").param("page", page)), 400);
    }

    @ParameterizedTest
    @CsvSource({
        "abc, approve",
        "abc, reject",
        "99999999999999999999, approve",
        "1.5, reject"
    })
    void queuePage_approveOrRejectOfAnIdThatIsNoLong_isTheHtml404Page(String id, String action) throws Exception {
        assertHtmlErrorPage(
                asAdmin(post("/moderation/queue/" + id + "/" + action).with(csrfField()).param("reason", "x")), 404);
    }

    @Test
    void queuePage_withAnUnsupportedMethod_isTheHtml405Page_withAllow() throws Exception {
        MockHttpServletResponse response = asAdmin(delete("/moderation/queue").with(csrfField()));

        assertHtmlErrorPage(response, 405);
        assertThat(response.getHeader("Allow")).contains("GET").doesNotContain("DELETE");
    }

    @Test
    void unknownModerationPage_isTheHtml404Page() throws Exception {
        assertHtmlErrorPage(asAdmin(get("/moderation/nothing-here")), 404);
    }
}
