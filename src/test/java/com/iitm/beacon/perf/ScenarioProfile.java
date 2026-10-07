package com.iitm.beacon.perf;

/**
 * What one request of a scenario costs besides time: the SQL statements
 * Hibernate prepared for it, and how many rows its filter matched (the
 * page's {@code totalElements}); {@code null} where not probed.
 */
record ScenarioProfile(PerfScenario scenario, Long sqlStatements, Long matchedRows) {
}
