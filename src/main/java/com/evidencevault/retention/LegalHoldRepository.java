package com.evidencevault.retention;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LegalHoldRepository extends JpaRepository<LegalHold, UUID> {

    List<LegalHold> findByCaseIdAndReleasedAtIsNullOrderByPlacedAt(UUID caseId);
}
