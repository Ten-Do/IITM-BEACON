package com.iitm.beacon.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Confirms the shared view-layer stylesheet at {@code
 * src/main/resources/static/css/beacon.css} is actually reachable through
 * the real application, not just present on disk — relying on Spring
 * Boot's default static-resource handling (files under {@code
 * src/main/resources/static/} are served at their path minus {@code
 * static/}) plus {@code SecurityConfig}'s {@code permitAll()} on {@code
 * /css/**}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class StaticResourcesTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void beaconCssIsServedWithCssContentType() throws Exception {
        mockMvc.perform(get("/css/beacon.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    /**
     * Confirms {@code gallery/detail.html}'s fullscreen photo-viewer script
     * is actually reachable through the real application, mirroring {@link
     * #beaconCssIsServedWithCssContentType()} — no dedicated JS test runner
     * exists in this Java/Maven project, so this MockMvc reachability check
     * is the only automated coverage {@code photo-viewer.js} gets.
     */
    @Test
    void photoViewerJsIsServedWithJavascriptContentType() throws Exception {
        mockMvc.perform(get("/js/photo-viewer.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"));
    }
}
