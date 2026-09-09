package at.aimon.core.llms.anthropic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
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
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ToolUseBlock;

import at.aimon.core.llm.ReasoningTrace;
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
 * <li>{@code content_block_start(thinking | redacted_thinking | text)} — remember the block's kind and index too. The
 * kinds matter even for blocks that carry nothing we forward, because the anchor rule reads the block <em>order</em>:
 * a text block between a thinking block and a tool use is what makes that thinking block unanchored.</li>
 * <li>{@code content_block_delta(thinking | signature)} — accumulate into the thinking slot. Neither is emitted to
 * the sink: carrying the block across turns and showing it to a user are different features, and the second needs a
 * chunk kind {@code aimon-core} does not have.</li>
 * <li>{@code content_block_delta(text)} — emit one TEXT_DELTA per non-empty chunk.</li>
 * <li>{@code content_block_delta(input_json)} — append the partial JSON fragment to the slot via
 * {@link ChunkAggregator#appendToolCallDelta(int, String, String, String)}. Parsed once at STREAM_END.</li>
 * <li>{@code content_block_stop(tool_use)} — the tool_use block's arguments are complete; emit one TOOL_USE_READY so
 * the executor can start a side-effect-free tool during the remaining stream (design §4.1, streaming-tool
 * overlap).</li>
 * <li>{@code message_delta} — capture the stop reason and accumulated output token count.</li>
 * <li>{@code message_stop} — resolve the observed blocks into {@link ReasoningTrace}s, hand them to the aggregator,
 * and then emit exactly one terminal STREAM_END with the merged usage and finish reason. The order is not cosmetic:
 * {@link ChunkAggregator#addReasoningTrace} throws once the aggregator is closed, and the terminal chunk closes
 * it.</li>
 * </ul>
 *
 * <p>
 * Stateful and non-thread-safe by design — consume each SDK stream through a fresh instance.
 */
final class AnthropicStreamingMapper {

    private static final Logger log = LoggerFactory.getLogger(AnthropicStreamingMapper.class);

    private final LlmStreamSink sink;
    private final ChunkAggregator aggregator;
    private final String providerName;
    private final AnthropicDivergenceReporter reporter;

    private int nextChunkIndex;
    private Optional<String> lastFinishReason = Optional.empty();
    private StopReason lastStopReason = StopReason.UNKNOWN;
    private long inputTokens;
    private long outputTokens;
    private int thinkingTokens;
    private boolean streamEnded;

    // Anthropic uses opaque content-block indices; we track tool_use slots by the block index we saw on
    // content_block_start, and hand the same index through to the aggregator for arguments accumulation.
    private final Map<Long, ToolUseSlot> toolUseByBlockIndex = new HashMap<>();

    // Thinking blocks under construction, keyed the same way. A slot is seeded from the block content_block_start
    // carried — which is where any field this SDK version does not model lives — so what is finally serialised is
    // that block with its two streamed fields settled, rather than one rebuilt from scratch. The two settle
    // differently: thinking text is *appended* to whatever the start block already held, while the signature arrives
    // in a single delta and is simply set. Absence of that one delta is the drop condition (see closeThinkingBlock),
    // which is why the slot's signature starts as null rather than as the start block's empty string.
    private final Map<Long, ThinkingSlot> thinkingByBlockIndex = new HashMap<>();

    // Redacted thinking payloads, complete on arrival and held until their block closes.
    private final Map<Long, String> redactedByBlockIndex = new HashMap<>();

    // Text block indices, remembered only so content_block_stop can place them in the ordered list below.
    private final Set<Long> textBlockIndices = new HashSet<>();

    // Every closed block, in the order the provider emitted it. This is the input to the anchor rule, and it is why
    // text blocks are recorded at all: a text block between a thinking block and a tool use unanchors that thinking
    // block.
    private final List<AnthropicOutputBlocks.Block> orderedBlocks = new ArrayList<>();

    AnthropicStreamingMapper(LlmStreamSink sink, ChunkAggregator aggregator, String providerName,
            AnthropicDivergenceReporter reporter) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.providerName = Objects.requireNonNull(providerName, "providerName");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
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
        final long blockIndex = event.index();
        if (block.isToolUse()) {
            final ToolUseBlock toolUse = block.asToolUse();
            toolUseByBlockIndex.put(blockIndex, new ToolUseSlot(toolUse.id(), toolUse.name()));
            // Register the slot with the aggregator immediately; subsequent input_json fragments append to it.
            aggregator.appendToolCallDelta(Math.toIntExact(blockIndex), toolUse.id(), toolUse.name(), "");
        } else if (block.isThinking()) {
            thinkingByBlockIndex.put(blockIndex, new ThinkingSlot(block.asThinking()));
        } else if (block.isRedactedThinking()) {
            // A redacted block arrives whole here: there is no redacted-thinking delta variant, so it is complete the
            // moment it starts. It is still parked until content_block_stop rather than appended now, so that every
            // block reaches the ordered list from one place and the order cannot depend on which kinds a stream
            // happens to contain.
            redactedByBlockIndex.put(blockIndex, AnthropicReasoningTraces.payloadOf(block.asRedactedThinking()));
        } else if (block.isText()) {
            textBlockIndices.add(blockIndex);
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
        } else if (delta.isThinking()) {
            withThinkingSlot(event.index(), "thinking", slot -> slot.thinking.append(delta.asThinking().thinking()));
        } else if (delta.isSignature()) {
            withThinkingSlot(event.index(), "signature", slot -> slot.signature = delta.asSignature().signature());
        }
        // Thinking and signature deltas feed the trace payload above and are deliberately *not* forwarded to the
        // sink: replaying the block on the next request and rendering it live to a user are different features, and
        // the second needs a chunk kind aimon-core does not have. Citation deltas are still not modelled.
    }

    private void withThinkingSlot(long blockIndex, String deltaKind, Consumer<ThinkingSlot> work) {
        final ThinkingSlot slot = thinkingByBlockIndex.get(blockIndex);
        if (slot == null) {
            log.debug("Received {} delta for unknown block index {} — dropping", deltaKind, blockIndex);
            return;
        }
        work.accept(slot);
    }

    /**
     * Handles {@code content_block_stop}. When it closes a tool_use block, that block's arguments are fully streamed,
     * so
     * finalize the slot from the aggregator (a non-mutating read — {@code toLlmResponse()} remains authoritative) and
     * emit one TOOL_USE_READY to the sink so the executor can start the tool during the remaining stream (design
     * §4.1).
     * Anthropic streams content blocks sequentially, so blocks close in ascending index order — the same order they
     * appear in the final response, which is the order the anchor rule needs. Blocks of a kind that was never started
     * are ignored.
     */
    private void onContentBlockStop(RawContentBlockStopEvent event) {
        final long blockIndex = event.index();
        // remove, not get: every other slot map below is drained here too, and a block that has stopped has no
        // further deltas to route. Keeping one map filled while draining the other three reads as an oversight even
        // though the mapper is per-stream and nothing would notice.
        final ToolUseSlot toolUseSlot = toolUseByBlockIndex.remove(blockIndex);
        if (toolUseSlot != null) {
            orderedBlocks.add(AnthropicOutputBlocks.Block.toolUse(toolUseSlot.id));
            final int index = Math.toIntExact(blockIndex);
            aggregator.finalizeToolCall(index)
                    .ifPresent(toolUse -> sink.accept(LlmStreamChunk.toolUseReady(index, toolUse)));
            return;
        }
        final ThinkingSlot thinkingSlot = thinkingByBlockIndex.remove(blockIndex);
        if (thinkingSlot != null) {
            closeThinkingBlock(thinkingSlot);
            return;
        }
        final String redacted = redactedByBlockIndex.remove(blockIndex);
        if (redacted != null) {
            orderedBlocks.add(AnthropicOutputBlocks.Block.thinking(redacted));
            return;
        }
        if (textBlockIndices.remove(blockIndex)) {
            orderedBlocks.add(AnthropicOutputBlocks.Block.text());
        }
    }

    /**
     * Turns a finished thinking slot into an ordered block, or drops it.
     *
     * <p>
     * A thinking block with no {@code signature_delta} — an aborted stream, or a protocol change — is dropped rather
     * than replayed unsigned. An unsigned block is a guaranteed rejection on the next request; a dropped one costs
     * only re-derived reasoning. Note the reverse is <em>not</em> a drop condition: on the newer models thinking text
     * is omitted by default, so a block can legitimately stream zero {@code thinking_delta}s and still have to be
     * replayed. The signature is the load-bearing half.
     */
    private void closeThinkingBlock(ThinkingSlot slot) {
        if (slot.signature == null) {
            reporter.report("unsignedThinkingBlock@" + providerName,
                    "A streamed {} thinking block carried no signature and is being dropped rather than replayed "
                            + "unsigned; the model will re-derive that reasoning.",
                    providerName);
            return;
        }
        final ThinkingBlock completed = slot.block.toBuilder().thinking(slot.thinking.toString())
                .signature(slot.signature).build();
        orderedBlocks.add(AnthropicOutputBlocks.Block.thinking(AnthropicReasoningTraces.payloadOf(completed)));
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
        // The docs put the thinking breakdown only on the final message_delta, so this is the one place to read it.
        this.thinkingTokens = AnthropicUsages.thinkingTokens(usage);
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
        // Before the terminal chunk, never after: the STREAM_END closes the aggregator and addReasoningTrace throws
        // once it is closed — from toLlmResponse()'s caller, outside the client's try-with-resources, where nothing
        // would map it. Both callers of this method reach the flush through here for that reason.
        flushReasoningTraces();
        final TokenUsage usage = buildTokenUsage();
        final LlmStreamChunk end = LlmStreamChunk.streamEnd(nextChunkIndex, usage, lastFinishReason, lastStopReason);
        aggregator.accept(end);
        sink.accept(end);
    }

    private void flushReasoningTraces() {
        for (ReasoningTrace trace : AnthropicOutputBlocks.resolve(orderedBlocks, providerName)) {
            aggregator.addReasoningTrace(trace);
        }
    }

    private TokenUsage buildTokenUsage() {
        if (inputTokens == 0 && outputTokens == 0) {
            return null;
        }
        try {
            final int in = Math.toIntExact(inputTokens);
            final int out = Math.toIntExact(outputTokens);
            // Thinking tokens are billed as output tokens, so the fourth counter reports a breakdown of `out` rather
            // than adding to the total.
            return TokenUsage.of(in, out, in + out, thinkingTokens);
        } catch (ArithmeticException e) {
            log.warn("Token count exceeded Integer range; falling back to empty usage: {}", e.getMessage());
            return TokenUsage.empty();
        }
    }

    private void updateInputTokens(long value) {
        // message_delta.usage.input_tokens is rarely present but authoritative when it is.
        this.inputTokens = value;
    }

    /**
     * A thinking block being streamed: the block as it started, plus the two fields the protocol re-streams.
     *
     * <p>
     * Mutable on purpose and package-private to nothing — this is the mapper's own accumulation buffer, in the same
     * shape as {@link ToolUseSlot} beside it, and the mapper is documented as stateful and single-threaded.
     */
    private static final class ThinkingSlot {
        private final ThinkingBlock block;
        private final StringBuilder thinking = new StringBuilder();
        private String signature;

        ThinkingSlot(ThinkingBlock block) {
            this.block = block;
            // Read through the raw accessor: thinking() is required and throws on a block that omits it, and this
            // runs on a live stream where a lost trace is cheaper than a lost turn. Anthropic sends "" here today,
            // so seeding is inert against the current protocol — it is what stops a server that ever put text on
            // content_block_start from having it silently overwritten by the deltas.
            block._thinking().asKnown().ifPresent(this.thinking::append);
        }
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
