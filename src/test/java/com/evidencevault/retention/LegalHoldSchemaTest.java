package com.evidencevault.retention;

import com.evidencevault.TestcontainersConfiguration;
import com.evidencevault.casefile.CaseFileService;
import com.evidencevault.support.SqlStates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LegalHoldSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CaseFileService caseFiles;

    @Autowired
    LegalHoldRepository holds;

    @Test
    void migrationValidates() {
        // Context startup ran Flyway V2 and Hibernate's schema validation against the LegalHold mapping.
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);
        assertThat(holds.count()).isNotNegative();
    }

    @Test
    void checkRejectsHeldAndDeleted() {
        UUID caseId = caseFiles.create("C-" + UUID.randomUUID(), "Schema").getId();
        UUID evidenceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evidence (id, case_id, file_name, content_type, size_bytes, status, deleted_at)
                VALUES (?, ?, 'cam.mp4', 'video/mp4', 1, 'DELETED', now())
                """, evidenceId, caseId);

        assertThatThrownBy(() -> jdbc.update("UPDATE evidence SET legal_hold = true WHERE id = ?", evidenceId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(SqlStates.of(e)).isEqualTo("23514"));
    }
}

