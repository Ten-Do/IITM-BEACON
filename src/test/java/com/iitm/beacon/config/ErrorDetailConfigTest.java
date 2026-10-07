package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * NFR-ERROR-TRANSPARENCY: no shipped configuration — the main {@code
 * application.yml}, nor the dev or prod profile — switches on Spring Boot's
 * error details for {@code /error}: stack traces, exception class names,
 * exception messages or binding errors. Each such key must be absent (Boot's
 * default is "never"/false) or explicitly "never"/false; "always" leaks to
 * every client, and "on_param" to any client that adds {@code ?trace=true}.
 * The main file is shadowed on the test classpath, so like {@link
 * UploadLimitsConfigTest} this reads the files directly, with their env-var
 * placeholders resolved to their defaults.
 */
class ErrorDetailConfigTest {

    private static Properties load(String fileName) throws URISyntaxException {
        Path outputDir = Path.of(SecurityConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(outputDir.resolve(fileName)));
        Properties properties = yaml.getObject();
        assertThat(properties).as(fileName).isNotNull().isNotEmpty();
        return properties;
    }

    private static String resolved(Properties properties, String key) {
        String raw = properties.getProperty(key);
        return raw == null ? null : new MockEnvironment().resolvePlaceholders(raw).trim().toLowerCase(Locale.ROOT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-dev.yml", "application-prod.yml"})
    void stackTracesMessagesAndBindingErrors_areNeverIncluded(String fileName) throws Exception {
        Properties properties = load(fileName);

        for (String key : new String[] {
            "server.error.include-stacktrace", "server.error.include-message", "server.error.include-binding-errors"
        }) {
            assertThat(resolved(properties, key)).as(fileName + ": " + key).isIn(null, "never");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-dev.yml", "application-prod.yml"})
    void exceptionClassNames_areNeverIncluded(String fileName) throws Exception {
        assertThat(resolved(load(fileName), "server.error.include-exception"))
                .as(fileName + ": server.error.include-exception")
                .isIn(null, "false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-dev.yml", "application-prod.yml"})
    void noOtherErrorDetailSwitch_isSetUnderServerError(String fileName) throws Exception {
        assertThat(load(fileName).stringPropertyNames())
                .filteredOn(key -> key.startsWith("server.error.include"))
                .allMatch(key -> key.equals("server.error.include-stacktrace")
                        || key.equals("server.error.include-message")
                        || key.equals("server.error.include-binding-errors")
                        || key.equals("server.error.include-exception")
                        || key.equals("server.error.include-path"));
    }
}
