package com.evidencevault.evidence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/cases/{caseId}/evidence")
public class EvidenceController {

    private final EvidenceService service;

    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceResponse register(@PathVariable UUID caseId,
                                     @Valid @RequestBody RegisterEvidenceRequest request) {
        return EvidenceResponse.from(service.register(caseId, request.fileName(),
                request.contentType(), request.sizeBytes(), request.retainUntil()));
    }

    @GetMapping
    public List<EvidenceResponse> list(@PathVariable UUID caseId) {
        return service.listByCase(caseId).stream().map(EvidenceResponse::from).toList();
    }

    public record RegisterEvidenceRequest(
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 127) String contentType,
            @PositiveOrZero long sizeBytes,
            @Future Instant retainUntil) {
    }

    public record EvidenceResponse(UUID id, String fileName, String contentType, long sizeBytes,
                                   String sha256, EvidenceStatus status, boolean legalHold,
                                   Instant retainUntil, long version, Instant createdAt) {

        static EvidenceResponse from(Evidence e) {
            return new EvidenceResponse(e.getId(), e.getFileName(), e.getContentType(),
                    e.getSizeBytes(), e.getSha256(), e.getStatus(), e.isLegalHold(),
                    e.getRetainUntil(), e.getVersion(), e.getCreatedAt());
        }
    }
}
