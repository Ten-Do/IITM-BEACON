package com.iitm.beacon.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.domain.achievement.AchievementRepository;
import com.iitm.beacon.domain.contacttype.ContactTypeRepository;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import com.iitm.beacon.domain.testimonial.TestimonialStatus;
import com.iitm.beacon.domain.topic.Topic;
import com.iitm.beacon.domain.topic.TopicGroup;
import com.iitm.beacon.domain.topic.TopicGroupRepository;
import com.iitm.beacon.domain.topic.TopicRepository;
import com.iitm.beacon.perf.PerfCatalog.TopicRef;
import com.iitm.beacon.perf.PerfDataPlan.PlannedSection;
import com.iitm.beacon.perf.PerfDataPlan.PlannedTestimonial;
import com.iitm.beacon.testsupport.Csrf;
import com.iitm.beacon.testsupport.PostgresContainerSupport;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.DockerClientFactory;

/**
 * The manual performance pass (docs/test_plan.md §1) for NFR-GALLERY-PERFORMANCE,
 * NFR-SEARCH-PERFORMANCE, NFR-MODERATION-QUEUE-PERFORMANCE and
 * NFR-DASHBOARD-PERFORMANCE: the real application on a random port, on
 * PostgreSQL 16, with a production-sized dataset ({@link PerfDataPlan},
 * seeded once by {@link PerfDataSeeder}), measured over real HTTP.
 *
 * <p>Per scenario: {@value #WARM_UP} warm-up requests, then {@value
 * #MEASURED} sequential requests over one keep-alive connection; then the
 * same scenario from {@value #CLIENTS} concurrent clients, {@value
 * #PER_CLIENT} requests each, after {@value #CLIENT_WARM_UP} warm-ups each.
 * A latency is the time from sending the request to having the whole
 * response body, measured by the client. Every response must be a 200 and
 * the nearest-rank p95 within the NFR's threshold, both ways. One more
 * request per scenario, with Hibernate statistics switched on just for it,
 * counts the SQL statements it costs.
 *
 * <p>The admin scenarios log in over HTTP like any client: a GET hands out
 * the {@code XSRF-TOKEN} cookie, the OTP request and verify echo it in the
 * {@code X-XSRF-TOKEN} header (decision 32), the code comes from the mocked
 * {@link OtpMailer}, and the verify response rotates both the session id
 * and the token.
 *
 * <p>Tagged {@code perf}: plain {@code mvn test} skips it; {@code make perf}
 * ({@code ./mvnw -Pperf test}, on the host — Testcontainers needs Docker)
 * runs only it. Results: logged, and written to {@value #RESULTS_FILE}.
 */
@Tag("perf")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceNfrTest {

    private static final Logger log = LoggerFactory.getLogger(PerformanceNfrTest.class);

    private static final String DATABASE = "beacon_perf";
    private static final String RESULTS_FILE = "target/perf/results.md";

    private static final int WARM_UP = 20;
    private static final int MEASURED = 200;
    private static final int CLIENTS = 5;
    private static final int PER_CLIENT = 40;
    private static final int CLIENT_WARM_UP = WARM_UP / CLIENTS;

    private static final Duration PAGE_LOAD = Duration.ofSeconds(2);
    private static final Duration FILTER = Duration.ofSeconds(1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final String GALLERY_NFR = "NFR-GALLERY-PERFORMANCE";
    private static final String SEARCH_NFR = "NFR-SEARCH-PERFORMANCE";
    private static final String QUEUE_NFR = "NFR-MODERATION-QUEUE-PERFORMANCE";
    private static final String DASHBOARD_NFR = "NFR-DASHBOARD-PERFORMANCE";

    private static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    private static final String JSON = "application/json";

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerDataSource(registry, DATABASE);
    }

    @LocalServerPort
    private int port;

    @Value("${admin.email}")
    private String adminEmail;

    @MockitoBean
    private OtpMailer otpMailer;

    @Autowired
    private TestimonialRepository testimonials;

    @Autowired
    private CountryRepository countries;

    @Autowired
    private TopicRepository topics;

    @Autowired
    private TopicGroupRepository topicGroups;

    @Autowired
    private AchievementRepository achievements;

    @Autowired
    private ContactTypeRepository contactTypes;

    @Autowired
    private EmailLookupHashService emailLookupHashes;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ObjectMapper objectMapper;

    private final PerfReport report = new PerfReport("Performance NFR pass");
    private final Instant started = Instant.now();
    private PerfCatalog catalog;
    private PerfDataPlan plan;
    private Duration seeding = Duration.ZERO;
    private CookieJar adminCookies;
    private List<PerfScenario> scenarios;

    // -- setup: seed, log in, pick the scenarios --

    @BeforeAll
    void seedLogInAndPickScenarios() throws Exception {
        PerfDataSeeder seeder = new PerfDataSeeder(testimonials, countries, topics, achievements, contactTypes,
                emailLookupHashes, transactionManager);
        catalog = seeder.loadCatalog();
        plan = PerfDataPlan.generate(catalog, PerfDataPlan.SEED);
        long seedStart = System.nanoTime();
        if (seeder.seedOnce(plan)) {
            // What autovacuum would have done long ago on a production database: fresh planner statistics.
            jdbc.execute("ANALYZE");
        }
        seeding = Duration.ofNanos(System.nanoTime() - seedStart);
        log.info("Perf dataset ready in {} ms", seeding.toMillis());

        adminCookies = logInAsAdmin();
        scenarios = pickScenarios();
        describeEnvironment();
        describeDataset();
    }

    private List<PerfScenario> pickScenarios() {
        String country = plan.mostPopulousCountry();
        TopicGroup group = largestGroup();
        Topic standalone = mostUsedStandaloneTopic();
        String combined = "country=" + country + "&groupIds=" + group.getId() + "&q=" + PerfDataPlan.COMMON_WORD;
        String api = "/api/gallery/testimonials";
        return List.of(
                new PerfScenario("Gallery page, first page", GALLERY_NFR, "/gallery", PAGE_LOAD, false),
                new PerfScenario("Gallery API, first page", GALLERY_NFR, api, PAGE_LOAD, false),
                new PerfScenario("Filter: country", SEARCH_NFR, api + "?country=" + country, FILTER, false),
                new PerfScenario("Filter: topic group", SEARCH_NFR, api + "?groupIds=" + group.getId(), FILTER, false),
                new PerfScenario("Filter: standalone topic", SEARCH_NFR, api + "?topicIds=" + standalone.getId(),
                        FILTER, false),
                new PerfScenario("Search: common word", SEARCH_NFR, api + "?q=" + PerfDataPlan.COMMON_WORD,
                        FILTER, false),
                new PerfScenario("Search: rare word", SEARCH_NFR, api + "?q=" + PerfDataPlan.RARE_WORD, FILTER, false),
                new PerfScenario("Combined: country + group + search", SEARCH_NFR, api + "?" + combined, FILTER, false),
                new PerfScenario("Combined, gallery page", SEARCH_NFR, "/gallery?" + combined, FILTER, false),
                new PerfScenario("Moderation queue page", QUEUE_NFR, "/moderation/queue", PAGE_LOAD, true),
                new PerfScenario("Moderation API, pending", QUEUE_NFR, "/api/moderation/testimonials/pending",
                        PAGE_LOAD, true),
                new PerfScenario("Dashboard page", DASHBOARD_NFR, "/", PAGE_LOAD, false),
                new PerfScenario("Analytics API, summary", DASHBOARD_NFR, "/api/analytics/summary", PAGE_LOAD, false));
    }

    /** The active group with the most active topics: its filter expands to the longest topic-id list. */
    private TopicGroup largestGroup() {
        Long groupId = catalog.topics().stream()
                .filter(topic -> topic.groupId() != null)
                .collect(Collectors.groupingBy(TopicRef::groupId, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.<Long, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey(
                        Comparator.reverseOrder())))
                .orElseThrow()
                .getKey();
        return topicGroups.findById(groupId).orElseThrow();
    }

    /** The standalone topic with the most approved sections in the plan. */
    private Topic mostUsedStandaloneTopic() {
        List<String> standalone = catalog.topics().stream()
                .filter(topic -> topic.groupId() == null).map(TopicRef::slug).toList();
        String slug = plan.withStatus(TestimonialStatus.APPROVED).stream()
                .flatMap(t -> t.sections().stream())
                .map(PlannedSection::topicSlug)
                .filter(standalone::contains)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey(
                        Comparator.reverseOrder())))
                .orElseThrow()
                .getKey();
        return topics.findBySlug(slug).orElseThrow();
    }

    /**
     * Logs in as the admin over HTTP: a GET for the {@code XSRF-TOKEN}
     * cookie, the OTP request and verify with that token in {@code
     * X-XSRF-TOKEN}, and the rotated session and token kept from the verify
     * response.
     */
    private CookieJar logInAsAdmin() throws Exception {
        CookieJar jar = new CookieJar();
        try (HttpClient client = newClient()) {
            assertThat(send(client, jar, get("/api/analytics/summary", jar)).statusCode()).isEqualTo(200);
            String tokenBeforeLogin = jar.get(Csrf.COOKIE).orElseThrow();

            HttpResponse<byte[]> requested = send(client, jar, post("/api/admin/auth/otp/request", jar,
                    Map.of("email", adminEmail)));
            assertThat(requested.statusCode()).as("OTP request").isEqualTo(202);
            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(otpMailer, timeout(5_000)).sendOtp(anyString(), code.capture());

            HttpResponse<byte[]> verified = send(client, jar, post("/api/admin/auth/otp/verify", jar,
                    Map.of("email", adminEmail, "code", code.getValue())));
            assertThat(verified.statusCode()).as("OTP verify").isEqualTo(200);
            assertThat(jar.get("JSESSIONID")).as("admin session cookie").isPresent();
            assertThat(jar.get(Csrf.COOKIE)).as("XSRF-TOKEN rotated at login").isPresent()
                    .get().isNotEqualTo(tokenBeforeLogin);

            assertThat(send(client, jar, get("/api/moderation/session", jar)).statusCode())
                    .as("admin session ping").isEqualTo(204);
        }
        return jar;
    }

    // -- the dataset as saved --

    @Test
    @Order(1)
    void theSeededDataset_isTheProductionSizedPlan() {
        Map<String, Long> perStatus = countPerStatus();

        assertThat(perStatus).containsEntry("APPROVED", (long) PerfDataPlan.APPROVED)
                .containsEntry("PENDING", (long) PerfDataPlan.PENDING)
                .containsEntry("REJECTED", (long) PerfDataPlan.REJECTED);
        assertThat(count("SELECT COUNT(DISTINCT country_code) FROM testimonial WHERE status = 'APPROVED'"))
                .isEqualTo(PerfDataPlan.COUNTRIES);
        assertThat(count("SELECT COUNT(*) FROM testimonial_section")).isEqualTo(plannedSections().count());
        assertThat(count("SELECT COUNT(*) FROM photo"))
                .isEqualTo(plannedSections().mapToLong(s -> s.photos().size()).sum());
        assertThat(count("SELECT COUNT(*) FROM photo_tag")).isEqualTo(plannedSections()
                .flatMap(s -> s.photos().stream()).mapToLong(p -> p.tags().size()).sum());
        assertThat(count("SELECT COUNT(*) FROM contact_method"))
                .isEqualTo(plan.testimonials().stream().mapToLong(t -> t.contacts().size()).sum());
        assertThat(count("SELECT COUNT(*) FROM testimonial_achievement"))
                .isEqualTo(plan.testimonials().stream().mapToLong(t -> t.achievementSlugs().size()).sum());
        assertThat(jdbc.queryForList("SELECT email FROM testimonial LIMIT 20", String.class))
                .as("emails are encrypted at rest").noneMatch(email -> email.contains("@"));
        assertThat(jdbc.queryForList("SELECT value FROM contact_method LIMIT 20", String.class))
                .as("contact values are encrypted at rest").noneMatch(value -> value.contains("perf"));
    }

    private Stream<PlannedSection> plannedSections() {
        return plan.testimonials().stream().flatMap(t -> t.sections().stream());
    }

    // -- the measurements --

    Stream<Arguments> scenarios() {
        return scenarios.stream().map(scenario -> Arguments.of(Named.of(scenario.name(), scenario)));
    }

    @ParameterizedTest(name = "sequential: {0}")
    @MethodSource("scenarios")
    @Order(2)
    void sequential(PerfScenario scenario) throws Exception {
        ScenarioResult result;
        try (HttpClient client = newClient()) {
            CookieJar jar = cookiesFor(scenario);
            warmUp(client, jar, scenario, WARM_UP);
            long[] samples = new long[MEASURED];
            int non200 = 0;
            long start = System.nanoTime();
            for (int i = 0; i < MEASURED; i++) {
                long sent = System.nanoTime();
                HttpResponse<byte[]> response = send(client, jar, get(scenario.path(), jar));
                samples[i] = System.nanoTime() - sent;
                non200 += response.statusCode() == 200 ? 0 : 1;
            }
            double perSecond = MEASURED / seconds(System.nanoTime() - start);
            result = new ScenarioResult(scenario, "sequential", LatencySummary.of(samples), non200, perSecond);
            record(result);
            report.profile(new ScenarioProfile(scenario, sqlStatements(client, jar, scenario), matchedRows(client,
                    jar, scenario)));
        }
        assertMeetsTheNfr(result);
    }

    @ParameterizedTest(name = "concurrent: {0}")
    @MethodSource("scenarios")
    @Order(3)
    void concurrent(PerfScenario scenario) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        CountDownLatch warmedUp = new CountDownLatch(CLIENTS);
        CountDownLatch go = new CountDownLatch(1);
        ScenarioResult result;
        try {
            List<Future<ClientRun>> runs = new ArrayList<>();
            for (int c = 0; c < CLIENTS; c++) {
                CookieJar jar = cookiesFor(scenario);
                runs.add(pool.submit(() -> clientRun(scenario, jar, warmedUp, go)));
            }
            warmedUp.await();
            long start = System.nanoTime();
            go.countDown();
            long[] samples = new long[CLIENTS * PER_CLIENT];
            int non200 = 0;
            for (int c = 0; c < CLIENTS; c++) {
                ClientRun run = runs.get(c).get();
                System.arraycopy(run.samples(), 0, samples, c * PER_CLIENT, PER_CLIENT);
                non200 += run.non200();
            }
            double perSecond = samples.length / seconds(System.nanoTime() - start);
            result = new ScenarioResult(scenario, CLIENTS + " concurrent clients x " + PER_CLIENT,
                    LatencySummary.of(samples), non200, perSecond);
            record(result);
        } finally {
            pool.shutdownNow();
        }
        assertMeetsTheNfr(result);
    }

    private ClientRun clientRun(PerfScenario scenario, CookieJar jar, CountDownLatch warmedUp, CountDownLatch go)
            throws Exception {
        try (HttpClient client = newClient()) {
            try {
                warmUp(client, jar, scenario, CLIENT_WARM_UP);
            } finally {
                warmedUp.countDown();
            }
            go.await();
            long[] samples = new long[PER_CLIENT];
            int non200 = 0;
            for (int i = 0; i < PER_CLIENT; i++) {
                long sent = System.nanoTime();
                HttpResponse<byte[]> response = send(client, jar, get(scenario.path(), jar));
                samples[i] = System.nanoTime() - sent;
                non200 += response.statusCode() == 200 ? 0 : 1;
            }
            return new ClientRun(samples, non200);
        }
    }

    private record ClientRun(long[] samples, int non200) {
    }

    private void warmUp(HttpClient client, CookieJar jar, PerfScenario scenario, int requests) throws Exception {
        for (int i = 0; i < requests; i++) {
            HttpResponse<byte[]> response = send(client, jar, get(scenario.path(), jar));
            assertThat(response.statusCode())
                    .as("warm-up %s of %s answered %s", scenario.path(), scenario.name(),
                            response.headers().firstValue("Location").map(l -> "redirect to " + l).orElse("it"))
                    .isEqualTo(200);
        }
    }

    private void record(ScenarioResult result) {
        report.result(result);
        LatencySummary latency = result.latency();
        log.info("PERF {} [{}] {}: n={} p50={} ms p95={} ms p99={} ms max={} ms, {} req/s, non-200: {} -> {}",
                result.scenario().name(), result.mode(), result.scenario().path(), latency.count(),
                PerfReport.millis(latency.p50()), PerfReport.millis(latency.p95()), PerfReport.millis(latency.p99()),
                PerfReport.millis(latency.max()), String.format(Locale.ROOT, "%.1f", result.requestsPerSecond()),
                result.non200(), result.passed() ? "PASS" : "MISS");
    }

    private static void assertMeetsTheNfr(ScenarioResult result) {
        assertThat(result.non200()).as("non-200 responses of %s", result.scenario().path()).isZero();
        assertThat(result.latency().p95())
                .as("p95 of %s (%s) in ns, %s threshold %s", result.scenario().path(), result.mode(),
                        result.scenario().nfr(), result.scenario().threshold())
                .isLessThanOrEqualTo(result.scenario().threshold().toNanos());
    }

    /**
     * One more request with Hibernate statistics switched on for just its
     * duration: the SQL statements it prepared. Only this one request runs
     * meanwhile, so the global counters are its own.
     */
    private Long sqlStatements(HttpClient client, CookieJar jar, PerfScenario scenario) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        statistics.setStatisticsEnabled(true);
        try {
            HttpResponse<byte[]> response = send(client, jar, get(scenario.path(), jar));
            assertThat(response.statusCode()).isEqualTo(200);
            log.info("PERF SQL {}: {} statements, {} entity loads, {} entity fetches, {} collection fetches,"
                            + " {} queries",
                    scenario.name(), statistics.getPrepareStatementCount(), statistics.getEntityLoadCount(),
                    statistics.getEntityFetchCount(), statistics.getCollectionFetchCount(),
                    statistics.getQueryExecutionCount());
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
            statistics.clear();
        }
    }

    /** {@code totalElements} of a JSON page answer; {@code null} for a page or any other answer. */
    private Long matchedRows(HttpClient client, CookieJar jar, PerfScenario scenario) throws Exception {
        if (!scenario.path().startsWith("/api/") || scenario.path().startsWith("/api/analytics")) {
            return null;
        }
        HttpResponse<byte[]> response = send(client, jar, get(scenario.path(), jar));
        return objectMapper.readTree(response.body()).path("totalElements").asLong();
    }

    // -- HTTP --

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    private CookieJar cookiesFor(PerfScenario scenario) {
        return scenario.asAdmin() ? adminCookies.copy() : new CookieJar();
    }

    private HttpRequest get(String path, CookieJar jar) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", path.startsWith("/api/") ? JSON : BROWSER_ACCEPT)
                .GET();
        jar.cookieHeader().ifPresent(cookies -> request.header("Cookie", cookies));
        return request.build();
    }

    private HttpRequest post(String path, CookieJar jar, Map<String, String> json) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", JSON)
                .header("Content-Type", JSON)
                .header(Csrf.HEADER, jar.get(Csrf.COOKIE).orElseThrow())
                .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(json)));
        jar.cookieHeader().ifPresent(cookies -> request.header("Cookie", cookies));
        return request.build();
    }

    private static HttpResponse<byte[]> send(HttpClient client, CookieJar jar, HttpRequest request)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        jar.accept(response.headers().allValues("Set-Cookie"));
        return response;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    // -- report --

    private void describeEnvironment() {
        Runtime runtime = Runtime.getRuntime();
        report.environment("Started", started.truncatedTo(ChronoUnit.SECONDS).toString());
        report.environment("CPUs (JVM)", String.valueOf(runtime.availableProcessors()));
        report.environment("Max heap", runtime.maxMemory() / (1024 * 1024) + " MB");
        report.environment("JVM", System.getProperty("java.vm.vendor") + " " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.runtime.version"));
        report.environment("OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        report.environment("Spring Boot", SpringBootVersion.getVersion());
        report.environment("PostgreSQL", jdbc.queryForObject("SHOW server_version", String.class)
                + " (Testcontainers " + PostgresContainerSupport.IMAGE + ", started with fsync=off)");
        report.environment("Docker", dockerDescription());
        if (dataSource instanceof HikariDataSource hikari) {
            report.environment("Connection pool", "HikariCP, max " + hikari.getMaximumPoolSize() + " connections");
        }
        report.environment("Client", "java.net.http.HttpClient, HTTP/1.1 keep-alive, over loopback, in the same JVM"
                + " as the application (client and server share the CPUs)");
    }

    private static String dockerDescription() {
        try {
            var info = DockerClientFactory.instance().client().infoCmd().exec();
            return info.getServerVersion() + " on " + info.getOperatingSystem() + ", " + info.getNCPU() + " CPUs, "
                    + info.getMemTotal() / (1024 * 1024) + " MB";
        } catch (RuntimeException e) {
            return "unknown (" + e.getMessage() + ")";
        }
    }

    private void describeDataset() {
        Map<String, Long> perStatus = countPerStatus();
        String country = plan.mostPopulousCountry();
        report.dataset("Testimonials: approved / pending / rejected", perStatus.getOrDefault("APPROVED", 0L) + " / "
                + perStatus.getOrDefault("PENDING", 0L) + " / " + perStatus.getOrDefault("REJECTED", 0L));
        report.dataset("Countries with approved testimonials", String.valueOf(
                count("SELECT COUNT(DISTINCT country_code) FROM testimonial WHERE status = 'APPROVED'")));
        report.dataset("Most populous country (approved)", country + ": " + jdbc.queryForObject(
                "SELECT COUNT(*) FROM testimonial WHERE status = 'APPROVED' AND country_code = ?",
                Long.class, country));
        report.dataset("Sections (of approved testimonials)", count("SELECT COUNT(*) FROM testimonial_section") + " ("
                + count("SELECT COUNT(*) FROM testimonial_section s JOIN testimonial t ON t.id = s.testimonial_id"
                        + " WHERE t.status = 'APPROVED'") + ")");
        report.dataset("Answer length, chars (min / avg / max)", jdbc.queryForObject(
                "SELECT MIN(LENGTH(answer_text)) || ' / ' || ROUND(AVG(LENGTH(answer_text))) || ' / '"
                        + " || MAX(LENGTH(answer_text)) FROM testimonial_section", String.class));
        report.dataset("Photo rows / photo tags", count("SELECT COUNT(*) FROM photo") + " / "
                + count("SELECT COUNT(*) FROM photo_tag"));
        report.dataset("Contact methods (encrypted)", String.valueOf(count("SELECT COUNT(*) FROM contact_method")));
        report.dataset("Achievement ticks", String.valueOf(count("SELECT COUNT(*) FROM testimonial_achievement")));
        report.dataset("Approved matching the common word \"" + PerfDataPlan.COMMON_WORD + "\"",
                String.valueOf(plan.withStatus(TestimonialStatus.APPROVED).stream()
                        .filter(t -> mentions(t, PerfDataPlan.COMMON_WORD)).count()));
        report.dataset("Approved matching the rare word \"" + PerfDataPlan.RARE_WORD + "\"",
                String.valueOf(PerfDataPlan.RARE_WORD_TESTIMONIALS));
        report.dataset("Random seed", String.valueOf(PerfDataPlan.SEED));
        report.dataset("Seeding time (incl. ANALYZE)", seeding.toMillis() + " ms");
    }

    private static boolean mentions(PlannedTestimonial testimonial, String word) {
        return testimonial.sections().stream().anyMatch(s -> s.answerText().toLowerCase(Locale.ROOT).contains(word));
    }

    private Map<String, Long> countPerStatus() {
        return jdbc.queryForList("SELECT status, COUNT(*) AS n FROM testimonial GROUP BY status").stream()
                .collect(Collectors.toMap(
                        row -> (String) row.get("status"), row -> ((Number) row.get("n")).longValue()));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @AfterAll
    void writeReport() throws IOException {
        Duration took = Duration.between(started, Instant.now());
        report.note("Generated by `make perf` (`./mvnw -Pperf test`, `" + getClass().getSimpleName() + "`).");
        report.note("");
        report.note("Per scenario: " + WARM_UP + " warm-up requests, then " + MEASURED + " sequential requests on one"
                + " keep-alive connection; then " + CLIENTS + " concurrent clients x " + PER_CLIENT + " requests ("
                + CLIENT_WARM_UP + " warm-ups each). Latency: request sent to full response body received, measured by"
                + " the client. Percentiles: nearest rank. Every response must be a 200 and the p95 within the NFR"
                + " threshold. SQL statements: one extra request with Hibernate statistics on.");
        report.note("");
        report.note("Test class run took " + took.toSeconds() + " s (seeding " + seeding.toMillis() + " ms).");
        String markdown = report.markdown();
        Path results = Path.of(RESULTS_FILE);
        Files.createDirectories(results.getParent());
        Files.writeString(results, markdown);
        log.info("Performance results written to {}:\n{}", results.toAbsolutePath(), markdown);
    }
}
