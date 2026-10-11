package com.evidencevault.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Data for retention tests. Retention purges <i>every</i> expired row in the database, so these tests cannot
 * rely on unique keys alone: they start from {@link #reset} and assert only on rows they created.
 */
public final class RetentionTestData {

    private RetentionTestData() {
    }

    /** Removes all evidence and holds. Cases stay; nothing else references evidence. */
    public static void reset(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE legal_hold, evidence");
    }

    public static UUID newCase(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO case_file (id, case_number, title, status) VALUES (?, ?, 'Retention', 'OPEN')",
                id, "C-" + id);
        return id;
    }

    /** Inserts {@code count} live evidence with the given {@code retain_until}; returns their ids in order. */
    public static List<UUID> addEvidence(JdbcTemplate jdbc, UUID caseId, int count, Instant retainUntil) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO evidence (id, case_id, file_name, content_type, size_bytes, status, retain_until)
                    VALUES (?, ?, ?, 'video/mp4', 1, 'AVAILABLE', ?)
                    """, id, caseId, "cam-" + i + ".mp4", utc(retainUntil));
            ids.add(id);
        }
        return ids;
    }

    public static boolean isDeleted(JdbcTemplate jdbc, UUID evidenceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM evidence WHERE id = ?", Boolean.class, evidenceId));
    }

    /** The subset of {@code evidenceIds} that is soft-deleted. */
    public static Set<UUID> deletedAmong(JdbcTemplate jdbc, Collection<UUID> evidenceIds) {
        Set<UUID> deleted = new HashSet<>();
        for (UUID id : evidenceIds) {
            if (isDeleted(jdbc, id)) {
                deleted.add(id);
            }
        }
        return deleted;
    }

    public static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
