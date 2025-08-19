package io.github.rdfc.json;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * DynamicJsonLdDeserializer
 */
public class DynamicJsonLdDeserializer extends JsonDeserializer<Object> {

    private final Map<String, Function<JsonNode, Object>> registry = new HashMap<>();

    public void register(String typeUri, Function<JsonNode, Object> handler) {
        registry.put(typeUri, handler);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
        JsonNode node = p.readValueAsTree();

        if (node.has("@type")) {
            String type = node.get("@type").asText();
            Function<JsonNode, Object> handler = registry.get(type);
            if (handler != null) {
                return handler.apply(node);
            }
        }

        // fallback
        return node;
    }
}
