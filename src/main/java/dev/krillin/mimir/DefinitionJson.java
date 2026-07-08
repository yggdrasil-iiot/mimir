package dev.krillin.mimir;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class DefinitionJson {

    private DefinitionJson() {
    }

    public static String toJson(UdtDefinition definition) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
            return mapper.writeValueAsString(definition);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize UdtDefinition to JSON", e);
        }
    }
}
