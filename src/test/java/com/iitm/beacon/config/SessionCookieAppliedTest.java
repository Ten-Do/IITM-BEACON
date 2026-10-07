package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Cookie.SameSite;

/**
 * The configured session cookie {@code SameSite} attribute ({@link
 * SessionCookieConfigTest} guards its value) actually reaches the embedded
 * Tomcat that writes the {@code JSESSIONID} cookie. {@code
 * SessionFixationTest} sees it on a real response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SessionCookieAppliedTest {

    @Autowired
    private TomcatServletWebServerFactory tomcat;

    @Test
    void embeddedTomcat_writesTheSessionCookieSameSiteLax() {
        assertThat(tomcat.getSession().getCookie().getSameSite()).isEqualTo(SameSite.LAX);
    }
}
