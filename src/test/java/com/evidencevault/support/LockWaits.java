package com.evidencevault.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Waits until other sessions are blocked on a lock, by polling {@code pg_stat_activity}.
 * <p>
 * Interleavings are still created with latches; this only answers "has that thread reached the lock wait yet?",
 * which no latch can signal because the thread is parked inside the database. The 5 ms pause is a poll interval,
 * not a guess at how long an interleaving takes.
 */
public final class LockWaits {

    private static final String BLOCKED_SESSIONS = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND datname = current_database() AND pid <> pg_backend_pid()
            """;

    private LockWaits() {
    }

    public static void awaitBlocked(JdbcTemplate jdbc, int sessions, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int blocked = 0;
        while (System.nanoTime() < deadline) {
            blocked = blockedSessions(jdbc);
            if (blocked >= sessions) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        throw new AssertionError("Expected " + sessions + " session(s) blocked on a lock within " + timeout
                + ", saw " + blocked);
    }

    public static int blockedSessions(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(BLOCKED_SESSIONS, Integer.class);
        return count == null ? 0 : count;
    }
}
