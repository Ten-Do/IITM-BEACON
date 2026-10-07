package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * Guards the session cookie's {@code SameSite} attribute in {@code
 * src/main/resources/application.yml}: {@code Lax}, so a cross-site POST
 * never carries the session (BL-004). On the test classpath that file is
 * shadowed by {@code src/test/resources/application.yml}, so — like {@link
 * SessionTimeoutConfigTest} — this test reads both files directly and fails
 * if the test copy drifts from the main one. {@link
 * SessionCookieAppliedTest} checks the value reaches the servlet container.
 */
class SessionCookieConfigTest {

    private static final String KEY = "server.servlet.session.cookie.same-site";

    private static Properties load(Class<?> fromOutputDirectoryOf) throws URISyntaxException {
        Path outputDir = Path.of(fromOutputDirectoryOf.getProtectionDomain().getCodeSource().getLocation().toURI());
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(outputDir.resolve("application.yml")));
        return yaml.getObject();
    }

    @Test
    void mainConfig_sendsTheSessionCookieSameSiteLax() throws Exception {
        assertThat(load(SecurityConfig.class).getProperty(KEY)).isEqualTo("lax");
    }

    @Test
    void testConfig_repeatsTheMainSameSiteVerbatim() throws Exception {
        assertThat(load(SessionCookieConfigTest.class).getProperty(KEY))
                .isEqualTo(load(SecurityConfig.class).getProperty(KEY));
    }
}
