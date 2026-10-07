package com.iitm.beacon.perf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The performance pass's Markdown report (target/perf/results.md): notes,
 * the environment and the dataset as two-column tables, then each
 * scenario's request and per-request cost, then every measurement with its
 * p50/p95/p99/max in milliseconds and its verdict against the NFR. Rows keep
 * the order they were added in. Collected over a whole test class, one
 * thread at a time.
 */
final class PerfReport {

    private static final String NOT_PROBED = "n/a";

    private final String title;
    private final List<String> notes = new ArrayList<>();
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final Map<String, String> dataset = new LinkedHashMap<>();
    private final List<ScenarioProfile> profiles = new ArrayList<>();
    private final List<ScenarioResult> results = new ArrayList<>();

    PerfReport(String title) {
        this.title = title;
    }

    void note(String note) {
        notes.add(note);
    }

    void environment(String key, String value) {
        environment.put(key, value);
    }

    void dataset(String key, String value) {
        dataset.put(key, value);
    }

    void profile(ScenarioProfile profile) {
        profiles.add(profile);
    }

    void result(ScenarioResult result) {
        results.add(result);
    }

    List<ScenarioResult> results() {
        return List.copyOf(results);
    }

    /** {@code nanos} as milliseconds with one decimal, rounded half up: 1 050 000 ns is "1.1". */
    static String millis(long nanos) {
        long tenths = (nanos + 50_000) / 100_000;
        return tenths / 10 + "." + tenths % 10;
    }

    String markdown() {
        StringBuilder md = new StringBuilder("# ").append(title).append("\n\n");
        notes.forEach(note -> md.append(note).append('\n'));
        md.append('\n');

        md.append("## Environment\n\n");
        keyValueTable(md, environment);
        md.append("## Dataset\n\n");
        keyValueTable(md, dataset);

        md.append("## Scenarios\n\n");
        md.append("| Scenario | Request | NFR | p95 threshold (ms) | SQL statements per request | Rows matched |\n");
        md.append("|---|---|---|---:|---:|---:|\n");
        for (ScenarioProfile profile : profiles) {
            PerfScenario scenario = profile.scenario();
            row(md, scenario.name(), "`" + scenario.path() + "`", scenario.nfr(),
                    String.valueOf(scenario.threshold().toMillis()),
                    orNotProbed(profile.sqlStatements()), orNotProbed(profile.matchedRows()));
        }
        md.append('\n');

        md.append("## Results\n\n");
        md.append("| Scenario | Mode | Requests | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) | req/s | Non-200"
                + " | p95 threshold (ms) | Result |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (ScenarioResult result : results) {
            LatencySummary latency = result.latency();
            row(md, result.scenario().name(), result.mode(), String.valueOf(latency.count()),
                    millis(latency.p50()), millis(latency.p95()), millis(latency.p99()), millis(latency.max()),
                    String.format(Locale.ROOT, "%.1f", result.requestsPerSecond()), String.valueOf(result.non200()),
                    String.valueOf(result.scenario().threshold().toMillis()), result.passed() ? "PASS" : "MISS");
        }
        md.append('\n').append(summary()).append('\n');
        return md.toString();
    }

    private String summary() {
        if (results.isEmpty()) {
            return "No scenario was measured.";
        }
        long missed = results.stream().filter(result -> !result.passed()).count();
        return missed == 0
                ? "All " + results.size() + " measurements met their NFR thresholds."
                : missed + " of " + results.size() + " measurements missed their NFR threshold.";
    }

    private static void keyValueTable(StringBuilder md, Map<String, String> rows) {
        md.append("| | |\n|---|---|\n");
        rows.forEach((key, value) -> row(md, key, value));
        md.append('\n');
    }

    private static void row(StringBuilder md, String... cells) {
        md.append('|');
        for (String cell : cells) {
            md.append(' ').append(cell.replace("|", "\\|")).append(" |");
        }
        md.append('\n');
    }

    private static String orNotProbed(Long value) {
        return value == null ? NOT_PROBED : value.toString();
    }
}
