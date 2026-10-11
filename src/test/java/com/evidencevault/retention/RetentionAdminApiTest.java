package com.evidencevault.retention;

import com.evidencevault.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.evidencevault.support.RetentionTestData.addEvidence;
import static com.evidencevault.support.RetentionTestData.deletedAmong;
import static com.evidencevault.support.RetentionTestData.newCase;
import static com.evidencevault.support.RetentionTestData.reset;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RetentionAdminApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    RetentionStrategies strategies;

    @Autowired
    RetentionProperties properties;

    @Autowired
    Clock clock;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        reset(jdbc);
    }

    @Test
    void runsOneBatch() {
        List<UUID> expired = addEvidence(jdbc, newCase(jdbc), 2, Instant.now().minus(Duration.ofDays(1)));

        MvcTestResult result = mvc.post().uri("/api/admin/retention/run").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.strategy").isEqualTo("LOCKING");
        assertThat(result).bodyJson().extractingPath("$.deleted").isEqualTo(2);
        assertThat(deletedAmong(jdbc, expired)).hasSize(2);
    }

    /** The job bean is disabled in tests; build one to check that it loops batches until one comes back short. */
    @Test
    void jobRunsBatchesUntilOneIsShort() {
        List<UUID> expired = addEvidence(jdbc, newCase(jdbc), 7, Instant.now().minus(Duration.ofDays(1)));
        RetentionProperties smallBatches = new RetentionProperties(true, properties.strategy(), 3,
                properties.interval(), 100);

        int deleted = new RetentionJob(strategies, smallBatches, clock).purge();

        assertThat(deleted).isEqualTo(7);
        assertThat(deletedAmong(jdbc, expired)).hasSize(7);
    }

    @Test
    void jobStopsAtMaxBatchesPerRun() {
        addEvidence(jdbc, newCase(jdbc), 7, Instant.now().minus(Duration.ofDays(1)));
        RetentionProperties capped = new RetentionProperties(true, properties.strategy(), 2,
                properties.interval(), 2);

        assertThat(new RetentionJob(strategies, capped, clock).purge()).isEqualTo(4);
    }
}
