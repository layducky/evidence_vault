package com.evidencevault.evidence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidenceRepository extends JpaRepository<Evidence, UUID> {

    List<Evidence> findByCaseFileIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID caseId);
}
