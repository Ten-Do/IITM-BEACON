package com.iitm.beacon.domain.contacttype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * V17 ({@code contact_type.value_pattern}, decision 5): every seeded type
 * gets its validation pattern — stored without {@code ^}/{@code $}, matched
 * against the whole (trimmed) value — and a type added by hand is left with
 * none. Same private-in-memory-H2 approach as {@link
 * ContactTypeNameMigrationTest}, so the shared test database is never
 * migrated twice.
 *
 * <p>Browser-side ({@code v}-flag) validity of the same patterns can't be
 * checked from Java; the samples below pin down the Java-side semantics.
 */
class ContactTypeValuePatternMigrationTest {

    private static final String PHONE = "\\+?\\(?[0-9](?:[ \\-\\(\\)]{0,2}[0-9]){6,14}";

    private static final Map<String, String> EXPECTED_PATTERNS = Map.of(
            "email", "[^@\\s]+@[^@\\s]+\\.[^@\\s]+",
            "whatsapp", PHONE + "|(?:https?://)?wa\\.me/\\+?[0-9]{7,15}",
            "telegram", PHONE + "|@?[A-Za-z0-9_]{5,32}|(?:https?://)?t\\.me/[A-Za-z0-9_]{5,32}",
            "instagram", "@?[A-Za-z0-9._]{1,30}|(?:https?://)?(?:www\\.)?instagram\\.com/[A-Za-z0-9._]{1,30}/?",
            "twitter", "@?[A-Za-z0-9_]{1,15}|(?:https?://)?(?:www\\.)?(?:x|twitter)\\.com/[A-Za-z0-9_]{1,15}/?");

    private static final Map<String, String> MIGRATED_PATTERNS = new HashMap<>();

    private static String freshDatabaseUrl() {
        return "jdbc:h2:mem:v17-" + UUID.randomUUID() + ";MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1";
    }

    private static Flyway flywayUpTo(String url, String version) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load();
    }

    private static String patternOf(Connection connection, String slug) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT value_pattern FROM contact_type WHERE slug = '" + slug + "'")) {
            assertThat(rs.next()).as(slug).isTrue();
            return rs.getString(1);
        }
    }

    @BeforeAll
    static void migrateOnceAndReadTheSeededPatterns() throws Exception {
        String url = freshDatabaseUrl();
        flywayUpTo(url, "17").migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            for (String slug : EXPECTED_PATTERNS.keySet()) {
                MIGRATED_PATTERNS.put(slug, patternOf(connection, slug));
            }
        }
    }

    private static boolean accepts(String slug, String value) {
        return Pattern.compile(MIGRATED_PATTERNS.get(slug)).matcher(value).matches();
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "whatsapp", "telegram", "instagram", "twitter"})
    void v17_seedsExactlyTheDocumentedPatternForEachType(String slug) {
        assertThat(MIGRATED_PATTERNS.get(slug)).isEqualTo(EXPECTED_PATTERNS.get(slug));
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "whatsapp", "telegram", "instagram", "twitter"})
    void v17_everySeededPatternCompilesInJavaAndCarriesNoAnchors(String slug) {
        String pattern = MIGRATED_PATTERNS.get(slug);

        assertThatCode(() -> Pattern.compile(pattern)).doesNotThrowAnyException();
        assertThat(pattern).doesNotStartWith("^").doesNotEndWith("$");
    }

    static Stream<Arguments> acceptedValues() {
        return Stream.of(
                Arguments.of("email", "x@y.z"),
                Arguments.of("email", "david.jones@example.com"),
                Arguments.of("whatsapp", "+91 98765 43210"),
                Arguments.of("whatsapp", "919876543210"),
                Arguments.of("whatsapp", "(044) 2257-8000"),
                Arguments.of("whatsapp", "1234567"), // 7 digits: the shortest phone number
                Arguments.of("whatsapp", "123456789012345"), // 15 digits: the longest
                Arguments.of("whatsapp", "wa.me/919876543210"),
                Arguments.of("whatsapp", "https://wa.me/+919876543210"),
                Arguments.of("telegram", "@john_doe"),
                Arguments.of("telegram", "john_doe"),
                Arguments.of("telegram", "@abcde"), // 5 characters: the shortest username
                Arguments.of("telegram", "@" + "a".repeat(32)), // 32: the longest
                Arguments.of("telegram", "t.me/john_doe"),
                Arguments.of("telegram", "https://t.me/john_doe"),
                Arguments.of("telegram", "+7 912 345-67-89"),
                Arguments.of("instagram", "2354567"),
                Arguments.of("instagram", "@john.doe"),
                Arguments.of("instagram", "a"),
                Arguments.of("instagram", "a".repeat(30)),
                Arguments.of("instagram", "instagram.com/john_doe"),
                Arguments.of("instagram", "https://www.instagram.com/john_doe/"),
                Arguments.of("twitter", "@jack"),
                Arguments.of("twitter", "@" + "a".repeat(15)),
                Arguments.of("twitter", "x.com/jack"),
                Arguments.of("twitter", "https://twitter.com/jack/"),
                Arguments.of("twitter", "www.x.com/jack_doe"));
    }

    @ParameterizedTest(name = "{0} accepts \"{1}\"")
    @MethodSource("acceptedValues")
    void v17_patternAcceptsEveryDocumentedShape(String slug, String value) {
        assertThat(accepts(slug, value)).isTrue();
    }

    static Stream<Arguments> rejectedValues() {
        return Stream.of(
                Arguments.of("email", "x@y"),
                Arguments.of("email", "@y.z"),
                Arguments.of("email", "x y@z.w"),
                Arguments.of("email", "x@@y.z"),
                Arguments.of("whatsapp", "abc"),
                Arguments.of("whatsapp", "12"),
                Arguments.of("whatsapp", "123456"), // one digit short
                Arguments.of("whatsapp", "1234567890123456"), // one digit too many
                Arguments.of("whatsapp", "wa.me/john"),
                Arguments.of("whatsapp", "t.me/john_doe"),
                Arguments.of("telegram", "@ab"),
                Arguments.of("telegram", "@abcd"), // one character short
                Arguments.of("telegram", "@" + "a".repeat(33)), // one too many
                Arguments.of("telegram", "t.me/ab"),
                Arguments.of("telegram", "john-doe"),
                Arguments.of("instagram", "a b"),
                Arguments.of("instagram", "john!doe"),
                Arguments.of("instagram", "a".repeat(31)),
                Arguments.of("instagram", "facebook.com/john_doe"),
                Arguments.of("twitter", "@" + "a".repeat(16)),
                Arguments.of("twitter", "jack.doe"),
                Arguments.of("twitter", "x.com/"),
                // Full match only: a valid handle embedded in junk is still rejected.
                Arguments.of("twitter", "hello @jack"));
    }

    @ParameterizedTest(name = "{0} rejects \"{1}\"")
    @MethodSource("rejectedValues")
    void v17_patternRejectsValuesOfAnyOtherShape(String slug, String value) {
        assertThat(accepts(slug, value)).isFalse();
    }

    @Test
    void v17_handAddedContactTypeWithUnknownSlug_keepsANullPatternAndDoesNotBlockTheMigration() throws Exception {
        String url = freshDatabaseUrl();
        flywayUpTo(url, "16").migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO contact_type (slug, name, label, display_order)"
                    + " VALUES ('signal', 'Signal', 'phone number', 6)");
        }

        flywayUpTo(url, "17").migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(patternOf(connection, "signal")).isNull();
            assertThat(patternOf(connection, "email")).isEqualTo(EXPECTED_PATTERNS.get("email"));
        }
    }

    @Test
    void v17_afterwardsATypeWithoutAPatternCanStillBeAddedByHand() throws Exception {
        String url = freshDatabaseUrl();
        flywayUpTo(url, "17").migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO contact_type (slug, name, label, display_order)"
                    + " VALUES ('matrix', 'Matrix', '@user:server', 7)");

            assertThat(patternOf(connection, "matrix")).isNull();
        }
    }
}
