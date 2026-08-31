package at.aimon.session.postgres.internal;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.session.signal.SessionSignal;

/**
 * Jackson codec for the {@code payload} JSONB column of {@code conversation_signal}.
 *
 * <p>
 * {@code conversation_id}, {@code kind}, {@code origin_node_id} are stored as their own columns; the JSONB body holds
 * only the {@code payload} map. Decode rebuilds the {@link SessionSignal} from the column values plus the JSONB
 * payload.
 */
public final class SessionSignalRowCodec {

    private final ObjectMapper mapper;

    public SessionSignalRowCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    public String encodePayload(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            final ObjectNode root = mapper.valueToTree(payload);
            return mapper.writeValueAsString(root);
        } catch (IllegalArgumentException | IOException e) {
            throw new IllegalStateException("Failed to encode signal payload", e);
        }
    }

    public Map<String, Object> decodePayload(String json) {
        Objects.requireNonNull(json, "json must not be null");
        try {
            final JsonNode root = mapper.readTree(json);
            if (!root.isObject()) {
                return Map.of();
            }
            return jsonNodeToMap(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode signal payload", e);
        }
    }

    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        final Map<String, Object> out = new LinkedHashMap<>();
        final Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> e = fields.next();
            out.put(e.getKey(), jsonNodeToValue(e.getValue()));
        }
        return out;
    }

    private Object jsonNodeToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isLong()) {
            return node.asLong();
        }
        if (node.isFloat() || node.isDouble()) {
            return node.asDouble();
        }
        if (node.isObject()) {
            return jsonNodeToMap(node);
        }
        if (node.isArray()) {
            final java.util.List<Object> list = new java.util.ArrayList<>(node.size());
            node.forEach(child -> list.add(jsonNodeToValue(child)));
            return list;
        }
        return node.asText();
    }
}
