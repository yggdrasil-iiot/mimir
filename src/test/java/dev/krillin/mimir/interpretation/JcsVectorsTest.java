package dev.krillin.mimir.interpretation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hand-written RFC 8785 vectors that scripts/interpretation_digest.py also has to reproduce. */
class JcsVectorsTest {

    static final Path VECTORS = Path.of("fixtures/interpretation/1.0.0/jcs-vectors.json");

    @TestFactory
    List<DynamicTest> vectors() throws Exception {
        JsonNode file = InterpretationContract.parse(Files.readString(VECTORS));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : file.get("canonical")) {
            tests.add(DynamicTest.dynamicTest("canonical " + c.get("name").asText(), () ->
                    assertEquals(c.get("canonical").asText(),
                            Jcs.canonicalize(InterpretationContract.parse(c.get("input").asText())))));
        }
        for (JsonNode c : file.get("rejected")) {
            tests.add(DynamicTest.dynamicTest("rejected " + c.get("name").asText(), () -> {
                ContractViolation v = assertThrows(ContractViolation.class,
                        () -> Jcs.canonicalize(InterpretationContract.parse(c.get("input").asText())));
                assertEquals(c.get("code").asText(), v.code());
            }));
        }
        assertTrue(tests.size() >= 14, "the vector file shrank: " + tests.size());
        return tests;
    }
}
