package com.iitm.beacon.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code BEACON_ALLOWED_ORIGINS} (comma-separated) reaches the same-origin
 * check of a real {@link SameOriginOnly} endpoint — the article's contact
 * reveal — and replaces the request's own origin: behind a reverse proxy the
 * app is reached as {@code http://localhost} (MockMvc's default) while the
 * browser's pages are on the listed origins. The id is unknown on purpose: a
 * 404 means the check let the request through, a 403 that it didn't.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "BEACON_ALLOWED_ORIGINS=https://beacon.example, https://www.beacon.example")
class AllowedOriginsAppliedTest {

    private static final String CONTACT = "/gallery/999999/contact";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void eachListedOrigin_passesTheCheck() throws Exception {
        mockMvc.perform(post(CONTACT).header("Origin", "https://beacon.example").header("X-Requested-With", "fetch"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(CONTACT).header("Origin", "https://www.beacon.example")
                        .header("X-Requested-With", "fetch"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRequestsOwnOrigin_isRefused_onceTheListIsSet() throws Exception {
        mockMvc.perform(post(CONTACT).header("Origin", "http://localhost").header("X-Requested-With", "fetch"))
                .andExpect(status().isForbidden());
    }
}
