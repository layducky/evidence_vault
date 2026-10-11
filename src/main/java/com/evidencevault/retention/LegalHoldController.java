package com.evidencevault.retention;

import com.evidencevault.retention.LegalHoldService.PlacedHold;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/cases/{caseId}/legal-holds")
public class LegalHoldController {

    private final LegalHoldService service;

    public LegalHoldController(LegalHoldService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PlacedHoldResponse> place(@PathVariable UUID caseId,
                                                    @Valid @RequestBody PlaceHoldRequest request) {
        PlacedHold placed = service.placeCaseHold(caseId, request.reason(), request.placedBy());
        return ResponseEntity.created(URI.create("/api/cases/" + caseId + "/legal-holds/" + placed.hold().getId()))
                .body(PlacedHoldResponse.from(placed));
    }

    @GetMapping
    public List<HoldResponse> listActive(@PathVariable UUID caseId) {
        return service.listActive(caseId).stream().map(HoldResponse::from).toList();
    }

    @DeleteMapping("/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID caseId, @PathVariable UUID holdId) {
        service.releaseCaseHold(caseId, holdId);
    }

    public record PlaceHoldRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 128) String placedBy) {
    }

    public record HoldResponse(UUID id, UUID caseId, String reason, String placedBy, Instant placedAt) {

        static HoldResponse from(LegalHold h) {
            return new HoldResponse(h.getId(), h.getCaseId(), h.getReason(), h.getPlacedBy(), h.getPlacedAt());
        }
    }

    public record PlacedHoldResponse(UUID id, UUID caseId, String reason, String placedBy, Instant placedAt,
                                     List<UUID> frozenEvidenceIds) {

        static PlacedHoldResponse from(PlacedHold placed) {
            LegalHold h = placed.hold();
            return new PlacedHoldResponse(h.getId(), h.getCaseId(), h.getReason(), h.getPlacedBy(),
                    h.getPlacedAt(), placed.frozenEvidenceIds());
        }
    }
}
