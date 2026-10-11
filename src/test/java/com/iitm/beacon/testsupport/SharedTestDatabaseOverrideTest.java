package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A test class that names a database of its own through {@link
 * PostgresContainerSupport#registerDataSource} keeps it: the shared {@code
 * beacon_test} (see {@link SharedTestDatabaseTest}) is only the default. The
 * context migrates its own database and still gets the test application.yml's
 * database settings.
 */
@SpringBootTest
class SharedTestDatabaseOverrideTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void ownDatabase(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerDataSource(registry, "beacon_own_database_check");
    }

    @Test
    void aClassNamingItsOwnDatabase_runsOnThatOne_notOnTheSharedOne() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("beacon_own_database_check");
    }

    @Test
    void itsOwnDatabase_isMigrated_andNothingIsLeftPending() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM country", Long.class)).isPositive();
    }
}
