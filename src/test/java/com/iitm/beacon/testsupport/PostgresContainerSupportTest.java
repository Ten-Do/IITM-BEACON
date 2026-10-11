package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.cache.ContextCacheUtils;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@link PostgresContainerSupport} against the real shared container (so,
 * like every test with a Spring context, this needs Docker), except the name
 * checks, which must reject a bad name before Docker is ever touched.
 */
class PostgresContainerSupportTest {

    /** Captures what {@code registerDataSource} registers, resolving each value straight away. */
    private static final class CapturingRegistry implements DynamicPropertyRegistry {

        private final Map<String, Object> properties = new LinkedHashMap<>();

        @Override
        public void add(String name, Supplier<Object> valueSupplier) {
            properties.put(name, valueSupplier.get());
        }

        String get(String name) {
            assertThat(properties).containsKey(name);
            return String.valueOf(properties.get(name));
        }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(
                    get("spring.datasource.url"), get("spring.datasource.username"), get("spring.datasource.password"));
        }
    }

    private static CapturingRegistry register(String databaseName) {
        CapturingRegistry registry = new CapturingRegistry();
        PostgresContainerSupport.registerDataSource(registry, databaseName);
        return registry;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void container_isOneRunningPostgres16_sharedByEveryCall() throws SQLException {
        PostgreSQLContainer<?> first = PostgresContainerSupport.container();
        PostgreSQLContainer<?> second = PostgresContainerSupport.container();

        assertThat(second).isSameAs(first);
        assertThat(first.isRunning()).isTrue();
        assertThat(first.getDockerImageName()).isEqualTo("postgres:16-alpine");
        try (Connection connection =
                DriverManager.getConnection(first.getJdbcUrl(), first.getUsername(), first.getPassword())) {
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(16);
        }
    }

    @Test
    void registerDataSource_newName_createsAnEmptyDatabaseOfThatName_andPointsTheDatasourceAtIt() throws Exception {
        CapturingRegistry registry = register("support_fresh");

        assertThat(registry.get("spring.datasource.driver-class-name")).isEqualTo("org.postgresql.Driver");
        try (Connection connection = registry.connect()) {
            assertThat(scalar(connection, "SELECT current_database()")).isEqualTo("support_fresh");
            assertThat(scalar(connection, "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'public'")).isEqualTo("0");
        }
    }

    @Test
    void registerDataSource_sameNameAgain_reusesThatDatabase_keepingItsData() throws Exception {
        try (Connection connection = register("support_reused").connect();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (id INT)");
        }

        try (Connection connection = register("support_reused").connect()) {
            assertThat(scalar(connection, "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_name = 'marker'")).isEqualTo("1");
        }
    }

    @Test
    void registerDataSource_differentNames_getSeparateDatabases_inTheOneSharedContainer() throws Exception {
        CapturingRegistry first = register("support_isolated_a");
        CapturingRegistry second = register("support_isolated_b");
        try (Connection connection = first.connect();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE only_in_a (id INT)");
        }

        try (Connection connection = second.connect()) {
            assertThat(scalar(connection, "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_name = 'only_in_a'")).isEqualTo("0");
        }
        String sharedPort = String.valueOf(PostgresContainerSupport.container().getFirstMappedPort());
        assertThat(first.get("spring.datasource.url")).contains(":" + sharedPort + "/support_isolated_a");
        assertThat(second.get("spring.datasource.url")).contains(":" + sharedPort + "/support_isolated_b");
    }

    @Test
    void registerDataSource_nameOfTheMaximum63Characters_isAccepted() throws Exception {
        String longest = "support_longest_" + "x".repeat(63 - "support_longest_".length());

        try (Connection connection = register(longest).connect()) {
            assertThat(scalar(connection, "SELECT current_database()")).isEqualTo(longest).hasSize(63);
        }
    }

    @Test
    void registerDataSource_nameThatIsAnSqlReservedWord_isAccepted() throws Exception {
        try (Connection connection = register("user").connect()) {
            assertThat(scalar(connection, "SELECT current_database()")).isEqualTo("user");
        }
    }

    @Test
    void registerDataSource_concurrentFirstRegistrationsOfOneName_allSucceed_onOneDatabase() throws Exception {
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<CapturingRegistry>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return register("support_concurrent");
                }));
            }
            go.countDown();

            for (Future<CapturingRegistry> result : results) {
                try (Connection connection = result.get(60, TimeUnit.SECONDS).connect()) {
                    assertThat(scalar(connection, "SELECT current_database()")).isEqualTo("support_concurrent");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        " ",
        "Support_upper",
        "1support",
        "_support",
        "support-dash",
        "support space",
        "support\"; DROP DATABASE test; --",
        "support_ünicode",
        "template0",
        "template1"
    })
    void registerDataSource_invalidName_isRejected_withoutRegisteringAnything(String databaseName) {
        CapturingRegistry registry = new CapturingRegistry();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostgresContainerSupport.registerDataSource(registry, databaseName));
        assertThat(registry.properties).isEmpty();
    }

    @Test
    void registerDataSource_nameOf64Characters_isRejected() {
        String tooLong = "x".repeat(64);
        CapturingRegistry registry = new CapturingRegistry();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostgresContainerSupport.registerDataSource(registry, tooLong));
        assertThat(registry.properties).isEmpty();
    }

    @Test
    void registerDataSource_nullNameOrRegistry_isRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> PostgresContainerSupport.registerDataSource(new CapturingRegistry(), null));
        assertThatNullPointerException()
                .isThrownBy(() -> PostgresContainerSupport.registerDataSource(null, "support_null_registry"));
    }

    // -- freshDatabase: a new, empty database per call --

    @Test
    void freshDatabase_isAnEmptyDatabase_inTheOneSharedContainer() throws Exception {
        PostgresContainerSupport.Database fresh = PostgresContainerSupport.freshDatabase();

        try (Connection connection = fresh.connect()) {
            assertThat(scalar(connection, "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'public'")).isEqualTo("0");
            assertThat(scalar(connection, "SELECT current_database()")).hasSizeLessThanOrEqualTo(63);
        }
        String sharedPort = String.valueOf(PostgresContainerSupport.container().getFirstMappedPort());
        assertThat(fresh.url()).startsWith("jdbc:postgresql://").contains(":" + sharedPort + "/");
        assertThat(fresh.username()).isEqualTo(PostgresContainerSupport.container().getUsername());
        assertThat(fresh.password()).isEqualTo(PostgresContainerSupport.container().getPassword());
    }

    @Test
    void freshDatabase_calledTwice_givesTwoSeparateDatabases() throws Exception {
        PostgresContainerSupport.Database first = PostgresContainerSupport.freshDatabase();
        PostgresContainerSupport.Database second = PostgresContainerSupport.freshDatabase();
        try (Connection connection = first.connect();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE only_in_first (id INT)");
        }

        assertThat(second.url()).isNotEqualTo(first.url());
        try (Connection connection = second.connect()) {
            assertThat(scalar(connection, "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'public'")).isEqualTo("0");
        }
    }

    @Test
    void freshDatabase_neverHandsOutTheSharedTestDatabase() throws Exception {
        try (Connection connection = PostgresContainerSupport.freshDatabase().connect()) {
            assertThat(scalar(connection, "SELECT current_database()")).isNotEqualTo("beacon_test");
        }
    }

    @Test
    void freshDatabase_concurrentCalls_eachGetTheirOwnDatabase() throws Exception {
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<PostgresContainerSupport.Database>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return PostgresContainerSupport.freshDatabase();
                }));
            }
            go.countDown();

            Set<String> names = new HashSet<>();
            for (Future<PostgresContainerSupport.Database> result : results) {
                try (Connection connection = result.get(60, TimeUnit.SECONDS).connect()) {
                    names.add(scalar(connection, "SELECT current_database()"));
                }
            }
            assertThat(names).hasSize(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    // -- the connection limit --

    /**
     * Every Spring context the test framework keeps cached holds its own
     * connection pool open against this one server; even with every pool
     * full, the server must have room for them all and for the tests that
     * connect directly (freshDatabase, these tests), or the suite fails with
     * "too many clients".
     */
    @Test
    void container_admitsEveryConnectionTheCachedContextsPoolsCanOpen_withRoomToSpare() throws Exception {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        int poolSize = Integer.parseInt(yaml.getObject().getProperty("spring.datasource.hikari.maximum-pool-size"));
        int cachedContexts = ContextCacheUtils.retrieveMaxCacheSize();
        PostgreSQLContainer<?> shared = PostgresContainerSupport.container();

        try (Connection connection =
                DriverManager.getConnection(shared.getJdbcUrl(), shared.getUsername(), shared.getPassword())) {
            int maxConnections = Integer.parseInt(scalar(connection, "SHOW max_connections"));
            int reserved = Integer.parseInt(scalar(connection, "SHOW superuser_reserved_connections"))
                    + Integer.parseInt(scalar(connection, "SHOW reserved_connections"));

            assertThat(maxConnections - reserved).isGreaterThanOrEqualTo(cachedContexts * poolSize + 50);
        }
    }
}
