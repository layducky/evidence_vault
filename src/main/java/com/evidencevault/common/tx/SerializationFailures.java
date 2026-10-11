package com.evidencevault.common.tx;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Classifies exceptions for {@link SerializableTx}. Only SQLState {@code 40001} (serialization failure) is
 * retryable: the transaction lost a race that the database detected, and a fresh attempt sees the winner's data.
 * Constraint violations ({@code 23xxx}) and deadlocks ({@code 40P01}) are not: retrying would hide a real bug.
 */
public final class SerializationFailures {

    public static final String SERIALIZATION_FAILURE = "40001";

    private SerializationFailures() {
    }

    /** True if an {@link SQLException} with SQLState 40001 appears anywhere in the cause chain. */
    public static boolean isRetryable(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql && SERIALIZATION_FAILURE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
