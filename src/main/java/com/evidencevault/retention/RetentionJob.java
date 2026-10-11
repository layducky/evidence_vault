package com.evidencevault.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

/**
 * Scheduled purge. Registered by {@link RetentionConfig} only when {@code evidence-vault.retention.enabled=true}.
 * Each batch is its own transaction (inside the strategy), so a run never holds locks for long.
 */
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionStrategies strategies;
    private final RetentionProperties properties;
    private final Clock clock;

    public RetentionJob(RetentionStrategies strategies, RetentionProperties properties, Clock clock) {
        this.strategies = strategies;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${evidence-vault.retention.interval:PT1M}")
    void scheduledRun() {
        purge();
    }

    /**
     * Runs batches until one comes back short (nothing left to purge right now), at most
     * {@code max-batches-per-run} per call so one run cannot keep going forever under constant inflow.
     */
    public int purge() {
        RetentionStrategy strategy = strategies.active();
        int batchSize = properties.batchSize();
        int total = 0;
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            int deleted = strategy.purgeExpired(clock.instant(), batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("Retention ({}) soft-deleted {} evidence", strategy.kind(), total);
        }
        return total;
    }
}
