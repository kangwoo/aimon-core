package at.aimon.session.postgres.internal;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.store.StoredAgentExecutionResult;

/**
 * Jackson codec for the {@code idempotency_entry.result_blob} JSONB column.
 *
 * <p>
 * Only the {@link AgentExecutionResult} subset needed for replay is serialized — see
 * {@link StoredAgentExecutionResult}. The remaining {@link at.aimon.core.agent.session.idempotency.IdempotencyEntry}
 * fields live in
 * their own columns
 * ({@code key, conversation_id, input_hash, status, holder_id, created_at, last_touched_at, expires_at}); only the
 * {@code result_blob} round-trips through JSON.
 */
public final class IdempotencyEntryRowCodec {

    private final ObjectMapper mapper;

    public IdempotencyEntryRowCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    public String encodeResult(AgentExecutionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        try {
            final ObjectNode out = mapper.createObjectNode();
            out.put("success", result.isSuccess());
            if (result.getFinalAnswer() != null) {
                out.put("finalAnswer", result.getFinalAnswer());
            } else {
                out.putNull("finalAnswer");
            }
            if (result.getErrorMessage() != null) {
                out.put("errorMessage", result.getErrorMessage());
            } else {
                out.putNull("errorMessage");
            }
            out.put("completionReason", result.getCompletionReason().name());
            out.put("wasStreamed", result.wasStreamed());
            return mapper.writeValueAsString(out);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode AgentExecutionResult", e);
        }
    }

    public Optional<AgentExecutionResult> decodeResult(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            final JsonNode node = mapper.readTree(json);
            if (node == null || node.isNull()) {
                return Optional.empty();
            }
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
            return Optional.of(builder.build());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode AgentExecutionResult", e);
        }
    }
}
