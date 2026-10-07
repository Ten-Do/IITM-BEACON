package com.iitm.beacon.perf;

import java.time.Duration;

/**
 * One measured request: a GET of {@code path}, as the admin when {@code
 * asAdmin}, whose p95 must stay within {@code threshold} ({@code nfr}'s
 * Response Measure, docs/nfr.md).
 */
record PerfScenario(String name, String nfr, String path, Duration threshold, boolean asAdmin) {
}
