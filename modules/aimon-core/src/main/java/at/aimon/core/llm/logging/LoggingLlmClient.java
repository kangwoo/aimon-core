package at.aimon.core.llm.logging;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * Decorator that emits structured request/response/error log lines around any {@link LlmClient} delegate.
 *
 * <p>
 * Logging policy:
 * <ul>
 * <li>{@code INFO}: provider, model, message/tool counts, streaming flag, latency, token usage — metadata only,
 * always safe</li>
 * <li>{@code DEBUG} (only when {@link LlmLoggingOptions#isLogBodies()} is true): truncated previews of the system
 * prompt and assistant text response — opt-in to avoid leaking user data</li>
 * <li>{@code ERROR}: re-thrown provider exceptions are logged with stack trace before propagating</li>
 * </ul>
 *
 * <p>
 * Streaming calls override {@link #sendMessageStreaming} so native provider streaming is preserved (the default
 * fallback would collapse the response into a single chunk).
 *
 * <p>
 * Example wiring:
 *
 * <pre>
 * {@code
 * LlmClient base = new OpenAILlmClient(apiKey);
 * LlmClient logged = new LoggingLlmClient(base);                      // metadata-only
 * LlmClient verbose = new LoggingLlmClient(base, LlmLoggingOptions    // DEBUG previews
 *         .builder().logBodies(true).maxPreviewChars(500).build());
 * }
 * </pre>
 *
 * <p>
 * Thread-safe provided the delegate is thread-safe. Stateless beyond the immutable options.
 */
public final class LoggingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingLlmClient.class);

    private final LlmClient delegate;
    private final LlmLoggingOptions options;

    /**
     * Creates a logging decorator with {@link LlmLoggingOptions#defaults() default options}.
     *
     * @param delegate
     *            the underlying client (must not be null)
     */
    public LoggingLlmClient(LlmClient delegate) {
        this(delegate, LlmLoggingOptions.defaults());
    }

    /**
     * Creates a logging decorator with custom options.
     *
     * @param delegate
     *            the underlying client (must not be null)
     * @param options
     *            the logging options (must not be null)
     */
    public LoggingLlmClient(LlmClient delegate, LlmLoggingOptions options) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate cannot be null");
        this.options = Objects.requireNonNull(options, "Options cannot be null");
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, LlmCallMetadata metadata) {
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(metadata, "Metadata cannot be null");

        logRequest(systemPrompt, messages, tools, modelConfig, false);
        final long startNanos = System.nanoTime();
        try {
            final LlmResponse response = delegate.sendMessage(systemPrompt, messages, tools, modelConfig, metadata);
            logResponse(modelConfig, response, startNanos, false);
            return response;
        } catch (RuntimeException e) {
            logFailure(modelConfig, startNanos, e);
            throw e;
        }
    }

    @Override
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmCancellation cancellation) {
        Objects.requireNonNull(systemPromptParts, "SystemPromptParts cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        Objects.requireNonNull(cancellation, "Cancellation cannot be null");

        // Forward the cancellation token on the non-streaming path too; without this override the LlmClient default
        // would silently drop it (delegating to the non-cancellation overload).
        logRequest(systemPromptParts.concatenated(), messages, tools, modelConfig, false);
        final long startNanos = System.nanoTime();
        try {
            final LlmResponse response = delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, metadata,
                    cancellation);
            logResponse(modelConfig, response, startNanos, false);
            return response;
        } catch (RuntimeException e) {
            logFailure(modelConfig, startNanos, e);
            throw e;
        }
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
            LlmStreamingOptions streamingOptions, LlmStreamSink sink) {
        Objects.requireNonNull(systemPromptParts, "SystemPromptParts cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        Objects.requireNonNull(streamingOptions, "StreamingOptions cannot be null");
        Objects.requireNonNull(sink, "Sink cannot be null");

        logRequest(systemPromptParts.concatenated(), messages, tools, modelConfig, true);
        final long startNanos = System.nanoTime();
        try {
            final LlmResponse response = delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig,
                    metadata, streamingOptions, sink);
            logResponse(modelConfig, response, startNanos, true);
            return response;
        } catch (RuntimeException e) {
            logFailure(modelConfig, startNanos, e);
            throw e;
        }
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
            LlmStreamingOptions streamingOptions, LlmStreamSink sink, LlmCancellation cancellation) {
        Objects.requireNonNull(systemPromptParts, "SystemPromptParts cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        Objects.requireNonNull(streamingOptions, "StreamingOptions cannot be null");
        Objects.requireNonNull(sink, "Sink cannot be null");
        Objects.requireNonNull(cancellation, "Cancellation cannot be null");

        logRequest(systemPromptParts.concatenated(), messages, tools, modelConfig, true);
        final long startNanos = System.nanoTime();
        try {
            final LlmResponse response = delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig,
                    metadata, streamingOptions, sink, cancellation);
            logResponse(modelConfig, response, startNanos, true);
            return response;
        } catch (RuntimeException e) {
            logFailure(modelConfig, startNanos, e);
            throw e;
        }
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    private void logRequest(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, boolean streaming) {
        log.info("[LLM->] provider={} model={} messages={} tools={} streaming={}", delegate.getProviderName(),
                modelName(modelConfig), messages.size(), tools.size(), streaming);
        if (options.isLogBodies() && log.isDebugEnabled()) {
            log.debug("[LLM->] systemPrompt={}", preview(systemPrompt));
        }
    }

    private void logResponse(LlmModel modelConfig, LlmResponse response, long startNanos, boolean streaming) {
        final long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        final TokenUsage usage = response.getTokenUsage();
        log.info("[LLM<-] provider={} model={} latencyMs={} hasText={} toolCalls={} tokens={}/{}/{} streaming={}",
                delegate.getProviderName(), modelName(modelConfig), latencyMs, response.hasTextContent(),
                response.getToolUses().size(), usage.getPromptTokens(), usage.getCompletionTokens(),
                usage.getTotalTokens(), streaming);
        if (options.isLogBodies() && log.isDebugEnabled() && response.hasTextContent()) {
            log.debug("[LLM<-] text={}", preview(response.getTextContent()));
        }
    }

    private void logFailure(LlmModel modelConfig, long startNanos, RuntimeException e) {
        final long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.error("[LLM!!] provider={} model={} latencyMs={} error={}", delegate.getProviderName(),
                modelName(modelConfig), latencyMs, e.getMessage(), e);
    }

    private String preview(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final int max = options.getMaxPreviewChars();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...(+" + (text.length() - max) + " chars)";
    }

    private static String modelName(LlmModel modelConfig) {
        return modelConfig == null ? "" : modelConfig.getName().orElse("");
    }
}
