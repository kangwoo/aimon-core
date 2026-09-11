package at.aimon.core.llm.streaming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;

/**
 * Accumulates streaming chunks into a final {@link LlmResponse}.
 *
 * <p>
 * Provider mappers call {@link #accept(LlmStreamChunk)} for each chunk they emit to the sink, and
 * {@link #appendToolCallDelta(int, String, String, String)} for tool_call argument fragments. At stream end, callers
 * invoke {@link #toLlmResponse()} to obtain the full response with the final text, parsed tool uses, and cumulative
 * token usage.
 *
 * <p>
 * {@link #peekText()} exposes the currently accumulated text in a thread-safe manner — used by
 * {@link at.aimon.core.agent.impl.orca.OrcaAgentExecutor} to preserve partial text in the conversation history when the
 * user cancels a streaming turn.
 *
 * <p>
 * <b>Reasoning deltas accumulate separately and are never part of the response.</b>
 * {@link LlmStreamChunk.Kind#REASONING_DELTA} feeds {@link #peekReasoningText()} alone — not {@link #peekText()}, not
 * {@link #toLlmResponse()}. Because {@code peekText()} is what the executor commits to the transcript on a mid-stream
 * cancel, a reasoning delta reaching {@code textBuffer} would persist the model's private deliberation as its public
 * answer, and a transcript is not recoverable. Two buffers, one of which the response builder does not read, is how
 * that is made structurally impossible rather than remembered.
 */
public final class ChunkAggregator {

    private static final Logger log = LoggerFactory.getLogger(ChunkAggregator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Object lock = new Object();
    private final StringBuilder textBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private final Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
    private final List<ReasoningTrace> reasoningTraces = new ArrayList<>();

    private TokenUsage tokenUsage;
    private Optional<String> finishReason = Optional.empty();
    private StopReason stopReason = StopReason.UNKNOWN;
    private boolean closed;
    private int chunksAccepted;

    /**
     * Consumes a chunk. Only {@link LlmStreamChunk.Kind#TEXT_DELTA} contributes to {@link #peekText()} — that
     * exclusivity is load-bearing rather than incidental, because {@code peekText()} is what an interrupted execution
     * commits to the transcript as the assistant's answer. {@link LlmStreamChunk.Kind#REASONING_DELTA} goes to
     * {@link #peekReasoningText()} instead. A {@link LlmStreamChunk.Kind#STREAM_END} chunk marks the aggregator as
     * closed and captures the terminal usage / finish reason.
     *
     * @throws IllegalStateException
     *             if a second {@code STREAM_END} is received
     */
    public void accept(LlmStreamChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Aggregator is already closed");
            }
            chunksAccepted++;
            switch (chunk.getKind()) {
                case TEXT_DELTA -> {
                    final String delta = chunk.getTextDelta().orElseThrow();
                    textBuffer.append(delta);
                }
                case REASONING_DELTA -> {
                    // Deliberation, not answer: a separate buffer that toLlmResponse() does not read.
                    final String delta = chunk.getReasoningDelta().orElseThrow();
                    reasoningBuffer.append(delta);
                }
                case TOOL_USE_READY -> {
                    // Overlap signal (design §3): a completed tool_use block is surfaced to the sink for early
                    // execution. This slot's arguments are already held here (appended via appendToolCallDelta), and
                    // toLlmResponse() remains the source of truth for the final response — nothing to accumulate.
                }
                case STREAM_END -> {
                    chunk.getTokenUsage().ifPresent(usage -> this.tokenUsage = usage);
                    this.finishReason = chunk.getFinishReason();
                    this.stopReason = chunk.getStopReason().orElse(StopReason.UNKNOWN);
                    this.closed = true;
                }
                default -> throw new IllegalStateException("Unknown chunk kind: " + chunk.getKind());
            }
        }
    }

    /**
     * Appends a tool_call argument fragment for the given tool_call slot.
     *
     * <p>
     * Providers send {@code id} and {@code name} on the first fragment (OpenAI) or on the {@code content_block_start}
     * event (Anthropic) and then only partial JSON on subsequent fragments. Subsequent calls with {@code null} for
     * {@code id} / {@code name} preserve the previously set values.
     *
     * @param toolCallIndex
     *            provider-assigned slot index (0-based); multiple tool_calls may be produced in one response
     * @param id
     *            tool_call id; may be {@code null} on continuations
     * @param name
     *            tool name; may be {@code null} on continuations
     * @param argumentsFragment
     *            JSON fragment to append to the accumulated arguments buffer; may be empty
     */
    public void appendToolCallDelta(int toolCallIndex, String id, String name, String argumentsFragment) {
        if (toolCallIndex < 0) {
            throw new IllegalArgumentException("toolCallIndex must be non-negative, got: " + toolCallIndex);
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Aggregator is already closed");
            }
            final ToolCallAccumulator slot = toolCalls.computeIfAbsent(toolCallIndex, k -> new ToolCallAccumulator());
            if (id != null && !id.isEmpty()) {
                slot.id = id;
            }
            if (name != null && !name.isEmpty()) {
                slot.name = name;
            }
            if (argumentsFragment != null && !argumentsFragment.isEmpty()) {
                slot.arguments.append(argumentsFragment);
            }
        }
    }

    /**
     * Records one provider-authored reasoning trace produced during this stream.
     *
     * <p>
     * Traces accumulate in call order and are attached to the response {@link #toLlmResponse()} builds. This lives in
     * the aggregator rather than in a provider mapper because the aggregator is already the single authority for the
     * streamed response, and because every provider that returns reasoning payloads needs the identical seam.
     *
     * @param trace
     *            the trace to record (must not be null)
     * @throws IllegalStateException
     *             if the stream has already ended
     */
    public void addReasoningTrace(ReasoningTrace trace) {
        Objects.requireNonNull(trace, "trace");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Aggregator is already closed");
            }
            reasoningTraces.add(trace);
        }
    }

    /**
     * @return a snapshot of the currently accumulated assistant text. Safe to call at any time.
     */
    public String peekText() {
        synchronized (lock) {
            return textBuffer.toString();
        }
    }

    /**
     * Returns the model's accumulated deliberation for this stream.
     *
     * <p>
     * Deliberately <em>not</em> read by {@link #toLlmResponse()} and deliberately not merged into {@link #peekText()}:
     * see the class javadoc. Nothing downstream persists or replays it — it exists so the invariant can be asserted
     * positively, and so a caller that genuinely wants "what was it thinking" has one seam to read rather than a
     * reason to reach into the text buffer.
     *
     * @return a snapshot of the accumulated reasoning text. Safe to call at any time; empty when nothing asked for it.
     */
    public String peekReasoningText() {
        synchronized (lock) {
            return reasoningBuffer.toString();
        }
    }

    /**
     * @return whether the aggregator has received its terminal {@code STREAM_END} chunk.
     */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /**
     * @return the number of chunks accepted so far (including the terminal one if already seen).
     */
    public int chunksAccepted() {
        synchronized (lock) {
            return chunksAccepted;
        }
    }

    /**
     * @return the terminal finish reason, if available (only after {@code STREAM_END}).
     */
    public Optional<String> finishReason() {
        synchronized (lock) {
            return finishReason;
        }
    }

    /**
     * Builds the final {@link LlmResponse} once the stream has ended. Tool_call argument JSON is parsed lazily at this
     * point — malformed fragments result in an empty input map with a warning logged.
     *
     * @throws IllegalStateException
     *             if the stream has not yet ended (no {@code STREAM_END} seen)
     */
    public LlmResponse toLlmResponse() {
        final String text;
        final TokenUsage usage;
        final StopReason reason;
        final List<ToolCallAccumulator> slots;
        final List<ReasoningTrace> traces;
        synchronized (lock) {
            if (!closed) {
                throw new IllegalStateException("Aggregator has not received STREAM_END yet");
            }
            // reasoningBuffer is deliberately absent here — see the class javadoc.
            text = textBuffer.toString();
            usage = tokenUsage == null ? TokenUsage.empty() : tokenUsage;
            reason = stopReason;
            slots = new ArrayList<>(toolCalls.values());
            traces = List.copyOf(reasoningTraces);
        }
        final List<ToolUse> toolUses = new ArrayList<>(slots.size());
        for (ToolCallAccumulator slot : slots) {
            if (slot.id == null || slot.name == null) {
                log.warn("Skipping tool_call slot with missing id/name (id={}, name={})", slot.id, slot.name);
                continue;
            }
            toolUses.add(ToolUse.of(slot.id, slot.name, parseArguments(slot.arguments.toString())));
        }
        return LlmResponse.of(text, Collections.unmodifiableList(toolUses), usage, reason).withReasoningTraces(traces);
    }

    /**
     * Parses the arguments accumulated so far for a single tool_call slot into a {@link ToolUse}, without closing the
     * aggregator or mutating any state.
     *
     * <p>
     * Used by provider mappers to surface a completed tool_use block early (design §3, streaming-tool overlap) once
     * that slot's argument stream has ended, so a side-effect-free tool can start executing while the rest of the
     * response is still streaming. {@link #toLlmResponse()} stays the authoritative builder of the final response — it
     * re-parses the same (now identical) argument buffer at stream end.
     *
     * @param toolCallIndex
     *            the slot index previously registered via {@link #appendToolCallDelta(int, String, String, String)}
     * @return the parsed tool use, or {@link Optional#empty()} if the slot is unknown or still missing its id / name
     */
    public Optional<ToolUse> finalizeToolCall(int toolCallIndex) {
        final String id;
        final String name;
        final String args;
        synchronized (lock) {
            final ToolCallAccumulator slot = toolCalls.get(toolCallIndex);
            if (slot == null || slot.id == null || slot.name == null) {
                return Optional.empty();
            }
            id = slot.id;
            name = slot.name;
            args = slot.arguments.toString();
        }
        return Optional.of(ToolUse.of(id, name, parseArguments(args)));
    }

    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            final Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            // Neither the arguments nor e.getMessage(): the arguments hold whatever the call carried — a file body, a
            // command line — and Jackson copies the offending token into its message. The failure's type, where
            // parsing stopped and the length say what a cut or malformed call looks like without either.
            log.warn("Failed to parse accumulated tool_call arguments as JSON: {}{} ({} chars); the arguments are not "
                    + "logged", e.getClass().getSimpleName(), offsetClause(e), json.length());
            return new HashMap<>();
        }
    }

    /**
     * {@code " at offset N"} when the parse failure carries a known character offset, otherwise {@code ""}. Jackson
     * reports {@code -1} for an offset it does not know, and "at offset -1" would read as a fact.
     */
    private static String offsetClause(Exception e) {
        if (e instanceof JsonProcessingException parseFailure && parseFailure.getLocation() != null
                && parseFailure.getLocation().getCharOffset() >= 0) {
            return " at offset " + parseFailure.getLocation().getCharOffset();
        }
        return "";
    }

    private static final class ToolCallAccumulator {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
