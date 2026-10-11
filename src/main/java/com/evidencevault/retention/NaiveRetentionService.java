package com.evidencevault.retention;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * <b>Deliberately wrong.</b> Kept to reproduce the write skew (docs/concurrency/01-write-skew-legal-hold.md).
 * <p>
 * READ COMMITTED, no locks: it checks "the case has no active hold" when selecting candidates, then deletes.
 * Nothing stops a hold from being placed and committed between the check and the delete, and the hold and the
 * delete write different rows ({@code legal_hold} vs {@code evidence}), so no row lock or constraint notices.
 */
@Service
public class NaiveRetentionService implements RetentionStrategy {

    private final NamedParameterJdbcTemplate jdbc;

    /** Test hook: runs between the hold check and the delete. Set it on the target bean, not the proxy. */
    volatile Runnable pauseAfterCheck = () -> {
    };

    public NaiveRetentionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Kind kind() {
        return Kind.NAIVE;
    }

    @Override
    @Transactional
    public int purgeExpired(Instant now, int batchSize) {
        List<UUID> candidates = RetentionSql.selectCandidates(jdbc, now, batchSize);
        pauseAfterCheck.run();
        return RetentionSql.softDelete(jdbc, candidates, now);
    }
}
