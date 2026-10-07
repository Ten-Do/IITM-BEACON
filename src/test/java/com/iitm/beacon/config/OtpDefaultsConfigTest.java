package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guards the shipped OTP brute-force bounds in {@code
 * src/main/resources/application.yml} (NFR-ADMIN-OTP-BRUTEFORCE,
 * NFR-VISITOR-OTP-BRUTEFORCE): for both the admin and the visitor OTP, a
 * 5-minute TTL, at most 5 attempts per issued OTP, 1 request per email per
 * minute and 5 per IP per minute — each overridable through its own
 * environment variable. On the test classpath that file is shadowed by
 * {@code src/test/resources/application.yml} (which deliberately raises the
 * request limits), so, like {@link UploadLimitsConfigTest}, this test reads
 * the main file directly. The resolved defaults are also bound to {@link
 * AdminOtpProperties}/{@link VisitorOtpProperties}, so a misspelt key fails
 * here rather than leaving a field unset in production.
 */
class OtpDefaultsConfigTest {

    /** Key suffix under {@code admin.otp}/{@code visitor.otp}, its shipped default, and its env-var suffix. */
    private static final Map<String, String[]> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("ttl", new String[] {"PT5M", "TTL", "PT10M"});
        DEFAULTS.put("max-attempts", new String[] {"5", "MAX_ATTEMPTS", "3"});
        DEFAULTS.put("request-limit-per-email", new String[] {"1", "REQUEST_LIMIT_PER_EMAIL", "2"});
        DEFAULTS.put("request-window-per-email", new String[] {"PT1M", "REQUEST_WINDOW_PER_EMAIL", "PT2M"});
        DEFAULTS.put("request-limit-per-ip", new String[] {"5", "REQUEST_LIMIT_PER_IP", "7"});
        DEFAULTS.put("request-window-per-ip", new String[] {"PT1M", "REQUEST_WINDOW_PER_IP", "PT3M"});
    }

    private static Properties load(String fileName) throws URISyntaxException {
        Path outputDir = Path.of(SecurityConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(outputDir.resolve(fileName)));
        Properties properties = yaml.getObject();
        assertThat(properties).as(fileName).isNotNull();
        return properties;
    }

    private static String raw(String prefix, String suffix) throws URISyntaxException {
        String key = prefix + "." + suffix;
        String raw = load("application.yml").getProperty(key);
        assertThat(raw).as(key + " must be configured").isNotNull();
        return raw;
    }

    /** {@code prefix} and the env-var prefix its keys read from. */
    static Stream<Arguments> otpConfigs() {
        return Stream.of(Arguments.of("admin.otp", "ADMIN_OTP_"), Arguments.of("visitor.otp", "VISITOR_OTP_"));
    }

    @ParameterizedTest
    @MethodSource("otpConfigs")
    void mainConfig_shipsTheNfrDefaults_whenNoEnvironmentVariableIsSet(String prefix, String envPrefix)
            throws Exception {
        // An empty MockEnvironment resolves ${ENV_VAR:default} to its default, whatever this machine sets.
        MockEnvironment noOverrides = new MockEnvironment();

        for (Map.Entry<String, String[]> entry : DEFAULTS.entrySet()) {
            assertThat(noOverrides.resolvePlaceholders(raw(prefix, entry.getKey())))
                    .as(prefix + "." + entry.getKey())
                    .isEqualTo(entry.getValue()[0]);
        }
    }

    @ParameterizedTest
    @MethodSource("otpConfigs")
    void mainConfig_eachBoundIsOverridableThroughItsOwnEnvironmentVariable(String prefix, String envPrefix)
            throws Exception {
        for (Map.Entry<String, String[]> entry : DEFAULTS.entrySet()) {
            String envVar = envPrefix + entry.getValue()[1];
            String override = entry.getValue()[2];
            MockEnvironment environment = new MockEnvironment().withProperty(envVar, override);

            assertThat(environment.resolvePlaceholders(raw(prefix, entry.getKey())))
                    .as(prefix + "." + entry.getKey() + " via " + envVar)
                    .isEqualTo(override);
        }
    }

    @ParameterizedTest
    @MethodSource("otpConfigs")
    void mainConfig_anEnvironmentVariableOfTheOtherActor_doesNotLeakIntoThisOne(String prefix, String envPrefix)
            throws Exception {
        String otherPrefix = envPrefix.startsWith("ADMIN") ? "VISITOR_OTP_" : "ADMIN_OTP_";
        MockEnvironment environment = new MockEnvironment()
                .withProperty(otherPrefix + "MAX_ATTEMPTS", "99")
                .withProperty(otherPrefix + "TTL", "PT99M");

        assertThat(environment.resolvePlaceholders(raw(prefix, "max-attempts"))).isEqualTo("5");
        assertThat(environment.resolvePlaceholders(raw(prefix, "ttl"))).isEqualTo("PT5M");
    }

    @ParameterizedTest
    @MethodSource("otpConfigs")
    void mainConfig_resolvedDefaults_bindToTheOtpPropertiesRecord(String prefix, String envPrefix) throws Exception {
        MockEnvironment noOverrides = new MockEnvironment();
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String suffix : DEFAULTS.keySet()) {
            resolved.put(prefix + "." + suffix, noOverrides.resolvePlaceholders(raw(prefix, suffix)));
        }
        Binder binder = new Binder(new MapConfigurationPropertySource(resolved));

        Object bound = prefix.startsWith("admin")
                ? binder.bind(prefix, AdminOtpProperties.class).get()
                : binder.bind(prefix, VisitorOtpProperties.class).get();

        Object expected = prefix.startsWith("admin")
                ? new AdminOtpProperties(Duration.ofMinutes(5), 5, 1, Duration.ofMinutes(1), 5, Duration.ofMinutes(1))
                : new VisitorOtpProperties(
                        Duration.ofMinutes(5), 5, 1, Duration.ofMinutes(1), 5, Duration.ofMinutes(1));
        assertThat(bound).isEqualTo(expected);
    }

    /**
     * A profile file that set its own OTP bounds would silently replace the
     * shipped defaults (and their env-var overrides) in that environment.
     */
    @ParameterizedTest
    @ValueSource(strings = {"application-dev.yml", "application-prod.yml"})
    void profileConfigs_neverOverrideTheOtpBounds(String fileName) throws Exception {
        Properties profile = load(fileName);

        assertThat(profile.stringPropertyNames())
                .noneMatch(key -> key.startsWith("admin.otp") || key.startsWith("visitor.otp"));
    }
}
