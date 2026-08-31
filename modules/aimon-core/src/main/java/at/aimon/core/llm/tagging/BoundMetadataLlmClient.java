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
 * Decorator that statically binds a fixed {@link LlmCallMetadata} to every outgoing call.
 *
 * <p>
 * Use this when a single component owns its identity tags (e.g. {@code component=memory},
 * {@code feature=summarize}) and wants those attached unconditionally — without relying on the per-thread
 * {@link LlmCallMetadataHolder}.
 *
 * <p>
 * Merge policy: caller-supplied metadata wins on every set field/tag, the bound metadata fills in only the fields the
 * caller left unset (delegated to {@link LlmCallMetadata#withDefaults(LlmCallMetadata)}). The 4-arg
 * {@link #sendMessage(String, List, List, LlmModel)} overload — which carries no caller metadata — is delegated with
 * exactly the bound metadata.
 *
 * <p>
 * Typical wiring inside a component constructor:
 *
 * <pre>
 * {@code
 * public LlmMemorySummarizer(LlmClient base) {
 *     this.llm = new BoundMetadataLlmClient(base,
 *             LlmCallMetadata.builder().component("memory").feature("summarize").build());
 * }
 * }
 * </pre>
 *
 * <p>
 * This decorator is independent of {@link TaggingLlmClient}: pair them when both per-component identity tags and
 * thread-ambient context tags should flow into the same call. The recommended layering is
 * {@code Tagging( Bound( Logging( provider ) ) )} so that ambient ThreadLocal tags can override or augment what the
 * component bound at construction time, while the component's identity is preserved when no ambient is active.
 *
 * <p>
 * Thread-safe provided the delegate is thread-safe. Stateless beyond the immutable references.
 */
public final class BoundMetadataLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final LlmCallMetadata bound;

    /**
     * Wraps the given delegate and binds a fixed metadata to every outgoing call.
     *
     * @param delegate
     *            the underlying client (must not be null)
     * @param bound
     *            the metadata to attach as defaults (must not be null; pass {@link LlmCallMetadata#empty()} for a
     *            no-op decorator)
     */
    public BoundMetadataLlmClient(LlmClient delegate, LlmCallMetadata bound) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate cannot be null");
        this.bound = Objects.requireNonNull(bound, "Bound metadata cannot be null");
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        return delegate.sendMessage(systemPrompt, messages, tools, modelConfig, bound);
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, LlmCallMetadata metadata) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return delegate.sendMessage(systemPrompt, messages, tools, modelConfig, metadata.withDefaults(bound));
    }

    @Override
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, metadata.withDefaults(bound));
    }

    @Override
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmCancellation cancellation) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, metadata.withDefaults(bound),
                cancellation);
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig,
                metadata.withDefaults(bound), options, sink);
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink, LlmCancellation cancellation) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig,
                metadata.withDefaults(bound), options, sink, cancellation);
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

}
