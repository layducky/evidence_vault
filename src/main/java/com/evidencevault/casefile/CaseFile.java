package com.evidencevault.casefile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "case_file")
public class CaseFile {

    @Id
    private UUID id;

    @Column(name = "case_number", nullable = false, unique = true)
    private String caseNumber;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus status;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CaseFile() {
    }

    public CaseFile(String caseNumber, String title) {
        this.id = UUID.randomUUID();
        this.caseNumber = caseNumber;
        this.title = title;
        this.status = CaseStatus.OPEN;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCaseNumber() {
        return caseNumber;
    }

    public String getTitle() {
        return title;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
