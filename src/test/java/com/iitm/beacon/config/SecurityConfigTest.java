package com.iitm.beacon.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code SecurityConfig}-level tests that don't belong to any single
 * feature-slice controller test:
 *
 * <ul>
 *   <li>the {@code anyRequest().denyAll()} catch-all tail — any route not
 *       explicitly matched must be blocked outright, even for an
 *       authenticated principal with a real role, rather than falling
 *       through to {@code authenticated()} (which a VISITOR-role session
 *       would satisfy);
 *   <li>the {@code permitAll()} static-asset route group ({@code /css/**},
 *       {@code /js/**}, {@code /images/**}, {@code /webjars/**}) — no content is served from
 *       these exact paths, so a passing test here only confirms the
 *       security layer lets the request through to Spring MVC's own "no
 *       handler found" 404 instead of blocking it with 401/403. Every other
 *       view-layer route group ({@code /}, {@code /gallery/**}, {@code
 *       /submissions/login/**}, {@code /admin/login/**}) now has its own
 *       real controller — see {@code gallery.GalleryViewControllerTest},
 *       {@code submission.SubmissionViewControllerTest}, and {@code
 *       adminauth.AdminAuthViewControllerTest} instead.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    private static Authentication visitor() {
        return new UsernamePasswordAuthenticationToken(
                "visitor@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    @Test
    void unmatchedRoute_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/does-not-exist-anywhere")).andExpect(status().isUnauthorized());
    }

    @Test
    void unmatchedRoute_authenticatedVisitor_returns403() throws Exception {
        mockMvc.perform(get("/api/does-not-exist-anywhere").with(authentication(visitor())))
                .andExpect(status().isForbidden());
    }

    @Test
    void staticAssetRoutes_permitAllButNoContentYet_returns404NotSecurityError() throws Exception {
        mockMvc.perform(get("/css/app.css")).andExpect(status().isNotFound());
        mockMvc.perform(get("/js/app.js")).andExpect(status().isNotFound());
        mockMvc.perform(get("/images/logo.png")).andExpect(status().isNotFound());
        mockMvc.perform(get("/webjars/no-such-library/dist/missing.js")).andExpect(status().isNotFound());
    }
}
