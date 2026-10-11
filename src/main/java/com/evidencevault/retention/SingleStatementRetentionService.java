package com.evidencevault.retention;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * <b>Experiment C, still wrong.</b> Check and delete in one atomic UPDATE, at READ COMMITTED.
 * <p>
 * "One statement" does not mean "serializable". The statement reads with the snapshot taken when it starts.
 * If it then waits for a row lock (here: the {@code FOR SHARE} a hold in progress takes on the evidence) and
 * the hold commits meanwhile, Postgres only re-checks rows that were <i>updated</i>; this row was merely locked,
 * so the statement continues with its old snapshot, in which the hold does not exist, and deletes the row.
 * No SKIP LOCKED on purpose: skipping would hide the anomaly in this interleaving, not fix it.
 * <p>
 * The innermost query picks the oldest candidates; the middle one locks them in id order (the same order as
 * {@code placeCaseHold}, so the two cannot deadlock) and re-checks the row's own columns after a lock wait.
 */
@Service
public class SingleStatementRetentionService implements RetentionStrategy {

    private static final String PURGE = """
            UPDATE evidence SET deleted_at = :now, status = 'DELETED', version = version + 1
            WHERE id IN (
                SELECT l.id FROM evidence l
                WHERE l.id IN (SELECT e.id FROM evidence e WHERE %s ORDER BY e.retain_until, e.id LIMIT :n)
                  AND l.deleted_at IS NULL AND l.legal_hold = false
                ORDER BY l.id FOR UPDATE OF l)
            """.formatted(RetentionSql.CANDIDATE_FILTER);

    private final NamedParameterJdbcTemplate jdbc;

    public SingleStatementRetentionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Kind kind() {
        return Kind.SINGLE_STATEMENT;
    }

    /** READ COMMITTED, one statement; row locks taken by its subquery. */
    @Override
    @Transactional
    public int purgeExpired(Instant now, int batchSize) {
        return jdbc.update(PURGE, Map.of("now", Timestamps.utc(now), "n", batchSize));
    }
}
