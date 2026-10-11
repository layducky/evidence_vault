package com.evidencevault.retention;

import com.evidencevault.retention.RetentionStrategy.Kind;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code evidence-vault.retention.*}. {@code enabled} only controls the scheduled job; the admin endpoint and
 * the strategies are always available.
 */
@ConfigurationProperties("evidence-vault.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("locking") Kind strategy,
        @DefaultValue("500") int batchSize,
        @DefaultValue("PT1M") Duration interval,
        @DefaultValue("100") int maxBatchesPerRun) {
}
