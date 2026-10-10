package com.evidencevault.casefile;

import com.evidencevault.TestcontainersConfiguration;
import com.evidencevault.common.web.ConflictException;
import com.evidencevault.support.Concurrently;
import com.evidencevault.support.Concurrently.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CaseFileConcurrencyTest {

    @Autowired
    CaseFileService service;

    @Test
    void concurrentCreatesWithSameCaseNumberProduceExactlyOneCase() throws Exception {
        String caseNumber = "C-" + UUID.randomUUID();

        List<Outcome<CaseFile>> outcomes =
                Concurrently.run(20, () -> service.create(caseNumber, "Burglary at 5th Ave"));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .hasSize(19)
                .allSatisfy(o -> assertThat(o.error()).isInstanceOf(ConflictException.class));
    }
}
