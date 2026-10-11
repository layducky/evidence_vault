package com.evidencevault.retention;

import java.time.Instant;

/**
 * One batch of the retention purge: soft-delete up to {@code batchSize} evidence whose {@code retain_until} is
 * before {@code now}, unless the evidence or its case is under legal hold. Each call is one transaction.
 * The implementations differ only in how they keep the hold check valid until the delete commits.
 */
public interface RetentionStrategy {

    Kind kind();

    /** Returns the number of evidence rows soft-deleted. */
    int purgeExpired(Instant now, int batchSize);

    enum Kind {
        NAIVE,
        LOCKING,
        SERIALIZABLE,
        SINGLE_STATEMENT
    }
}
