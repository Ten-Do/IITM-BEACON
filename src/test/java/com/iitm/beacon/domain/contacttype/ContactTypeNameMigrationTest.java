package com.iitm.beacon.domain.contacttype;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.testsupport.PostgresContainerSupport;
import com.iitm.beacon.testsupport.PostgresContainerSupport.Database;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * V16 ({@code contact_type.name}) against a database that already went
 * through V1-V15 — including a contact type added by hand, which decision 5
 * names as the way new types are introduced. Runs Flyway directly on a
 * fresh, empty PostgreSQL database of its own per test ({@link
 * PostgresContainerSupport#freshDatabase()}), so the shared test database is
 * never migrated twice.
 */
class ContactTypeNameMigrationTest {

    private static Flyway flywayUpTo(Database database, String version) {
        return Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load();
    }

    private static String nameOf(Connection connection, String slug) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT name FROM contact_type WHERE slug = '" + slug + "'")) {
            assertThat(rs.next()).as(slug).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void v16_namesTheSeededTypes_andLeavesNoRowWithoutAName() throws Exception {
        Database database = PostgresContainerSupport.freshDatabase();
        flywayUpTo(database, "16").migrate();

        try (Connection connection = database.connect()) {
            assertThat(nameOf(connection, "email")).isEqualTo("Email");
            assertThat(nameOf(connection, "whatsapp")).isEqualTo("WhatsApp");
            assertThat(nameOf(connection, "telegram")).isEqualTo("Telegram");
            assertThat(nameOf(connection, "instagram")).isEqualTo("Instagram");
            assertThat(nameOf(connection, "twitter")).isEqualTo("X (Twitter)");
        }
    }

    @Test
    void v16_handAddedContactTypeWithUnknownSlug_doesNotBlockTheMigration() throws Exception {
        Database database = PostgresContainerSupport.freshDatabase();
        flywayUpTo(database, "15").migrate();
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO contact_type (slug, label, display_order) VALUES ('signal', 'phone number', 6)");
        }

        flywayUpTo(database, "16").migrate();

        try (Connection connection = database.connect()) {
            assertThat(nameOf(connection, "signal")).isNotBlank();
            assertThat(nameOf(connection, "email")).isEqualTo("Email");
        }
    }

    @Test
    void v16_afterwardsANamelessInsertIsRejected() throws Exception {
        Database database = PostgresContainerSupport.freshDatabase();
        flywayUpTo(database, "16").migrate();

        try (Connection connection = database.connect();
                Statement statement = connection.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.executeUpdate(
                            "INSERT INTO contact_type (slug, label, display_order) VALUES ('matrix', '@user', 7)"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
