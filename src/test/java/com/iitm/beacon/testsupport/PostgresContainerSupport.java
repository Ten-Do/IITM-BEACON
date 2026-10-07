package com.iitm.beacon.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL ({@value #IMAGE}, the image docker-compose.yml runs in
 * production) for the tests that must not rely on H2's PostgreSQL mode. One
 * container is started per JVM, on first use, and shared by every such test
 * class (see {@link SharedContainer}); each class gets its own database in
 * it, so no class sees another's rows.
 *
 * <p>Usage, in a {@code @SpringBootTest} class:
 *
 * <pre>{@code
 * @SpringBootTest
 * class SomePostgresTest {
 *
 *     @DynamicPropertySource
 *     static void postgres(DynamicPropertyRegistry registry) {
 *         PostgresContainerSupport.registerDataSource(registry, "beacon_some_postgres_test");
 *     }
 * }
 * }</pre>
 *
 * <p>The context then migrates that database with Flyway and validates every
 * entity against it at startup, as production does. A database name used
 * again in the same JVM gets the same database, with whatever an earlier
 * class left in it; use one name per test class unless classes deliberately
 * share data. Every Spring context keeps its own connection pool (10
 * connections by default) open against PostgreSQL's limit of 100.
 *
 * <p>Needs Docker. These tests run in the normal {@code ./mvnw test} and are
 * never skipped: without a Docker daemon Testcontainers can reach, they fail
 * with an explanation of how to point Testcontainers at one.
 */
public final class PostgresContainerSupport {

    public static final String IMAGE = "postgres:16-alpine";

    /** Lower case, so it needs no quoting rules beyond double quotes; at most 63 bytes, PostgreSQL's limit. */
    private static final Pattern DATABASE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    /** Migrating one of these would copy the schema into every database created after it. */
    private static final Set<String> TEMPLATE_DATABASES = Set.of("template0", "template1");

    private static final SharedContainer<PostgreSQLContainer<?>> POSTGRES = new SharedContainer<>(
            "PostgreSQL (" + IMAGE + ")", () -> new PostgreSQLContainer<>(DockerImageName.parse(IMAGE)));

    private PostgresContainerSupport() {
    }

    /** The shared, running container, started by this call if nothing has started it yet. */
    public static PostgreSQLContainer<?> container() {
        return POSTGRES.get();
    }

    /**
     * Points a Spring context's datasource at database {@code databaseName}
     * in the shared container, creating that database if it doesn't exist
     * yet, and registers the database settings of the main application.yml
     * that src/test/resources/application.yml shadows: Flyway on, Hibernate
     * {@code ddl-auto=validate}, JDBC time zone UTC, open-in-view off.
     *
     * @param databaseName lower-case letters, digits and underscores, starting
     *     with a letter, at most 63 characters; not {@code template0}/{@code template1}
     * @throws IllegalArgumentException for any other name, before Docker is touched
     */
    public static void registerDataSource(DynamicPropertyRegistry registry, String databaseName) {
        Objects.requireNonNull(registry, "registry");
        checkDatabaseName(databaseName);
        PostgreSQLContainer<?> postgres = container();
        String url = createDatabaseIfAbsent(postgres, databaseName);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    private static void checkDatabaseName(String databaseName) {
        Objects.requireNonNull(databaseName, "databaseName");
        if (!DATABASE_NAME.matcher(databaseName).matches()) {
            throw new IllegalArgumentException("Database name \"" + databaseName + "\" must be 1-63 lower-case"
                    + " letters, digits and underscores, starting with a letter");
        }
        if (TEMPLATE_DATABASES.contains(databaseName)) {
            throw new IllegalArgumentException("Database name \"" + databaseName + "\" is a PostgreSQL template");
        }
    }

    /** Synchronized: two first registrations of one name must not both try to create it. */
    private static synchronized String createDatabaseIfAbsent(PostgreSQLContainer<?> postgres, String databaseName) {
        try (Connection connection =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            if (!databaseExists(connection, databaseName)) {
                try (Statement statement = connection.createStatement()) {
                    // Safe to concatenate: checkDatabaseName admits no quote.
                    statement.execute("CREATE DATABASE \"" + databaseName + "\"");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not create database " + databaseName + " in the shared PostgreSQL container", e);
        }
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + databaseName;
    }

    private static boolean databaseExists(Connection connection, String databaseName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, databaseName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
