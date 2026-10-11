package com.evidencevault.retention;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/evidence/{id}/legal-hold")
public class EvidenceHoldController {

    private final LegalHoldService service;

    public EvidenceHoldController(LegalHoldService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hold(@PathVariable UUID id) {
        service.holdEvidence(id);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID id) {
        service.releaseEvidence(id);
    }
}
