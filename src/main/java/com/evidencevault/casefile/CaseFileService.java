package com.evidencevault.casefile;

import com.evidencevault.common.web.ConflictException;
import com.evidencevault.common.web.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CaseFileService {

    private final CaseFileRepository repository;

    public CaseFileService(CaseFileRepository repository) {
        this.repository = repository;
    }

    /**
     * The unique constraint on case_number is the source of truth. An existsBy check
     * first would be check-then-act: two concurrent requests could both pass it.
     */
    @Transactional
    public CaseFile create(String caseNumber, String title) {
        try {
            return repository.saveAndFlush(new CaseFile(caseNumber, title));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Case number already exists: " + caseNumber);
        }
    }

    @Transactional(readOnly = true)
    public CaseFile get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Case not found: " + id));
    }
}
