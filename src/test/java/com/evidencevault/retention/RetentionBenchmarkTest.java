package com.evidencevault.retention;

import com.evidencevault.TestcontainersConfiguration;
import com.evidencevault.common.tx.SerializableTx;
import com.evidencevault.retention.LegalHoldService.PlacedHold;
import com.evidencevault.retention.RetentionStrategy.Kind;
import com.evidencevault.support.SqlStates;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

import static com.evidencevault.support.RetentionTestData.deletedAmong;
import static com.evidencevault.support.RetentionTestData.reset;
import static com.evidencevault.support.RetentionTestData.utc;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention strategies under a mixed load: 10k expired evidence across 200 cases, 8 purge threads (batch 100)
 * and 4 threads placing up to 40 case holds while the purge runs. Holds are paced by purge progress (one permit
 * per 2 batches) so they spread over the whole run instead of freezing every case up front.
 * <p>
 * On demand only: {@code ./mvnw test -Dgroups=bench -DexcludedGroups= -Dtest=RetentionBenchmarkTest}.
 * Writes {@code target/bench/retention.md}. Each strategy runs 3 times; the table shows medians.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Tag("bench")
class RetentionBenchmarkTest {

    private static final int CASES = 200;
    private static final int EVIDENCE_PER_CASE = 50;
    private static final int PURGE_THREADS = 8;
    private static final int HOLD_THREADS = 4;
    private static final int MAX_HOLDS = 40;
    private static final int BATCHES_PER_HOLD = 2;
    private static final int BATCH_SIZE = 100;
    private static final int RUNS = 3;
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(3);

    @Autowired
    RetentionStrategies strategies;

    @Autowired
    LegalHoldService holds;

    @Autowired
    SerializableTx serializableTx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NamedParameterJdbcTemplate namedJdbc;

    @Test
    void compareStrategies() throws Exception {
        // -Dbench.strategies=LOCKING,NAIVE to run a subset
        List<Kind> kinds = Arrays.stream(System.getProperty("bench.strategies",
                        "LOCKING,SERIALIZABLE,SINGLE_STATEMENT,NAIVE").split(","))
                .map(String::trim).map(Kind::valueOf).toList();
        StringBuilder table = new StringBuilder("""
                | Strategy | Deleted/s | p95 batch (ms) | p95 hold (ms) | Retries | Failed calls | Frozen deleted |
                |---|---:|---:|---:|---:|---:|---:|
                """);
        for (Kind kind : kinds) {
            List<RunResult> runs = new ArrayList<>();
            for (int i = 0; i < RUNS; i++) {
                runs.add(run(strategies.get(kind)));
            }
            table.append("| %s | %.0f | %.1f | %.1f | %.0f | %.0f | %.0f |%n".formatted(kind,
                    median(runs, RunResult::deletedPerSecond), median(runs, RunResult::p95BatchMillis),
                    median(runs, RunResult::p95HoldMillis), median(runs, RunResult::retries),
                    median(runs, RunResult::failedCalls), median(runs, RunResult::frozenDeleted)));
        }
        String report = """
                ## Retention benchmark
                %d expired evidence across %d cases; %d purge threads (batch %d); %d hold threads, up to %d holds.
                Median of %d runs. Postgres 17 in Testcontainers; JVM %s, %d CPUs.

                %s""".formatted(CASES * EVIDENCE_PER_CASE, CASES, PURGE_THREADS, BATCH_SIZE, HOLD_THREADS,
                MAX_HOLDS, RUNS, Runtime.version(), Runtime.getRuntime().availableProcessors(), table);
        System.out.println(report);
        Path out = Path.of("target", "bench", "retention.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
    }

    private RunResult run(RetentionStrategy strategy) throws Exception {
        reset(jdbc);
        seed();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long retriesBefore = serializableTx.retryCount();
        List<Long> batchNanos = new CopyOnWriteArrayList<>();
        List<Long> holdNanos = new CopyOnWriteArrayList<>();
        Set<UUID> frozen = ConcurrentHashMap.newKeySet();
        AtomicInteger deleted = new AtomicInteger();
        AtomicInteger failedCalls = new AtomicInteger();
        Map<String, Integer> failures = new ConcurrentHashMap<>();
        AtomicInteger batches = new AtomicInteger();
        AtomicInteger holdsPlaced = new AtomicInteger();
        AtomicBoolean purgeDone = new AtomicBoolean();
        Semaphore holdPermits = new Semaphore(0);
        List<UUID> caseIds = new ArrayList<>(jdbc.queryForList(
                "SELECT DISTINCT case_id FROM evidence", UUID.class));
        Collections.shuffle(caseIds);
        long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();

        long start = System.nanoTime();
        List<Future<?>> purgers = new ArrayList<>();
        List<Future<?>> holders = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(PURGE_THREADS + HOLD_THREADS)) {
            for (int t = 0; t < PURGE_THREADS; t++) {
                purgers.add(pool.submit(() -> {
                    while (System.nanoTime() < deadline) {
                        long t0 = System.nanoTime();
                        int n;
                        try {
                            n = strategy.purgeExpired(now, BATCH_SIZE);
                        } catch (RuntimeException e) {
                            failedCalls.incrementAndGet();
                            failures.merge("purge " + SqlStates.of(e), 1, Integer::sum);
                            continue;
                        }
                        batchNanos.add(System.nanoTime() - t0);
                        deleted.addAndGet(n);
                        if (batches.incrementAndGet() % BATCHES_PER_HOLD == 0) {
                            holdPermits.release();
                        }
                        if (n == 0 && remainingCandidates(now) == 0) {
                            return;
                        }
                    }
                    throw new IllegalStateException("Purge did not finish within " + RUN_TIMEOUT);
                }));
            }
            for (int t = 0; t < HOLD_THREADS; t++) {
                holders.add(pool.submit(() -> {
                    while (!purgeDone.get()) {
                        if (!holdPermits.tryAcquire(50, TimeUnit.MILLISECONDS)) {
                            continue;
                        }
                        int index = holdsPlaced.getAndIncrement();
                        if (index >= MAX_HOLDS) {
                            return null;
                        }
                        long t0 = System.nanoTime();
                        try {
                            PlacedHold hold = holds.placeCaseHold(caseIds.get(index), "Benchmark", "bench");
                            holdNanos.add(System.nanoTime() - t0);
                            frozen.addAll(hold.frozenEvidenceIds());
                        } catch (RuntimeException e) {
                            failedCalls.incrementAndGet();
                            failures.merge("hold " + SqlStates.of(e), 1, Integer::sum);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : purgers) {
                f.get();
            }
            purgeDone.set(true);
            for (Future<?> f : holders) {
                f.get();
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        RunResult result = new RunResult(deleted.get() / seconds, p95Millis(batchNanos), p95Millis(holdNanos),
                serializableTx.retryCount() - retriesBefore, failedCalls.get(), deletedAmong(jdbc, frozen).size());
        System.out.printf("[bench] %s: %s, %d deleted, %d holds%n", strategy.kind(), result, deleted.get(),
                holdNanos.size());
        failures.forEach((kind, count) -> System.out.printf("[bench]   %d x %s%n", count, kind));
        assertThat(deleted.get()).isPositive();
        return result;
    }

    private void seed() {
        String tag = "BENCH-" + UUID.randomUUID() + "-";
        jdbc.update("""
                INSERT INTO case_file (id, case_number, title, status)
                SELECT gen_random_uuid(), ? || g, 'Benchmark', 'OPEN' FROM generate_series(1, ?) g
                """, tag, CASES);
        jdbc.update("""
                INSERT INTO evidence (id, case_id, file_name, content_type, size_bytes, status, retain_until)
                SELECT gen_random_uuid(), c.id, 'cam-' || g || '.mp4', 'video/mp4', 1, 'AVAILABLE',
                       ? - (g || ' minutes')::interval
                FROM case_file c CROSS JOIN generate_series(1, ?) g
                WHERE c.case_number LIKE ?
                """, utc(Instant.now().minus(Duration.ofDays(1))), EVIDENCE_PER_CASE, tag + "%");
        jdbc.execute("ANALYZE evidence");
    }

    private int remainingCandidates(Instant now) {
        Integer n = namedJdbc.queryForObject("SELECT count(*) FROM evidence e WHERE " + RetentionSql.CANDIDATE_FILTER,
                Map.of("now", utc(now)), Integer.class);
        return n == null ? 0 : n;
    }

    private static double p95Millis(List<Long> nanos) {
        if (nanos.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        return sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1) / 1e6;
    }

    private static double median(List<RunResult> runs, ToDoubleFunction<RunResult> metric) {
        double[] values = runs.stream().mapToDouble(metric).sorted().toArray();
        return values[values.length / 2];
    }

    private record RunResult(double deletedPerSecond, double p95BatchMillis, double p95HoldMillis, double retries,
                             double failedCalls, double frozenDeleted) {
    }
}
