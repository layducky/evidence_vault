package com.evidencevault.common.tx;

import com.evidencevault.common.tx.SerializableTx.Backoff;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SerializableTxBackoffTest {

    private final Backoff backoff = new Backoff(5, Duration.ofMillis(10), Duration.ofMillis(200));

    @Test
    void delayIsWithinRangeGrowsAndIsCapped() {
        Random random = new Random(42);
        long[] lowerMillis = {10, 20, 40, 80, 160, 200, 200, 200};
        Duration previousMin = Duration.ZERO;
        for (int attempt = 0; attempt < lowerMillis.length; attempt++) {
            Duration lower = Duration.ofMillis(lowerMillis[attempt]);
            Duration upper = Duration.ofMillis(Math.min(200, 2 * lowerMillis[attempt]));
            Duration min = Duration.ofDays(1);
            for (int sample = 0; sample < 1_000; sample++) {
                Duration delay = backoff.delay(attempt, random);
                assertThat(delay).isBetween(lower, upper);
                min = delay.compareTo(min) < 0 ? delay : min;
            }
            assertThat(min).isGreaterThanOrEqualTo(previousMin);
            previousMin = min;
        }
    }

    @Test
    void hugeAttemptNumbersDoNotOverflow() {
        assertThat(backoff.delay(10_000, new Random(1))).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void jitterActuallyVaries() {
        Random random = new Random(7);
        long distinct = IntStream.range(0, 100).mapToObj(i -> backoff.delay(2, random)).distinct().count();
        assertThat(distinct).isGreaterThan(1);
    }
}
