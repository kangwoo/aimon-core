package at.aimon.core.llms.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
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
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * OpenAI implementation of {@link LlmClient}.
 *
 * <p>
 * Supports both of OpenAI's request surfaces with tool calling, and picks between them <em>per request</em> rather
 * than per client: a model whose {@link ModelCapabilities#supportsReasoningTraceRoundTrip()} is true goes to
 * {@code /v1/responses}, where its reasoning items can be replayed on the next turn so the reasoning survives a tool
 * call; everything else goes to {@code /v1/chat/completions} exactly as before. Everything that differs between the
 * two lives behind {@link OpenAIEndpointExchange}; everything they share — the cancellation fast path, the abort
 * lever, the catch cascade and the per-request timeout — stays here, once.
 *
 * <p>
 * Key features:
 *
 * <ul>
 * <li>Uses OpenAI tool calling for tool execution
 * <li>Supports GPT-4o, GPT-5.x, and other chat models
 * <li>Carries reasoning traces across tool calls for models that return them
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
    private final OpenAIResponsesMessageConverter responsesConverter;
    private final OpenAIResponsesRequestFactory responsesRequestFactory;

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
        this.responsesConverter = new OpenAIResponsesMessageConverter();
        this.responsesRequestFactory = new OpenAIResponsesRequestFactory(this.responsesConverter, this.config,
                this::reportDivergence);
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
        this.responsesConverter = new OpenAIResponsesMessageConverter();
        this.responsesRequestFactory = new OpenAIResponsesRequestFactory(this.responsesConverter, this.config,
                this::reportDivergence);
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(modelConfig, "Model config cannot be null");

        // Built before the try, exactly where buildRequest used to run: a failure while building the request escapes
        // unmapped, as it does today, rather than being accidentally improved or worsened by the refactor.
        final OpenAIEndpointExchange exchange = exchangeFor(systemPrompt, messages, tools, modelConfig, null);

        try {
            // Call OpenAI API. When the caller set a per-request timeout, pass it through as a
            // RequestOptions override; otherwise keep the single-argument overload so the client-wide default timeout
            // applies unchanged (zero behaviour change for the common case).
            return exchange.callBlocking(perRequestOptions(modelConfig));

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

        final OpenAIEndpointExchange exchange = exchangeFor(systemPromptParts.concatenated(), messages, tools,
                modelConfig, options);
        final ChunkAggregator aggregator = new ChunkAggregator();

        // A per-request timeout, when set, also bounds the streaming call (worst-case ceiling incl. no-progress
        // stalls); when unset, keep the single-argument overload so the client-wide default applies unchanged.
        final RequestOptions requestOptions = perRequestOptions(modelConfig);
        try (OpenAIStreamHandle handle = exchange.openStream(requestOptions, sink, aggregator)) {
            // Register the abort lever: the handle's close() delegates to StreamResponse.close(), which cancels the
            // underlying OkHttp call — thread-safe and idempotent. If cancellation already fired, onCancel invokes
            // close() synchronously now, so the stream read below unwinds through the catch blocks as a cancellation.
            cancellation.onCancel(handle::close);
            handle.consume();
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
     * Picks the endpoint for this request and builds its parameters.
     *
     * <p>
     * The model name is resolved once — the request's {@link LlmModel} overrides this client's configured model — and
     * everything that varies per model comes back through the capability registry, so there is still no model-name
     * string anywhere in this decision. That the name comes from the <em>request</em> is what makes the choice
     * per-request rather than per-client: a {@code gpt-4o} compaction call inside a {@code gpt-5.x} session is the
     * ordinary case, not an exotic one, and it must reach Chat Completions while its session reaches Responses.
     *
     * <p>
     * The provider name is resolved here for the same reason and at the same moment, then handed to both halves of
     * the reasoning round trip, so the name a trace is tagged with is the name it is later matched against.
     *
     * <p>
     * Two conditions, answering two different questions. {@link ModelCapabilities#supportsReasoningTraceRoundTrip()}
     * says what the <em>model</em> does; {@link OpenAIConfig#isResponsesApiEnabled()} says what this
     * <em>deployment's endpoint</em> offers. A model whose capabilities could not be resolved degrades to
     * {@link ModelCapabilities#unknown()}, whose answer is {@code false}, so an unresolvable model is never routed to
     * the new endpoint.
     *
     * @param streamingOptions
     *            when non-null, enables {@code stream_options.include_usage} per the caller's preference on the Chat
     *            path; when null the request is built for the synchronous path. The Responses API has no counterpart
     *            — its {@code stream_options} carries only {@code include_obfuscation} — because usage is not opt-in
     *            there.
     */
    private OpenAIEndpointExchange exchangeFor(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, LlmStreamingOptions streamingOptions) {
        final String modelName = modelConfig.getName().orElse(config.getModel());
        final ModelCapabilities capabilities = capabilitiesFor(modelName);

        if (capabilities.supportsReasoningTraceRoundTrip() && config.isResponsesApiEnabled()) {
            // Read once, and used on both sides of the round trip: the request factory decides which stored traces
            // are ours, the exchange tags the ones that come back. getProviderName() is overridable, so resolving it
            // in two places at two times is how a subclass ends up tagging traces it then drops as foreign.
            final String providerName = getProviderName();
            return new OpenAIResponsesExchange(client, responsesConverter, responsesRequestFactory.build(systemPrompt,
                    messages, tools, modelConfig, capabilities, modelName, providerName), providerName,
                    this::reportDivergence);
        }
        return new OpenAIChatCompletionsExchange(client, converter, buildChatRequest(systemPrompt, messages, tools,
                modelConfig, streamingOptions, capabilities, modelName));
    }

    /**
     * Builds a {@link ChatCompletionCreateParams} request shared by both the synchronous and streaming entry points.
     *
     * @param streamingOptions
     *            when non-null, enables {@code stream_options.include_usage} per the caller's preference; when null
     *            the request is built for the synchronous path.
     */
    private ChatCompletionCreateParams buildChatRequest(String systemPrompt, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmStreamingOptions streamingOptions,
            ModelCapabilities capabilities, String modelName) {
        List<ChatCompletionMessageParam> chatMessages = buildChatMessages(systemPrompt, messages);

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
        // The rule itself lives in OpenAiRequestParameters, because it is the same rule on both endpoints and a
        // second copy would drift silently. The reporter is this client's own method so that the WARN keeps this
        // class's logger and its single once-per-signature set no matter which endpoint the request took.
        OpenAiRequestParameters.applySampling(modelConfig, config, capabilities, modelName,
                new ChatSamplingSink(requestBuilder), this::reportDivergence);
    }

    /** Applies accepted sampling values to a Chat Completions request; all four parameters exist on this endpoint. */
    private static final class ChatSamplingSink implements OpenAiRequestParameters.SamplingSink {

        private final ChatCompletionCreateParams.Builder builder;

        private ChatSamplingSink(ChatCompletionCreateParams.Builder builder) {
            this.builder = builder;
        }

        @Override
        public void temperature(double value) {
            builder.temperature(value);
        }

        @Override
        public void topP(double value) {
            builder.topP(value);
        }

        @Override
        public void presencePenalty(double value) {
            builder.presencePenalty(value);
        }

        @Override
        public void frequencyPenalty(double value) {
            builder.frequencyPenalty(value);
        }
    }

    /**
     * Sets {@code reasoning_effort}, or leaves it off.
     *
     * <p>
     * Three ways it comes off, and only the second is a Chat Completions rule. A model that takes no reasoning effort
     * gets nothing, which is what every release before this one sent to every model. A model that takes one but
     * cannot combine it with tools has the effort <em>omitted</em> whenever tools are present — that middle case is
     * the endpoint's, and {@code /v1/responses} deliberately has no counterpart to it. A rung the model's ladder does
     * not have is omitted by {@link OpenAiRequestParameters#maySendEffort}, which is <em>shared</em> with the other
     * endpoint precisely because it is a fact about the model rather than about this request surface. Otherwise the
     * configured effort goes through as asked, or nothing does when none was configured.
     *
     * <p>
     * The middle case used to send {@link ReasoningEffort#NONE} explicitly, on the theory that omission would leave
     * the server's own default of {@code medium} in force and fail. Measured against the live API on 2026-09-09, both
     * halves of that were wrong: {@code none} is not an accepted value for these models ({@code gpt-5-nano} answers
     * <em>Supported values are: 'minimal', 'low', 'medium', and 'high'</em>), while a tools request that simply omits
     * the parameter returns 200. So the remedy is omission, and sending {@code NONE} was itself the bug. The probe
     * table is in section 11 of {@code docs/design/llm/openai-model-capabilities.md}.
     */
    private void applyReasoningEffort(ChatCompletionCreateParams.Builder requestBuilder, LlmModel modelConfig,
            ModelCapabilities capabilities, String modelName, List<ToolDefinition> tools) {
        final Optional<ReasoningEffort> requested = OpenAiRequestParameters.requestedEffort(modelConfig, config);

        if (!capabilities.supportsReasoningEffort()) {
            requested.ifPresent(effort -> OpenAiRequestParameters.reportUnsupportedEffort(effort, modelName,
                    this::reportDivergence));
            return;
        }

        // Endpoint rule, and the only one of the three that is: it exists because Chat Completions is where tools and
        // reasoning were believed to conflict. It stays here rather than in OpenAiRequestParameters for that reason.
        if (!tools.isEmpty() && !capabilities.supportsToolsWithReasoning()) {
            requested.ifPresent(effort -> reportDivergence("reasoningEffortOmitted=" + effort + "@" + modelName,
                    "reasoningEffort {} is set on this request but {} does not accept tools together with reasoning; "
                            + "it is being omitted for this call.",
                    effort, modelName));
            return;
        }

        // Model rule, shared with the Responses path: NONE is off every OpenAI ladder, and MINIMAL is off the
        // o-series one.
        if (requested.isPresent() && !OpenAiRequestParameters.maySendEffort(requested.get(), capabilities, modelName,
                this::reportDivergence)) {
            return;
        }

        requested.ifPresent(effort -> requestBuilder.reasoningEffort(OpenAiReasoningEfforts.toWire(effort)));
    }

    /**
     * Resolves the model's capabilities, degrading to {@link ModelCapabilities#unknown()} if the registry misbehaves.
     *
     * <p>
     * The registry is caller-supplied, and {@link #exchangeFor} — this method's only caller — runs <em>outside</em>
     * the streaming path's try-with-resources: an exception escaping here would bypass both the exception mapper and
     * the cancellation classification. Swallowing it applies this SPI's own fail-open rule to the SPI itself, so a
     * third-party bug
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
     * Reports, at most once per distinct signature, that this provider is not sending a configured value as given.
     *
     * <p>
     * {@code WARN} rather than {@code DEBUG} because the observable outcome is a request that <em>succeeds</em> with
     * settings other than the ones configured: there is no error, no status code, and nothing else in the system that
     * would tell an operator the two differ. At {@code DEBUG} the divergence exists but nobody sees it, which is
     * indistinguishable from it not happening.
     *
     * <p>
     * Once per signature rather than once per call because its callers — {@link #exchangeFor} and everything it
     * builds a request through, plus the converters and the stream mapper — all run on every ReAct iteration: a value
     * set once in an agent definition would otherwise warn for the lifetime of the process.
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
}
