package com.iitm.beacon;

import com.iitm.beacon.testsupport.PostgresContainerSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The application for local development, on a throwaway PostgreSQL 16 (the
 * image production runs) that Testcontainers starts in Docker and removes
 * when the application stops — so every run starts from a freshly migrated,
 * empty database:
 *
 * <pre>
 * ./mvnw spring-boot:test-run -Dspring-boot.run.profiles=dev
 * </pre>
 *
 * <p>Needs a running Docker daemon, like the tests.
 */
public final class TestBeaconApplication {

    private TestBeaconApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.from(BeaconApplication::main).with(DevDatabase.class).run(args);
    }

    /** The dev run's database: the app's datasource and Flyway connect to it through {@code @ServiceConnection}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class DevDatabase {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse(PostgresContainerSupport.IMAGE));
        }
    }
}
