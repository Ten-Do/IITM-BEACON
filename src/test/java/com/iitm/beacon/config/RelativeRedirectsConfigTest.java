package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * Guards {@code server.tomcat.use-relative-redirects} in {@code
 * src/main/resources/application.yml}: on, so the servlet container keeps a
 * redirect's {@code Location} relative ({@code /admin/login}) instead of
 * building an absolute one from the request's own scheme and host — {@code
 * http://} behind a proxy that terminates TLS (decision 23). Spring Boot
 * turns Tomcat's own default off. Like {@link SessionTimeoutConfigTest}, it
 * reads both application.yml files directly (the test copy shadows the main
 * one) and fails if they drift apart. {@link RelativeRedirectsTest} checks
 * real redirects.
 */
class RelativeRedirectsConfigTest {

    private static final String KEY = "server.tomcat.use-relative-redirects";

    private static Properties load(Class<?> fromOutputDirectoryOf) throws URISyntaxException {
        Path outputDir = Path.of(fromOutputDirectoryOf.getProtectionDomain().getCodeSource().getLocation().toURI());
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(outputDir.resolve("application.yml")));
        return yaml.getObject();
    }

    @Test
    void mainConfig_keepsRedirectsRelative() throws Exception {
        assertThat(load(SecurityConfig.class).getProperty(KEY)).isEqualTo("true");
    }

    @Test
    void testConfig_repeatsTheMainValueVerbatim() throws Exception {
        assertThat(load(RelativeRedirectsConfigTest.class).getProperty(KEY))
                .isEqualTo(load(SecurityConfig.class).getProperty(KEY));
    }
}
