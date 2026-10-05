package com.iitm.beacon.domain.testimonial;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * V18 ({@code photo.thumbnail_path}, {@code width}, {@code height}) against a
 * database that already holds photos stored before the WebP pipeline. Runs
 * Flyway directly on a private in-memory H2 database (PostgreSQL mode, like
 * every other test), so the shared test database is never migrated twice.
 */
class PhotoThumbnailAndSizeMigrationTest {

    private static String freshDatabaseUrl() {
        return "jdbc:h2:mem:v18-" + UUID.randomUUID() + ";MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1";
    }

    private static Flyway flywayUpTo(String url, String version) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load();
    }

    private static String isNullable(Connection connection, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE LOWER(TABLE_NAME) = 'photo' AND LOWER(COLUMN_NAME) = '" + column + "'")) {
            assertThat(rs.next()).as("column photo." + column + " exists").isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void v18_addsNullableThumbnailPathWidthAndHeight() throws Exception {
        String url = freshDatabaseUrl();
        flywayUpTo(url, "18").migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(isNullable(connection, "thumbnail_path")).isEqualTo("YES");
            assertThat(isNullable(connection, "width")).isEqualTo("YES");
            assertThat(isNullable(connection, "height")).isEqualTo("YES");
        }
    }

    @Test
    void v18_existingPhotoRowsKeepTheirFile_andGetNoThumbnailOrSize() throws Exception {
        String url = freshDatabaseUrl();
        flywayUpTo(url, "17").migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
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

        flywayUpTo(url, "18").migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
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
        String url = freshDatabaseUrl();
        flywayUpTo(url, "18").migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
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
