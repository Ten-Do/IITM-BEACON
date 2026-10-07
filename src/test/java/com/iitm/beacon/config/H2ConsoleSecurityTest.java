package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

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

    /**
     * The console's own pages run inline scripts and event handlers, which
     * the site's Content-Security-Policy would block: like CSRF, the policy
     * is off for the dev-only console. Framed by itself, it may still be.
     */
    @Test
    void h2Console_isServedWithoutTheSitesContentSecurityPolicy() {
        ResponseEntity<String> response = restTemplate.getForEntity("/h2-console/login.jsp", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().get("Content-Security-Policy")).isNull();
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("SAMEORIGIN");
    }

    /** The console's own pages post forms without the app's CSRF token (BL-004): CSRF is off for it. */
    @Test
    void h2ConsoleLoginPost_needsNoCsrfToken() {
        String loginPage = restTemplate.getForObject("/h2-console/login.jsp", String.class);
        Matcher sessionId = Pattern.compile("jsessionid=([0-9a-f]+)").matcher(loginPage);
        assertThat(sessionId.find()).as("the console's own session id on its login page").isTrue();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("language", "en");
        form.add("setting", "Generic H2 (Embedded)");
        form.add("name", "Generic H2 (Embedded)");
        form.add("driver", "org.h2.Driver");
        form.add("url", "jdbc:h2:mem:beacon_test");
        form.add("user", "sa");
        form.add("password", "");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/h2-console/login.do?jsessionid=" + sessionId.group(1), new HttpEntity<>(form, headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).doesNotContain("Access denied");
    }
}
