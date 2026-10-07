package com.iitm.beacon.analytics;

import static com.iitm.beacon.testsupport.ClientErrors.assertJsonError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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

/**
 * Client errors on the analytics routes: the summary for a client that
 * accepts no JSON is a 406 — still answered with the JSON {@code
 * ErrorResponse}, not an empty body and a logged 500. The homepage itself
 * answers HTML whatever the client accepts. Its only methods are GET and
 * HEAD; anything else is refused by the security filter chain before Spring
 * MVC (the {@code denyAll()} tail), so no page client error reaches it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AnalyticsClientErrorsTest {

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void nothingWasLoggedAsAServerError() {
        assertThat(logs.errors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_HTML_VALUE, "image/png"})
    void summary_forAClientAcceptingNoJson_isA406_stillAnsweredAsJson(String accept) throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/api/analytics/summary").accept(accept)).andReturn().getResponse();

        assertJsonError(response, 406, "This resource is only available as JSON.", "/api/analytics/summary");
    }

    @Test
    void homepage_forAClientAskingForJson_isStillTheHtmlPage() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/").accept(MediaType.APPLICATION_JSON)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
    }
}
