package at.aimon.core.llm;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * Client interface for interacting with Large Language Model APIs.
 *
 * <p>
 * Provides an abstraction over LLM providers (Anthropic, OpenAI, etc.), allowing for different implementations and easy
 * testing.
 *
 * <p>
 * Implementations should be thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     LlmClient client = new AnthropicClient(apiKey);
 *
 *     List&lt;Message&gt; messages = List.of(new Message(Role.USER, "Hello, how are you?"));
 *
 *     LlmResponse response = client.sendMessage("You are a helpful assistant", messages, List.of()); // No tools
 *
 *     System.out.println(response.getTextContent());
 * }
 * </pre>
 */
public interface LlmClient {

    /**
     * Sends a message to the LLM and returns the response.
     *
     * <p>
     * The system prompt defines the LLM's behavior and capabilities. Messages contain the conversation history (user
     * and assistant messages). Tools define available functions the LLM can call.
     *
     * <p>
     * This method uses the default model configuration from the client. For dynamic model selection, use
     * {@link #sendMessage(String, List, List, LlmModel)}.
     *
     * @param systemPrompt
     *            The system prompt defining LLM behavior (must not be null)
     * @param messages
     *            The conversation history (must not be null, can be empty)
     * @param tools
     *            Available tools for the LLM to use (must not be null, can be empty)
     * @return The LLM response containing text and/or tools uses
     * @throws LlmClientException
     *             if the API call fails or returns an error
     * @throws NullPointerException
     *             if any parameter is null
     */
    default LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
        return sendMessage(systemPrompt, messages, tools, LlmModel.builder().build());
    }

    /**
     * Sends a message to the LLM with custom model configuration and usage attribution metadata.
     *
     * <p>
     * The default implementation ignores {@code metadata} and delegates to
     * {@link #sendMessage(String, List, List, LlmModel)}. Observability-aware clients (for example the metering
     * decorator) override this method to record attributed usage, while concrete provider implementations only need to
     * implement the non-metadata variant.
     *
     * @param systemPrompt
     *            The system prompt defining LLM behavior (must not be null)
     * @param messages
     *            The conversation history (must not be null, can be empty)
     * @param tools
     *            Available tools for the LLM to use (must not be null, can be empty)
     * @param modelConfig
     *            The model configuration (must not be null, can be empty for defaults)
     * @param metadata
     *            Usage attribution metadata (must not be null, use {@link LlmCallMetadata#empty()} if none)
     * @return The LLM response containing text and/or tools uses
     * @throws LlmClientException
     *             if the API call fails or returns an error
     * @throws NullPointerException
     *             if any parameter is null
     */
    default LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, LlmCallMetadata metadata) {
        return sendMessage(systemPrompt, messages, tools, modelConfig);
    }

    /**
     * Sends a message to the LLM using a structured, parts-aware system prompt.
     *
     * <p>
     * This overload is <b>additive and backward compatible</b>: the default implementation collapses the supplied
     * {@link SystemPromptParts} into a single {@link String} via {@link SystemPromptParts#concatenated()} and delegates
     * to {@link #sendMessage(String, List, List, LlmModel, LlmCallMetadata)}, which remains the authoritative entry
     * point for provider implementations. Existing providers therefore require <b>no changes</b>.
     *
     * <p>
     * Providers that support provider-specific caching hints (for example, Anthropic's {@code cache_control}
     * breakpoint)
     * <b>MAY override</b> this method to attach cache boundaries at part seams — typically at transitions between
     * {@link at.aimon.core.agent.prompt.Staticness#STATIC STATIC} /
     * {@link at.aimon.core.agent.prompt.Staticness#SEMI_STATIC
     * SEMI_STATIC} parts and the trailing {@link at.aimon.core.agent.prompt.Staticness#DYNAMIC DYNAMIC} parts — to
     * maximize cache hit rates without changing the LLM-visible prompt text.
     *
     * @param systemPromptParts
     *            The structured system prompt as an ordered list of parts (must not be null)
     * @param messages
     *            The conversation history (must not be null, can be empty)
     * @param tools
     *            Available tools for the LLM to use (must not be null, can be empty)
     * @param modelConfig
     *            The model configuration (must not be null, can be empty for defaults)
     * @param metadata
     *            Usage attribution metadata (must not be null, use {@link LlmCallMetadata#empty()} if none)
     * @return The LLM response containing text and/or tools uses
     * @throws LlmClientException
     *             if the API call fails or returns an error
     * @throws NullPointerException
     *             if any parameter is null
     */
    default LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
        Objects.requireNonNull(systemPromptParts, "systemPromptParts");
        return sendMessage(systemPromptParts.concatenated(), messages, tools, modelConfig, metadata);
    }

    /**
     * Cancellation-aware variant of {@link #sendMessage(SystemPromptParts, List, List, LlmModel, LlmCallMetadata)}.
     *
     * <p>
     * The default implementation <b>ignores {@code cancellation}</b> and delegates to the non-cancellation overload, so
     * providers that cannot actively abort an in-flight call automatically fall back to iteration-boundary cancellation
     * (the historical behaviour) with no code change required. Providers that can abort (and decorators in the call
     * chain) <b>MUST override</b> this method and forward {@code cancellation} so the token reaches the provider that
     * registers its abort lever via {@link LlmCancellation#onCancel(Runnable)}.
     *
     * @param systemPromptParts
     *            structured system prompt (must not be null)
     * @param messages
     *            conversation history (must not be null, can be empty)
     * @param tools
     *            available tools (must not be null, can be empty)
     * @param modelConfig
     *            model configuration (must not be null)
     * @param metadata
     *            usage attribution metadata (must not be null; use {@link LlmCallMetadata#empty()} when none)
     * @param cancellation
     *            cooperative cancellation token (must not be null; use {@link LlmCancellation#none()} when unwired)
     * @return the complete response
     * @throws at.aimon.core.llm.exception.LlmCallCancelledException
     *             if the call was aborted because {@code cancellation} was tripped
     * @throws LlmClientException
     *             if the API call fails
     * @throws NullPointerException
     *             if any parameter is null
     */
    default LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        return sendMessage(systemPromptParts, messages, tools, modelConfig, metadata);
    }

    /**
     * Sends a message to the LLM with custom model configuration.
     *
     * <p>
     * The system prompt defines the LLM's behavior and capabilities. Messages contain the conversation history (user
     * and assistant messages). Tools define available functions the LLM can call. Model config allows dynamic control
     * over model selection and parameters.
     *
     * <p>
     * Example usage:
     *
     * <pre>
     * {
     *     &#64;code
     *     LlmModel config = LlmModel.builder().model("gpt-4").temperature(0.7).maxTokens(2000).build();
     *
     *     LlmResponse response = client.sendMessage(systemPrompt, messages, tools, config);
     * }
     * </pre>
     *
     * @param systemPrompt
     *            The system prompt defining LLM behavior (must not be null)
     * @param messages
     *            The conversation history (must not be null, can be empty)
     * @param tools
     *            Available tools for the LLM to use (must not be null, can be empty)
     * @param modelConfig
     *            The model configuration (must not be null, can be empty for defaults)
     * @return The LLM response containing text and/or tools uses
     * @throws LlmClientException
     *             if the API call fails or returns an error
     * @throws NullPointerException
     *             if any parameter is null
     */
    LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig);

    /**
     * Sends a message to the LLM and publishes partial results to {@code sink} as the provider emits chunks.
     *
     * <p>
     * The default implementation is a <b>non-streaming fallback</b>: it delegates to
     * {@link #sendMessage(SystemPromptParts, List, List, LlmModel, LlmCallMetadata)}, then emits the complete response
     * as a single {@link LlmStreamChunk.Kind#TEXT_DELTA} (when there is text content) followed by a terminal
     * {@link LlmStreamChunk.Kind#STREAM_END}. Providers that support native streaming (e.g., OpenAI, Anthropic) should
     * override this method to deliver chunks as they arrive, yielding a Time-To-First-Token benefit.
     *
     * <p>
     * The returned {@link LlmResponse} must represent the complete response (same contract as {@code sendMessage});
     * callers therefore do not need to reconstruct the full response from sink-observed chunks.
     *
     * @param systemPromptParts
     *            structured system prompt (must not be null)
     * @param messages
     *            conversation history (must not be null, can be empty)
     * @param tools
     *            available tools for the LLM to use (must not be null, can be empty)
     * @param modelConfig
     *            model configuration (must not be null)
     * @param metadata
     *            usage attribution metadata (must not be null; use {@link LlmCallMetadata#empty()} when none)
     * @param options
     *            streaming options (must not be null; use {@link LlmStreamingOptions#defaults()} for standard
     *            behaviour)
     * @param sink
     *            callback consuming one or more text-delta chunks followed by exactly one stream-end chunk
     * @return the complete response (text + tool uses + usage)
     * @throws LlmClientException
     *             if the API call fails
     * @throws NullPointerException
     *             if any parameter is null
     */
    default LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink) {
        Objects.requireNonNull(systemPromptParts, "systemPromptParts");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(modelConfig, "modelConfig");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");

        final LlmResponse response = sendMessage(systemPromptParts, messages, tools, modelConfig, metadata);
        int index = 0;
        if (response.hasTextContent()) {
            sink.accept(LlmStreamChunk.textDelta(index++, response.getTextContent()));
        }
        sink.accept(LlmStreamChunk.streamEnd(index, response.getTokenUsage(), Optional.empty(),
                response.getStopReason().orElse(StopReason.UNKNOWN)));
        return response;
    }

    /**
     * Cancellation-aware variant of
     * {@link #sendMessageStreaming(SystemPromptParts, List, List, LlmModel, LlmCallMetadata, LlmStreamingOptions,
     * LlmStreamSink)}.
     *
     * <p>
     * The default implementation <b>ignores {@code cancellation}</b> and delegates to the non-cancellation overload.
     * Native-streaming providers <b>SHOULD override</b> this method and register their {@code StreamResponse.close()}
     * lever via {@link LlmCancellation#onCancel(Runnable)} so a mid-stream cancellation aborts the HTTP connection
     * immediately instead of waiting for the next chunk. Decorators in the call chain <b>MUST override</b> it and
     * forward {@code cancellation}, otherwise the token is silently dropped before it reaches the provider.
     *
     * @param systemPromptParts
     *            structured system prompt (must not be null)
     * @param messages
     *            conversation history (must not be null, can be empty)
     * @param tools
     *            available tools (must not be null, can be empty)
     * @param modelConfig
     *            model configuration (must not be null)
     * @param metadata
     *            usage attribution metadata (must not be null; use {@link LlmCallMetadata#empty()} when none)
     * @param options
     *            streaming options (must not be null)
     * @param sink
     *            chunk consumer (must not be null)
     * @param cancellation
     *            cooperative cancellation token (must not be null; use {@link LlmCancellation#none()} when unwired)
     * @return the complete response
     * @throws at.aimon.core.llm.exception.LlmCallCancelledException
     *             if the stream was aborted because {@code cancellation} was tripped
     * @throws LlmClientException
     *             if the API call fails
     * @throws NullPointerException
     *             if any parameter is null
     */
    // Stable cross-module provider contract (overridden in provider modules, decorators, and test
    // doubles); the 8 params are a cohesive positional overload family and cannot be grouped.
    @SuppressWarnings("checkstyle:ParameterNumber")
    default LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink, LlmCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        return sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata, options, sink);
    }

    /**
     * Gets the name of this LLM provider.
     *
     * @return The provider name (e.g., "Anthropic Claude", "OpenAI GPT-4")
     */
    String getProviderName();
}
