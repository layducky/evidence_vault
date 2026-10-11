package com.evidencevault.retention;

import com.evidencevault.casefile.CaseFileService;
import com.evidencevault.common.tx.SerializableTx;
import com.evidencevault.common.web.ConflictException;
import com.evidencevault.common.web.NotFoundException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LegalHoldService {

    private final NamedParameterJdbcTemplate jdbc;
    private final LegalHoldRepository holds;
    private final CaseFileService caseFiles;
    private final SerializableTx serializableTx;
    private final Clock clock;

    /** Test hook: runs after the hold is inserted, before commit. Set it on the target bean, not the proxy. */
    volatile Runnable pauseBeforeCommit = () -> {
    };

    public LegalHoldService(NamedParameterJdbcTemplate jdbc, LegalHoldRepository holds, CaseFileService caseFiles,
                            SerializableTx serializableTx, Clock clock) {
        this.jdbc = jdbc;
        this.holds = holds;
        this.caseFiles = caseFiles;
        this.serializableTx = serializableTx;
        this.clock = clock;
    }

    /**
     * Places a case-level hold and returns the evidence it froze. Invariant: no evidence in
     * {@code frozenEvidenceIds} is ever deleted.
     * <p>
     * Transaction: one SERIALIZABLE transaction via {@link SerializableTx}, retried on 40001. Three mechanisms,
     * each needed by a different retention strategy:
     * <ol>
     * <li>{@code case_file FOR NO KEY UPDATE} conflicts with the {@code FOR SHARE} that locking retention (A)
     * takes on the case, so a hold and a purge of the same case run one after the other.</li>
     * <li>The frozen evidence is read {@code FOR SHARE}. This transaction's snapshot was taken when its first
     * statement started, i.e. <i>before</i> it waited for the case lock. If retention deleted a row in the
     * meantime, the locking read of that row fails with 40001 and the retry reads a fresh snapshot, so a deleted
     * row is never reported as frozen. The share locks also block deletion of the frozen rows until commit.</li>
     * <li>SERIALIZABLE lets Postgres SSI detect the read/write cycle with serializable retention (B), which takes
     * no locks at all. SSI only sees transactions that both run at SERIALIZABLE.</li>
     * </ol>
     * Holds are rare and important, so paying for all three is acceptable.
     */
    public PlacedHold placeCaseHold(UUID caseId, String reason, String placedBy) {
        return serializableTx.execute(() -> {
            Map<String, Object> params = Map.of("caseId", caseId);
            List<UUID> lockedCase = jdbc.queryForList(
                    "SELECT id FROM case_file WHERE id = :caseId FOR NO KEY UPDATE", params, UUID.class);
            if (lockedCase.isEmpty()) {
                throw new NotFoundException("Case not found: " + caseId);
            }
            List<UUID> frozen = jdbc.queryForList("""
                    SELECT id FROM evidence WHERE case_id = :caseId AND deleted_at IS NULL ORDER BY id FOR SHARE
                    """, params, UUID.class);
            LegalHold hold = holds.saveAndFlush(new LegalHold(caseId, reason, placedBy, clock.instant()));
            pauseBeforeCommit.run();
            return new PlacedHold(hold, frozen);
        });
    }

    @Transactional(readOnly = true)
    public List<LegalHold> listActive(UUID caseId) {
        caseFiles.get(caseId);
        return holds.findByCaseIdAndReleasedAtIsNullOrderByPlacedAt(caseId);
    }

    /**
     * READ COMMITTED, one atomic UPDATE. Releasing can only allow more deletion, so it needs no coordination
     * with retention: a purge that still sees the hold just keeps the evidence until its next run.
     */
    @Transactional
    public void releaseCaseHold(UUID caseId, UUID holdId) {
        Map<String, Object> params = Map.of("id", holdId, "caseId", caseId, "now", Timestamps.utc(clock.instant()));
        int released = jdbc.update("""
                UPDATE legal_hold SET released_at = :now, version = version + 1
                WHERE id = :id AND case_id = :caseId AND released_at IS NULL
                """, params);
        if (released == 0) {
            boolean exists = !jdbc.queryForList(
                    "SELECT id FROM legal_hold WHERE id = :id AND case_id = :caseId", params, UUID.class).isEmpty();
            throw exists
                    ? new ConflictException("Legal hold already released: " + holdId)
                    : new NotFoundException("Legal hold not found: " + holdId);
        }
    }

    /**
     * Evidence-level hold. READ COMMITTED, one atomic UPDATE on the evidence row. Hold and delete touch the
     * <i>same</i> row, so no write skew is possible: a concurrent soft-delete either commits first (Postgres
     * re-checks {@code deleted_at IS NULL} after waiting, 0 rows, 409) or runs after us and is rejected by
     * {@code chk_evidence_not_held_and_deleted}. Idempotent: holding held evidence succeeds.
     */
    @Transactional
    public void holdEvidence(UUID evidenceId) {
        setEvidenceHold(evidenceId, true);
    }

    /** READ COMMITTED, one atomic UPDATE. Deleted evidence cannot be held, so releasing it is a 409 too. */
    @Transactional
    public void releaseEvidence(UUID evidenceId) {
        setEvidenceHold(evidenceId, false);
    }

    private void setEvidenceHold(UUID evidenceId, boolean held) {
        Map<String, Object> params = Map.of("id", evidenceId, "held", held);
        int updated = jdbc.update("""
                UPDATE evidence SET legal_hold = :held, version = version + 1
                WHERE id = :id AND deleted_at IS NULL
                """, params);
        if (updated == 0) {
            boolean exists = !jdbc.queryForList("SELECT id FROM evidence WHERE id = :id", params, UUID.class).isEmpty();
            throw exists
                    ? new ConflictException("Evidence already deleted: " + evidenceId)
                    : new NotFoundException("Evidence not found: " + evidenceId);
        }
    }

    public record PlacedHold(LegalHold hold, List<UUID> frozenEvidenceIds) {
    }
}
