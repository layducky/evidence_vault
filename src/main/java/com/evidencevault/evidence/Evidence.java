package com.evidencevault.evidence;

import com.evidencevault.casefile.CaseFile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evidence")
public class Evidence {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false)
    private CaseFile caseFile;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EvidenceStatus status;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    @Column(name = "retain_until")
    private Instant retainUntil;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Evidence() {
    }

    public Evidence(CaseFile caseFile, String fileName, String contentType, long sizeBytes,
                    Instant retainUntil) {
        this.id = UUID.randomUUID();
        this.caseFile = caseFile;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.retainUntil = retainUntil;
        this.status = EvidenceStatus.PENDING_UPLOAD;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public CaseFile getCaseFile() {
        return caseFile;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public EvidenceStatus getStatus() {
        return status;
    }

    public boolean isLegalHold() {
        return legalHold;
    }

    public Instant getRetainUntil() {
        return retainUntil;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
