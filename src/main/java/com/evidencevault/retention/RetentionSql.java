package com.evidencevault.retention;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SQL shared by all retention strategies, so they differ only in locking and isolation. */
final class RetentionSql {

    /**
     * Expired, live, not held, and the case has no active hold. The hold check is part of the candidate query
     * so that rows of held cases never fill a batch: otherwise a batch made only of held-case rows would delete
     * nothing, and the job would stop before reaching anything else (starvation).
     */
    static final String CANDIDATE_FILTER = """
            e.retain_until < :now AND e.deleted_at IS NULL AND e.legal_hold = false
            AND NOT EXISTS (SELECT 1 FROM legal_hold h WHERE h.case_id = e.case_id AND h.released_at IS NULL)
            """;

    static final String SELECT_CANDIDATES =
            "SELECT e.id FROM evidence e WHERE " + CANDIDATE_FILTER + " ORDER BY e.retain_until, e.id LIMIT :n";

    /**
     * Re-checks {@code deleted_at} and {@code legal_hold} on the row itself, so an evidence-level hold placed
     * concurrently is respected (same row: Postgres re-evaluates this WHERE after waiting for the row lock).
     * It does <b>not</b> re-check case-level holds; that is the write skew each strategy must deal with.
     * <p>
     * The subquery takes the row locks in id order. A plain {@code UPDATE … WHERE id IN (…)} locks rows in
     * scan order, while {@code placeCaseHold} share-locks a case's evidence in id order: two orders, so the
     * two could deadlock (seen as 40P01 under the stress test).
     */
    private static final String SOFT_DELETE = """
            UPDATE evidence SET deleted_at = :now, status = 'DELETED', version = version + 1
            WHERE id IN (SELECT id FROM evidence WHERE id IN (:ids) AND deleted_at IS NULL AND legal_hold = false
                         ORDER BY id FOR UPDATE)
            """;

    private RetentionSql() {
    }

    static List<UUID> selectCandidates(NamedParameterJdbcTemplate jdbc, Instant now, int batchSize) {
        return jdbc.queryForList(SELECT_CANDIDATES, Map.of("now", Timestamps.utc(now), "n", batchSize), UUID.class);
    }

    static int softDelete(NamedParameterJdbcTemplate jdbc, Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.update(SOFT_DELETE, Map.of("ids", ids, "now", Timestamps.utc(now)));
    }
}
