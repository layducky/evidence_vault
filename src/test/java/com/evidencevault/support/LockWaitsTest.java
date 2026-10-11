package com.evidencevault.support;

import com.evidencevault.TestcontainersConfiguration;
import com.evidencevault.casefile.CaseFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LockWaitsTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    CaseFileService caseFiles;

    @Test
    void detectsSessionBlockedOnRowLock() throws Exception {
        UUID caseId = caseFiles.create("C-" + UUID.randomUUID(), "Lock wait").getId();
        String lockRow = "SELECT id FROM case_file WHERE id = ? FOR UPDATE";
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject(lockRow, UUID.class, caseId);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s ->
                jdbc.queryForObject(lockRow, UUID.class, caseId)));

        LockWaits.awaitBlocked(jdbc, 1, Duration.ofSeconds(10));
        assertThat(waiter).isNotDone();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        waiter.get(10, TimeUnit.SECONDS);
        assertThat(LockWaits.blockedSessions(jdbc)).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
