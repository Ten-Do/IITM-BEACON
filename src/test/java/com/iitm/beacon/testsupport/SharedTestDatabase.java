package com.iitm.beacon.testsupport;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Points the datasource of every Spring test context — {@code
 * @SpringBootTest} and {@code @DataJpaTest} alike — at database {@value
 * #DATABASE} in the shared PostgreSQL container ({@link
 * PostgresContainerSupport}): no test runs on anything but PostgreSQL 16.
 * Every such context shares that one database and whatever rows the tests
 * before it committed; the first context to start migrates it with Flyway,
 * and every context validates its entities against it.
 *
 * <p>The datasource settings go into the context's environment last, below
 * every other property source, so anything that names a datasource itself
 * wins: a class's {@code @DynamicPropertySource} calling {@link
 * PostgresContainerSupport#registerDataSource} for a database of its own, a
 * {@code @TestPropertySource}, a system property or environment variable.
 * The other database settings (Flyway, Hibernate validation, time zone,
 * pool size) are in src/test/resources/application.yml.
 *
 * <p>Registered in src/test/resources/META-INF/spring.factories as a test
 * {@link ContextCustomizerFactory}, rather than an {@code
 * ApplicationContextInitializer}: the test framework applies it to every
 * test context and nothing else, whereas an initializer listed there would
 * also run in the dev run ({@code TestBeaconApplication}, which starts on
 * the test classpath with a container of its own) and start this one for
 * nothing.
 */
public final class SharedTestDatabase implements ContextCustomizerFactory {

    public static final String DATABASE = "beacon_test";

    private static final String PROPERTY_SOURCE = "sharedTestDatabase";

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return new Customizer();
    }

    /**
     * Stateless, so every instance equals every other: the test framework
     * caches contexts by their customizers, and this one must never make two
     * test classes' contexts differ.
     */
    private record Customizer() implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource(
                    PROPERTY_SOURCE, PostgresContainerSupport.database(DATABASE).dataSourceProperties()));
        }
    }
}
