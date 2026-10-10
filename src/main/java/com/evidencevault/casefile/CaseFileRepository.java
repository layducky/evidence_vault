package com.evidencevault.casefile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CaseFileRepository extends JpaRepository<CaseFile, UUID> {
}
