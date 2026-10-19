package at.aimon.core.tracing.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Spans;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * {@link LlmClient} decorator that records an {@link SpanType#LLM LLM} span around each metadata-bearing call.
 *
 * <p>
 * LLM calls are the one observation point neither hooks nor interceptors cover, so tracing them requires wrapping the
 * client. The parent span is read from the reserved {@link SpanContext} tags on {@link LlmCallMetadata} (written by the
 * executor's {@code effectiveMetadata} enrich); when the tags are absent (call not enriched) the decorator delegates
 * transparently and creates no span — it never produces orphan spans.
 *
 * <p>
 * The call's {@link LlmCallMetadata#getFeature() feature}, when set, becomes the span's {@code feature} attribute.
 * Inputs are summarized (message/tool counts, model). Outputs are summarized too (text length, tool-use count, tokens,
 * and the provider's {@code stopReason} when it reported one, so a {@code MAX_TOKENS} cut-off is visible); TRACE-02:
 * when the injected {@link TracePayloadPolicy} {@link TracePayloadPolicy#capturesContent() captures content}, the
 * response text is additionally attached (truncated to the policy cap). Secret masking is applied separately by the
 * tracer's {@code SpanRedactor} at storage time.
 *
 * <p>
 * A streaming call is marked with the {@value #STREAMING_ATTR} attribute and records {@value #TTFT_ATTR}: the
 * milliseconds from the call's start to the first content chunk (text, reasoning or a ready tool_use; the end marker
 * does not count) handed to the sink this client was given. Under {@code LlmCallGateway} that sink is the gateway's
 * per-attempt sink (a buffering one when it buffers until the first success), so the value is the provider's latency
 * for that attempt, not what an end user saw. It is absent when the stream delivered no content chunk. A call
 * cancelled through its {@link LlmCancellation} ({@link LlmCallCancelledException}) ends {@code INTERRUPTED}, not
 * {@code ERROR}; any other failure is an error. A tracer that fails to start the span leaves the call untraced, never
 * failed.
 *
 * <p>
 * Thread-safe and stateless (delegates own any state).
 */
public final class TracingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(TracingLlmClient.class);

    /** Span attribute marking a streaming call ({@code "true"}); absent on a non-streaming call. */
    public static final String STREAMING_ATTR = "streaming";

    /** Span attribute: milliseconds from a streaming call's start to its first content chunk. */
    public static final String TTFT_ATTR = "ttftMs";

    private final LlmClient delegate;
    private final Tracer tracer;
    private final TracePayloadPolicy payloadPolicy;

    /**
     * Creates a tracing client that captures summary-only outputs (no response text).
     *
     * @param delegate
     *            the wrapped LLM client that performs the actual call (must not be null)
     * @param tracer
     *            the tracer used to record LLM spans (must not be null)
     */
    public TracingLlmClient(LlmClient delegate, Tracer tracer) {
        this(delegate, tracer, TracePayloadPolicy.summaryOnly());
    }

    /**
     * TRACE-02: creates a tracing client whose output capture is governed by {@code payloadPolicy}.
     *
     * @param delegate
     *            the wrapped LLM client that performs the actual call (must not be null)
     * @param tracer
     *            the tracer used to record LLM spans (must not be null)
     * @param payloadPolicy
     *            the payload capture policy (must not be null; use {@link TracePayloadPolicy#summaryOnly()} for
     *            summary-only)
     */
    public TracingLlmClient(LlmClient delegate, Tracer tracer, TracePayloadPolicy payloadPolicy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.tracer = Objects.requireNonNull(tracer, "tracer cannot be null");
        this.payloadPolicy = Objects.requireNonNull(payloadPolicy, "payloadPolicy cannot be null");
    }

    // ── sendMessage overloads (non-metadata variant delegates; metadata variants are traced) ──

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        return delegate.sendMessage(systemPrompt, messages, tools, modelConfig);
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, LlmCallMetadata metadata) {
        return traced(messages, tools, modelConfig, metadata,
                () -> delegate.sendMessage(systemPrompt, messages, tools, modelConfig, metadata));
    }

    @Override
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
        return traced(messages, tools, modelConfig, metadata,
                () -> delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, metadata));
    }

    @Override
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmCancellation cancellation) {
        return traced(messages, tools, modelConfig, metadata,
                () -> delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, metadata, cancellation));
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink) {
        return tracedStreaming(messages, tools, modelConfig, metadata, sink, timedSink -> delegate
                .sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata, options, timedSink));
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink, LlmCancellation cancellation) {
        return tracedStreaming(messages, tools, modelConfig, metadata, sink,
                timedSink -> delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata,
                        options, timedSink, cancellation));
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public Optional<String> getDefaultModelName() {
        return delegate.getDefaultModelName();
    }

    private LlmResponse traced(List<Message> messages, List<ToolDefinition> tools, LlmModel modelConfig,
            LlmCallMetadata metadata, Supplier<LlmResponse> call) {
        final Optional<SpanContext> parent = SpanContext.readFrom(metadata);
        if (parent.isEmpty()) {
            // Not enriched by the executor — delegate without producing an orphan span.
            return call.get();
        }
        final String model = spanModel(modelConfig);
        final Tracer.Span span = Spans.startChildSafely(tracer, parent.get(), SpanType.LLM, "llm:" + model,
                inputsSummary(messages, tools, model));
        return record(span, model, metadata, call);
    }

    /**
     * Traces a streaming call like {@link #traced}, handing the delegate a sink that stamps the span's
     * {@value #TTFT_ATTR} on the first content chunk before forwarding every chunk to the caller's {@code sink}
     * unchanged. Without a parent the delegate receives the caller's sink itself.
     */
    private LlmResponse tracedStreaming(List<Message> messages, List<ToolDefinition> tools, LlmModel modelConfig,
            LlmCallMetadata metadata, LlmStreamSink sink, Function<LlmStreamSink, LlmResponse> call) {
        final Optional<SpanContext> parent = SpanContext.readFrom(metadata);
        if (parent.isEmpty()) {
            return call.apply(sink);
        }
        final String model = spanModel(modelConfig);
        final Tracer.Span span = Spans.startChildSafely(tracer, parent.get(), SpanType.LLM, "llm:" + model,
                inputsSummary(messages, tools, model));
        final long startNanos = System.nanoTime();
        final AtomicBoolean firstChunkSeen = new AtomicBoolean();
        final LlmStreamSink timedSink = chunk -> {
            if (chunk != null && chunk.getKind() != LlmStreamChunk.Kind.STREAM_END
                    && firstChunkSeen.compareAndSet(false, true)) {
                setAttributeSafely(span, TTFT_ATTR,
                        String.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)));
            }
            sink.accept(chunk);
        };
        setAttributeSafely(span, STREAMING_ATTR, "true");
        return record(span, model, metadata, () -> call.apply(timedSink));
    }

    /**
     * Sets a streaming attribute, swallowing a failing span: the stream must not depend on the tracer, and on the
     * chunk path the chunk is still forwarded to the caller's sink.
     */
    private static void setAttributeSafely(Tracer.Span span, String key, String value) {
        try {
            span.setAttribute(key, value);
        } catch (RuntimeException e) {
            log.debug("Setting span attribute '{}' failed: {}", key, e.getMessage());
        }
    }

    /**
     * The model the span is named after: the request's, then the client's own default, then the provider name. The
     * provider name no longer carries a model, so falling straight through to it would name every unoverridden span
     * after the vendor alone.
     */
    private String spanModel(LlmModel modelConfig) {
        return modelConfig.getName().or(delegate::getDefaultModelName).orElseGet(delegate::getProviderName);
    }

    /** Runs {@code call} inside {@code span}, records its outcome and closes the span. */
    private LlmResponse record(Tracer.Span span, String model, LlmCallMetadata metadata, Supplier<LlmResponse> call) {
        try {
            // The usage feature tells calls apart that share a parent — a compaction summary call under an iteration.
            metadata.getFeature().ifPresent(feature -> span.setAttribute("feature", feature));
            final LlmResponse response = call.get();
            span.setModel(model);
            if (response.hasTokenUsage()) {
                span.setTokenUsage(response.getTokenUsage());
            }
            span.setOutputs(outputsSummary(response));
            return response;
        } catch (LlmCallCancelledException e) {
            span.interrupted();
            throw e;
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.close();
        }
    }

    private static Map<String, Object> inputsSummary(List<Message> messages, List<ToolDefinition> tools, String model) {
        return Map.of("messages", messages.size(), "tools", tools.size(), "model", model);
    }

    private Map<String, Object> outputsSummary(LlmResponse response) {
        final String text = response.getTextContent();
        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("textChars", text == null ? 0 : text.length());
        summary.put("toolUses", response.getToolUses().size());
        // Only record the token count when present, so a genuine 0-token response is distinguishable from "unknown".
        if (response.hasTokenUsage()) {
            summary.put("totalTokens", response.getTokenUsage().getTotalTokens());
        }
        response.getStopReason().ifPresent(reason -> summary.put("stopReason", reason.name()));
        // TRACE-02: attach the (truncated) response text only when the policy captures content.
        if (payloadPolicy.capturesContent() && text != null) {
            summary.put("text", payloadPolicy.truncate(text));
        }
        return summary;
    }
}
