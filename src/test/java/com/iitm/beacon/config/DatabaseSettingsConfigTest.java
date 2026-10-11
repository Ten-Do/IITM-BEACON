package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * Guards the database settings of {@code src/main/resources/application.yml}
 * — Flyway on, Hibernate validating the entities against the migrated schema
 * instead of changing it, timestamps read and written in UTC, no
 * open-session-in-view — and that the test copy, which shadows the main file
 * on the test classpath, repeats each verbatim, so every test context runs
 * on the database settings production does. Reads both files directly, like
 * {@link RelativeRedirectsConfigTest}.
 */
class DatabaseSettingsConfigTest {

    private static Properties load(Class<?> fromOutputDirectoryOf) throws URISyntaxException {
        Path outputDir = Path.of(fromOutputDirectoryOf.getProtectionDomain().getCodeSource().getLocation().toURI());
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(outputDir.resolve("application.yml")));
        return yaml.getObject();
    }

    @ParameterizedTest
    @CsvSource({
        "spring.flyway.enabled, true",
        "spring.jpa.hibernate.ddl-auto, validate",
        "spring.jpa.properties.hibernate.jdbc.time_zone, UTC",
        "spring.jpa.open-in-view, false"
    })
    void mainConfig_hasTheDatabaseSetting(String key, String expected) throws Exception {
        assertThat(load(SecurityConfig.class).getProperty(key)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.flyway.enabled",
        "spring.jpa.hibernate.ddl-auto",
        "spring.jpa.properties.hibernate.jdbc.time_zone",
        "spring.jpa.open-in-view"
    })
    void testConfig_repeatsTheMainValueVerbatim(String key) throws Exception {
        assertThat(load(DatabaseSettingsConfigTest.class).getProperty(key))
                .isNotNull()
                .isEqualTo(load(SecurityConfig.class).getProperty(key));
    }
}
