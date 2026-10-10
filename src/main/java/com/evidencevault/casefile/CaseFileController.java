package com.evidencevault.casefile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/cases")
public class CaseFileController {

    private final CaseFileService service;

    public CaseFileController(CaseFileService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CaseResponse> create(@Valid @RequestBody CreateCaseRequest request) {
        CaseFile created = service.create(request.caseNumber(), request.title());
        return ResponseEntity.created(URI.create("/api/cases/" + created.getId()))
                .body(CaseResponse.from(created));
    }

    @GetMapping("/{id}")
    public CaseResponse get(@PathVariable UUID id) {
        return CaseResponse.from(service.get(id));
    }

    public record CreateCaseRequest(
            @NotBlank @Size(max = 64) String caseNumber,
            @NotBlank @Size(max = 255) String title) {
    }

    public record CaseResponse(UUID id, String caseNumber, String title, CaseStatus status,
                               long version, Instant createdAt) {

        static CaseResponse from(CaseFile c) {
            return new CaseResponse(c.getId(), c.getCaseNumber(), c.getTitle(), c.getStatus(),
                    c.getVersion(), c.getCreatedAt());
        }
    }
}
