package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

/**
 * The configured session timeout ({@link SessionTimeoutConfigTest} guards its
 * value) actually reaches the embedded Tomcat that creates the sessions —
 * i.e. the property is bound under the right key and in a valid format.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SessionTimeoutAppliedTest {

    @Autowired
    private TomcatServletWebServerFactory tomcat;

    @Test
    void embeddedTomcat_expiresIdleSessionsAfter24Hours() {
        assertThat(tomcat.getSession().getTimeout()).isEqualTo(Duration.ofHours(24));
    }
}
