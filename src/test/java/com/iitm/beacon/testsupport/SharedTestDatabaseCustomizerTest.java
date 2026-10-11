package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextCustomizer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@link SharedTestDatabase}'s customizer on its own, outside a test
 * context: what it adds to a context's environment, and that it never makes
 * two test classes' contexts differ (the test framework caches contexts by
 * their customizers, among others).
 */
class SharedTestDatabaseCustomizerTest {

    private static final SharedTestDatabase FACTORY = new SharedTestDatabase();

    private static final class SomeTestClass {
    }

    private static final class AnotherTestClass {
    }

    private static ConfigurableEnvironment customize(GenericApplicationContext context) {
        FACTORY.createContextCustomizer(SomeTestClass.class, List.of()).customizeContext(context, null);
        return context.getEnvironment();
    }

    @Test
    void everyTestClass_getsAnEqualCustomizer_soNoContextIsCachedApartForIt() {
        ContextCustomizer some = FACTORY.createContextCustomizer(SomeTestClass.class, List.of());
        ContextCustomizer another = FACTORY.createContextCustomizer(AnotherTestClass.class, List.of());
        ContextCustomizer fromAnotherFactory =
                new SharedTestDatabase().createContextCustomizer(SomeTestClass.class, List.of());

        assertThat(some).isNotNull().isEqualTo(another).isEqualTo(fromAnotherFactory);
        assertThat(some.hashCode()).isEqualTo(another.hashCode()).isEqualTo(fromAnotherFactory.hashCode());
    }

    @Test
    void aContextNamingNoDatasource_isPointedAtDatabaseBeaconTest_inTheSharedContainer() {
        PostgreSQLContainer<?> shared = PostgresContainerSupport.container();

        ConfigurableEnvironment environment = customize(new GenericApplicationContext());

        assertThat(environment.getProperty("spring.datasource.url"))
                .startsWith("jdbc:postgresql://")
                .endsWith(":" + shared.getFirstMappedPort() + "/beacon_test");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo(shared.getUsername());
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo(shared.getPassword());
    }

    @Test
    void aDatasourceNamedAnywhereElse_wins_overEveryValueTheCustomizerAdds() {
        GenericApplicationContext context = new GenericApplicationContext();
        Map<String, Object> own = Map.of(
                "spring.datasource.url", "jdbc:postgresql://elsewhere:5432/own",
                "spring.datasource.username", "own_user",
                "spring.datasource.password", "own_password");
        context.getEnvironment().getPropertySources().addLast(new MapPropertySource("own", own));

        ConfigurableEnvironment environment = customize(context);

        own.forEach((key, value) -> assertThat(environment.getProperty(key)).as(key).isEqualTo(value));
    }
}
