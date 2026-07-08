package dev.krillin.mimir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionJsonTest {

    @Test
    void writesUdtDefinitionAsExpectedJsonTree() throws Exception {
        UdtDefinition def = new UdtDefinition(
                "Line1-Mixer",
                "1.0.0",
                List.of(
                        new Member("Rpm", "Double", "urn:bifrost:sem:Mixer/Rpm", new Range(0, 3000)),
                        new Member("Running", "Boolean", "urn:bifrost:sem:Mixer/Running", null)
                ),
                List.of(),
                null
        );

        String json = DefinitionJson.toJson(def);

        JsonNode tree = new ObjectMapper().readTree(json);

        assertEquals("Line1-Mixer", tree.get("templateRef").asText());
        assertEquals("1.0.0", tree.get("version").asText());
        assertEquals(2, tree.get("members").size());
        assertEquals(3000, tree.get("members").get(0).get("range").get("high").asDouble());
        assertTrue(tree.get("members").get(1).get("range").isNull());
        assertTrue(tree.get("params").isArray());
        assertEquals(0, tree.get("params").size());
        assertTrue(tree.get("conformsTo").isNull());
    }
}
