package com.evidencevault.retention;

import com.evidencevault.TestcontainersConfiguration;
import com.evidencevault.casefile.CaseFileService;
import com.evidencevault.evidence.EvidenceService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LegalHoldApiTest {

    private static final String HOLD_BODY = """
            {"reason": "Litigation 2026-17", "placedBy": "counsel@example.com"}
            """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    CaseFileService caseFiles;

    @Autowired
    EvidenceService evidence;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void placeHold() {
        UUID caseId = newCase();
        UUID live1 = newEvidence(caseId);
        UUID live2 = newEvidence(caseId);
        UUID deleted = newEvidence(caseId);
        jdbc.update("UPDATE evidence SET deleted_at = now(), status = 'DELETED' WHERE id = ?", deleted);

        MvcTestResult result = placeHold(caseId, HOLD_BODY);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).headers().containsHeader("Location");
        assertThat(result).bodyJson().extractingPath("$.caseId").isEqualTo(caseId.toString());
        assertThat(result).bodyJson().extractingPath("$.placedBy").isEqualTo("counsel@example.com");
        assertThat(result).bodyJson().extractingPath("$.frozenEvidenceIds").asArray()
                .containsExactlyInAnyOrder(live1.toString(), live2.toString());
    }

    @Test
    void unknownCase() {
        MvcTestResult result = placeHold(UUID.randomUUID(), HOLD_BODY);

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(404);
    }

    @Test
    void releaseTwice() throws Exception {
        UUID caseId = newCase();
        String holdId = holdIdOf(placeHold(caseId, HOLD_BODY));
        String path = "/api/cases/" + caseId + "/legal-holds/" + holdId;

        assertThat(mvc.delete().uri(path)).hasStatus(HttpStatus.NO_CONTENT);
        MvcTestResult second = mvc.delete().uri(path).exchange();

        assertThat(second).hasStatus(HttpStatus.CONFLICT);
        assertThat(second).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(mvc.delete().uri("/api/cases/" + caseId + "/legal-holds/" + UUID.randomUUID()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void listActiveOnly() throws Exception {
        UUID caseId = newCase();
        String released = holdIdOf(placeHold(caseId, HOLD_BODY));
        String active = holdIdOf(placeHold(caseId, HOLD_BODY));
        assertThat(mvc.delete().uri("/api/cases/" + caseId + "/legal-holds/" + released))
                .hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult result = mvc.get().uri("/api/cases/" + caseId + "/legal-holds").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$[*].id").asArray().containsExactly(active);
        assertThat(mvc.get().uri("/api/cases/" + UUID.randomUUID() + "/legal-holds"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void validation() {
        MvcTestResult result = placeHold(newCase(), """
                {"reason": " ", "placedBy": "counsel@example.com"}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("reason");
    }

    private MvcTestResult placeHold(UUID caseId, String body) {
        return mvc.post().uri("/api/cases/" + caseId + "/legal-holds")
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static String holdIdOf(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
    }

    private UUID newCase() {
        return caseFiles.create("C-" + UUID.randomUUID(), "Hold API").getId();
    }

    private UUID newEvidence(UUID caseId) {
        return evidence.register(caseId, "cam.mp4", "video/mp4", 1,
                Instant.now().plus(30, ChronoUnit.DAYS)).getId();
    }
}
