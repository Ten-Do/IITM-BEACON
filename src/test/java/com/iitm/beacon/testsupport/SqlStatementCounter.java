package com.iitm.beacon.testsupport;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Counts the SQL statements one action costs, through the Hibernate session
 * factory's statistics: cleared and switched on just before the action,
 * read right after it, and then put back as they were. The counts are the
 * action's own as long as nothing else uses the same session factory
 * meanwhile — the test suite runs one test at a time, and the performance
 * pass sends only that one request.
 *
 * <p>A statement is one JDBC statement Hibernate prepared: every query, and
 * — no JDBC batching is configured — every single-row INSERT, UPDATE and
 * DELETE of an entity. {@link Statements#withoutEntityWrites()} leaves those
 * row writes out, for an action that saves entities: what is left is what
 * reading them cost.
 */
public final class SqlStatementCounter {

    private final Statistics statistics;

    public SqlStatementCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** An action whose statements are counted; it may throw, like an HTTP request. */
    @FunctionalInterface
    public interface Action {

        void run() throws Exception;
    }

    /**
     * What one action cost, from Hibernate's statistics.
     *
     * @param prepared every JDBC statement prepared — what "SQL statements" means everywhere else
     * @param entityLoads entities built from query results
     * @param entityFetches lazy entity references initialized by a statement of their own
     * @param collectionFetches lazy collections initialized by a statement of their own
     * @param queries HQL, criteria and native queries executed
     * @param entityInserts entity rows inserted, one statement each
     * @param entityUpdates entity rows updated, one statement each
     * @param entityDeletes entity rows deleted, one statement each
     */
    public record Statements(
            long prepared,
            long entityLoads,
            long entityFetches,
            long collectionFetches,
            long queries,
            long entityInserts,
            long entityUpdates,
            long entityDeletes) {

        /** {@link #prepared()} minus the entity row writes: the statements that loaded what was written. */
        public long withoutEntityWrites() {
            return prepared - entityInserts - entityUpdates - entityDeletes;
        }
    }

    /**
     * Runs {@code action} with the statistics on and returns what it cost.
     * Not reentrant: a count inside another count's action resets it.
     *
     * @throws Exception whatever {@code action} throws, after the statistics are put back
     */
    public Statements count(Action action) throws Exception {
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.clear();
        statistics.setStatisticsEnabled(true);
        try {
            action.run();
            return new Statements(
                    statistics.getPrepareStatementCount(),
                    statistics.getEntityLoadCount(),
                    statistics.getEntityFetchCount(),
                    statistics.getCollectionFetchCount(),
                    statistics.getQueryExecutionCount(),
                    statistics.getEntityInsertCount(),
                    statistics.getEntityUpdateCount(),
                    statistics.getEntityDeleteCount());
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
            statistics.clear();
        }
    }
}
