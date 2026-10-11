package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.testsupport.PostgresContainerSupport;
import com.iitm.beacon.testsupport.PostgresContainerSupport.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * V18 ({@code photo.thumbnail_path}, {@code width}, {@code height}) against a
 * database that already holds photos stored before the WebP pipeline. Runs
 * Flyway directly on a fresh, empty PostgreSQL database of its own per test
 * ({@link PostgresContainerSupport#freshDatabase()}), so the shared test
 * database is never migrated twice.
 */
class PhotoThumbnailAndSizeMigrationTest {

    private static Flyway flywayUpTo(Database database, String version) {
        return Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load();
    }

    private static String isNullable(Connection connection, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT is_nullable"
                + " FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'photo' AND column_name = ?")) {
            statement.setString(1, column);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("column photo." + column + " exists").isTrue();
                return rs.getString(1);
            }
        }
    }

    @Test
    void v18_addsNullableThumbnailPathWidthAndHeight() throws Exception {
        Database database = PostgresContainerSupport.freshDatabase();
        flywayUpTo(database, "18").migrate();

        try (Connection connection = database.connect()) {
            assertThat(isNullable(connection, "thumbnail_path")).isEqualTo("YES");
            assertThat(isNullable(connection, "width")).isEqualTo("YES");
            assertThat(isNullable(connection, "height")).isEqualTo("YES");
        }
    }

    @Test
    void v18_existingPhotoRowsKeepTheirFile_andGetNoThumbnailOrSize() throws Exception {
        Database database = PostgresContainerSupport.freshDatabase();
        flywayUpTo(database, "17").migrate();
        try (Connection connection = database.connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO testimonial (first_name, last_name, roll_number, admission_year,"
                    + " email, email_lookup_hash, country_code, recommendation_score, data_processing_consent,"
                    + " created_at) VALUES ('David', 'Jones', 'GE26Z001', 2024, 'x', 'hash-1', 'IN', 8, TRUE,"
                    + " TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00')");
            statement.executeUpdate("INSERT INTO testimonial_section (testimonial_id, topic_id, answer_text)"
                    + " SELECT t.id, tp.id, 'Text.' FROM testimonial t, topic tp WHERE tp.slug = 'general'");
            statement.executeUpdate("INSERT INTO photo (testimonial_section_id, file_path, display_order)"
                    + " SELECT id, 'legacy.jpeg', 0 FROM testimonial_section");
        }

        flywayUpTo(database, "18").migrate();

        try (Connection connection = database.connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT file_path, thumbnail_path, width, height FROM photo")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("file_path")).isEqualTo("legacy.jpeg");
            assertThat(rs.getString("thumbnail_path")).isNull();
            assertThat(rs.getObject("width")).isNull();
            assertThat(rs.getObject("height")).isNull();
        }
    }

    @Test
    void v18_acceptsARowWithThumbnailAndSize() throws Exception {
        Database database = PostgresContainerSupport.freshDatabase();
        flywayUpTo(database, "18").migrate();

        try (Connection connection = database.connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO testimonial (first_name, last_name, roll_number, admission_year,"
                    + " email, email_lookup_hash, country_code, recommendation_score, data_processing_consent,"
                    + " created_at) VALUES ('David', 'Jones', 'GE26Z001', 2024, 'x', 'hash-1', 'IN', 8, TRUE,"
                    + " TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00')");
            statement.executeUpdate("INSERT INTO testimonial_section (testimonial_id, topic_id, answer_text)"
                    + " SELECT t.id, tp.id, 'Text.' FROM testimonial t, topic tp WHERE tp.slug = 'general'");
            statement.executeUpdate("INSERT INTO photo (testimonial_section_id, file_path, display_order,"
                    + " thumbnail_path, width, height) SELECT id, 'a.webp', 0, 'a-thumb.webp', 2560, 1707"
                    + " FROM testimonial_section");
            try (ResultSet rs = statement.executeQuery("SELECT thumbnail_path, width, height FROM photo")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("a-thumb.webp");
                assertThat(rs.getInt(2)).isEqualTo(2560);
                assertThat(rs.getInt(3)).isEqualTo(1707);
            }
        }
    }
}
