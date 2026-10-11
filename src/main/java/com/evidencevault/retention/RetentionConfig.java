package com.evidencevault.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetentionProperties.class)
class RetentionConfig {

    /** Tests set {@code enabled=false} so the scheduler cannot purge rows in the middle of an interleaving. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty(name = "evidence-vault.retention.enabled", matchIfMissing = true)
    static class Scheduling {

        @Bean
        RetentionJob retentionJob(RetentionStrategies strategies, RetentionProperties properties, Clock clock) {
            return new RetentionJob(strategies, properties, clock);
        }
    }
}
