package at.aimon.session.redis.internal;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.store.StoredAgentExecutionResult;

/**
 * Jackson-based codec for {@link IdempotencyEntry} JSON representation stored in Redis.
 *
 * <p>
 * Layout (single Redis STRING per key, with PX TTL):
 *
 * <pre>{@code
 * {
 *   "key": "...",
 *   "conversationId": "...",
 *   "inputHash": "...",
 *   "status": "IN_FLIGHT" | "DONE",
 *   "holderId": "..." | null,
 *   "createdAt": "ISO-8601",
 *   "lastTouchedAt": "ISO-8601",
 *   "ttlMillis": <long>,
 *   "result": { "success": ..., "finalAnswer": ..., ... } | null
 * }
 * }</pre>
 *
 * <p>
 * The {@code ttlMillis} field carries the original TTL so {@code touch} can refresh PEXPIRE without an external
 * parameter. The {@code result} subtree is a {@link StoredAgentExecutionResult} projection — full
 * {@link AgentExecutionResult} polymorphism is out of scope (design §9.2).
 *
 * <p>
 * <b>{@code conversationId} is a frozen wire key.</b> The Java accessor already moved with the type it belongs to —
 * it reads {@link IdempotencyEntry#getSessionId()} now — but this key did not follow it and may not. Outliving the turn
 * it guards is the entire point of an entry — it sits under its TTL waiting for a retry that may arrive after the
 * writer has been replaced — so a reader on a new build is always reading entries an older build wrote. Renaming
 * encode() and decode() together keeps the round-trips green while every such entry decodes with the wrong session or
 * not at all, which is exactly the double-execution the store exists to prevent.
 * {@code IdempotencyEntryCodecTest} pins the literal in both directions.
 */
public final class IdempotencyEntryCodec {

    private final ObjectMapper mapper;

    public IdempotencyEntryCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    public String encode(IdempotencyEntry entry, Duration ttl) {
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        try {
            final ObjectNode root = mapper.createObjectNode();
            root.put("key", entry.getKey());
            root.put("conversationId", entry.getSessionId().value());
            root.put("inputHash", entry.getInputHash());
            root.put("status", entry.getStatus().name());
            entry.getHolderId().ifPresentOrElse(h -> root.put("holderId", h), () -> root.putNull("holderId"));
            root.put("createdAt", entry.getCreatedAt().toString());
            root.put("lastTouchedAt", entry.getLastTouchedAt().toString());
            root.put("ttlMillis", ttl.toMillis());
            entry.getResult().ifPresentOrElse(r -> root.set("result", encodeResult(r)), () -> root.putNull("result"));
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode IdempotencyEntry", e);
        }
    }

    public IdempotencyEntry decode(String json) {
        Objects.requireNonNull(json, "json must not be null");
        try {
            final JsonNode root = mapper.readTree(json);
            final IdempotencyEntry.Builder builder = IdempotencyEntry.builder().key(root.get("key").asText())
                    .sessionId(SessionId.of(root.get("conversationId").asText()))
                    .inputHash(root.get("inputHash").asText())
                    .status(IdempotencyEntry.Status.valueOf(root.get("status").asText()))
                    .createdAt(Instant.parse(root.get("createdAt").asText()))
                    .lastTouchedAt(Instant.parse(root.get("lastTouchedAt").asText()));
            if (root.hasNonNull("holderId")) {
                builder.holderId(root.get("holderId").asText());
            }
            if (root.hasNonNull("result")) {
                builder.result(decodeResult(root.get("result")));
            }
            return builder.build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode IdempotencyEntry", e);
        }
    }

    public long readTtlMillis(String json) {
        try {
            return mapper.readTree(json).get("ttlMillis").asLong();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ttlMillis from JSON", e);
        }
    }

    public String reencodeWithUpdates(String json, IdempotencyEntry replacement, Duration newTtl) {
        return encode(replacement, newTtl);
    }

    private ObjectNode encodeResult(AgentExecutionResult r) {
        final ObjectNode out = mapper.createObjectNode();
        out.put("success", r.isSuccess());
        if (r.getFinalAnswer() != null) {
            out.put("finalAnswer", r.getFinalAnswer());
        } else {
            out.putNull("finalAnswer");
        }
        if (r.getErrorMessage() != null) {
            out.put("errorMessage", r.getErrorMessage());
        } else {
            out.putNull("errorMessage");
        }
        out.put("completionReason", r.getCompletionReason().name());
        out.put("wasStreamed", r.wasStreamed());
        return out;
    }

    private AgentExecutionResult decodeResult(JsonNode node) {
        final StoredAgentExecutionResult.Builder builder = StoredAgentExecutionResult.builder()
                .success(node.get("success").asBoolean())
                .completionReason(CompletionReason.valueOf(node.get("completionReason").asText()))
                .wasStreamed(node.get("wasStreamed").asBoolean());
        if (node.hasNonNull("finalAnswer")) {
            builder.finalAnswer(node.get("finalAnswer").asText());
        }
        if (node.hasNonNull("errorMessage")) {
            builder.errorMessage(node.get("errorMessage").asText());
        }
        return builder.build();
    }
}
