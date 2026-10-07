package com.iitm.beacon.perf;

/**
 * A scenario measured one way ({@code mode}, e.g. sequential or concurrent):
 * its latencies in nanoseconds, how many responses were not a 200, and the
 * request rate the client achieved.
 */
record ScenarioResult(
        PerfScenario scenario, String mode, LatencySummary latency, int non200, double requestsPerSecond) {

    /** Meets the NFR: every response a 200, and the p95 at most the threshold. */
    boolean passed() {
        return non200 == 0 && latency.p95() <= scenario.threshold().toNanos();
    }
}
