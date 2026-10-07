package com.iitm.beacon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.testsupport.CatalogVisibilityFixture;
import com.iitm.beacon.testsupport.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * BL-003: the Flyway migrations on a real PostgreSQL 16 (the image
 * docker-compose.yml runs in production) instead of the H2 PostgreSQL mode
 * every other test uses. Starting the context is itself part of the check:
 * it migrates its own fresh database in the shared container, and Hibernate
 * validates every entity against the result ({@code ddl-auto=validate}).
 * The resulting schema and seed data are then compared with an H2 database
 * migrated from the same scripts, so a difference H2's compatibility mode
 * masks shows up here instead of in production.
 */
@SpringBootTest
class PostgresMigrationParityTest {

    private static final String MIGRATIONS = "db/migration/";

    /** A private H2 database in the URL mode of src/test/resources/application.yml, so no other test touches it. */
    private static final String H2_URL = "jdbc:h2:mem:postgres-parity-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1";

    /** Both databases' own tables, for queries against their information_schema. */
    private static final String OWN_TABLES =
            "LOWER(table_schema) = 'public' AND LOWER(table_name) <> 'flyway_schema_history'";

    private static JdbcTemplate h2;

    @Autowired
    private JdbcTemplate postgres;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private TopicGroupRepository topicGroupRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @Autowired
    private Validator validator;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerDataSource(registry, "beacon_migration_parity");
    }

    @BeforeAll
    static void migrateTheH2Reference() {
        Flyway.configure()
                .dataSource(H2_URL, "sa", "")
                .locations("classpath:" + MIGRATIONS)
                .load()
                .migrate();
        h2 = new JdbcTemplate(new DriverManagerDataSource(H2_URL, "sa", ""));
    }

    /** Every row of {@code sql}'s result, each as its column values in select order. */
    private static List<List<Object>> rows(JdbcTemplate jdbc, String sql) {
        return jdbc.query(sql, (rs, rowNum) -> {
            List<Object> row = new ArrayList<>();
            for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
                row.add(rs.getObject(column));
            }
            return row;
        });
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
    void theContextRunsOnPostgres16_notOnH2() throws SQLException {
        try (Connection connection = postgres.getDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            assertThat(metaData.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(metaData.getDatabaseMajorVersion()).isEqualTo(16);
        }
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
    void hibernateValidatedEveryEntityAgainstThePostgresSchema_whenTheContextStarted() {
        assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto", "validate");
    }

    @Test
    void everyTableAndColumn_matchesH2MigratedFromTheSameScripts() {
        String columns = "SELECT LOWER(table_name), LOWER(column_name), LOWER(data_type), is_nullable"
                + " FROM information_schema.columns WHERE " + OWN_TABLES;

        assertThat(rows(postgres, columns)).isNotEmpty().containsExactlyInAnyOrderElementsOf(rows(h2, columns));
    }

    /**
     * A foreign key declared without ON DELETE reads RESTRICT on H2 and NO
     * ACTION on PostgreSQL. For a non-deferrable key, as all of ours are, both
     * reject deleting a row that is still referenced (they differ only in
     * when, within one statement, the check runs), so they compare as equal.
     */
    @Test
    void everyPrimaryUniqueAndForeignKey_matchesH2MigratedFromTheSameScripts() {
        String keys = "SELECT LOWER(tc.table_name), tc.constraint_type, LOWER(kcu.column_name), kcu.ordinal_position,"
                + " LOWER(referenced.table_name),"
                + " CASE rc.delete_rule WHEN 'RESTRICT' THEN 'NO ACTION' ELSE rc.delete_rule END"
                + " FROM information_schema.table_constraints tc"
                + " JOIN information_schema.key_column_usage kcu ON kcu.constraint_schema = tc.constraint_schema"
                + " AND kcu.constraint_name = tc.constraint_name AND kcu.table_name = tc.table_name"
                + " LEFT JOIN information_schema.referential_constraints rc"
                + " ON rc.constraint_schema = tc.constraint_schema AND rc.constraint_name = tc.constraint_name"
                + " LEFT JOIN information_schema.table_constraints referenced"
                + " ON referenced.constraint_schema = rc.unique_constraint_schema"
                + " AND referenced.constraint_name = rc.unique_constraint_name"
                + " WHERE tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')"
                + " AND LOWER(tc.table_schema) = 'public' AND LOWER(tc.table_name) <> 'flyway_schema_history'";

        assertThat(rows(postgres, keys)).isNotEmpty().containsExactlyInAnyOrderElementsOf(rows(h2, keys));
    }

    @Test
    void everyTablesRows_matchH2MigratedFromTheSameScripts_andTheCatalogsAreSeeded() {
        List<String> tables = postgres.queryForList(
                "SELECT LOWER(table_name) FROM information_schema.tables WHERE " + OWN_TABLES, String.class);

        assertThat(tables).isNotEmpty();
        for (String table : tables) {
            String everyRow = "SELECT * FROM " + table;
            assertThat(rows(postgres, everyRow)).as(table).containsExactlyInAnyOrderElementsOf(rows(h2, everyRow));
        }
        for (String seeded : List.of("country", "topic_group", "topic", "contact_type", "achievement")) {
            assertThat(postgres.queryForObject("SELECT COUNT(*) FROM " + seeded, Long.class)).as(seeded).isPositive();
        }
        assertThat(topicRepository.findBySlug("general"))
                .hasValueSatisfying(general -> assertThat(general.getTopicGroup()).isNull());
    }

    @Test
    @Transactional
    void aNewTopicGroup_getsAnIdTheSeedsExplicitIdsLeftFree() {
        Long highestSeededId = postgres.queryForObject("SELECT MAX(id) FROM topic_group", Long.class);

        TopicGroup saved = topicGroupRepository.saveAndFlush(
                TopicGroup.builder().label("New group").displayOrder(100).active(true).build());

        assertThat(saved.getId()).isGreaterThan(highestSeededId);
    }

    @Test
    @Transactional
    void aTopicWithAnExistingSlug_isRejectedAsADataIntegrityViolation() {
        Topic duplicate = CatalogVisibilityFixture.topic("general", "Second general", null, 99, true);

        assertThatThrownBy(() -> topicRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
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
        Map<String, Object> stored = postgres.queryForMap("SELECT email,"
                + " TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') AS created_at_utc"
                + " FROM testimonial WHERE id = ?", saved.getId());
        assertThat((String) stored.get("email")).doesNotContain("@").hasSizeLessThanOrEqualTo(500);
        assertThat(stored.get("created_at_utc")).isEqualTo("2026-03-29 01:30:00.123456");
    }
}
