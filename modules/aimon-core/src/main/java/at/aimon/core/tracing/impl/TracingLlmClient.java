package at.aimon.core.tracing.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanType;
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
 * Inputs are summarized (message/tool counts, model). Outputs are summarized too (text length, tool-use count, tokens);
 * TRACE-02: when the injected {@link TracePayloadPolicy} {@link TracePayloadPolicy#capturesContent() captures content},
 * the response text is additionally attached (truncated to the policy cap). Secret masking is applied separately by the
 * tracer's {@code SpanRedactor} at storage time.
 *
 * <p>
 * Thread-safe and stateless (delegates own any state).
 */
public final class TracingLlmClient implements LlmClient {

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
        return traced(messages, tools, modelConfig, metadata, () -> delegate.sendMessageStreaming(systemPromptParts,
                messages, tools, modelConfig, metadata, options, sink));
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink, LlmCancellation cancellation) {
        return traced(messages, tools, modelConfig, metadata, () -> delegate.sendMessageStreaming(systemPromptParts,
                messages, tools, modelConfig, metadata, options, sink, cancellation));
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    private LlmResponse traced(List<Message> messages, List<ToolDefinition> tools, LlmModel modelConfig,
            LlmCallMetadata metadata, Supplier<LlmResponse> call) {
        final Optional<SpanContext> parent = SpanContext.readFrom(metadata);
        if (parent.isEmpty()) {
            // Not enriched by the executor — delegate without producing an orphan span.
            return call.get();
        }
        final String model = modelConfig.getName().orElseGet(delegate::getProviderName);
        final Tracer.Span span = tracer.startChild(parent.get(), SpanType.LLM, "llm:" + model,
                inputsSummary(messages, tools, model));
        try {
            final LlmResponse response = call.get();
            span.setModel(model);
            if (response.hasTokenUsage()) {
                span.setTokenUsage(response.getTokenUsage());
            }
            span.setOutputs(outputsSummary(response));
            return response;
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
        // TRACE-02: attach the (truncated) response text only when the policy captures content.
        if (payloadPolicy.capturesContent() && text != null) {
            summary.put("text", payloadPolicy.truncate(text));
        }
        return summary;
    }
}
