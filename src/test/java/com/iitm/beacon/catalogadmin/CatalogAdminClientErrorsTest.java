package com.iitm.beacon.catalogadmin;

import static com.iitm.beacon.testsupport.ClientErrors.assertHtmlErrorPage;
import static com.iitm.beacon.testsupport.ClientErrors.assertJsonError;
import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Client errors on the catalog admin routes, for a logged-in admin (BL-037,
 * BL-029): every {@code /api/catalog/**} id that isn't a number — or doesn't
 * fit one — is a 404 like an unknown id; a method a URL doesn't take is a
 * 405 with {@code Allow}; a body in another format than JSON is a 415. The
 * catalog pages (whose ids are digits-only patterns) answer an unknown path
 * with the HTML 404 page instead of JSON. Nothing is logged as a server
 * error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class CatalogAdminClientErrorsTest {

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
        "topic-groups, abc",
        "topics, abc",
        "achievements, abc",
        "topics, 1.5",
        "achievements, 99999999999999999999",
        "topic-groups, 9223372036854775808"
    })
    void apiDelete_idThatIsNoLong_isA404_likeAnUnknownId(String kind, String id) throws Exception {
        String path = "/api/catalog/" + kind + "/" + id;

        assertJsonError(asAdmin(delete(path).with(csrfHeader())), 404, "Resource not found", path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"topic-groups", "topics", "achievements"})
    void apiPatch_idThatIsNoLong_isA404_beforeTheBodyIsEvenRead(String kind) throws Exception {
        String path = "/api/catalog/" + kind + "/abc";

        assertJsonError(asAdmin(patch(path).with(csrfHeader()).contentType(MediaType.APPLICATION_JSON).content("{")),
                404, "Resource not found", path);
    }

    @Test
    void apiGetOfAnEntry_isA405_thereIsNoSuchMethod_evenForAMalformedId() throws Exception {
        MockHttpServletResponse response = asAdmin(get("/api/catalog/topics/abc"));

        assertJsonError(response, 405, "This method is not supported for this resource.", "/api/catalog/topics/abc");
        assertThat(response.getHeader("Allow")).contains("PATCH", "DELETE").doesNotContain("GET");
    }

    @Test
    void apiCreate_withABodyThatIsNotJson_isA415_listingJson() throws Exception {
        MockHttpServletResponse response = asAdmin(post("/api/catalog/topics").with(csrfHeader())
                .contentType(MediaType.TEXT_PLAIN).content("label=x"));

        assertJsonError(response, 415, "This content type is not supported for this resource.", "/api/catalog/topics");
        assertThat(response.getHeader("Accept")).contains(MediaType.APPLICATION_JSON_VALUE);
    }

    // -- pages --

    @ParameterizedTest
    @ValueSource(strings = {
        "/catalog/topics/abc", "/catalog/topic-groups/1.5", "/catalog/achievements/99999999999999999999",
        "/catalog/topics/abc/delete", "/catalog/nothing-here"
    })
    void pageWithAnIdThatIsNoId_orNoSuchPage_isTheHtml404Page(String path) throws Exception {
        assertHtmlErrorPage(asAdmin(get(path)), 404);
    }

    @Test
    void pagePostWithAnIdThatIsNoId_isTheHtml404Page() throws Exception {
        assertHtmlErrorPage(asAdmin(post("/catalog/achievements/abc/delete").with(csrfField())), 404);
    }

    @Test
    void listPage_withAnUnsupportedMethod_isTheHtml405Page_withAllow() throws Exception {
        MockHttpServletResponse response = asAdmin(put("/catalog/topics").with(csrfField()));

        assertHtmlErrorPage(response, 405);
        assertThat(response.getHeader("Allow")).contains("GET").doesNotContain("PUT");
    }
}
