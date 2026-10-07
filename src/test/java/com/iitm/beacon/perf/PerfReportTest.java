package com.iitm.beacon.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The performance pass's report: what target/perf/results.md says, and the
 * pass rule behind its verdicts — a measurement meets its NFR when every
 * response was a 200 and its p95 is within ("at most") the threshold.
 */
class PerfReportTest {

    private static final PerfScenario GALLERY =
            new PerfScenario("Gallery page", "NFR-GALLERY-PERFORMANCE", "/gallery", Duration.ofSeconds(2), false);

    private static final long TWO_SECONDS = Duration.ofSeconds(2).toNanos();

    private static ScenarioResult result(PerfScenario scenario, long p95, int non200) {
        LatencySummary latency = new LatencySummary(200, p95 / 2, p95, p95, p95);
        return new ScenarioResult(scenario, "sequential", latency, non200, 12.34);
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0.0",
        "49999, 0.0",
        "50000, 0.1",
        "1049999, 1.0",
        "1050000, 1.1",
        "2000000000, 2000.0"
    })
    void milliseconds_haveOneDecimal_roundedHalfUp(long nanos, String expected) {
        assertThat(PerfReport.millis(nanos)).isEqualTo(expected);
    }

    @Test
    void aP95ExactlyAtTheThreshold_meetsIt_oneNanosecondMoreMissesIt() {
        assertThat(result(GALLERY, TWO_SECONDS, 0).passed()).isTrue();
        assertThat(result(GALLERY, TWO_SECONDS + 1, 0).passed()).isFalse();
    }

    @Test
    void anyNon200Response_missesTheNfr_howeverFast() {
        assertThat(result(GALLERY, 1, 1).passed()).isFalse();
    }

    @Test
    void environmentAndDataset_areTwoColumnTables_inTheOrderGiven() {
        PerfReport report = new PerfReport("Run");
        report.environment("CPUs", "12");
        report.environment("JVM", "21");
        report.dataset("Approved testimonials", "500");

        String markdown = report.markdown();

        assertThat(markdown).contains("| CPUs | 12 |\n| JVM | 21 |").contains("| Approved testimonials | 500 |");
        assertThat(markdown.indexOf("## Environment")).isLessThan(markdown.indexOf("## Dataset"));
    }

    @Test
    void results_areListedInTheOrderMeasured_withTheirVerdicts() {
        PerfScenario search =
                new PerfScenario("Search", "NFR-SEARCH-PERFORMANCE", "/api/x?q=a", Duration.ofSeconds(1), false);
        PerfReport report = new PerfReport("Run");
        report.result(result(GALLERY, 150_000_000, 0));
        report.result(result(search, 1_500_000_000, 0));

        String markdown = report.markdown();

        assertThat(markdown).contains(
                "| Gallery page | sequential | 200 | 75.0 | 150.0 | 150.0 | 150.0 | 12.3 | 0 | 2000 | PASS |");
        assertThat(markdown).contains(
                "| Search | sequential | 200 | 750.0 | 1500.0 | 1500.0 | 1500.0 | 12.3 | 0 | 1000 | MISS |");
        assertThat(markdown.indexOf("| Gallery page | sequential")).isLessThan(markdown.indexOf("| Search |"));
        assertThat(markdown).contains("1 of 2 measurements missed their NFR threshold.");
    }

    @Test
    void whenEveryMeasurementPasses_theSummarySaysSo() {
        PerfReport report = new PerfReport("Run");
        report.result(result(GALLERY, 1, 0));

        assertThat(report.markdown()).contains("All 1 measurements met their NFR thresholds.");
    }

    @Test
    void withNoMeasurement_theReportSaysNothingWasMeasured() {
        assertThat(new PerfReport("Run").markdown()).contains("No scenario was measured.");
    }

    @Test
    void scenarioDetails_showTheRequest_andNaForWhatWasNotProbed() {
        PerfReport report = new PerfReport("Run");
        report.profile(new ScenarioProfile(GALLERY, 87L, null));

        assertThat(report.markdown())
                .contains("| Gallery page | `/gallery` | NFR-GALLERY-PERFORMANCE | 2000 | 87 | n/a |");
    }

    @Test
    void pipesInACell_areEscaped_soTheTableKeepsItsColumns() {
        PerfScenario piped =
                new PerfScenario("A|B", "NFR-X", "/api?q=a|b", Duration.ofSeconds(1), false);
        PerfReport report = new PerfReport("Run");
        report.environment("Key|1", "v|2");
        report.profile(new ScenarioProfile(piped, null, 3L));

        assertThat(report.markdown())
                .contains("| Key\\|1 | v\\|2 |")
                .contains("| A\\|B | `/api?q=a\\|b` | NFR-X | 1000 | n/a | 3 |");
    }

    @Test
    void theTitleAndNotes_comeFirst() {
        PerfReport report = new PerfReport("Performance NFR pass");
        report.note("Took 3 min.");

        assertThat(report.markdown()).startsWith("# Performance NFR pass\n\nTook 3 min.\n");
    }
}
