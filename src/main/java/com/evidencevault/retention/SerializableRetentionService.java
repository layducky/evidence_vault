package com.evidencevault.retention;

import com.evidencevault.common.tx.SerializableTx;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fix B: the naive algorithm, unchanged, at SERIALIZABLE.
 * <p>
 * No explicit locks. Postgres SSI tracks what each serializable transaction read and wrote. Purge reads "no
 * active hold" and writes evidence; {@code placeCaseHold} reads that evidence and writes a hold. That is a
 * read/write cycle, so one of the two is aborted with 40001 and {@link SerializableTx} retries it from scratch.
 * This only works because {@code placeCaseHold} also runs at SERIALIZABLE: SSI ignores other isolation levels.
 */
@Service
public class SerializableRetentionService implements RetentionStrategy {

    private final NamedParameterJdbcTemplate jdbc;
    private final SerializableTx serializableTx;

    /** Test hook: runs between the hold check and the delete, again on every retry. */
    volatile Runnable pauseAfterCheck = () -> {
    };

    public SerializableRetentionService(NamedParameterJdbcTemplate jdbc, SerializableTx serializableTx) {
        this.jdbc = jdbc;
        this.serializableTx = serializableTx;
    }

    @Override
    public Kind kind() {
        return Kind.SERIALIZABLE;
    }

    /** SERIALIZABLE via {@link SerializableTx}, retried on 40001. */
    @Override
    public int purgeExpired(Instant now, int batchSize) {
        return serializableTx.execute(() -> {
            List<UUID> candidates = RetentionSql.selectCandidates(jdbc, now, batchSize);
            pauseAfterCheck.run();
            return RetentionSql.softDelete(jdbc, candidates, now);
        });
    }
}
