package at.aimon.core.llms.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputItemDoneEvent;
import com.openai.models.responses.ResponseStreamEvent;

import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
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
 * unmapped. The two throwing events below unwind before the drain finishes, which is exactly what they are for.
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
 * Stateful and non-thread-safe by design — consume each SDK stream through a fresh instance.
 */
final class OpenAIResponsesStreamingMapper {

    private final LlmStreamSink sink;
    private final ChunkAggregator aggregator;
    private final OpenAIResponsesMessageConverter converter;
    private final String providerName;
    private final OpenAIDivergenceReporter reporter;

    /**
     * The output items as {@code output_item.done} delivered them: in output order, and including the
     * {@code function_call} items the reasoning anchor rule needs.
     */
    private final List<ResponseOutputItem> completedItems = new ArrayList<>();

    private int nextChunkIndex;
    private TokenUsage lastUsage;
    private String lastStatus;
    private String lastIncompleteReason;

    OpenAIResponsesStreamingMapper(LlmStreamSink sink, ChunkAggregator aggregator,
            OpenAIResponsesMessageConverter converter, String providerName, OpenAIDivergenceReporter reporter) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.providerName = Objects.requireNonNull(providerName, "providerName");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    /**
     * Consumes the SDK stream end-to-end, emitting one TEXT_DELTA per non-empty text delta and exactly one STREAM_END
     * at the tail.
     */
    void consume(Stream<ResponseStreamEvent> stream) {
        Objects.requireNonNull(stream, "stream");
        stream.forEach(this::onEvent);
        emitStreamEnd();
    }

    private void onEvent(ResponseStreamEvent event) {
        if (event.isOutputTextDelta()) {
            final String delta = event.asOutputTextDelta().delta();
            if (!delta.isEmpty()) {
                emitTextDelta(delta);
            }
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

    private void captureTerminal(Response response) {
        this.lastUsage = OpenAiResponseUsages.toTokenUsage(response.usage());
        this.lastStatus = response.status().map(status -> status._value().asString().orElse(null)).orElse(null);
        this.lastIncompleteReason = response.incompleteDetails().flatMap(Response.IncompleteDetails::reason)
                .map(reason -> reason._value().asString().orElse(null)).orElse(null);
    }

    private void emitStreamEnd() {
        final OpenAIResponsesMessageConverter.Output output = converter.convertOutput(completedItems, providerName);
        for (ReasoningTrace trace : output.getReasoningTraces()) {
            aggregator.addReasoningTrace(trace);
        }
        if (output.reasoningWithoutEncryptedContent()) {
            // A feature that quietly does nothing is the failure this whole path exists to fix; it should at least
            // say so once.
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
