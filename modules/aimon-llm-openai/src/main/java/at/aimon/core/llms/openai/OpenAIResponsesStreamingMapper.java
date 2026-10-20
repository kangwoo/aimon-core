package at.aimon.core.llms.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.errors.OpenAIIoException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputItemDoneEvent;
import com.openai.models.responses.ResponseStreamEvent;

import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * Maps Responses streaming events to provider-neutral {@link LlmStreamChunk}s and feeds them through a
 * {@link ChunkAggregator} into the caller's {@link LlmStreamSink}.
 *
 * <p>
 * Mirrors {@link OpenAIStreamingMapper}'s shape — per-event dispatch, then exactly one {@code STREAM_END} after the
 * stream is drained. Draining before closing matters beyond symmetry: {@link ChunkAggregator#toLlmResponse()} throws
 * if it is reached unclosed, and the client calls it <em>outside</em> its try, so an unclosed aggregator would escape
 * unmapped. The three throwing arms below — {@code response.failed}, {@code response.error}, and a terminal event
 * whose nested status is itself a failure — unwind before the drain finishes, which is exactly what they are for.
 *
 * <p>
 * <strong>Reasoning items are taken from {@code response.output_item.done}, not from the terminal response.</strong>
 * The SDK says so about this exact field: <em>"When streaming, use the completed reasoning item and its
 * {@code encrypted_content} from the {@code response.output_item.done} event in subsequent requests… This is
 * especially important when {@code store} is {@code false}"</em> — and {@code store: false} is what this client
 * chooses. Reading the terminal {@code Response.output()} instead would leave the whole feature inert on the streaming
 * path (the ReAct loop's primary path) while every test whose fixtures the author writes stayed green.
 *
 * <p>
 * <strong>Reasoning <em>text</em> is a different payload from the reasoning <em>item</em>, and the two never
 * mix.</strong>
 * The item above carries {@code encrypted_content} and is what the next request replays; the deltas below carry a
 * server-written summary (or, on a model that emits it, raw reasoning text) and are what a person watches. They are
 * forwarded only when this deployment asked for a summary, and {@code addReasoningTrace} is never called with one —
 * putting summary text into a trace would change what is replayed to the model on the next iteration.
 *
 * <p>
 * Stateful and non-thread-safe by design — consume each SDK stream through a fresh instance.
 */
final class OpenAIResponsesStreamingMapper {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponsesStreamingMapper.class);

    private final LlmStreamSink sink;
    private final ChunkAggregator aggregator;
    private final OpenAIResponsesMessageConverter converter;
    private final String providerName;
    private final OpenAIDivergenceReporter reporter;

    /** Whether reasoning deltas reach the sink — see {@link OpenAIResponsesExchange}'s constructor. */
    private final boolean forwardReasoning;

    /** Whether reasoning items become traces at all — see {@link OpenAIResponsesExchange}'s constructor. */
    private final boolean captureReasoning;

    /**
     * The output items as {@code output_item.done} delivered them: in output order, and including the
     * {@code function_call} items the reasoning anchor rule needs.
     */
    private final List<ResponseOutputItem> completedItems = new ArrayList<>();

    private int nextChunkIndex;
    private TokenUsage lastUsage;
    private String lastStatus;
    private String lastIncompleteReason;

    /** Whether {@code response.completed} or {@code response.incomplete} arrived — see {@link #consume}. */
    private boolean terminalSeen;

    OpenAIResponsesStreamingMapper(LlmStreamSink sink, ChunkAggregator aggregator,
            OpenAIResponsesMessageConverter converter, String providerName, OpenAIDivergenceReporter reporter,
            boolean forwardReasoning) {
        this(sink, aggregator, converter, providerName, reporter, forwardReasoning, true);
    }

    OpenAIResponsesStreamingMapper(LlmStreamSink sink, ChunkAggregator aggregator,
            OpenAIResponsesMessageConverter converter, String providerName, OpenAIDivergenceReporter reporter,
            boolean forwardReasoning, boolean captureReasoning) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.providerName = Objects.requireNonNull(providerName, "providerName");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.forwardReasoning = forwardReasoning;
        this.captureReasoning = captureReasoning;
    }

    /**
     * Consumes the SDK stream end-to-end, emitting one TEXT_DELTA per non-empty text delta and exactly one STREAM_END
     * at the tail.
     */
    void consume(Stream<ResponseStreamEvent> stream) {
        consume(stream, LlmCancellation.none());
    }

    /**
     * As {@link #consume(Stream)} — unless the stream stopped before its terminal event
     * ({@code response.completed} or {@code response.incomplete}) while {@code cancellation} is tripped.
     *
     * <p>
     * A stream can end in two ways short of EOF, and the terminal event decides what either means. A stream the SDK
     * closed itself ends silently: the sequence simply stops. A stream whose transport was cut — by the call's abort
     * lever, or by the network — fails loudly, as the SDK's {@code OpenAIIoException} out of the loop. Before the
     * terminal event both are classified by the token: a silent end under a tripped token is a cancellation (the
     * part that arrived must not be handed back as the whole answer, so no terminal chunk is emitted — the sink's
     * stream is the caller's to end), and a loud one propagates for the client's cascade to classify. After the
     * terminal event neither changes the answer: it is complete, usage included, so a transport failure in the tail
     * is logged and the terminal chunk is emitted as if the stream had ended well.
     *
     * @throws LlmCallCancelledException
     *             if the stream ended without a terminal event and {@code cancellation} is tripped
     * @throws OpenAIIoException
     *             if the transport failed before the terminal event
     */
    void consume(Stream<ResponseStreamEvent> stream, LlmCancellation cancellation) {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(cancellation, "cancellation");
        try {
            stream.forEach(this::onEvent);
        } catch (OpenAIIoException e) {
            if (!terminalSeen) {
                throw e;
            }
            logFailureAfterTerminalEvent(cancellation, e);
        }
        if (!terminalSeen && cancellation.isCancelled()) {
            throw new LlmCallCancelledException(
                    "OpenAI streaming call aborted by cancellation: the stream ended before response.completed");
        }
        emitStreamEnd();
    }

    /**
     * DEBUG under a tripped token — the abort cut the tail, which is what it is for — and WARN otherwise, because a
     * provider that resets after its answer is news an operator should see even though the call succeeds.
     */
    private static void logFailureAfterTerminalEvent(LlmCancellation cancellation, OpenAIIoException e) {
        if (cancellation.isCancelled()) {
            log.debug("OpenAI stream cut after its terminal event; the answer is complete", e);
        } else {
            log.warn("OpenAI stream failed after its terminal event; the answer is complete and is returned", e);
        }
    }

    private void onEvent(ResponseStreamEvent event) {
        if (event.isOutputTextDelta()) {
            final String delta = event.asOutputTextDelta().delta();
            if (!delta.isEmpty()) {
                emitTextDelta(delta);
            }
        } else if (forwardReasoning && event.isReasoningSummaryTextDelta()) {
            emitReasoningDelta(event.asReasoningSummaryTextDelta().delta());
        } else if (forwardReasoning && event.isReasoningTextDelta()) {
            // Both families, under the one gate. A model that emits raw reasoning text rather than a summary would
            // otherwise show nothing to a deployment that asked to see reasoning — the always-empty channel this
            // whole feature exists to remove, at one-model granularity.
            emitReasoningDelta(event.asReasoningTextDelta().delta());
        } else if (event.isOutputItemAdded()) {
            openToolCallSlot(event.asOutputItemAdded());
        } else if (event.isFunctionCallArgumentsDelta()) {
            appendToolCallArguments(event.asFunctionCallArgumentsDelta());
        } else if (event.isFunctionCallArgumentsDone()) {
            finishToolCall(event.asFunctionCallArgumentsDone());
        } else if (event.isOutputItemDone()) {
            final ResponseOutputItemDoneEvent done = event.asOutputItemDone();
            completedItems.add(done.item());
        } else if (event.isCompleted()) {
            captureTerminal(event.asCompleted().response());
        } else if (event.isIncomplete()) {
            // Not an error: "incomplete" is a stop reason (max_output_tokens, content_filter).
            captureTerminal(event.asIncomplete().response());
        } else if (event.isFailed()) {
            throw OpenAiResponseErrors.fromFailedEvent(event.asFailed(), "OpenAI streaming call failed");
        } else if (event.isError()) {
            throw OpenAiResponseErrors.fromErrorEvent(event.asError(), "OpenAI streaming call failed");
        }
        // Everything else is ignored, as OpenAIStreamingMapper ignores what it does not model.
    }

    private void emitTextDelta(String text) {
        final LlmStreamChunk chunk = LlmStreamChunk.textDelta(nextChunkIndex++, text);
        aggregator.accept(chunk);
        sink.accept(chunk);
    }

    /**
     * Emits one reasoning delta, filtering empties as {@link #emitTextDelta} does.
     *
     * <p>
     * Deliberately does <b>not</b> call {@code addReasoningTrace}: the trace on this path is the reasoning item's
     * {@code encrypted_content}, and a summary is not a substitute for it. Merging the two would change what is
     * replayed to the model next iteration, which is the kind of thing a later reader tidies up — hence this sentence.
     */
    private void emitReasoningDelta(String reasoning) {
        if (reasoning == null || reasoning.isEmpty()) {
            return;
        }
        final LlmStreamChunk chunk = LlmStreamChunk.reasoningDelta(nextChunkIndex++, reasoning);
        aggregator.accept(chunk);
        sink.accept(chunk);
    }

    /**
     * Opens a tool-call slot, keyed by {@code output_index}.
     *
     * <p>
     * Not by the item's own id: {@code ResponseFunctionToolCall.id()} is optional in the SDK while {@code call_id} is
     * required, so a gateway that omits the item id would lose the slot. {@code output_index} is a required
     * {@code Long} on this event family <em>and</em> on the argument-delta family, which is what makes it the key both
     * ends agree on.
     */
    private void openToolCallSlot(ResponseOutputItemAddedEvent event) {
        final ResponseOutputItem item = event.item();
        if (!item.isFunctionCall()) {
            return;
        }
        final var call = item.asFunctionCall();
        aggregator.appendToolCallDelta(Math.toIntExact(event.outputIndex()), call.callId(), call.name(), "");
    }

    private void appendToolCallArguments(ResponseFunctionCallArgumentsDeltaEvent event) {
        aggregator.appendToolCallDelta(Math.toIntExact(event.outputIndex()), null, null, event.delta());
    }

    /**
     * Surfaces one completed tool_call block so the executor can start a side-effect-free tool while the rest of the
     * response is still streaming — the same overlap signal the Chat and Anthropic mappers emit, and simpler here
     * because this endpoint says outright when a call's arguments are done.
     */
    private void finishToolCall(ResponseFunctionCallArgumentsDoneEvent event) {
        final int slot = Math.toIntExact(event.outputIndex());
        aggregator.finalizeToolCall(slot).ifPresent(toolUse -> sink.accept(LlmStreamChunk.toolUseReady(slot, toolUse)));
    }

    /**
     * Records the terminal event's usage and status — or fails, when that status says the turn did not produce an
     * answer.
     *
     * <p>
     * The check mirrors {@code OpenAIResponsesExchange.convertResponse}, and it is here for the same reason it is
     * there: without it a {@code response.completed} whose nested {@code response.status} is {@code failed} or
     * {@code cancelled} would close the aggregator normally and yield a successful {@link at.aimon.core.llm.LlmResponse
     * LlmResponse} with {@link StopReason#UNKNOWN} — the silent success the executor accepts as the assistant's final
     * answer, which is precisely what {@link OpenAiResponseErrors} exists to prevent. A conforming provider sends
     * {@code response.failed} for that condition and never reaches here; a gateway that does not, must not be able to
     * turn a failure into an empty answer by picking the wrong event type. The throw happens inside
     * {@code consume(...)}, so it lands in the client's cascade exactly as the two throwing event arms above do.
     */
    private void captureTerminal(Response response) {
        if (OpenAiResponseErrors.isFailureStatus(response)) {
            throw OpenAiResponseErrors.fromResponse(response, "OpenAI streaming call failed");
        }

        this.terminalSeen = true;
        this.lastUsage = OpenAiResponseUsages.toTokenUsage(response.usage());
        this.lastStatus = response.status().map(status -> status._value().asString().orElse(null)).orElse(null);
        this.lastIncompleteReason = response.incompleteDetails().flatMap(Response.IncompleteDetails::reason)
                .map(reason -> reason._value().asString().orElse(null)).orElse(null);
    }

    private void emitStreamEnd() {
        final OpenAIResponsesMessageConverter.Output output = converter.convertStreamedOutput(completedItems,
                providerName);
        if (captureReasoning) {
            for (ReasoningTrace trace : output.getReasoningTraces()) {
                aggregator.addReasoningTrace(trace);
            }
        }
        if (captureReasoning && output.reasoningWithoutEncryptedContent()) {
            // A feature that quietly does nothing is the failure this whole path exists to fix; it should at least
            // say so — and keep saying so while it lasts, which is why the client hands this mapper its recurring
            // counter rather than its once set.
            reporter.report("reasoningWithoutEncryptedContent@" + providerName,
                    "This turn produced reasoning items but none carried encrypted_content, so nothing can be "
                            + "replayed on the next turn and the model will re-derive its reasoning. Check that the "
                            + "deployment honours include=reasoning.encrypted_content.");
        }

        final StopReason stopReason = OpenAiResponseStopReasons.fromStatus(lastStatus, lastIncompleteReason,
                output.hasToolCalls());
        final LlmStreamChunk end = LlmStreamChunk.streamEnd(nextChunkIndex, lastUsage, Optional.ofNullable(lastStatus),
                stopReason);
        aggregator.accept(end);
        sink.accept(end);
    }
}
