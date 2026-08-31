package at.aimon.core.llm.usage;

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
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * Decorator that forwards every {@link LlmClient} call to a delegate and emits a
 * {@link LlmUsageRecorder#record(String, String, at.aimon.core.llm.TokenUsage, LlmCallMetadata) usage event} for the
 * response.
 *
 * <p>
 * This keeps provider implementations (e.g. {@code OpenAILlmClient}, {@code AnthropicLlmClient}) unaware of metering
 * while allowing any call site to attach {@link LlmCallMetadata} that is captured in the recorded event.
 *
 * <p>
 * Example wiring:
 *
 * <pre>
 * {@code
 * InMemoryLlmUsageRecorder recorder = new InMemoryLlmUsageRecorder();
 * LlmClient client = new MeteringLlmClient(new OpenAILlmClient(apiKey), recorder);
 * }
 * </pre>
 *
 * <p>
 * Thread-safe provided the delegate and the recorder are thread-safe.
 */
public final class MeteringLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MeteringLlmClient.class);

    private final LlmClient delegate;
    private final LlmUsageRecorder recorder;

    /**
     * Creates a metering decorator.
     *
     * @param delegate
     *            the underlying client (must not be null)
     * @param recorder
     *            the usage recorder (must not be null; use {@link LlmUsageRecorder#NOOP} to disable)
     */
    public MeteringLlmClient(LlmClient delegate, LlmUsageRecorder recorder) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate cannot be null");
        this.recorder = Objects.requireNonNull(recorder, "Recorder cannot be null");
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, LlmCallMetadata metadata) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        // Pass metadata through so chained decorators (e.g., another MeteringLlmClient, a caching layer) see the
        // same attribution. Raw provider implementations ignore the extra argument via the default-method fallback.
        final LlmResponse response = delegate.sendMessage(systemPrompt, messages, tools, modelConfig, metadata);
        try {
            final String model = modelConfig != null ? modelConfig.getName().orElse(null) : null;
            recorder.record(delegate.getProviderName(), model, response.getTokenUsage(), metadata);
        } catch (RuntimeException e) {
            log.warn("Failed to record LLM usage: {}", e.getMessage(), e);
        }
        return response;
    }

    @Override
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmCancellation cancellation) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        // Forward the cancellation token on the non-streaming path too; without this override the LlmClient default
        // would silently drop it (delegating to the non-cancellation overload). A cancelled call throws before a
        // response is available, so no usage is recorded — consistent with any other thrown failure.
        final LlmResponse response = delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, metadata,
                cancellation);
        try {
            final String model = modelConfig != null ? modelConfig.getName().orElse(null) : null;
            recorder.record(delegate.getProviderName(), model, response.getTokenUsage(), metadata);
        } catch (RuntimeException e) {
            log.warn("Failed to record LLM usage: {}", e.getMessage(), e);
        }
        return response;
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        // Forward directly to the delegate's streaming path so providers with native streaming (OpenAI, Anthropic)
        // keep emitting token-level chunks. Without this override, the LlmClient default fallback would collapse
        // the response into a single TEXT_DELTA chunk, defeating streaming.
        final LlmResponse response = delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig,
                metadata, options, sink);
        try {
            final String model = modelConfig != null ? modelConfig.getName().orElse(null) : null;
            recorder.record(delegate.getProviderName(), model, response.getTokenUsage(), metadata);
        } catch (RuntimeException e) {
            log.warn("Failed to record LLM usage: {}", e.getMessage(), e);
        }
        return response;
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink, LlmCancellation cancellation) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        // Forward the cancellation token so provider streaming can be aborted mid-flight. A cancelled stream throws
        // before the response is available, so no usage is recorded — consistent with any other thrown failure.
        final LlmResponse response = delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig,
                metadata, options, sink, cancellation);
        try {
            final String model = modelConfig != null ? modelConfig.getName().orElse(null) : null;
            recorder.record(delegate.getProviderName(), model, response.getTokenUsage(), metadata);
        } catch (RuntimeException e) {
            log.warn("Failed to record LLM usage: {}", e.getMessage(), e);
        }
        return response;
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

}
