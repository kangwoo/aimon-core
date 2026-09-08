package at.aimon.core.llms.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * OpenAI implementation of {@link LlmClient}.
 *
 * <p>
 * Supports OpenAI's Chat Completion API with tool calling.
 *
 * <p>
 * Key features:
 *
 * <ul>
 * <li>Uses OpenAI tool calling for tool execution
 * <li>Supports GPT-4o, GPT-5.x, and other chat models
 * </ul>
 *
 * <p>
 * Thread-safe if OpenAIClient is thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OpenAIConfig config = OpenAIConfig.builder().apiKey(System.getenv("OPENAI_API_KEY")).model("gpt-4o")
 *             .build();
 *
 *     LlmClient client = new OpenAILlmClient(config);
 *
 *     List<Message> messages = List.of(Message.user("What is 2+2?"));
 *
 *     LlmResponse response = client.sendMessage("You are a helpful assistant", messages, List.of());
 * }
 * </pre>
 */
public class OpenAILlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAILlmClient.class);

    /**
     * Ceiling on how many distinct request divergences {@link #reportedDivergences} remembers.
     *
     * <p>
     * Distinct values come from configuration — an agent definition's frontmatter, a starter property — so in any real
     * deployment the count is the number of agents, not the number of requests. The cap exists because this client
     * outlives every request that passes through it, and a caller that generates model configs programmatically would
     * otherwise grow the set without bound. Past the cap the client stops reporting rather than stops remembering: by
     * then it has already emitted 32 warnings, and a deployment that diverges in 32 distinct ways has a configuration
     * problem that a log line is the wrong instrument for.
     */
    private static final int MAX_REPORTED_DIVERGENCES = 32;

    private final OpenAIConfig config;
    private final OpenAIClient client;
    private final OpenAIMessageConverter converter;

    /**
     * Request divergences already reported, so a value dropped on every request is said once instead of once per ReAct
     * iteration.
     *
     * <p>
     * Keyed by parameter, value <em>and model</em>, not by parameter alone. One client is shared by every agent bound
     * to this provider and a deployment can address more than one model, so keying more narrowly would report whichever
     * combination went first and leave the others silent — which is the failure this reporting exists to remove.
     */
    private final Set<String> reportedDivergences = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new OpenAILlmClient.
     *
     * @param config
     *            The OpenAI configuration (must not be null)
     * @throws NullPointerException
     *             if config is null
     */
    public OpenAILlmClient(OpenAIConfig config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.client = createOpenAIClient(config);
        this.converter = new OpenAIMessageConverter();
    }

    /**
     * Creates OpenAIClient with configuration.
     *
     * @param config
     *            The OpenAI configuration
     * @return Configured OpenAIClient instance
     */
    private OpenAIClient createOpenAIClient(OpenAIConfig config) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(config.getApiKey())
                .timeout(config.getTimeout());

        String baseUrl = config.getBaseUrl();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    /**
     * Creates a new OpenAILlmClient with custom client (for testing).
     *
     * @param config
     *            The OpenAI configuration
     * @param client
     *            The OpenAI client
     */
    OpenAILlmClient(OpenAIConfig config, OpenAIClient client) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.client = Objects.requireNonNull(client, "Client cannot be null");
        this.converter = new OpenAIMessageConverter();
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(modelConfig, "Model config cannot be null");

        ChatCompletionCreateParams request = buildRequest(systemPrompt, messages, tools, modelConfig, null);

        try {
            // Call OpenAI API. When the caller set a per-request timeout, pass it through as a
            // RequestOptions override; otherwise keep the single-argument overload so the client-wide default timeout
            // applies unchanged (zero behaviour change for the common case).
            final RequestOptions requestOptions = perRequestOptions(modelConfig);
            ChatCompletion result = requestOptions == null
                    ? client.chat().completions().create(request)
                    : client.chat().completions().create(request, requestOptions);

            // Convert response
            return convertResponse(result);

        } catch (LlmClientException e) {
            // Do not double-wrap framework exceptions propagated from response conversion.
            throw e;
        } catch (Exception e) {
            log.error("OpenAI API call failed: {}", e.getMessage(), e);
            throw OpenAIExceptionMapper.map(e, "OpenAI API call failed");
        }
    }

    @Override
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmCancellation cancellation) {
        Objects.requireNonNull(systemPromptParts, "systemPromptParts");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(modelConfig, "modelConfig");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(cancellation, "cancellation");

        // Non-streaming in-flight abort: a blocking chat().completions().create() call exposes no handle we can
        // trip mid-flight, so on its own it can only observe cancellation at the next iteration boundary. When the
        // caller supplied a live cancellation token, route the non-streaming request through the streaming path —
        // which owns the proven, thread-safe StreamResponse.close() abort lever — and reassemble the aggregated chunks
        // into a single response. The caller wanted a non-streaming *result*, not incremental delivery, so chunk
        // emissions are discarded; usage is still requested (includeUsage defaults to true, wiring
        // stream_options.include_usage) so token accounting matches the blocking path. When the token can never fire
        // (LlmCancellation.none(), the common non-cancellation case), keep the cheaper single-shot blocking call
        // unchanged — zero behaviour change for that path.
        if (!cancellation.isSupported()) {
            return sendMessage(systemPromptParts, messages, tools, modelConfig, metadata);
        }
        return sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata,
                LlmStreamingOptions.defaults(), LlmStreamSink.discarding(), cancellation);
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink) {
        Objects.requireNonNull(systemPromptParts, "systemPromptParts");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(modelConfig, "modelConfig");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");

        return sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata, options, sink,
                LlmCancellation.none());
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink, LlmCancellation cancellation) {
        Objects.requireNonNull(systemPromptParts, "systemPromptParts");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(modelConfig, "modelConfig");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(cancellation, "cancellation");

        // Fast path: cancelled before we open the HTTP connection.
        if (cancellation.isCancelled()) {
            throw new LlmCallCancelledException("OpenAI streaming call cancelled before start");
        }

        final ChatCompletionCreateParams request = buildRequest(systemPromptParts.concatenated(), messages, tools,
                modelConfig, options);
        final ChunkAggregator aggregator = new ChunkAggregator();
        final OpenAIStreamingMapper mapper = new OpenAIStreamingMapper(sink, aggregator);

        // A per-request timeout, when set, also bounds the streaming call (worst-case ceiling incl. no-progress
        // stalls); when unset, keep the single-argument overload so the client-wide default applies unchanged.
        final RequestOptions requestOptions = perRequestOptions(modelConfig);
        try (StreamResponse<ChatCompletionChunk> streamResponse = requestOptions == null
                ? client.chat().completions().createStreaming(request)
                : client.chat().completions().createStreaming(request, requestOptions)) {
            // Register the abort lever: StreamResponse.close() cancels the underlying OkHttp call — thread-safe and
            // idempotent. If cancellation already fired, onCancel invokes close() synchronously now, so the stream
            // read below unwinds through the catch blocks as a cancellation.
            cancellation.onCancel(streamResponse::close);
            mapper.consume(streamResponse.stream());
        } catch (LlmCallCancelledException e) {
            throw e;
        } catch (LlmClientException e) {
            if (cancellation.isCancelled()) {
                throw new LlmCallCancelledException("OpenAI streaming call aborted by cancellation", e);
            }
            throw e;
        } catch (Exception e) {
            // A cancelled stream surfaces here as the SDK's stream-closed IOException; classify it as a cancellation
            // (terminal, non-retryable) rather than a generic transient failure.
            if (cancellation.isCancelled()) {
                throw new LlmCallCancelledException("OpenAI streaming call aborted by cancellation", e);
            }
            log.error("OpenAI streaming call failed: {}", e.getMessage(), e);
            throw OpenAIExceptionMapper.map(e, "OpenAI streaming call failed");
        }

        return aggregator.toLlmResponse();
    }

    /**
     * Builds a {@link ChatCompletionCreateParams} request shared by both the synchronous and streaming entry points.
     *
     * @param streamingOptions
     *            when non-null, enables {@code stream_options.include_usage} per the caller's preference; when null
     *            the request is built for the synchronous path.
     */
    private ChatCompletionCreateParams buildRequest(String systemPrompt, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmStreamingOptions streamingOptions) {
        List<ChatCompletionMessageParam> chatMessages = buildChatMessages(systemPrompt, messages);

        // The model name is resolved once and is the only thing this method knows about the model. Everything that
        // varies per model comes back through the capability registry, so there is no model-name branching here.
        final String modelName = modelConfig.getName().orElse(config.getModel());
        final ModelCapabilities capabilities = capabilitiesFor(modelName);

        ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder().model(modelName)
                .messages(chatMessages)
                .maxCompletionTokens((long) modelConfig.getMaxTokens().orElse(config.getMaxTokens()));

        applySamplingParameters(requestBuilder, modelConfig, capabilities, modelName);
        applyReasoningEffort(requestBuilder, modelConfig, capabilities, modelName, tools);

        if (!tools.isEmpty()) {
            List<ChatCompletionTool> openaiTools = converter.convertTools(tools);
            requestBuilder.tools(openaiTools);
        }

        if (streamingOptions != null) {
            requestBuilder.streamOptions(
                    ChatCompletionStreamOptions.builder().includeUsage(streamingOptions.isIncludeUsage()).build());
        }

        return requestBuilder.build();
    }

    /**
     * Sets {@code temperature} / {@code top_p} / the two penalties, or sets none of them.
     *
     * <p>
     * "Sets none of them" means the setters are never called. It cannot be expressed as passing {@code null} or an
     * empty {@link Optional}: both SDK overloads route through {@code JsonField.ofNullable}, which turns null into
     * {@code JsonNull} and puts {@code "temperature": null} on the wire — and a model that rejects the parameter
     * rejects it by <em>presence</em>, so the null form fails exactly like the value form. That is why the capability
     * check branches before the builder call rather than computing a nullable effective value.
     *
     * <p>
     * When sampling is accepted, a parameter is set if and only if somebody put a value on the request: the request's
     * {@link LlmModel} first, then this client's {@link OpenAIConfig}. There is no third step — this client does not
     * manufacture a sampling value nobody asked for, so an unconfigured request leaves the server's own default in
     * force. All four parameters resolve and apply identically.
     */
    private void applySamplingParameters(ChatCompletionCreateParams.Builder requestBuilder, LlmModel modelConfig,
            ModelCapabilities capabilities, String modelName) {
        final Optional<Double> temperature = modelConfig.getTemperature().or(config::getTemperature);
        final Optional<Double> topP = modelConfig.getTopP().or(config::getTopP);
        final Optional<Double> presencePenalty = modelConfig.getPresencePenalty().or(config::getPresencePenalty);
        final Optional<Double> frequencyPenalty = modelConfig.getFrequencyPenalty().or(config::getFrequencyPenalty);

        if (!capabilities.supportsSamplingParameters()) {
            // Every suppressible value is one somebody set, so every suppression is worth reporting and an
            // unconfigured request stays silent by construction rather than by a special case.
            reportSuppressedSamplingParameter("temperature", temperature, modelName);
            reportSuppressedSamplingParameter("topP", topP, modelName);
            reportSuppressedSamplingParameter("presencePenalty", presencePenalty, modelName);
            reportSuppressedSamplingParameter("frequencyPenalty", frequencyPenalty, modelName);
            return;
        }

        temperature.ifPresent(requestBuilder::temperature);
        topP.ifPresent(requestBuilder::topP);
        presencePenalty.ifPresent(requestBuilder::presencePenalty);
        frequencyPenalty.ifPresent(requestBuilder::frequencyPenalty);
    }

    /**
     * Sets {@code reasoning_effort}, or leaves it off.
     *
     * <p>
     * Three cases, and the middle one is the whole point of this method. A model that takes no reasoning effort gets
     * nothing, which is what every release before this one sent to every model. A model that takes one but cannot
     * combine it with tools gets {@link ReasoningEffort#NONE} <em>explicitly</em> whenever tools are present:
     * <em>omitting</em> the parameter is not equivalent, because the server's own default for these models is
     * {@code medium} and the request fails on that. Otherwise the configured effort goes through as asked, or nothing
     * does when none was configured.
     */
    private void applyReasoningEffort(ChatCompletionCreateParams.Builder requestBuilder, LlmModel modelConfig,
            ModelCapabilities capabilities, String modelName, List<ToolDefinition> tools) {
        final Optional<ReasoningEffort> requested = modelConfig.getReasoningEffort().or(config::getReasoningEffort);

        if (!capabilities.supportsReasoningEffort()) {
            requested.ifPresent(effort -> reportDivergence("reasoningEffort=" + effort + "@" + modelName,
                    "reasoningEffort {} is set on this request but {} takes no reasoning-effort parameter; it is "
                            + "being omitted and the call will succeed without it.",
                    effort, modelName));
            return;
        }

        if (!tools.isEmpty() && !capabilities.supportsToolsWithReasoning()) {
            requested.filter(effort -> effort != ReasoningEffort.NONE)
                    .ifPresent(effort -> reportDivergence("reasoningEffortClamped=" + effort + "@" + modelName,
                            "reasoningEffort {} is set on this request but {} does not accept tools together with "
                                    + "reasoning; it is being sent as NONE for this call.",
                            effort, modelName));
            requestBuilder.reasoningEffort(OpenAiReasoningEfforts.toWire(ReasoningEffort.NONE));
            return;
        }

        requested.ifPresent(effort -> requestBuilder.reasoningEffort(OpenAiReasoningEfforts.toWire(effort)));
    }

    /**
     * Resolves the model's capabilities, degrading to {@link ModelCapabilities#unknown()} if the registry misbehaves.
     *
     * <p>
     * The registry is caller-supplied, and {@link #buildRequest} runs <em>outside</em> the streaming path's
     * try-with-resources: an exception escaping here would bypass both the exception mapper and the cancellation
     * classification. Swallowing it applies this SPI's own fail-open rule to the SPI itself, so a third-party bug
     * costs a warning rather than the request.
     */
    private ModelCapabilities capabilitiesFor(String modelName) {
        try {
            final ModelCapabilities resolved = config.getModelCapabilityRegistry().resolve(modelName);
            if (resolved == null) {
                // resolve() is documented as total, but an implementation may override the default method and break
                // that. Reported rather than absorbed: the throwing branch below warns, and a silent degradation
                // sitting beside a reported one teaches an operator that capability lookups never fail.
                reportDivergence("capabilityLookupReturnedNull@" + modelName,
                        "Model capability lookup for {} returned null, which its contract forbids; treating the "
                                + "model as unknown, so the request keeps its default shape.",
                        modelName);
                return ModelCapabilities.unknown();
            }
            return resolved;
        } catch (RuntimeException e) {
            reportDivergence("capabilityLookupFailed@" + modelName,
                    "Model capability lookup for {} failed ({}); treating the model as unknown, so the request keeps "
                            + "its default shape.",
                    modelName, e.toString());
            return ModelCapabilities.unknown();
        }
    }

    /**
     * Reports one sampling parameter that somebody configured and that the target model does not accept.
     *
     * <p>
     * The wording deliberately says only that the value <em>is set on this request</em>. It cannot say who set it: a
     * subagent turn carries a temperature from {@code SubagentLlmDefaults}, not from an operator, and nothing at this
     * point distinguishes the two.
     */
    private void reportSuppressedSamplingParameter(String name, Optional<Double> value, String modelName) {
        value.ifPresent(v -> reportDivergence(name + "=" + v + "@" + modelName,
                "{} {} is set on this request but {} does not accept sampling parameters; it is being omitted and the "
                        + "call will succeed without it.",
                name, v, modelName));
    }

    /**
     * Reports, at most once per distinct signature, that this provider is not sending a configured value as given.
     *
     * <p>
     * {@code WARN} rather than {@code DEBUG} because the observable outcome is a request that <em>succeeds</em> with
     * settings other than the ones configured: there is no error, no status code, and nothing else in the system that
     * would tell an operator the two differ. At {@code DEBUG} the divergence exists but nobody sees it, which is
     * indistinguishable from it not happening.
     *
     * <p>
     * Once per signature rather than once per call because this runs inside {@link #buildRequest}, which runs on every
     * ReAct iteration: a value set once in an agent definition would otherwise warn for the lifetime of the process.
     *
     * @param signature
     *            parameter, value and model, the key that decides whether this has already been said
     * @param message
     *            SLF4J-formatted message
     * @param args
     *            values for the message placeholders
     */
    private void reportDivergence(String signature, String message, Object... args) {
        // size() before add() can let a burst of concurrent first-time divergences overshoot the cap by the number of
        // threads in flight. That is a bounded, harmless overshoot, and paying for exactness here would mean locking
        // on a path that runs once per LLM call.
        if (reportedDivergences.size() >= MAX_REPORTED_DIVERGENCES || !reportedDivergences.add(signature)) {
            return;
        }
        log.warn(message, args);
    }

    /**
     * Builds a per-request {@link RequestOptions} carrying the model's {@code requestTimeout} worst-case ceiling,
     * or {@code null} when no per-request timeout is set.
     *
     * <p>
     * Returning {@code null} lets the caller keep the single-argument SDK overload, so the client-wide
     * {@link OpenAIConfig#getTimeout()} default applies unchanged — a true no-op for the common case. The
     * single-{@link java.time.Duration} {@code timeout(...)} sets the overall request ceiling; connect/read/write
     * fall back to the client defaults via the SDK's {@code applyDefaults}.
     */
    private RequestOptions perRequestOptions(LlmModel modelConfig) {
        return modelConfig.getRequestTimeout().map(timeout -> RequestOptions.builder().timeout(timeout).build())
                .orElse(null);
    }

    @Override
    public String getProviderName() {
        return "OpenAI";
    }

    @Override
    public Optional<String> getDefaultModelName() {
        return Optional.of(config.getModel());
    }

    /**
     * Builds chat messages including system prompt.
     *
     * @param systemPrompt
     *            The system prompt
     * @param messages
     *            The conversation messages
     * @return List of chat messages
     */
    private List<ChatCompletionMessageParam> buildChatMessages(String systemPrompt, List<Message> messages) {
        List<ChatCompletionMessageParam> chatMessages = new ArrayList<>();

        // Add system message
        chatMessages.add(ChatCompletionMessageParam
                .ofSystem(ChatCompletionSystemMessageParam.builder().content(systemPrompt).build()));

        // Add conversation messages (with role conversion)
        chatMessages.addAll(converter.convertMessages(messages));

        return chatMessages;
    }

    /**
     * Converts OpenAI response to aimon LlmResponse.
     *
     * @param result
     *            The OpenAI chat completion result
     * @return The aimon LlmResponse
     */
    private LlmResponse convertResponse(ChatCompletion result) {
        if (result.choices() == null || result.choices().isEmpty()) {
            throw new LlmInvalidRequestException("No choices in OpenAI response");
        }

        var choice = result.choices().get(0);
        var message = choice.message();

        // Log warning if response was truncated, and map the finish reason to the provider-neutral enum so
        // aimon-core can detect truncation (length) without knowing OpenAI's raw vocabulary.
        var finishReason = choice.finishReason();
        final String finishReasonWire = finishReason == null ? null : finishReason.toString();
        if ("length".equals(finishReasonWire)) {
            log.warn("OpenAI response was truncated due to max_tokens limit");
        }
        final at.aimon.core.llm.StopReason neutralStopReason = OpenAiStopReasons.fromWire(finishReasonWire);

        String textContent = message.content().orElse("");
        List<ToolUse> toolUses = new ArrayList<>();

        // Convert tool calls to tool uses
        if (message.toolCalls().isPresent() && !message.toolCalls().get().isEmpty()) {
            for (ChatCompletionMessageToolCall toolCall : message.toolCalls().get()) {
                if (toolCall.function().isPresent()) {
                    ChatCompletionMessageFunctionToolCall functionCall = toolCall.function().get();
                    Map<String, Object> input = converter.parseJsonToMap(functionCall.function().arguments());

                    ToolUse toolUse = ToolUse.of(functionCall.id(), functionCall.function().name(), input);
                    toolUses.add(toolUse);
                }
            }
        }

        TokenUsage tokenUsage = extractTokenUsage(result);
        return LlmResponse.of(textContent, toolUses, tokenUsage, neutralStopReason);
    }

    /**
     * Extracts token usage from OpenAI response.
     *
     * @param result
     *            The OpenAI chat completion result
     * @return The token usage (never null)
     */
    private TokenUsage extractTokenUsage(ChatCompletion result) {
        if (result.usage().isEmpty()) {
            return TokenUsage.empty();
        }

        var usage = result.usage().get();
        return TokenUsage.of(Math.toIntExact(usage.promptTokens()), Math.toIntExact(usage.completionTokens()),
                Math.toIntExact(usage.totalTokens()));
    }
}
