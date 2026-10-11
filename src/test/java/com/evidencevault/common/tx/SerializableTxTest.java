package com.evidencevault.common.tx;

import com.evidencevault.TestcontainersConfiguration;
import com.evidencevault.common.tx.SerializableTx.Backoff;
import com.evidencevault.support.Concurrently;
import com.evidencevault.support.Concurrently.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SerializableTxTest {

    @Autowired
    SerializableTx serializableTx;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void scratchTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS scratch_on_call (doctor TEXT PRIMARY KEY, on_call BOOLEAN NOT NULL)");
        jdbc.update("DELETE FROM scratch_on_call");
        jdbc.update("INSERT INTO scratch_on_call VALUES ('alice', true), ('bob', true)");
    }

    /**
     * Classic write skew, invariant "at least one doctor stays on call". Each doctor checks that two are on call,
     * then goes off call. The latch makes both read before either writes, so SSI must abort one of them.
     */
    @Test
    void retriesRealSerializationFailure() throws Exception {
        long retriesBefore = serializableTx.retryCount();
        CountDownLatch bothRead = new CountDownLatch(2);

        List<Outcome<Integer>> outcomes = Concurrently.run(List.of(
                goOffCall("alice", bothRead), goOffCall("bob", bothRead)));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        Integer onCall = jdbc.queryForObject("SELECT count(*) FROM scratch_on_call WHERE on_call", Integer.class);
        assertThat(onCall).isEqualTo(1);
        assertThat(serializableTx.retryCount() - retriesBefore).isGreaterThanOrEqualTo(1);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        SerializableTx tx = new SerializableTx(transactionManager,
                new Backoff(3, Duration.ofMillis(1), Duration.ofMillis(2)));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<RuntimeException> last = new AtomicReference<>();

        Throwable thrown = catchThrowable(() -> tx.execute(() -> {
            RuntimeException failure = new IllegalStateException("attempt " + calls.incrementAndGet(),
                    new SQLException("could not serialize access", "40001"));
            last.set(failure);
            throw failure;
        }));

        assertThat(calls).hasValue(3);
        assertThat(thrown).isSameAs(last.get());
        assertThat(tx.retryCount()).isEqualTo(2);
    }

    @Test
    void refusesToRunInsideTransaction() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.executeWithoutResult(status -> assertThatThrownBy(() -> serializableTx.execute(() -> 1))
                .isInstanceOf(IllegalStateException.class));
    }

    private Callable<Integer> goOffCall(String doctor, CountDownLatch bothRead) {
        return () -> serializableTx.execute(() -> {
            Integer onCall = jdbc.queryForObject(
                    "SELECT count(*) FROM scratch_on_call WHERE on_call", Integer.class);
            bothRead.countDown();
            awaitQuietly(bothRead); // on a retry the latch is already open
            if (onCall != null && onCall >= 2) {
                jdbc.update("UPDATE scratch_on_call SET on_call = false WHERE doctor = ?", doctor);
            }
            return onCall;
        });
    }

    private static void awaitQuietly(CountDownLatch latch) {
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
