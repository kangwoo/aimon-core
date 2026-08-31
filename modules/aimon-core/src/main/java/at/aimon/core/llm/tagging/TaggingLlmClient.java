package at.aimon.core.llm.tagging;

import java.util.List;
import java.util.Objects;

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
 * Decorator that auto-attaches the per-thread ambient {@link LlmCallMetadata} from {@link LlmCallMetadataHolder} to
 * every outgoing call.
 *
 * <p>
 * Wrap any {@link LlmClient} (typically the outermost decorator, ahead of logging and metering) so that callers which
 * cannot thread metadata explicitly — including the 4-argument {@link #sendMessage(String, List, List, LlmModel)}
 * overload — still emit attribution tags for billing/observability.
 *
 * <p>
 * Merge policy: caller-supplied metadata wins on every field/tag, the ambient holder fills in only fields the caller
 * left unset (delegated to {@link LlmCallMetadata#withDefaults(LlmCallMetadata)}). When no scope is active and the
 * caller passes {@link LlmCallMetadata#empty()}, the call propagates with empty metadata — i.e., this decorator never
 * fabricates information.
 *
 * <p>
 * Example wiring:
 *
 * <pre>
 * {@code
 * LlmClient base = new OpenAILlmClient(apiKey);
 * LlmClient logged = new LoggingLlmClient(base);
 * LlmClient tagged = new TaggingLlmClient(logged);   // outermost — sees caller intent + ambient
 * }
 * </pre>
 *
 * <p>
 * Thread-safe provided the delegate is thread-safe. Stateless beyond the immutable delegate reference.
 */
public final class TaggingLlmClient implements LlmClient {

    private final LlmClient delegate;

    /**
     * Wraps the given delegate. Attribution is sourced from the current thread's {@link LlmCallMetadataHolder} on every
     * call.
     *
     * @param delegate
     *            the underlying client (must not be null)
     */
    public TaggingLlmClient(LlmClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate cannot be null");
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        // 4-arg overload: caller supplied no metadata, so ambient becomes the entire metadata when present.
        return delegate.sendMessage(systemPrompt, messages, tools, modelConfig, ambient());
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, LlmCallMetadata metadata) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return delegate.sendMessage(systemPrompt, messages, tools, modelConfig, merge(metadata));
    }

    @Override
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, merge(metadata));
    }

    @Override
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmCancellation cancellation) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, merge(metadata), cancellation);
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, merge(metadata), options,
                sink);
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink, LlmCancellation cancellation) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, merge(metadata), options,
                sink, cancellation);
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    private static LlmCallMetadata ambient() {
        return LlmCallMetadataHolder.current();
    }

    private static LlmCallMetadata merge(LlmCallMetadata caller) {
        // caller wins on every set field/tag; ambient fills in unset ones
        return caller.withDefaults(ambient());
    }
}
