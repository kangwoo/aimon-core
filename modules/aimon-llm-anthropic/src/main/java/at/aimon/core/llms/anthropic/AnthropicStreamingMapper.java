package at.aimon.core.llms.anthropic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.models.messages.InputJsonDelta;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawContentBlockStopEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextDelta;
import com.anthropic.models.messages.ToolUseBlock;

import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * Maps Anthropic streaming SDK events ({@link RawMessageStreamEvent}) to provider-neutral {@link LlmStreamChunk}s and
 * feeds them through a {@link ChunkAggregator} into the caller's {@link LlmStreamSink}.
 *
 * <p>
 * Mapper responsibilities (see design §4.4):
 *
 * <ul>
 * <li>{@code message_start} — extract the initial input token count and reserve a placeholder for output tokens until
 * {@code message_delta} arrives.</li>
 * <li>{@code content_block_start(tool_use)} — remember the tool_call id / name keyed by block index, so subsequent
 * {@code input_json} fragments can be accumulated under the correct slot.</li>
 * <li>{@code content_block_delta(text)} — emit one TEXT_DELTA per non-empty chunk.</li>
 * <li>{@code content_block_delta(input_json)} — append the partial JSON fragment to the slot via
 * {@link ChunkAggregator#appendToolCallDelta(int, String, String, String)}. Parsed once at STREAM_END.</li>
 * <li>{@code content_block_stop(tool_use)} — the tool_use block's arguments are complete; emit one TOOL_USE_READY so
 * the executor can start a side-effect-free tool during the remaining stream (design §4.1, streaming-tool
 * overlap).</li>
 * <li>{@code message_delta} — capture the stop reason and accumulated output token count.</li>
 * <li>{@code message_stop} — emit exactly one terminal STREAM_END with the merged usage and finish reason.</li>
 * </ul>
 *
 * <p>
 * Stateful and non-thread-safe by design — consume each SDK stream through a fresh instance.
 */
final class AnthropicStreamingMapper {

    private static final Logger log = LoggerFactory.getLogger(AnthropicStreamingMapper.class);

    private final LlmStreamSink sink;
    private final ChunkAggregator aggregator;

    private int nextChunkIndex;
    private Optional<String> lastFinishReason = Optional.empty();
    private StopReason lastStopReason = StopReason.UNKNOWN;
    private long inputTokens;
    private long outputTokens;
    private boolean streamEnded;

    // Anthropic uses opaque content-block indices; we track tool_use slots by the block index we saw on
    // content_block_start, and hand the same index through to the aggregator for arguments accumulation.
    private final Map<Long, ToolUseSlot> toolUseByBlockIndex = new HashMap<>();

    AnthropicStreamingMapper(LlmStreamSink sink, ChunkAggregator aggregator) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    /**
     * Consumes the SDK stream end-to-end and emits a terminal {@code STREAM_END} exactly once.
     */
    void consume(Stream<RawMessageStreamEvent> stream) {
        Objects.requireNonNull(stream, "stream");
        stream.forEach(this::onEvent);
        if (!streamEnded) {
            // Defensive fallback: Anthropic's SDK typically terminates the stream with message_stop; if the server
            // closes the connection early without one, emit a synthetic STREAM_END so the aggregator closes cleanly.
            emitStreamEnd();
        }
    }

    private void onEvent(RawMessageStreamEvent event) {
        if (event.isMessageStart()) {
            onMessageStart(event.asMessageStart());
        } else if (event.isContentBlockStart()) {
            onContentBlockStart(event.asContentBlockStart());
        } else if (event.isContentBlockDelta()) {
            onContentBlockDelta(event.asContentBlockDelta());
        } else if (event.isContentBlockStop()) {
            onContentBlockStop(event.asContentBlockStop());
        } else if (event.isMessageDelta()) {
            onMessageDelta(event.asMessageDelta());
        } else if (event.isMessageStop()) {
            emitStreamEnd();
        }
        // Other events carry no data we need to forward.
    }

    private void onMessageStart(RawMessageStartEvent event) {
        try {
            this.inputTokens = event.message().usage().inputTokens();
        } catch (RuntimeException e) {
            log.debug("Could not read input tokens from message_start: {}", e.getMessage());
        }
    }

    private void onContentBlockStart(RawContentBlockStartEvent event) {
        final RawContentBlockStartEvent.ContentBlock block = event.contentBlock();
        if (block.isToolUse()) {
            final ToolUseBlock toolUse = block.asToolUse();
            final long blockIndex = event.index();
            toolUseByBlockIndex.put(blockIndex, new ToolUseSlot(toolUse.id(), toolUse.name()));
            // Register the slot with the aggregator immediately; subsequent input_json fragments append to it.
            aggregator.appendToolCallDelta(Math.toIntExact(blockIndex), toolUse.id(), toolUse.name(), "");
        }
    }

    private void onContentBlockDelta(RawContentBlockDeltaEvent event) {
        final RawContentBlockDelta delta = event.delta();
        if (delta.isText()) {
            final TextDelta textDelta = delta.asText();
            final String text = textDelta.text();
            if (text != null && !text.isEmpty()) {
                emitTextDelta(text);
            }
        } else if (delta.isInputJson()) {
            final InputJsonDelta jsonDelta = delta.asInputJson();
            final long blockIndex = event.index();
            final ToolUseSlot slot = toolUseByBlockIndex.get(blockIndex);
            if (slot == null) {
                log.debug("Received input_json delta for unknown block index {} — dropping", blockIndex);
                return;
            }
            aggregator.appendToolCallDelta(Math.toIntExact(blockIndex), slot.id, slot.name, jsonDelta.partialJson());
        }
        // Other delta variants (thinking, signature, citations) are not yet surfaced to the framework.
    }

    /**
     * Handles {@code content_block_stop}. When it closes a tool_use block, that block's arguments are fully streamed,
     * so
     * finalize the slot from the aggregator (a non-mutating read — {@code toLlmResponse()} remains authoritative) and
     * emit one TOOL_USE_READY to the sink so the executor can start the tool during the remaining stream (design
     * §4.1).
     * Anthropic streams content blocks sequentially, so blocks close in ascending index order — the same order they
     * appear in the final response. Non-tool blocks (text) have no slot and are ignored.
     */
    private void onContentBlockStop(RawContentBlockStopEvent event) {
        final long blockIndex = event.index();
        if (!toolUseByBlockIndex.containsKey(blockIndex)) {
            return; // text or other non-tool block — nothing to surface early
        }
        final int index = Math.toIntExact(blockIndex);
        aggregator.finalizeToolCall(index)
                .ifPresent(toolUse -> sink.accept(LlmStreamChunk.toolUseReady(index, toolUse)));
    }

    private void onMessageDelta(RawMessageDeltaEvent event) {
        event.delta().stopReason().ifPresent(reason -> {
            final String wire = reason.asString();
            this.lastFinishReason = Optional.of(wire);
            this.lastStopReason = AnthropicStopReasons.fromWire(wire);
        });
        final MessageDeltaUsage usage = event.usage();
        usage.inputTokens().ifPresent(this::updateInputTokens);
        this.outputTokens = usage.outputTokens();
    }

    private void emitTextDelta(String text) {
        final LlmStreamChunk chunk = LlmStreamChunk.textDelta(nextChunkIndex++, text);
        aggregator.accept(chunk);
        sink.accept(chunk);
    }

    private void emitStreamEnd() {
        if (streamEnded) {
            return;
        }
        streamEnded = true;
        final TokenUsage usage = buildTokenUsage();
        final LlmStreamChunk end = LlmStreamChunk.streamEnd(nextChunkIndex, usage, lastFinishReason, lastStopReason);
        aggregator.accept(end);
        sink.accept(end);
    }

    private TokenUsage buildTokenUsage() {
        if (inputTokens == 0 && outputTokens == 0) {
            return null;
        }
        try {
            final int in = Math.toIntExact(inputTokens);
            final int out = Math.toIntExact(outputTokens);
            return TokenUsage.of(in, out, in + out);
        } catch (ArithmeticException e) {
            log.warn("Token count exceeded Integer range; falling back to empty usage: {}", e.getMessage());
            return TokenUsage.empty();
        }
    }

    private void updateInputTokens(long value) {
        // message_delta.usage.input_tokens is rarely present but authoritative when it is.
        this.inputTokens = value;
    }

    private static final class ToolUseSlot {
        private final String id;
        private final String name;

        ToolUseSlot(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
