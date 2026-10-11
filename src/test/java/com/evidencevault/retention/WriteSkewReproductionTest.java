package com.evidencevault.retention;

import com.evidencevault.TestcontainersConfiguration;
import com.evidencevault.common.tx.SerializableTx;
import com.evidencevault.retention.LegalHoldService.PlacedHold;
import com.evidencevault.support.LockWaits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.evidencevault.support.RetentionTestData.addEvidence;
import static com.evidencevault.support.RetentionTestData.isDeleted;
import static com.evidencevault.support.RetentionTestData.newCase;
import static com.evidencevault.support.RetentionTestData.reset;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic interleavings of "place a case hold" against "purge expired evidence" (Phase 1).
 * Invariant: evidence listed in {@code frozenEvidenceIds} of a successful hold is never deleted.
 * Hooks are set on the target beans (not the Spring proxies) and must tolerate being run again on a retry.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WriteSkewReproductionTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Runnable NO_OP = () -> {
    };

    @Autowired
    LegalHoldService holds;

    @Autowired
    NaiveRetentionService naive;

    @Autowired
    LockingRetentionService locking;

    @Autowired
    SerializableRetentionService serializable;

    @Autowired
    SingleStatementRetentionService singleStatement;

    @Autowired
    SerializableTx serializableTx;

    @Autowired
    JdbcTemplate jdbc;

    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final CountDownLatch paused = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);
    private UUID caseId;
    private UUID evidence;

    @BeforeEach
    void setUp() {
        reset(jdbc);
        caseId = newCase(jdbc);
        evidence = addEvidence(jdbc, caseId, 1, NOW.minus(Duration.ofDays(1))).getFirst();
    }

    @AfterEach
    void tearDown() {
        proceed.countDown();
        target(naive).pauseAfterCheck = NO_OP;
        target(locking).pauseAfterCheck = NO_OP;
        target(serializable).pauseAfterCheck = NO_OP;
        target(holds).pauseBeforeCommit = NO_OP;
        threads.shutdownNow();
    }

    /**
     * Retention checks "no hold" and pauses; the hold is placed and committed (nothing blocks it: the purge holds
     * no locks), freezing the evidence; retention resumes and deletes it.
     */
    @Test
    void naive_holdPlacedDuringRetention_reproducesAnomaly() throws Exception {
        target(naive).pauseAfterCheck = pauseHere();

        Future<Integer> purge = async(() -> naive.purgeExpired(NOW, 10));
        awaitPaused();
        PlacedHold hold = holds.placeCaseHold(caseId, "Litigation", "counsel");
        proceed.countDown();

        assertThat(purge.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(hold.frozenEvidenceIds()).containsExactly(evidence);
        assertThat(isDeleted(jdbc, evidence)).as("frozen evidence was deleted: the write skew").isTrue();
    }

    /**
     * Retention holds the case FOR SHARE, so the hold waits. Its snapshot predates the delete, so it would see the
     * evidence as live (B-1) - but its FOR SHARE read of the deleted row raises 40001, and the retry sees the truth.
     */
    @Test
    void fixA_holdPlacedDuringRetention_preventsAnomaly() throws Exception {
        target(locking).pauseAfterCheck = pauseHere();
        long retriesBefore = serializableTx.retryCount();

        Future<Integer> purge = async(() -> locking.purgeExpired(NOW, 10));
        awaitPaused();
        Future<PlacedHold> hold = async(() -> holds.placeCaseHold(caseId, "Litigation", "counsel"));
        LockWaits.awaitBlocked(jdbc, 1, TIMEOUT);
        assertThat(hold).isNotDone();
        proceed.countDown();

        assertThat(purge.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(1);
        PlacedHold placed = hold.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(isDeleted(jdbc, evidence)).isTrue();
        assertThat(placed.frozenEvidenceIds()).as("a deleted row must not be reported as frozen").isEmpty();
        assertThat(serializableTx.retryCount() - retriesBefore).isGreaterThanOrEqualTo(1);
    }

    /**
     * Same interleaving as the naive test, both sides SERIALIZABLE. The hold commits first; SSI then aborts the
     * purge (read "no hold" + the hold read the evidence it deletes = a cycle), and the retry sees the hold.
     */
    @Test
    void fixB_holdPlacedDuringRetention_preventsAnomaly() throws Exception {
        target(serializable).pauseAfterCheck = pauseHere();
        long retriesBefore = serializableTx.retryCount();

        Future<Integer> purge = async(() -> serializable.purgeExpired(NOW, 10));
        awaitPaused();
        PlacedHold hold = holds.placeCaseHold(caseId, "Litigation", "counsel");
        proceed.countDown();

        assertThat(purge.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isZero();
        assertThat(hold.frozenEvidenceIds()).containsExactly(evidence);
        assertThat(isDeleted(jdbc, evidence)).isFalse();
        assertThat(serializableTx.retryCount() - retriesBefore).isGreaterThanOrEqualTo(1);
    }

    /**
     * The hold is inserted but not committed. The single UPDATE starts (its snapshot has no hold), waits for the
     * hold's share lock on the evidence, and after the hold commits deletes the row anyway: the row was locked,
     * not updated, so nothing is re-checked. Atomic is not serializable.
     */
    @Test
    void singleStatement_holdCommittedWhileUpdateWaits_reproducesAnomaly() throws Exception {
        target(holds).pauseBeforeCommit = pauseHere();

        Future<PlacedHold> hold = async(() -> holds.placeCaseHold(caseId, "Litigation", "counsel"));
        awaitPaused();
        Future<Integer> purge = async(() -> singleStatement.purgeExpired(NOW, 10));
        LockWaits.awaitBlocked(jdbc, 1, TIMEOUT);
        proceed.countDown();

        PlacedHold placed = hold.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(purge.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(placed.frozenEvidenceIds()).containsExactly(evidence);
        assertThat(isDeleted(jdbc, evidence)).as("frozen evidence was deleted by one atomic statement").isTrue();
    }

    /** Signals {@link #paused} and waits for {@link #proceed}; on a retry both latches are already open. */
    private Runnable pauseHere() {
        return () -> {
            paused.countDown();
            try {
                if (!proceed.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test never released the paused transaction");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
    }

    private void awaitPaused() throws InterruptedException {
        assertThat(paused.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("transaction reached the hook").isTrue();
    }

    private <T> Future<T> async(Callable<T> task) {
        return threads.submit(task);
    }

    private static <T> T target(T bean) {
        return AopTestUtils.getTargetObject(bean);
    }
}
