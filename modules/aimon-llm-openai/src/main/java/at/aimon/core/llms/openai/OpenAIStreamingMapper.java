package at.aimon.core.llms.openai;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.errors.OpenAIIoException;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.completions.CompletionUsage;

import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * Maps OpenAI streaming SDK events ({@link ChatCompletionChunk}) to provider-neutral {@link LlmStreamChunk}s and feeds
 * them through a {@link ChunkAggregator} into the caller's {@link LlmStreamSink}.
 *
 * <p>
 * Mapper responsibilities (see design §4.4):
 *
 * <ul>
 * <li>Filter out empty text deltas — {@link LlmStreamChunk#textDelta(int, String)} forbids empty strings.</li>
 * <li>Accumulate {@code choices[0].delta.tool_calls[*].function.arguments} fragments via
 * {@link ChunkAggregator#appendToolCallDelta(int, String, String, String)}. Tool_call argument JSON is parsed once at
 * {@code STREAM_END}.</li>
 * <li>Surface a completed tool_call block as one TOOL_USE_READY the moment a higher-index tool_call begins (OpenAI
 * streams tool_calls sequentially by index) — and flush the final one at STREAM_END — so the executor can start a
 * side-effect-free tool during the remaining stream (design §4.1, streaming-tool overlap).</li>
 * <li>Capture the {@code finish_reason} from the last chunk's choice and the cumulative {@code usage} (only emitted
 * when {@code stream_options.include_usage=true}).</li>
 * <li>Emit exactly one terminal {@link LlmStreamChunk.Kind#STREAM_END} after the SDK stream completes.</li>
 * </ul>
 *
 * <p>
 * Stateful and non-thread-safe by design — consume each SDK stream through a fresh instance.
 */
final class OpenAIStreamingMapper {

    private static final Logger log = LoggerFactory.getLogger(OpenAIStreamingMapper.class);

    private final LlmStreamSink sink;
    private final ChunkAggregator aggregator;

    private int nextChunkIndex;
    private Optional<String> lastFinishReason = Optional.empty();
    private StopReason lastStopReason = StopReason.UNKNOWN;
    private TokenUsage lastUsage;

    // Streaming-tool overlap (design §4.1): OpenAI streams tool_calls sequentially by index, so a slot is complete
    // once
    // a strictly-higher index appears. highestToolCallIndex tracks the max index seen; emittedToolUseUpTo tracks the
    // highest index already surfaced as TOOL_USE_READY. The final (highest) slot is flushed at emitStreamEnd().
    private int highestToolCallIndex = -1;
    private int emittedToolUseUpTo = -1;

    OpenAIStreamingMapper(LlmStreamSink sink, ChunkAggregator aggregator) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    /**
     * Consumes the SDK stream end-to-end, emitting one TEXT_DELTA per non-empty content delta and exactly one
     * STREAM_END at the tail.
     */
    void consume(Stream<ChatCompletionChunk> stream) {
        consume(stream, LlmCancellation.none());
    }

    /**
     * As {@link #consume(Stream)} — unless the stream stopped before any {@code finish_reason} while
     * {@code cancellation} is tripped.
     *
     * <p>
     * A stream can end in two ways short of {@code [DONE]}, and the terminal event decides what either means. A
     * stream the SDK closed itself ends silently: the sequence simply stops. A stream whose transport was cut — by
     * the call's abort lever, or by the network — fails loudly, as the SDK's {@code OpenAIIoException} out of the
     * loop. Before the {@code finish_reason} chunk both are classified by the token: a silent end under a tripped
     * token is a cancellation (the part that arrived must not be handed back as the whole answer, so no terminal
     * chunk is emitted — the sink's stream is the caller's to end), and a loud one propagates for the client's
     * cascade to classify. After the {@code finish_reason} chunk neither changes the answer: it is complete, so a
     * transport failure in the tail is logged and the terminal chunk is emitted as if the stream had ended well. On
     * this endpoint usage may trail {@code finish_reason} in its own chunk; a tail cut before it leaves the usage
     * empty, the same fallback a routed non-streaming call already accepts.
     *
     * @throws LlmCallCancelledException
     *             if the stream ended without a {@code finish_reason} and {@code cancellation} is tripped
     * @throws OpenAIIoException
     *             if the transport failed before any {@code finish_reason}
     */
    void consume(Stream<ChatCompletionChunk> stream, LlmCancellation cancellation) {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(cancellation, "cancellation");
        try {
            stream.forEach(this::onChunk);
        } catch (OpenAIIoException e) {
            if (lastFinishReason.isEmpty()) {
                throw e;
            }
            logFailureAfterTerminalEvent(cancellation, e);
        }
        if (lastFinishReason.isEmpty() && cancellation.isCancelled()) {
            throw new LlmCallCancelledException(
                    "OpenAI streaming call aborted by cancellation: the stream ended before a finish_reason");
        }
        emitStreamEnd();
    }

    /**
     * DEBUG under a tripped token — the abort cut the tail, which is what it is for — and WARN otherwise, because a
     * provider that resets after its answer is news an operator should see even though the call succeeds.
     */
    private static void logFailureAfterTerminalEvent(LlmCancellation cancellation, OpenAIIoException e) {
        if (cancellation.isCancelled()) {
            log.debug("OpenAI stream cut after its finish_reason; the answer is complete", e);
        } else {
            log.warn("OpenAI stream failed after its finish_reason; the answer is complete and is returned", e);
        }
    }

    private void onChunk(ChatCompletionChunk chunk) {
        chunk.usage().ifPresent(this::captureUsage);

        final List<ChatCompletionChunk.Choice> choices = chunk.choices();
        if (choices == null || choices.isEmpty()) {
            return;
        }

        final ChatCompletionChunk.Choice choice = choices.get(0);
        final ChatCompletionChunk.Choice.Delta delta = choice.delta();

        delta.content().filter(text -> !text.isEmpty()).ifPresent(this::emitTextDelta);
        delta.toolCalls().ifPresent(this::accumulateToolCalls);

        choice.finishReason().ifPresent(reason -> {
            final String wire = reason.asString();
            this.lastFinishReason = Optional.of(wire);
            this.lastStopReason = OpenAiStopReasons.fromWire(wire);
        });
    }

    private void emitTextDelta(String text) {
        final LlmStreamChunk chunk = LlmStreamChunk.textDelta(nextChunkIndex++, text);
        aggregator.accept(chunk);
        sink.accept(chunk);
    }

    private void accumulateToolCalls(List<ChatCompletionChunk.Choice.Delta.ToolCall> toolCalls) {
        for (ChatCompletionChunk.Choice.Delta.ToolCall call : toolCalls) {
            final int index = Math.toIntExact(call.index());
            final String id = call.id().orElse(null);
            final String name = call.function().flatMap(ChatCompletionChunk.Choice.Delta.ToolCall.Function::name)
                    .orElse(null);
            final String argsFragment = call.function()
                    .flatMap(ChatCompletionChunk.Choice.Delta.ToolCall.Function::arguments).orElse("");
            aggregator.appendToolCallDelta(index, id, name, argsFragment);
            if (index > highestToolCallIndex) {
                // A higher-index tool_call has begun: every slot strictly below it has finished streaming its
                // arguments (OpenAI streams them sequentially), so surface those completed slots now.
                emitToolUsesReadyThrough(index - 1);
                highestToolCallIndex = index;
            }
        }
    }

    /**
     * Surfaces each not-yet-emitted, completed tool_call slot in {@code [emittedToolUseUpTo+1, upToIndexInclusive]} as
     * a
     * TOOL_USE_READY, in ascending index order (the same order they appear in the final response). Finalizing is a
     * non-mutating read of the aggregator; {@code toLlmResponse()} stays the authoritative builder. A slot missing its
     * id/name yields no chunk but still advances the cursor — {@code toLlmResponse()} skips such a slot too, so it
     * never
     * becomes an un-harvested tool.
     */
    private void emitToolUsesReadyThrough(int upToIndexInclusive) {
        for (int i = emittedToolUseUpTo + 1; i <= upToIndexInclusive; i++) {
            final int slot = i;
            aggregator.finalizeToolCall(slot)
                    .ifPresent(toolUse -> sink.accept(LlmStreamChunk.toolUseReady(slot, toolUse)));
            emittedToolUseUpTo = slot;
        }
    }

    private void captureUsage(CompletionUsage usage) {
        try {
            this.lastUsage = TokenUsage.of(Math.toIntExact(usage.promptTokens()),
                    Math.toIntExact(usage.completionTokens()), Math.toIntExact(usage.totalTokens()));
        } catch (ArithmeticException e) {
            log.warn("Token count exceeded Integer range; falling back to empty usage: {}", e.getMessage());
            this.lastUsage = TokenUsage.empty();
        }
    }

    private void emitStreamEnd() {
        // Flush the final (highest-index) tool_call block, whose completion is only known once the stream ends — no
        // higher index ever arrives to trigger it. No-op when there were no tool_calls (highestToolCallIndex == -1).
        emitToolUsesReadyThrough(highestToolCallIndex);
        final LlmStreamChunk end = LlmStreamChunk.streamEnd(nextChunkIndex, lastUsage, lastFinishReason,
                lastStopReason);
        aggregator.accept(end);
        sink.accept(end);
    }
}
