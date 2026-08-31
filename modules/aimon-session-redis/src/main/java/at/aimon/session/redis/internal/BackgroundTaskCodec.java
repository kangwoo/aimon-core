package at.aimon.session.redis.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;

/**
 * Jackson-based codec for the {@link BackgroundTask} metadata snapshot persisted as a Redis STRING by
 * {@code RedisBackgroundTaskStore}.
 *
 * <p>
 * The JSON shape mirrors the immutable snapshot exactly — {@code taskId}, {@code subagentName}, {@code state},
 * {@code startTime}, {@code endTime}, {@code outputOffset}, {@code description}, plus optional {@code owner} (a nested
 * {@code type/id/displayName} object) and {@code contextId} (its string form). Instants are ISO-8601 strings so the
 * value is human-readable in {@code redis-cli}.
 *
 * <p>
 * The {@code state} field is emitted verbatim (Jackson does not escape the enum name), so the store's terminal-guard
 * Lua
 * script can match {@code "state":"COMPLETED"} / {@code "FAILED"} / {@code "KILLED"} as a plain substring —
 * user-supplied
 * strings (e.g. {@code description}) can never forge that token because Jackson backslash-escapes the quotes inside
 * them.
 *
 * <p>
 * <b>{@code contextId} is a frozen wire key.</b> The agent-scope refactor renamed the accessor to
 * {@code getAgentRuntimeId()} but deliberately did not touch persisted names (CHANGELOG, "Not changed (deliberately
 * frozen)"). These STRINGs outlive the process that wrote them and are read by every other node, so renaming the key
 * here — even on both sides at once, which keeps every round-trip test green — drops the owning runtime from every
 * snapshot already in Redis. {@code BackgroundTaskCodecTest} pins the literal in both directions.
 */
public final class BackgroundTaskCodec {

    private final ObjectMapper mapper;

    /**
     * Creates a codec backed by the given Jackson mapper.
     *
     * @param mapper
     *            the mapper used to build and read the snapshot JSON (must not be null; the codec serializes the
     *            {@code Instant} fields as ISO-8601 strings itself, so no JSR-310 module is required)
     */
    public BackgroundTaskCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Serializes a task snapshot to its JSON string form.
     *
     * @param task
     *            the snapshot (must not be null)
     * @return the JSON string (never null)
     */
    public String encode(BackgroundTask task) {
        Objects.requireNonNull(task, "task must not be null");
        try {
            final ObjectNode root = mapper.createObjectNode();
            root.put("taskId", task.getTaskId());
            root.put("subagentName", task.getSubagentName());
            root.put("state", task.getState().name());
            root.put("startTime", task.getStartTime().toString());
            task.getEndTime().ifPresent(end -> root.put("endTime", end.toString()));
            root.put("outputOffset", task.getOutputOffset());
            root.put("description", task.getDescription());
            task.getOwner().ifPresent(owner -> {
                final ObjectNode ownerNode = root.putObject("owner");
                ownerNode.put("type", owner.getType().name());
                ownerNode.put("id", owner.getId());
                ownerNode.put("displayName", owner.getDisplayName());
            });
            // "contextId" is a FROZEN WIRE KEY — see the class javadoc; do not rename it with the Java identifier.
            task.getAgentRuntimeId().ifPresent(contextId -> root.put("contextId", contextId.value()));
            task.getLastHeartbeat().ifPresent(hb -> root.put("lastHeartbeat", hb.toString()));
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode BackgroundTask " + task.getTaskId(), e);
        }
    }

    /**
     * Deserializes a task snapshot from its JSON string form.
     *
     * @param json
     *            the JSON string (must not be null)
     * @return the reconstructed snapshot (never null)
     */
    public BackgroundTask decode(String json) {
        Objects.requireNonNull(json, "json must not be null");
        try {
            final JsonNode root = mapper.readTree(json);
            final BackgroundTask.Builder builder = BackgroundTask.builder().taskId(root.get("taskId").asText())
                    .subagentName(root.get("subagentName").asText())
                    .state(BackgroundTaskState.valueOf(root.get("state").asText()))
                    .startTime(Instant.parse(root.get("startTime").asText()))
                    .outputOffset(root.path("outputOffset").asLong(0L)).description(textOrNull(root, "description"));
            final String endTime = textOrNull(root, "endTime");
            if (endTime != null) {
                builder.endTime(Instant.parse(endTime));
            }
            final JsonNode owner = root.get("owner");
            if (owner != null && owner.isObject()) {
                builder.owner(Principal.builder().type(Principal.Type.valueOf(owner.get("type").asText()))
                        .id(owner.get("id").asText()).displayName(owner.get("displayName").asText()).build());
            }
            // "contextId" is a FROZEN WIRE KEY — see the class javadoc; do not rename it with the Java identifier.
            final String contextId = textOrNull(root, "contextId");
            if (contextId != null) {
                builder.agentRuntimeId(AgentRuntimeId.of(contextId));
            }
            final String lastHeartbeat = textOrNull(root, "lastHeartbeat");
            if (lastHeartbeat != null) {
                builder.lastHeartbeat(Instant.parse(lastHeartbeat));
            }
            return builder.build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode BackgroundTask", e);
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        final JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
