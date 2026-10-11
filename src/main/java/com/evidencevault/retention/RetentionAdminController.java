package com.evidencevault.retention;

import com.evidencevault.retention.RetentionStrategy.Kind;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/** Runs one retention batch on demand (demo and manual checks). Unauthenticated until Phase 5. */
@RestController
@RequestMapping("/api/admin/retention")
public class RetentionAdminController {

    private final RetentionStrategies strategies;
    private final RetentionProperties properties;
    private final Clock clock;

    public RetentionAdminController(RetentionStrategies strategies, RetentionProperties properties, Clock clock) {
        this.strategies = strategies;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/run")
    public RunResponse run() {
        RetentionStrategy strategy = strategies.active();
        return new RunResponse(strategy.kind(), strategy.purgeExpired(clock.instant(), properties.batchSize()));
    }

    public record RunResponse(Kind strategy, int deleted) {
    }
}
