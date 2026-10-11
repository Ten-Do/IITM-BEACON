package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.domain.AbstractRepositoryTest;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A {@code @DataJpaTest} context — every repository test's — runs on the
 * shared PostgreSQL database {@code beacon_test} too, not on an embedded
 * database of its own (see {@link SharedTestDatabaseTest}).
 */
class SharedTestDatabaseRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aRepositoryTestContext_runsOnPostgres16_inDatabaseBeaconTest() throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(16);
        }
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("beacon_test");
    }
}
