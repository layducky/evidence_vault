package com.evidencevault.retention;

import com.evidencevault.TestcontainersConfiguration;
import com.evidencevault.common.tx.SerializableTx;
import com.evidencevault.retention.LegalHoldService.PlacedHold;
import com.evidencevault.support.Concurrently;
import com.evidencevault.support.Concurrently.Outcome;
import com.evidencevault.support.SqlStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.evidencevault.support.RetentionTestData.addEvidence;
import static com.evidencevault.support.RetentionTestData.deletedAmong;
import static com.evidencevault.support.RetentionTestData.newCase;
import static com.evidencevault.support.RetentionTestData.reset;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many rounds of purges and holds racing on the same cases. Invariant per round: no evidence frozen by a
 * successful hold is deleted. Each round: 4 cases x 10 expired evidence, 8 purges (batch 5) and 8 holds on
 * random cases, all released at once.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RetentionStressTest {

    private static final int ROUNDS = 50;
    private static final int CASES = 4;
    private static final int EVIDENCE_PER_CASE = 10;
    private static final int PURGES = 8;
    private static final int HOLDS = 8;
    private static final int BATCH_SIZE = 5;

    @Autowired
    LegalHoldService holds;

    @Autowired
    NaiveRetentionService naive;

    @Autowired
    LockingRetentionService locking;

    @Autowired
    SerializableRetentionService serializable;

    @Autowired
    SerializableTx serializableTx;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        reset(jdbc);
    }

    @Test
    void locking_neverDeletesFrozenEvidence() throws Exception {
        StressResult result = stress(locking);

        assertThat(result.errors()).as("no deadlocks or exhausted retries").isEmpty();
        assertThat(result.violatingRounds()).isZero();
        assertThat(result.deleted()).as("retention still made progress").isPositive();
    }

    @Test
    void serializable_neverDeletesFrozenEvidence() throws Exception {
        StressResult result = stress(serializable);

        // M-12: under this much contention B may run out of attempts. That is its measured cost, reported above;
        // any other error (deadlock, constraint) would be a bug.
        assertThat(result.errors()).as("only exhausted serialization retries")
                .allSatisfy(e -> assertThat(SqlStates.of(e)).isEqualTo("40001"));
        assertThat(result.violatingRounds()).isZero();
        assertThat(result.deleted()).as("retention still made progress").isPositive();
    }

    /** On demand only: {@code ./mvnw test -Dgroups=naive -DexcludedGroups= -Dtest=RetentionStressTest}. */
    @Test
    @Tag("naive")
    void naive_deletesFrozenEvidence_failureRate() throws Exception {
        StressResult result = stress(naive);

        assertThat(result.violatingRounds()).as("the naive version should lose frozen evidence").isPositive();
    }

    private StressResult stress(RetentionStrategy strategy) throws InterruptedException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long retriesBefore = serializableTx.retryCount();
        int violatingRounds = 0;
        int deleted = 0;
        List<Throwable> errors = new ArrayList<>();

        for (int round = 0; round < ROUNDS; round++) {
            List<UUID> cases = new ArrayList<>();
            for (int c = 0; c < CASES; c++) {
                UUID caseId = newCase(jdbc);
                addEvidence(jdbc, caseId, EVIDENCE_PER_CASE, now.minus(Duration.ofDays(1)));
                cases.add(caseId);
            }
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < PURGES; i++) {
                tasks.add(() -> strategy.purgeExpired(now, BATCH_SIZE));
            }
            for (int i = 0; i < HOLDS; i++) {
                UUID caseId = cases.get(ThreadLocalRandom.current().nextInt(CASES));
                tasks.add(() -> holds.placeCaseHold(caseId, "Stress", "counsel"));
            }

            List<Outcome<Object>> outcomes = Concurrently.run(tasks);

            Set<UUID> frozen = new HashSet<>();
            for (Outcome<Object> outcome : outcomes) {
                if (!outcome.succeeded()) {
                    errors.add(outcome.error());
                } else if (outcome.value() instanceof PlacedHold hold) {
                    frozen.addAll(hold.frozenEvidenceIds());
                } else if (outcome.value() instanceof Integer purged) {
                    deleted += purged;
                }
            }
            if (!deletedAmong(jdbc, frozen).isEmpty()) {
                violatingRounds++;
            }
        }

        StressResult result = new StressResult(violatingRounds, deleted, errors,
                serializableTx.retryCount() - retriesBefore);
        System.out.printf("[stress] %s: %d/%d rounds violated the invariant, %d evidence deleted, %d retries, "
                        + "%d errors%n", strategy.kind(), violatingRounds, ROUNDS, deleted, result.retries(),
                errors.size());
        errors.stream()
                .collect(Collectors.groupingBy(e -> e.getClass().getSimpleName() + " SQLState=" + SqlStates.of(e),
                        TreeMap::new, Collectors.counting()))
                .forEach((kind, count) -> System.out.println("[stress]   " + count + " x " + kind));
        return result;
    }

    private record StressResult(int violatingRounds, int deleted, List<Throwable> errors, long retries) {
    }
}
