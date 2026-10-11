package com.evidencevault.retention;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Fix A: pessimistic locking at READ COMMITTED.
 * <p>
 * The case row is the shared "thing" both sides must agree on. Retention locks each candidate's case
 * {@code FOR SHARE}; {@code LegalHoldService.placeCaseHold} locks it {@code FOR NO KEY UPDATE}. Those modes
 * conflict, so per case either the hold commits first (and the hold read below, a new statement, sees it) or
 * the purge commits first (and the hold, reading the evidence {@code FOR SHARE}, gets 40001 and retries with a
 * snapshot in which the evidence is already deleted). Purges of the same case share the lock and do not block
 * each other; the evidence rows are split between them with {@code SKIP LOCKED}.
 * <p>
 * Lock order is case → evidence, the same as {@code placeCaseHold}, so the two cannot deadlock.
 */
@Service
public class LockingRetentionService implements RetentionStrategy {

    private final NamedParameterJdbcTemplate jdbc;

    /** Test hook: runs after the hold check, before the delete. Set it on the target bean, not the proxy. */
    volatile Runnable pauseAfterCheck = () -> {
    };

    public LockingRetentionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Kind kind() {
        return Kind.LOCKING;
    }

    /** READ COMMITTED; locks: candidate cases FOR SHARE (by id), then evidence FOR UPDATE SKIP LOCKED. */
    @Override
    @Transactional
    public int purgeExpired(Instant now, int batchSize) {
        Map<String, Object> params = new HashMap<>(Map.of("now", Timestamps.utc(now), "n", batchSize));

        // Unlocked read: only decides which cases to lock. Everything is re-checked under the locks below.
        List<UUID> candidates = new ArrayList<>();
        Set<UUID> caseIds = new TreeSet<>();
        jdbc.query("SELECT e.id, e.case_id FROM evidence e WHERE " + RetentionSql.CANDIDATE_FILTER
                + " ORDER BY e.retain_until, e.id LIMIT :n", params, rs -> {
            candidates.add(rs.getObject("id", UUID.class));
            caseIds.add(rs.getObject("case_id", UUID.class));
        });
        if (candidates.isEmpty()) {
            return 0;
        }
        params.put("ids", candidates);
        params.put("caseIds", caseIds);

        // ORDER BY id: rows are locked in sort order, so concurrent purges take case locks in the same order.
        jdbc.queryForList("SELECT id FROM case_file WHERE id IN (:caseIds) ORDER BY id FOR SHARE", params, UUID.class);

        Map<UUID, UUID> lockedEvidenceToCase = new HashMap<>();
        jdbc.query("""
                SELECT id, case_id FROM evidence
                WHERE id IN (:ids) AND retain_until < :now AND deleted_at IS NULL AND legal_hold = false
                FOR UPDATE SKIP LOCKED
                """, params, rs -> {
            lockedEvidenceToCase.put(rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class));
        });

        // A new statement, taken after the case locks were granted: sees every hold committed before that.
        Set<UUID> heldCases = new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT case_id FROM legal_hold WHERE case_id IN (:caseIds) AND released_at IS NULL
                """, params, UUID.class));
        lockedEvidenceToCase.values().removeIf(heldCases::contains);

        pauseAfterCheck.run();
        return RetentionSql.softDelete(jdbc, lockedEvidenceToCase.keySet(), now);
    }
}
