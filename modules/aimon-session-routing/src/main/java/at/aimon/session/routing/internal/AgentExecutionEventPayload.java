package at.aimon.session.routing.internal;

import static at.aimon.session.routing.internal.PayloadValues.asBoolean;
import static at.aimon.session.routing.internal.PayloadValues.asInt;
import static at.aimon.session.routing.internal.PayloadValues.asList;
import static at.aimon.session.routing.internal.PayloadValues.asLong;
import static at.aimon.session.routing.internal.PayloadValues.asMap;
import static at.aimon.session.routing.internal.PayloadValues.asString;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantMessageReceived;
import at.aimon.core.agent.stream.AssistantTextDelta;
import at.aimon.core.agent.stream.AssistantTextStreamCompleted;
import at.aimon.core.agent.stream.AssistantTextStreamReset;
import at.aimon.core.agent.stream.CompactBoundary;
import at.aimon.core.agent.stream.ExecutionCompleted;
import at.aimon.core.agent.stream.ExecutionError;
import at.aimon.core.agent.stream.InterruptedAt;
import at.aimon.core.agent.stream.IterationCompleted;
import at.aimon.core.agent.stream.IterationStarted;
import at.aimon.core.agent.stream.RejectReason;
import at.aimon.core.agent.stream.RejectedAt;
import at.aimon.core.agent.stream.SkillTurnSuspendedEvent;
import at.aimon.core.agent.stream.SubagentTaskCompleted;
import at.aimon.core.agent.stream.ToolResultReady;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.pending.PendingTurnId;

/**
 * Flattens an {@link AgentExecutionEvent} to/from a JSON-primitive {@code Map<String, Object>} so it can be relayed
 * across nodes on the {@link at.aimon.core.agent.session.signal.SessionSignal.SignalKind#EVENT} rail.
 *
 * <p>
 * <b>Why this exists.</b> The cross-node signal codecs ({@code aimon-session-redis} / {@code aimon-session-mongodb})
 * decode a signal payload to a {@code LinkedHashMap} of JSON primitives and never reconstruct a typed object. The
 * previous relay published {@code Map.of("event", typedEvent)} and the receiver tested
 * {@code payload instanceof AgentExecutionEvent} — true only on the in-process bus, a silent no-op over every real bus,
 * so cross-node {@code events()} delivered nothing. This codec replaces that typed-object hop with an explicit flat-map
 * projection (the relay's documented §5.5 responsibility) that genuinely round-trips through the bus codecs.
 *
 * <p>
 * <b>Lossy fields.</b> {@link ExecutionError#getCause()} is carried as its message only (a live {@link Throwable}
 * cannot meaningfully cross a node boundary) and rebuilt as a {@link RuntimeException} with that message; the original
 * type and stack trace are dropped. {@link ToolUseStarted#getInputSummary()} is passed through as-is — values that are
 * not JSON primitives (or nested primitive maps/lists) are normalized by the bus codec and may not reconstruct
 * faithfully. Durations are carried at millisecond granularity. An unrecognized {@code type} (e.g. a newer node's event
 * an older node does not know) decodes to {@link Optional#empty()} so a rolling deployment degrades gracefully.
 */
final class AgentExecutionEventPayload {

    static final String KEY_TYPE = "type";
    static final String KEY_TIMESTAMP = "ts";
    static final String KEY_CONTEXT = "ctx";
    static final String KEY_ITERATION = "iter";
    static final String KEY_TURN = "turn";

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionEventPayload.class);

    private AgentExecutionEventPayload() {
    }

    /**
     * Flattens {@code event} into a JSON-primitive map suitable for an {@code EVENT} signal payload, stamped with the
     * turn that produced it.
     *
     * <p>
     * The turn stamp is what lets a receiver demultiplex frames per turn instead of inferring turn boundaries from
     * arrival order. It rides outside the per-subtype branches because it belongs to the envelope, not the event:
     * {@link AgentExecutionEvent} carries no turn identity of its own.
     *
     * @param event
     *            the event to flatten (must not be null)
     * @param turnId
     *            the turn that produced the event (must not be null)
     * @return a fresh mutable map of JSON primitives, or {@code null} if {@code event} is an unrecognized subtype (so
     *         the relay skips it rather than publishing an undecodable signal)
     */
    static Map<String, Object> toPayload(AgentExecutionEvent event, TurnId turnId) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(turnId, "turnId must not be null");
        return flatten(event, turnId.value());
    }

    /**
     * Flattens an {@code event} that belongs to no turn this node can name, leaving the stamp off entirely.
     *
     * <p>
     * Holder-loss recovery is the only producer: the sweeper works from an
     * {@link at.aimon.core.agent.session.idempotency.IdempotencyEntry}, which records the reservation and its holder
     * but not the turn
     * the dead holder was running — that id travelled in the inbox envelope the holder consumed before it died. Writing
     * a
     * fabricated or borrowed stamp would be worse than writing none, because a receiver demultiplexing per turn would
     * route the frame to the wrong turn instead of falling back; see {@link #turnIdOf} for the receiving contract.
     *
     * @param event
     *            the event to flatten (must not be null)
     * @return a fresh mutable map of JSON primitives with no {@value #KEY_TURN} entry, or {@code null} if {@code event}
     *         is an unrecognized subtype
     */
    static Map<String, Object> toUnstampedPayload(AgentExecutionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return flatten(event, null);
    }

    /**
     * Shared projection for both entry points. {@code turnValue} is null for a frame that names no turn — the only
     * difference between the two payload shapes, which is why they are one method: a subtype branch added to one must
     * not be missing from the other.
     */
    private static Map<String, Object> flatten(AgentExecutionEvent event, String turnValue) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_TIMESTAMP, event.getTimestamp().toEpochMilli());
        map.put(KEY_CONTEXT, event.getAgentRuntimeId().value());
        map.put(KEY_ITERATION, event.getIteration());
        if (turnValue != null) {
            map.put(KEY_TURN, turnValue);
        }

        if (event instanceof IterationStarted e) {
            map.put(KEY_TYPE, "IterationStarted");
            map.put("planned", e.getPlannedIteration());
        } else if (event instanceof IterationCompleted e) {
            map.put(KEY_TYPE, "IterationCompleted");
            map.put("completed", e.getCompletedIteration());
            map.put("willContinue", e.isWillContinue());
        } else if (event instanceof AssistantMessageReceived e) {
            map.put(KEY_TYPE, "AssistantMessageReceived");
            map.put("summary", e.getMessageSummary());
            e.getTokenUsage().ifPresent(tokens -> map.put("tokens", tokensToMap(tokens)));
        } else if (event instanceof AssistantTextDelta e) {
            map.put(KEY_TYPE, "AssistantTextDelta");
            map.put("delta", e.getDelta());
            map.put("chunk", e.getChunkIndex());
        } else if (event instanceof AssistantTextStreamReset e) {
            map.put(KEY_TYPE, "AssistantTextStreamReset");
            map.put("prev", e.getPreviousAttemptIndex());
            map.put("next", e.getNextAttemptIndex());
            map.put("reason", e.getReason());
        } else if (event instanceof AssistantTextStreamCompleted e) {
            map.put(KEY_TYPE, "AssistantTextStreamCompleted");
            map.put("totalLength", e.getTotalLength());
            e.getTokenUsage().ifPresent(tokens -> map.put("tokens", tokensToMap(tokens)));
            e.getFinishReason().ifPresent(finish -> map.put("finish", finish));
        } else if (event instanceof ToolUseStarted e) {
            map.put(KEY_TYPE, "ToolUseStarted");
            map.put("tool", e.getToolName());
            map.put("toolUseId", e.getToolUseId());
            map.put("input", sanitizeInputSummary(e.getInputSummary()));
        } else if (event instanceof ToolResultReady e) {
            map.put(KEY_TYPE, "ToolResultReady");
            map.put("tool", e.getToolName());
            map.put("toolUseId", e.getToolUseId());
            map.put("success", e.isSuccess());
            e.getErrorMessage().ifPresent(error -> map.put("error", error));
            e.getResultPreviewLength().ifPresent(preview -> map.put("preview", preview));
        } else if (event instanceof CompactBoundary e) {
            map.put(KEY_TYPE, "CompactBoundary");
            map.put("strategy", e.getStrategyName());
            map.put("before", e.getMessagesBefore());
            map.put("after", e.getMessagesAfter());
        } else if (event instanceof ExecutionCompleted e) {
            map.put(KEY_TYPE, "ExecutionCompleted");
            map.put("completion", e.getCompletionReason().name());
            map.put("totalIterations", e.getTotalIterations());
            e.getElapsed().ifPresent(elapsed -> map.put("elapsedMillis", elapsed.toMillis()));
        } else if (event instanceof ExecutionError e) {
            map.put(KEY_TYPE, "ExecutionError");
            map.put("error", e.getErrorMessage());
            e.getCause().ifPresent(cause -> {
                if (cause.getMessage() != null) {
                    map.put("causeMessage", cause.getMessage());
                }
            });
            e.getCompletionReason().ifPresent(completion -> map.put("completion", completion.name()));
        } else if (event instanceof InterruptedAt e) {
            map.put(KEY_TYPE, "InterruptedAt");
            map.put("reason", e.getReason().name());
            map.put("iterationIndex", e.getIterationIndex());
            map.put("partial", e.getPartialOutput());
        } else if (event instanceof RejectedAt e) {
            map.put(KEY_TYPE, "RejectedAt");
            map.put("reason", e.getReason().name());
            map.put("requested", e.getRequestedAgent());
            e.getExistingAgent().ifPresent(existing -> map.put("existing", existing));
            map.put("inbox", e.getInboxId());
        } else if (event instanceof SkillTurnSuspendedEvent e) {
            map.put(KEY_TYPE, "SkillTurnSuspendedEvent");
            map.put("pendingTurn", e.getPendingTurnId().value());
            map.put("skills", pendingSkillsToList(e.getPendingSkills()));
        } else if (event instanceof SubagentTaskCompleted e) {
            map.put(KEY_TYPE, "SubagentTaskCompleted");
            map.put("taskId", e.getTaskId());
            map.put("subagent", e.getSubagentName());
            map.put("outcome", e.getOutcome().name());
            e.getDetail().ifPresent(detail -> map.put("detail", detail));
        } else {
            // AgentExecutionEvent is sealed, so this is unreachable today; mirror fromPayload's graceful contract
            // (return null → relay skips) rather than throwing if a new subtype is ever added without a branch here.
            log.debug("Unrecognized AgentExecutionEvent subtype '{}' — not relayed cross-node",
                    event.getClass().getName());
            return null;
        }
        return map;
    }

    /**
     * Rebuilds an {@link AgentExecutionEvent} from a payload produced by {@link #toPayload} and possibly normalized by
     * a
     * JSON codec on the way across the bus. Never throws: a null, malformed, or unrecognized payload yields
     * {@link Optional#empty()} so a corrupt or forward-incompatible signal cannot break the receive path.
     *
     * @param payload
     *            the decoded signal payload (may be null)
     * @return the reconstructed event, or empty if the payload is absent/malformed/unknown
     */
    static Optional<AgentExecutionEvent> fromPayload(Map<String, Object> payload) {
        if (payload == null) {
            return Optional.empty();
        }
        try {
            final String type = asString(payload.get(KEY_TYPE));
            final Instant timestamp = Instant.ofEpochMilli(asLong(payload.get(KEY_TIMESTAMP)));
            final AgentRuntimeId context = AgentRuntimeId.of(asString(payload.get(KEY_CONTEXT)));
            final int iteration = asInt(payload.get(KEY_ITERATION));
            final AgentExecutionEvent event = decode(type, timestamp, context, iteration, payload);
            return Optional.ofNullable(event);
        } catch (RuntimeException e) {
            log.warn("Discarding malformed cross-node event payload: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Extracts the turn stamp written by {@link #toPayload(AgentExecutionEvent, TurnId)}.
     *
     * <p>
     * Returns empty for a payload that carries no usable stamp — a frame built by {@link #toUnstampedPayload} because
     * its
     * producer knew no turn, a signal published by a node on an older build, or a malformed value. A receiver that
     * demultiplexes per turn must treat an unstamped frame as "turn unknown" and fall back to session-wide
     * delivery
     * rather than dropping it, otherwise holder-loss recovery goes unheard and a rolling upgrade silently starves
     * subscribers for as long as one old node is still publishing.
     *
     * @param payload
     *            the decoded signal payload (may be null)
     * @return the stamped turn id, or empty when absent/blank/malformed
     */
    static Optional<TurnId> turnIdOf(Map<String, Object> payload) {
        if (payload == null) {
            return Optional.empty();
        }
        final Object raw = payload.get(KEY_TURN);
        if (raw == null) {
            return Optional.empty();
        }
        final String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(TurnId.of(value));
    }

    private static AgentExecutionEvent decode(String type, Instant timestamp, AgentRuntimeId context, int iteration,
            Map<String, Object> payload) {
        return switch (type) {
            case "IterationStarted" -> IterationStarted.builder().timestamp(timestamp).agentRuntimeId(context)
                    .iteration(iteration).plannedIteration(asInt(payload.get("planned"))).build();
            case "IterationCompleted" -> IterationCompleted.builder().timestamp(timestamp).agentRuntimeId(context)
                    .iteration(iteration).completedIteration(asInt(payload.get("completed")))
                    .willContinue(asBoolean(payload.get("willContinue"))).build();
            case "AssistantMessageReceived" -> {
                final AssistantMessageReceived.Builder builder = AssistantMessageReceived.builder().timestamp(timestamp)
                        .agentRuntimeId(context).iteration(iteration).messageSummary(asString(payload.get("summary")));
                if (payload.containsKey("tokens")) {
                    builder.tokenUsage(tokensFromMap(asMap(payload.get("tokens"))));
                }
                yield builder.build();
            }
            case "AssistantTextDelta" ->
                AssistantTextDelta.builder().timestamp(timestamp).agentRuntimeId(context).iteration(iteration)
                        .delta(asString(payload.get("delta"))).chunkIndex(asInt(payload.get("chunk"))).build();
            case "AssistantTextStreamReset" -> AssistantTextStreamReset.builder().timestamp(timestamp)
                    .agentRuntimeId(context).iteration(iteration).previousAttemptIndex(asInt(payload.get("prev")))
                    .nextAttemptIndex(asInt(payload.get("next"))).reason(asString(payload.get("reason"))).build();
            case "AssistantTextStreamCompleted" -> {
                final AssistantTextStreamCompleted.Builder builder = AssistantTextStreamCompleted.builder()
                        .timestamp(timestamp).agentRuntimeId(context).iteration(iteration)
                        .totalLength(asInt(payload.get("totalLength")));
                if (payload.containsKey("tokens")) {
                    builder.tokenUsage(tokensFromMap(asMap(payload.get("tokens"))));
                }
                if (payload.containsKey("finish")) {
                    builder.finishReason(asString(payload.get("finish")));
                }
                yield builder.build();
            }
            case "ToolUseStarted" -> ToolUseStarted.builder().timestamp(timestamp).agentRuntimeId(context)
                    .iteration(iteration).toolName(asString(payload.get("tool")))
                    .toolUseId(asString(payload.get("toolUseId"))).inputSummary(asMap(payload.get("input"))).build();
            case "ToolResultReady" -> {
                final ToolResultReady.Builder builder = ToolResultReady.builder().timestamp(timestamp)
                        .agentRuntimeId(context).iteration(iteration).toolName(asString(payload.get("tool")))
                        .toolUseId(asString(payload.get("toolUseId"))).success(asBoolean(payload.get("success")));
                if (payload.containsKey("error")) {
                    builder.errorMessage(asString(payload.get("error")));
                }
                if (payload.containsKey("preview")) {
                    builder.resultPreviewLength(asInt(payload.get("preview")));
                }
                yield builder.build();
            }
            case "CompactBoundary" -> CompactBoundary.builder().timestamp(timestamp).agentRuntimeId(context)
                    .iteration(iteration).strategyName(asString(payload.get("strategy")))
                    .messagesBefore(asInt(payload.get("before"))).messagesAfter(asInt(payload.get("after"))).build();
            case "ExecutionCompleted" -> {
                final ExecutionCompleted.Builder builder = ExecutionCompleted.builder().timestamp(timestamp)
                        .agentRuntimeId(context).iteration(iteration)
                        .completionReason(CompletionReason.valueOf(asString(payload.get("completion"))))
                        .totalIterations(asInt(payload.get("totalIterations")));
                if (payload.containsKey("elapsedMillis")) {
                    builder.elapsed(Duration.ofMillis(asLong(payload.get("elapsedMillis"))));
                }
                yield builder.build();
            }
            case "ExecutionError" -> {
                final ExecutionError.Builder builder = ExecutionError.builder().timestamp(timestamp)
                        .agentRuntimeId(context).iteration(iteration).errorMessage(asString(payload.get("error")));
                if (payload.containsKey("causeMessage")) {
                    builder.cause(new RuntimeException(asString(payload.get("causeMessage"))));
                }
                if (payload.containsKey("completion")) {
                    builder.completionReason(CompletionReason.valueOf(asString(payload.get("completion"))));
                }
                yield builder.build();
            }
            case "InterruptedAt" -> InterruptedAt.builder().timestamp(timestamp).agentRuntimeId(context)
                    .iteration(iteration).reason(InterruptReason.valueOf(asString(payload.get("reason"))))
                    .iterationIndex(asInt(payload.get("iterationIndex")))
                    .partialOutput(asString(payload.get("partial"))).build();
            case "RejectedAt" -> {
                final RejectedAt.Builder builder = RejectedAt.builder().timestamp(timestamp).agentRuntimeId(context)
                        .iteration(iteration).reason(RejectReason.valueOf(asString(payload.get("reason"))))
                        .requestedAgent(asString(payload.get("requested"))).inboxId(asString(payload.get("inbox")));
                if (payload.containsKey("existing")) {
                    builder.existingAgent(asString(payload.get("existing")));
                }
                yield builder.build();
            }
            case "SkillTurnSuspendedEvent" ->
                SkillTurnSuspendedEvent.builder().timestamp(timestamp).agentRuntimeId(context).iteration(iteration)
                        .pendingTurnId(PendingTurnId.of(asString(payload.get("pendingTurn"))))
                        .pendingSkills(pendingSkillsFromList(asList(payload.get("skills")))).build();
            case "SubagentTaskCompleted" -> {
                final SubagentTaskCompleted.Builder builder = SubagentTaskCompleted.builder().timestamp(timestamp)
                        .agentRuntimeId(context).iteration(iteration).taskId(asString(payload.get("taskId")))
                        .subagentName(asString(payload.get("subagent")))
                        .outcome(SubagentTaskCompleted.Outcome.valueOf(asString(payload.get("outcome"))));
                if (payload.containsKey("detail")) {
                    builder.detail(asString(payload.get("detail")));
                }
                yield builder.build();
            }
            default -> {
                log.debug("Unknown cross-node event type '{}' — skipping", type);
                yield null;
            }
        };
    }

    private static Map<String, Object> tokensToMap(TokenUsage tokens) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("prompt", tokens.getPromptTokens());
        map.put("completion", tokens.getCompletionTokens());
        map.put("total", tokens.getTotalTokens());
        return map;
    }

    private static TokenUsage tokensFromMap(Map<String, Object> map) {
        return TokenUsage.of(asInt(map.get("prompt")), asInt(map.get("completion")), asInt(map.get("total")));
    }

    /**
     * Drops top-level null-valued entries so the signal payload survives {@code SessionSignal}'s
     * {@code Map.copyOf}
     * (which rejects null values). Nested map/list values are passed through unchanged — nulls deeper than the top
     * level are tolerated by the bus codecs (serialized as JSON null) and by {@code ToolUseStarted}'s builder.
     */
    private static Map<String, Object> sanitizeInputSummary(Map<String, Object> inputSummary) {
        final Map<String, Object> map = new LinkedHashMap<>();
        inputSummary.forEach((key, value) -> {
            if (value != null) {
                map.put(key, value);
            }
        });
        return map;
    }

    private static List<Object> pendingSkillsToList(List<PendingSkillRequest> skills) {
        final List<Object> list = new ArrayList<>(skills.size());
        for (PendingSkillRequest skill : skills) {
            final Map<String, Object> map = new LinkedHashMap<>();
            if (skill.getToolUseId() != null) {
                map.put("toolUseId", skill.getToolUseId());
            }
            map.put("skillName", skill.getSkillName());
            map.put("args", skill.getArgs());
            list.add(map);
        }
        return list;
    }

    private static List<PendingSkillRequest> pendingSkillsFromList(List<Object> list) {
        final List<PendingSkillRequest> skills = new ArrayList<>(list.size());
        for (Object element : list) {
            final Map<String, Object> map = asMap(element);
            final PendingSkillRequest.Builder builder = PendingSkillRequest.builder()
                    .skillName(asString(map.get("skillName"))).args(asString(map.get("args")));
            if (map.containsKey("toolUseId")) {
                builder.toolUseId(asString(map.get("toolUseId")));
            }
            skills.add(builder.build());
        }
        return skills;
    }
}
