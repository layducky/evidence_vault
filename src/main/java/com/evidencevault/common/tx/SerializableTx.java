package com.evidencevault.common.tx;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Runs a callback in its own SERIALIZABLE transaction and retries the <b>whole</b> transaction when Postgres
 * reports a serialization failure (SQLState 40001).
 * <p>
 * At SERIALIZABLE, Postgres may abort a transaction that is part of a dangerous read/write pattern; the
 * application is expected to retry it. Retrying a single statement would be wrong, because the aborted
 * transaction's earlier reads are what made it unsafe. For the same reason this refuses to run inside an
 * existing transaction: once a statement fails, that outer transaction is aborted, so no retry inside it can
 * succeed. Callbacks must build everything they write (entities, ids) inside the callback, so each attempt
 * starts clean.
 */
@Component
public class SerializableTx {

    private final TransactionTemplate template;
    private final Backoff backoff;
    private final AtomicLong retries = new AtomicLong();

    @Autowired
    public SerializableTx(PlatformTransactionManager transactionManager) {
        this(transactionManager, Backoff.DEFAULT);
    }

    public SerializableTx(PlatformTransactionManager transactionManager, Backoff backoff) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.backoff = backoff;
    }

    public <T> T execute(Supplier<T> callback) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "SerializableTx must start its own transaction; it was called inside an active one");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return template.execute(status -> callback.get());
            } catch (RuntimeException e) {
                if (!SerializationFailures.isRetryable(e) || attempt + 1 >= backoff.maxAttempts()) {
                    throw e;
                }
                retries.incrementAndGet();
                pause(backoff.delay(attempt, ThreadLocalRandom.current()), e);
            }
        }
    }

    /** Total retries since startup, across all callers. Read before and after to measure one operation. */
    public long retryCount() {
        return retries.get();
    }

    private static void pause(Duration delay, RuntimeException cause) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }

    /**
     * Exponential backoff with jitter. For attempt {@code n} (0-based), {@code d = min(cap, base * 2^n)} and the
     * delay is {@code d} plus a random jitter in {@code [0, d]}, never above {@code cap}. The jitter spreads out
     * transactions that conflicted with each other, so they do not collide again on the next attempt.
     */
    public record Backoff(int maxAttempts, Duration base, Duration cap) {

        public static final Backoff DEFAULT = new Backoff(5, Duration.ofMillis(10), Duration.ofMillis(200));

        public Backoff {
            if (maxAttempts < 1 || base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
                throw new IllegalArgumentException("Invalid backoff: " + maxAttempts + ", " + base + ", " + cap);
            }
        }

        public Duration delay(int attempt, RandomGenerator random) {
            long capNanos = cap.toNanos();
            long d = base.toNanos();
            for (int i = 0; i < attempt && d < capNanos; i++) {
                d *= 2;
            }
            d = Math.min(d, capNanos);
            return Duration.ofNanos(Math.min(capNanos, d + random.nextLong(d + 1)));
        }
    }
}
