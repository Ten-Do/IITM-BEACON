package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Every Spring test context that names no database of its own runs on
 * database {@code beacon_test} in the one shared PostgreSQL 16 container —
 * the image production runs — never on anything else, migrated by Flyway and
 * validated by Hibernate at startup, with the main application.yml's
 * database settings. {@link SharedTestDatabaseRepositoryTest} checks the
 * same for a {@code @DataJpaTest} context, {@link SharedTestDatabaseOverrideTest}
 * that a class naming its own database keeps it.
 */
@SpringBootTest
class SharedTestDatabaseTest {

    private static final String MIGRATIONS = "db/migration/";

    /** The PostgreSQL server's own identity, the same for every database in it. */
    private static final String SERVER_IDENTITY = "SELECT system_identifier FROM pg_control_system()";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationContext context;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @Autowired
    private Validator validator;

    private static String sharedContainersServerIdentity() throws SQLException {
        PostgreSQLContainer<?> shared = PostgresContainerSupport.container();
        try (Connection connection =
                        DriverManager.getConnection(shared.getJdbcUrl(), shared.getUsername(), shared.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(SERVER_IDENTITY)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    /** Every migration script Flyway finds, as the path it reports it under (relative to its location). */
    private static List<String> migrationScriptsOnTheClasspath() throws IOException {
        Resource[] scripts =
                new PathMatchingResourcePatternResolver().getResources("classpath*:" + MIGRATIONS + "**/*.sql");
        List<String> names = new ArrayList<>();
        for (Resource script : scripts) {
            String url = script.getURL().toString();
            names.add(url.substring(url.lastIndexOf(MIGRATIONS) + MIGRATIONS.length()));
        }
        return names;
    }

    @Test
    void theContextRunsOnPostgres16() throws SQLException {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            assertThat(metaData.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(metaData.getDatabaseMajorVersion()).isEqualTo(16);
        }
    }

    @Test
    void theContextRunsOnDatabaseBeaconTest_inTheSharedContainer() throws SQLException {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("beacon_test");
        assertThat(jdbc.queryForObject(SERVER_IDENTITY, String.class)).isEqualTo(sharedContainersServerIdentity());
    }

    @Test
    void everyMigrationScriptOnTheClasspath_isAppliedSuccessfully_andNoneIsLeftPending() throws IOException {
        List<String> scripts = migrationScriptsOnTheClasspath();
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(scripts).isNotEmpty();
        assertThat(applied).extracting(MigrationInfo::getScript).containsExactlyInAnyOrderElementsOf(scripts);
        assertThat(applied).extracting(MigrationInfo::getState).containsOnly(MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void hibernateValidatedEveryEntityAgainstTheMigratedSchema_whenTheContextStarted() {
        assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto", "validate");
    }

    @Test
    void noEntityManagerIsKeptOpenForTheView() {
        assertThat(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
    }

    /** Only for checking that the round trip's address is one the app's {@code @Email} fields accept. */
    private record EmailField(@Email String email) {
    }

    @Test
    @Transactional
    void aTestimonial_roundTripsThroughItsRepository_withTheLongestValidEmailEncrypted_andTimesStoredInUtc() {
        // A 64-character local part and a 255-character domain: the longest
        // address @Email accepts, encrypted into the VARCHAR(500) column.
        String email = "a".repeat(64) + "@"
                + String.join(".", "b".repeat(63), "c".repeat(63), "d".repeat(63), "e".repeat(63));
        assertThat(email).hasSize(320);
        assertThat(validator.validate(new EmailField(email))).isEmpty();
        Instant createdAt = Instant.parse("2026-03-29T01:30:00.123456Z");
        Testimonial saved = testimonialRepository.saveAndFlush(Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z000")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(countryRepository.findById("IN").orElseThrow())
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(createdAt)
                .build());
        entityManager.clear();

        Testimonial reloaded = testimonialRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getEmail()).isEqualTo(email);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        Map<String, Object> stored = jdbc.queryForMap("SELECT email,"
                + " TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') AS created_at_utc"
                + " FROM testimonial WHERE id = ?", saved.getId());
        assertThat((String) stored.get("email")).doesNotContain("@").hasSizeLessThanOrEqualTo(500);
        assertThat(stored.get("created_at_utc")).isEqualTo("2026-03-29 01:30:00.123456");
    }
}
