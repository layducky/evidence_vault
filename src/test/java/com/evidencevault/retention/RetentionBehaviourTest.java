package com.evidencevault.retention;

import com.evidencevault.TestcontainersConfiguration;
import com.evidencevault.retention.RetentionStrategy.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.evidencevault.support.RetentionTestData.addEvidence;
import static com.evidencevault.support.RetentionTestData.deletedAmong;
import static com.evidencevault.support.RetentionTestData.isDeleted;
import static com.evidencevault.support.RetentionTestData.newCase;
import static com.evidencevault.support.RetentionTestData.reset;
import static org.assertj.core.api.Assertions.assertThat;

/** Single-threaded rules every strategy must follow, including the experiment C. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RetentionBehaviourTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Instant EXPIRED = NOW.minus(Duration.ofDays(1));

    @Autowired
    RetentionStrategies strategies;

    @Autowired
    LegalHoldService holds;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        reset(jdbc);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void expiredEvidenceIsDeleted(Kind kind) {
        List<UUID> expired = addEvidence(jdbc, newCase(jdbc), 3, EXPIRED);

        assertThat(strategies.get(kind).purgeExpired(NOW, 10)).isEqualTo(3);

        for (UUID id : expired) {
            assertThat(jdbc.queryForObject("SELECT status FROM evidence WHERE id = ?", String.class, id))
                    .isEqualTo("DELETED");
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM evidence WHERE id = ?", OffsetDateTime.class, id)
                    .toInstant())
                    .isEqualTo(NOW);
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void notYetExpiredEvidenceIsKept(Kind kind) {
        UUID caseId = newCase(jdbc);
        List<UUID> future = addEvidence(jdbc, caseId, 2, NOW.plus(Duration.ofDays(1)));
        UUID exactlyNow = addEvidence(jdbc, caseId, 1, NOW).getFirst();
        UUID noRetention = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evidence (id, case_id, file_name, content_type, size_bytes, status)
                VALUES (?, ?, 'forever.mp4', 'video/mp4', 1, 'AVAILABLE')
                """, noRetention, caseId);

        assertThat(strategies.get(kind).purgeExpired(NOW, 10)).isZero();

        assertThat(deletedAmong(jdbc, List.of(future.get(0), future.get(1), exactlyNow, noRetention))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void evidenceOfHeldCaseIsKeptUntilReleased(Kind kind) {
        UUID caseId = newCase(jdbc);
        List<UUID> expired = addEvidence(jdbc, caseId, 2, EXPIRED);
        UUID holdId = holds.placeCaseHold(caseId, "Litigation", "counsel").hold().getId();

        assertThat(strategies.get(kind).purgeExpired(NOW, 10)).isZero();
        assertThat(deletedAmong(jdbc, expired)).isEmpty();

        holds.releaseCaseHold(caseId, holdId);

        assertThat(strategies.get(kind).purgeExpired(NOW, 10)).isEqualTo(2);
        assertThat(deletedAmong(jdbc, expired)).containsExactlyInAnyOrderElementsOf(expired);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void heldEvidenceIsKept(Kind kind) {
        List<UUID> expired = addEvidence(jdbc, newCase(jdbc), 3, EXPIRED);
        holds.holdEvidence(expired.get(1));

        assertThat(strategies.get(kind).purgeExpired(NOW, 10)).isEqualTo(2);

        assertThat(isDeleted(jdbc, expired.get(1))).isFalse();
        assertThat(deletedAmong(jdbc, expired)).containsExactlyInAnyOrder(expired.get(0), expired.get(2));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void batchSizeIsRespected(Kind kind) {
        List<UUID> expired = addEvidence(jdbc, newCase(jdbc), 7, EXPIRED);
        RetentionStrategy strategy = strategies.get(kind);

        assertThat(strategy.purgeExpired(NOW, 3)).isEqualTo(3);
        assertThat(strategy.purgeExpired(NOW, 3)).isEqualTo(3);
        assertThat(strategy.purgeExpired(NOW, 3)).isEqualTo(1);
        assertThat(strategy.purgeExpired(NOW, 3)).isZero();
        assertThat(deletedAmong(jdbc, expired)).hasSize(7);
    }

    /** B-4: rows of a held case expire first, but must not fill every batch and block everything behind them. */
    @ParameterizedTest
    @EnumSource(Kind.class)
    void heldCaseDoesNotStarveLaterEvidence(Kind kind) {
        UUID heldCase = newCase(jdbc);
        List<UUID> oldestButHeld = addEvidence(jdbc, heldCase, 3, NOW.minus(Duration.ofDays(10)));
        holds.placeCaseHold(heldCase, "Litigation", "counsel");
        List<UUID> later = addEvidence(jdbc, newCase(jdbc), 2, EXPIRED);

        assertThat(strategies.get(kind).purgeExpired(NOW, 3)).isEqualTo(2);

        assertThat(deletedAmong(jdbc, later)).containsExactlyInAnyOrderElementsOf(later);
        assertThat(deletedAmong(jdbc, oldestButHeld)).isEmpty();
    }
}
