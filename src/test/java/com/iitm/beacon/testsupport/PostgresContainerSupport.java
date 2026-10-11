package com.iitm.beacon.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The test suite's PostgreSQL ({@value #IMAGE}, the image docker-compose.yml
 * runs in production): one container per JVM, started on first use and
 * shared by everything (see {@link SharedContainer}), holding one database
 * per purpose. Every Spring test context runs on its database {@value
 * SharedTestDatabase#DATABASE} unless its class names another ({@link
 * SharedTestDatabase}); {@link #registerDataSource} gives a class a database
 * of its own, and {@link #freshDatabase()} a new, empty one to run Flyway (or
 * anything else) on directly.
 *
 * <p>A test class with a database of its own:
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
 * entity against it at startup, as production does, with the database
 * settings of src/test/resources/application.yml. A database name used again
 * in the same JVM gets the same database, with whatever an earlier class left
 * in it; use one name per test class unless classes deliberately share data.
 *
 * <p>Every Spring context the test framework keeps cached holds its own
 * connection pool open against this one server, so the server admits {@value
 * #MAX_CONNECTIONS} connections, not PostgreSQL's default 100; the test
 * application.yml keeps each pool small.
 *
 * <p>Needs Docker. These tests run in the normal {@code ./mvnw test} and are
 * never skipped: without a Docker daemon Testcontainers can reach, they fail
 * with an explanation of how to point Testcontainers at one.
 */
public final class PostgresContainerSupport {

    public static final String IMAGE = "postgres:16-alpine";

    /** Room for every cached context's full pool (see the class comment), and then some. */
    static final int MAX_CONNECTIONS = 250;

    /** Lower case, so it needs no quoting rules beyond double quotes; at most 63 bytes, PostgreSQL's limit. */
    private static final Pattern DATABASE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    /** Migrating one of these would copy the schema into every database created after it. */
    private static final Set<String> TEMPLATE_DATABASES = Set.of("template0", "template1");

    /** {@link #freshDatabase()}'s names: this, then 32 hex digits — 38 characters. */
    private static final String FRESH_PREFIX = "fresh_";

    // fsync=off is Testcontainers' own default command for PostgreSQL, kept:
    // the data never outlives the JVM.
    private static final SharedContainer<PostgreSQLContainer<?>> POSTGRES = new SharedContainer<>(
            "PostgreSQL (" + IMAGE + ")",
            () -> new PostgreSQLContainer<>(DockerImageName.parse(IMAGE))
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=" + MAX_CONNECTIONS));

    private PostgresContainerSupport() {
    }

    /** Where one database in the shared container is, and the credentials to connect to it. */
    public record Database(String url, String username, String password) {

        public Connection connect() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        /** The {@code spring.datasource.*} properties that point a Spring context at this database. */
        Map<String, Object> dataSourceProperties() {
            return Map.of(
                    "spring.datasource.url", url,
                    "spring.datasource.username", username,
                    "spring.datasource.password", password,
                    "spring.datasource.driver-class-name", "org.postgresql.Driver");
        }
    }

    /** The shared, running container, started by this call if nothing has started it yet. */
    public static PostgreSQLContainer<?> container() {
        return POSTGRES.get();
    }

    /**
     * Points a Spring context's datasource at database {@code databaseName}
     * in the shared container, creating that database if it doesn't exist
     * yet. Registered through {@code @DynamicPropertySource}, it wins over the
     * shared test database {@link SharedTestDatabase} would otherwise supply.
     *
     * @param databaseName lower-case letters, digits and underscores, starting
     *     with a letter, at most 63 characters; not {@code template0}/{@code template1}
     * @throws IllegalArgumentException for any other name, before Docker is touched
     */
    public static void registerDataSource(DynamicPropertyRegistry registry, String databaseName) {
        Objects.requireNonNull(registry, "registry");
        database(databaseName).dataSourceProperties().forEach((key, value) -> registry.add(key, () -> value));
    }

    /**
     * Database {@code databaseName} in the shared container, created if it
     * doesn't exist yet; the same database on every call with that name.
     *
     * @throws IllegalArgumentException for a name {@link #registerDataSource} rejects, before Docker is touched
     */
    static Database database(String databaseName) {
        checkDatabaseName(databaseName);
        PostgreSQLContainer<?> postgres = container();
        createDatabaseIfAbsent(postgres, databaseName);
        return databaseIn(postgres, databaseName);
    }

    /**
     * A new, empty database in the shared container, never handed out
     * before — e.g. for a test that runs Flyway up to some version itself,
     * without touching any database a Spring context uses.
     */
    public static Database freshDatabase() {
        String databaseName = FRESH_PREFIX + UUID.randomUUID().toString().replace("-", "");
        PostgreSQLContainer<?> postgres = container();
        createDatabaseIfAbsent(postgres, databaseName);
        return databaseIn(postgres, databaseName);
    }

    private static Database databaseIn(PostgreSQLContainer<?> postgres, String databaseName) {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + databaseName;
        return new Database(url, postgres.getUsername(), postgres.getPassword());
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

    /** Synchronized: two first requests for one name must not both try to create it. */
    private static synchronized void createDatabaseIfAbsent(PostgreSQLContainer<?> postgres, String databaseName) {
        try (Connection connection =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            if (!databaseExists(connection, databaseName)) {
                try (Statement statement = connection.createStatement()) {
                    // Safe to concatenate: only names checkDatabaseName admits (or
                    // freshDatabase's own) get here, and none holds a quote.
                    statement.execute("CREATE DATABASE \"" + databaseName + "\"");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not create database " + databaseName + " in the shared PostgreSQL container", e);
        }
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
