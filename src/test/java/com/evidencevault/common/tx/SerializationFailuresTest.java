package com.evidencevault.common.tx;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class SerializationFailuresTest {

    @Test
    void detectsSerializationFailureDeepInCauseChain() {
        Throwable nested = new RuntimeException("tx",
                new IllegalStateException("jpa",
                        new RuntimeException("jdbc", new SQLException("could not serialize access", "40001"))));

        assertThat(SerializationFailures.isRetryable(nested)).isTrue();
    }

    @Test
    void uniqueViolationIsNotRetryable() {
        assertThat(SerializationFailures.isRetryable(
                new RuntimeException(new SQLException("duplicate key", "23505")))).isFalse();
    }

    @Test
    void deadlockIsNotRetryable() {
        assertThat(SerializationFailures.isRetryable(
                new RuntimeException(new SQLException("deadlock detected", "40P01")))).isFalse();
    }

    @Test
    void errorsWithoutSqlStateAreNotRetryable() {
        assertThat(SerializationFailures.isRetryable(new IllegalStateException("boom"))).isFalse();
        assertThat(SerializationFailures.isRetryable(null)).isFalse();
    }
}
