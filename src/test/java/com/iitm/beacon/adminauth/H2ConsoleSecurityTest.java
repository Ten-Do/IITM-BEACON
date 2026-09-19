package com.iitm.beacon.adminauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression check for the H2 console after Spring Security was added in M2:
 * {@code SecurityConfig} must {@code permitAll()} {@code /h2-console/**}
 * (M1's dev H2 console must keep working). Uses a real running embedded
 * server ({@code RANDOM_PORT}) rather than {@code MockMvc} — the H2 console
 * is a raw {@code Servlet} registration that bypasses Spring MVC's
 * {@code DispatcherServlet} entirely, so {@code MockMvc} (which only
 * dispatches through the MVC handler mapping) never actually exercises it;
 * only a real servlet container does. Enables the console via a property
 * override rather than switching to the {@code dev} profile, so this stays
 * isolated from the file-based dev datasource.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.h2.console.enabled=true")
class H2ConsoleSecurityTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void h2ConsoleIsReachableWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/h2-console/login.jsp", String.class);

        assertThat(response.getStatusCode().value()).isNotIn(401, 403);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsIgnoringCase("h2 console");
    }
}
