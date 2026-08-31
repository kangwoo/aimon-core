package at.aimon.session.redis.internal;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;

/**
 * Jackson-based codec for the {@link SessionSignal} envelope sent through Redis pub/sub.
 *
 * <p>
 * The envelope shape is intentionally narrow — {@code conversationId / kind / originNodeId / payload} — and
 * {@code payload} is a {@code Map<String, Object>} containing only JSON-friendly primitives the manager places there
 * (signal-specific fields like {@code reason}, {@code messageId}). Polymorphic round-trip of
 * {@code AgentExecutionEvent} is out of scope; the manager-side relay is expected to project events to a Map for
 * cross-node EVENT broadcast (design §5.4).
 *
 * <p>
 * <b>{@code conversationId} is a frozen wire key.</b> The Java accessor already moved with the type it belongs to —
 * it reads {@link SessionSignal#getSessionId()} now — but this key did not follow it and may not. Unlike the stored
 * codecs the exposure here is not the past but the other node: a rolling upgrade runs publishers and subscribers on
 * different builds at the same time, so a renamed key means INTERRUPT and EVICT cross the bus and land nowhere — an
 * interrupt that never trips a turn, a cache entry that never drops. Renaming encode() and decode() together keeps
 * the round-trips green because both halves are in the same process. {@code SessionSignalCodecTest} pins the literal
 * in both directions.
 */
public final class SessionSignalCodec {

    private final ObjectMapper mapper;

    public SessionSignalCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    public String encode(SessionSignal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        try {
            final ObjectNode root = mapper.createObjectNode();
            root.put("conversationId", signal.getSessionId().value());
            root.put("kind", signal.getKind().name());
            root.put("originNodeId", signal.getOriginNodeId());
            root.set("payload", mapper.valueToTree(signal.getPayload()));
            return mapper.writeValueAsString(root);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Failed to encode SessionSignal payload", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode SessionSignal", e);
        }
    }

    public SessionSignal decode(String json) {
        Objects.requireNonNull(json, "json must not be null");
        try {
            final JsonNode root = mapper.readTree(json);
            final SessionSignal.Builder builder = SessionSignal.builder()
                    .sessionId(SessionId.of(root.get("conversationId").asText()))
                    .kind(SignalKind.valueOf(root.get("kind").asText()))
                    .originNodeId(root.get("originNodeId").asText());
            final JsonNode payload = root.get("payload");
            if (payload != null && payload.isObject()) {
                builder.payload(jsonNodeToMap(payload));
            }
            return builder.build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode SessionSignal", e);
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
