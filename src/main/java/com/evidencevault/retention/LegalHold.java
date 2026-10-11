package com.evidencevault.retention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A case-level legal hold. While {@code releasedAt} is null, no evidence of the case may be purged.
 * Holds are never deleted; releasing one sets {@code releasedAt}.
 */
@Entity
@Table(name = "legal_hold")
public class LegalHold {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "placed_by", nullable = false, length = 128)
    private String placedBy;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Version
    private long version;

    protected LegalHold() {
    }

    public LegalHold(UUID caseId, String reason, String placedBy, Instant placedAt) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.reason = reason;
        this.placedBy = placedBy;
        this.placedAt = placedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getReason() {
        return reason;
    }

    public String getPlacedBy() {
        return placedBy;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public long getVersion() {
        return version;
    }
}
