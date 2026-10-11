package com.evidencevault.retention;

import com.evidencevault.TestcontainersConfiguration;
import com.evidencevault.casefile.CaseFileService;
import com.evidencevault.common.web.ConflictException;
import com.evidencevault.evidence.EvidenceService;
import com.evidencevault.support.Concurrently;
import com.evidencevault.support.Concurrently.Outcome;
import com.evidencevault.support.SqlStates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceHoldApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    LegalHoldService holds;

    @Autowired
    CaseFileService caseFiles;

    @Autowired
    EvidenceService evidence;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void holdAndRelease() {
        UUID id = newEvidence(newCase());
        String path = "/api/evidence/" + id + "/legal-hold";

        assertThat(mvc.post().uri(path)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(isHeld(id)).isTrue();
        assertThat(mvc.post().uri(path)).hasStatus(HttpStatus.NO_CONTENT); // idempotent

        assertThat(mvc.delete().uri(path)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(isHeld(id)).isFalse();

        assertThat(mvc.post().uri("/api/evidence/" + UUID.randomUUID() + "/legal-hold"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void holdDeletedEvidence() {
        UUID id = newEvidence(newCase());
        jdbc.update("UPDATE evidence SET deleted_at = now(), status = 'DELETED' WHERE id = ?", id);

        assertThat(mvc.post().uri("/api/evidence/" + id + "/legal-hold")).hasStatus(HttpStatus.CONFLICT);
        assertThat(isHeld(id)).isFalse();
    }

    /**
     * Same-row race: the soft-delete below deliberately ignores {@code legal_hold}, so only the CHECK constraint
     * and Postgres' re-check of {@code deleted_at IS NULL} after a row-lock wait protect the invariant.
     */
    @Test
    void concurrentHoldAndDelete() throws Exception {
        UUID caseId = newCase();
        List<UUID> ids = new ArrayList<>();
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID id = newEvidence(caseId);
            ids.add(id);
            tasks.add(() -> {
                holds.holdEvidence(id);
                return "held";
            });
            tasks.add(() -> {
                jdbc.update("UPDATE evidence SET deleted_at = now(), status = 'DELETED' "
                        + "WHERE id = ? AND deleted_at IS NULL", id);
                return "deleted";
            });
        }

        List<Outcome<String>> outcomes = Concurrently.run(tasks);

        assertThat(outcomes).filteredOn(o -> !o.succeeded()).allSatisfy(o -> assertThat(o.error())
                .satisfiesAnyOf(
                        e -> assertThat(e).isInstanceOf(ConflictException.class),
                        e -> assertThat(e).isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(ex -> assertThat(SqlStates.of(ex)).isEqualTo("23514"))));
        for (UUID id : ids) {
            Boolean[] state = jdbc.queryForObject(
                    "SELECT legal_hold, deleted_at IS NOT NULL FROM evidence WHERE id = ?",
                    (rs, n) -> new Boolean[]{rs.getBoolean(1), rs.getBoolean(2)}, id);
            assertThat(state[0] ^ state[1]).as("evidence %s is exactly one of held / deleted", id).isTrue();
        }
    }

    private boolean isHeld(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT legal_hold FROM evidence WHERE id = ?",
                Boolean.class, id));
    }

    private UUID newCase() {
        return caseFiles.create("C-" + UUID.randomUUID(), "Evidence hold").getId();
    }

    private UUID newEvidence(UUID caseId) {
        return evidence.register(caseId, "cam.mp4", "video/mp4", 1,
                Instant.now().plus(30, ChronoUnit.DAYS)).getId();
    }
}

