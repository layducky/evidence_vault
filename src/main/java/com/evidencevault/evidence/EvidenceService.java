package com.evidencevault.evidence;

import com.evidencevault.casefile.CaseFile;
import com.evidencevault.casefile.CaseFileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EvidenceService {

    private final EvidenceRepository repository;
    private final CaseFileService caseFileService;

    public EvidenceService(EvidenceRepository repository, CaseFileService caseFileService) {
        this.repository = repository;
        this.caseFileService = caseFileService;
    }

    @Transactional
    public Evidence register(UUID caseId, String fileName, String contentType, long sizeBytes,
                             Instant retainUntil) {
        CaseFile caseFile = caseFileService.get(caseId);
        return repository.save(new Evidence(caseFile, fileName, contentType, sizeBytes, retainUntil));
    }

    @Transactional(readOnly = true)
    public List<Evidence> listByCase(UUID caseId) {
        caseFileService.get(caseId);
        return repository.findByCaseFileIdAndDeletedAtIsNullOrderByCreatedAtDesc(caseId);
    }
}
