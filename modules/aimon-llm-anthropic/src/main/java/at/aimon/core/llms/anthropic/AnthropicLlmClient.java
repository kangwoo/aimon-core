package at.aimon.core.llms.anthropic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ThinkingConfigParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.core.type.TypeReference;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.llms.anthropic.exception.MessageConversionException;
import at.aimon.core.llms.anthropic.exception.ToolConversionException;

/**
 * Anthropic implementation of {@link LlmClient}.
 *
 * <p>
 * Supports Anthropic's Messages API with tool calling.
 *
 * <p>
 * Key features:
 *
 * <ul>
 * <li>Uses Anthropic tool calling for tool execution
 * <li>Supports Claude Sonnet, Opus, Haiku and other Claude models
 * <li>System prompt is set via the dedicated system parameter (not as a message)
 * </ul>
 *
 * <p>
 * Thread-safe if AnthropicClient is thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AnthropicConfig config = AnthropicConfig.builder().apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *             .model("claude-sonnet-4-20250514").build();
 *
 *     LlmClient client = new AnthropicLlmClient(config);
 *
 *     List<Message> messages = List.of(Message.user("What is 2+2?"));
 *
 *     LlmResponse response = client.sendMessage("You are a helpful assistant", messages, List.of());
 * }
 * </pre>
 */
public class AnthropicLlmClient implements LlmClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

    /**
     * Ceiling on how many distinct sampling divergences {@link #reportedDivergences} remembers.
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

    /**
     * The bottom of the {@code top_p} window Anthropic accepts alongside thinking. Outside {@code [0.95, 1.0]} the
     * parameter is rejected rather than clamped, so it is omitted instead.
     */
    private static final double MIN_TOP_P_WITH_THINKING = 0.95;

    private final AnthropicConfig config;
    private final AnthropicClient client;
    private final AnthropicMessageConverter converter;

    /**
     * Sampling divergences already reported, so a value that diverges on every request is said once instead of once per
     * ReAct iteration.
     *
     * <p>
     * Keyed by parameter <em>and value</em>, not by parameter alone. One client is shared by every agent bound to this
     * provider, so two agents can diverge differently; keying on the parameter would report whichever agent went first
     * and leave the other silent — which is the failure this reporting exists to remove.
     */
    private final Set<String> reportedDivergences = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new AnthropicLlmClient.
     *
     * @param config
     *            The Anthropic configuration (must not be null)
     * @throws NullPointerException
     *             if config is null
     */
    public AnthropicLlmClient(AnthropicConfig config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.client = createAnthropicClient(config);
        this.converter = new AnthropicMessageConverter();
    }

    /**
     * Creates AnthropicClient with configuration.
     *
     * @param config
     *            The Anthropic configuration
     * @return Configured AnthropicClient instance
     */
    private AnthropicClient createAnthropicClient(AnthropicConfig config) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(config.getApiKey())
                .timeout(config.getTimeout());

        String baseUrl = config.getBaseUrl();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    /**
     * Creates a new AnthropicLlmClient with custom client (for testing).
     *
     * @param config
     *            The Anthropic configuration
     * @param client
     *            The Anthropic client
     */
    AnthropicLlmClient(AnthropicConfig config, AnthropicClient client) {
        this(config, client, new AnthropicMessageConverter());
    }

    /**
     * Creates a new AnthropicLlmClient with custom client and converter (for testing).
     *
     * @param config
     *            The Anthropic configuration
     * @param client
     *            The Anthropic client
     * @param converter
     *            The message converter
     */
    AnthropicLlmClient(AnthropicConfig config, AnthropicClient client, AnthropicMessageConverter converter) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.client = Objects.requireNonNull(client, "Client cannot be null");
        this.converter = Objects.requireNonNull(converter, "Converter cannot be null");
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(modelConfig, "Model config cannot be null");

        try {
            // Resolved once and threaded into both halves of the reasoning round trip. Reading it separately in the
            // request factory and in the response converter is how a subclass that overrides getProviderName() ends
            // up tagging a trace with one name and matching it against another, dropping every trace as foreign and
            // leaving the feature silently doing nothing. The model name is already resolved this way.
            final String providerName = getProviderName();
            MessageCreateParams request = buildRequest(systemPrompt, messages, tools, modelConfig, providerName);

            // Call Anthropic API. When the caller set a per-request timeout, pass it through as a
            // RequestOptions override; otherwise keep the single-argument overload so the client-wide default timeout
            // applies unchanged (zero behaviour change for the common case).
            final RequestOptions requestOptions = perRequestOptions(modelConfig);
            com.anthropic.models.messages.Message result = requestOptions == null
                    ? client.messages().create(request)
                    : client.messages().create(request, requestOptions);

            // Convert response
            return convertResponse(result, providerName);

        } catch (MessageConversionException | ToolConversionException e) {
            // Already LlmClientException subtypes — propagate as-is
            throw e;
        } catch (com.anthropic.errors.AnthropicException e) {
            log.error("Anthropic SDK error: {}", e.getMessage(), e);
            // Map to the neutral taxonomy (rate-limit/overload/etc.) so retry & fallback policies can act on it.
            throw AnthropicExceptionMapper.map(e, "Anthropic API call failed");
        } catch (Exception e) {
            log.error("Anthropic API call failed: {}", e.getMessage(), e);
            throw new LlmClientException("Anthropic API call failed: " + e.getMessage(), e);
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

        // Non-streaming in-flight abort: a blocking messages().create() call exposes no handle we can trip
        // mid-flight, so on its own it can only observe cancellation at the next iteration boundary. When the caller
        // supplied a live cancellation token, route the non-streaming request through the streaming path — which owns
        // the proven, thread-safe StreamResponse.close() abort lever — and reassemble the aggregated chunks into a
        // single response. The caller wanted a non-streaming *result*, not incremental delivery, so chunk emissions
        // are discarded; usage is still requested (includeUsage defaults to true) so token accounting matches the
        // blocking path. When the token can never fire (LlmCancellation.none(), the common non-cancellation case),
        // keep the cheaper single-shot blocking call unchanged — zero behaviour change for that path.
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

        // Fast path: cancelled before we even open the HTTP connection — don't spend a request we would immediately
        // abort.
        if (cancellation.isCancelled()) {
            throw new LlmCallCancelledException("Anthropic streaming call cancelled before start");
        }

        // Resolved once, for the same reason as the blocking path above.
        final String providerName = getProviderName();
        final MessageCreateParams request = buildRequest(systemPromptParts.concatenated(), messages, tools, modelConfig,
                providerName);
        final ChunkAggregator aggregator = new ChunkAggregator();
        final AnthropicStreamingMapper mapper = new AnthropicStreamingMapper(sink, aggregator, providerName,
                this::reportDivergence);

        // A per-request timeout, when set, also bounds the streaming call (worst-case ceiling incl. no-progress
        // stalls); when unset, keep the single-argument overload so the client-wide default applies unchanged.
        final RequestOptions requestOptions = perRequestOptions(modelConfig);
        try (StreamResponse<RawMessageStreamEvent> streamResponse = requestOptions == null
                ? client.messages().createStreaming(request)
                : client.messages().createStreaming(request, requestOptions)) {
            // Register the abort lever: StreamResponse.close() cancels the underlying OkHttp call — thread-safe and
            // idempotent, so it is safe to fire from the TaskStop/parent-cascade thread mid-stream. If cancellation
            // already fired between the guard above and here, onCancel invokes close() synchronously now, so the
            // stream below reads an already-closed source and unwinds through the catch blocks as a cancellation.
            cancellation.onCancel(streamResponse::close);
            mapper.consume(streamResponse.stream());
        } catch (MessageConversionException | ToolConversionException e) {
            throw e;
        } catch (com.anthropic.errors.AnthropicException e) {
            if (cancellation.isCancelled()) {
                throw new LlmCallCancelledException("Anthropic streaming call aborted by cancellation", e);
            }
            log.error("Anthropic SDK streaming error: {}", e.getMessage(), e);
            // Map to the neutral taxonomy (rate-limit/overload/etc.) so retry & fallback policies can act on it.
            throw AnthropicExceptionMapper.map(e, "Anthropic streaming call failed");
        } catch (Exception e) {
            // A cancelled stream surfaces here as the SDK's stream-closed IOException; classify it as a cancellation
            // (terminal, non-retryable) rather than a generic transient failure.
            if (cancellation.isCancelled()) {
                throw new LlmCallCancelledException("Anthropic streaming call aborted by cancellation", e);
            }
            log.error("Anthropic streaming call failed: {}", e.getMessage(), e);
            throw new LlmClientException("Anthropic streaming call failed: " + e.getMessage(), e);
        }

        return aggregator.toLlmResponse();
    }

    /**
     * Builds a {@link MessageCreateParams} request shared by both the synchronous and streaming entry points.
     *
     * @param providerName
     *            this client's provider name, resolved once by the caller so that the trace-replay half and the
     *            trace-capture half agree even in a subclass that overrides {@link #getProviderName()}
     */
    private MessageCreateParams buildRequest(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig, String providerName) {
        final int maxTokens = modelConfig.getMaxTokens().orElse(config.getMaxTokens());
        final Optional<ThinkingConfigParam> thinking = resolveThinking(modelConfig, maxTokens);

        MessageCreateParams.Builder requestBuilder = MessageCreateParams.builder()
                .model(modelConfig.getName().orElse(config.getModel())).maxTokens((long) maxTokens);

        thinking.ifPresent(requestBuilder::thinking);
        if (thinking.isPresent() && config.getThinkingMode() == AnthropicThinkingMode.ADAPTIVE) {
            AnthropicThinkingBudgets.effortFor(modelConfig.getReasoningEffort().orElse(null))
                    .ifPresent(effort -> requestBuilder.outputConfig(OutputConfig.builder().effort(effort).build()));
        }

        applySamplingParameters(requestBuilder, modelConfig, thinking.isPresent());

        // Set system prompt via dedicated parameter (Anthropic-specific)
        requestBuilder.system(systemPrompt);

        // Anthropic has no counterpart to either penalty, so both are dropped. Dropping them is correct; doing it
        // quietly is not — the caller set a value that has no effect here and the call still succeeds.
        modelConfig.getPresencePenalty()
                .ifPresent(p -> reportDivergence("presencePenalty=" + p,
                        "presencePenalty {} has no Anthropic counterpart and is being dropped; the call will succeed "
                                + "without it.",
                        p));
        modelConfig.getFrequencyPenalty()
                .ifPresent(p -> reportDivergence("frequencyPenalty=" + p,
                        "frequencyPenalty {} has no Anthropic counterpart and is being dropped; the call will succeed "
                                + "without it.",
                        p));

        // Convert and add messages. Stripping the traces up front rather than passing a flag down keeps the converter
        // with one rule: it replays what it is given.
        final List<Message> outbound = config.isReplayThinkingBlocks()
                ? messages
                : AnthropicMessageConverter.withoutReasoningTraces(messages);
        requestBuilder.messages(converter.convertMessages(outbound, providerName, this::reportDivergence));

        // Add tools if provided
        if (!tools.isEmpty()) {
            List<ToolUnion> anthropicTools = converter.convertTools(tools);
            requestBuilder.tools(anthropicTools);
        }

        return requestBuilder.build();
    }

    /**
     * Resolves the {@code thinking} parameter this request carries, or empty for none.
     *
     * <p>
     * {@link AnthropicThinkingMode#OFF} and {@link at.aimon.core.llm.ReasoningEffort#NONE} both mean "send nothing",
     * uniformly across both dialects. The alternative for the second — {@code thinking: {"type": "disabled"}} — is
     * itself rejected by several models, and omission cannot 400. The cost, stated rather than hidden: on a model
     * where thinking is on by default, {@code NONE} does not turn it off.
     */
    private Optional<ThinkingConfigParam> resolveThinking(LlmModel modelConfig, int maxTokens) {
        final AnthropicThinkingMode mode = config.getThinkingMode();
        if (mode == AnthropicThinkingMode.OFF) {
            return Optional.empty();
        }
        final ReasoningEffort effort = modelConfig.getReasoningEffort().orElse(null);
        if (effort == ReasoningEffort.NONE) {
            return Optional.empty();
        }
        if (mode == AnthropicThinkingMode.ADAPTIVE) {
            return Optional.of(ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build()));
        }
        return resolveExtendedThinking(effort, maxTokens);
    }

    private Optional<ThinkingConfigParam> resolveExtendedThinking(ReasoningEffort effort, int maxTokens) {
        final Integer configuredBudget = config.getThinkingBudgetTokens();
        if (configuredBudget != null && effort != null) {
            reportDivergence("thinkingBudgetOverridesEffort=" + configuredBudget + "@" + effort,
                    "Both a thinking budget ({} tokens) and a reasoning effort ({}) are set; the explicit budget wins "
                            + "and the effort is ignored on this provider.",
                    configuredBudget, effort);
        }

        final OptionalInt budget = AnthropicThinkingBudgets.budgetFor(effort, configuredBudget, maxTokens);
        if (budget.isEmpty()) {
            // Thinking tokens count against max_tokens, so below ~1024 there is no legal budget at all. Sending a
            // request the server is certain to reject is worse than not asking for thinking.
            reportDivergence("thinkingBudgetImpossible=" + maxTokens,
                    "maxTokens is {}, which leaves no room for the {}-token minimum thinking budget; the thinking "
                            + "parameter is being omitted and the call will succeed without extended thinking.",
                    maxTokens, AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS);
            return Optional.empty();
        }

        final int requested = AnthropicThinkingBudgets.requestedBudget(effort, configuredBudget);
        final int resolved = budget.getAsInt();
        if (resolved != requested) {
            // What is lost here is the *answer*, not the thinking. Thinking tokens count against max_tokens, so a
            // budget clamped to maxTokens - 1 leaves one token for visible output and the turn ends on
            // stop_reason: max_tokens. Naming the reduced budget instead would point the operator at the harmless
            // half of the change.
            reportDivergence("thinkingBudgetClamped=" + requested + "->" + resolved,
                    "A thinking budget of {} tokens does not fit under maxTokens {}; sending {} instead, which leaves "
                            + "only {} tokens for the visible answer. Raise maxTokens: thinking tokens are counted "
                            + "against it, so the reply is what this squeezes out, not the reasoning.",
                    requested, maxTokens, resolved, maxTokens - resolved);
        }
        return Optional
                .of(ThinkingConfigParam.ofEnabled(ThinkingConfigEnabled.builder().budgetTokens(resolved).build()));
    }

    /**
     * Applies {@code temperature} and {@code top_p}, or omits them because thinking is on.
     *
     * <p>
     * The vendor's rule, on every model that has thinking: {@code temperature} and {@code top_k} are incompatible with
     * it, and {@code top_p} is accepted only between 0.95 and 1. So when this request carries a {@code thinking}
     * parameter the temperature setter is <strong>not called</strong> — omission means never calling it, because a
     * key left present with a null value is rejected the same way the value would be — and {@code top_p} is sent only
     * inside that window.
     *
     * <p>
     * Both omissions are reported at WARN, because the observable outcome is a request that <em>succeeds with
     * settings other than the ones configured</em>: no status code, and nothing else in the system that would tell an
     * operator. Nothing is silently substituted with a "safe" value. {@code top_k} needs no handling — {@link LlmModel}
     * has no such field.
     */
    private void applySamplingParameters(MessageCreateParams.Builder requestBuilder, LlmModel modelConfig,
            boolean thinkingRequested) {
        final double requested = modelConfig.getTemperature().orElse(config.getTemperature());
        if (thinkingRequested) {
            reportDivergence("temperatureOmittedForThinking=" + requested,
                    "temperature {} is incompatible with Anthropic extended thinking and is being omitted; the call "
                            + "will succeed with the provider's default sampling.",
                    requested);
        } else {
            double temperature = requested;
            if (temperature < 0.0 || temperature > 1.0) {
                temperature = Math.max(0.0, Math.min(1.0, temperature));
                reportDivergence("temperature=" + requested,
                        "Temperature {} is outside Anthropic's range [0.0, 1.0]; sending {} instead. "
                                + "The call will succeed with different sampling than was configured.",
                        requested, temperature);
            }
            requestBuilder.temperature(temperature);
        }

        modelConfig.getTopP().ifPresent(topP -> {
            if (thinkingRequested && (topP < MIN_TOP_P_WITH_THINKING || topP > 1.0)) {
                reportDivergence("topPOmittedForThinking=" + topP,
                        "topP {} is outside the [{}, 1.0] window Anthropic accepts alongside extended thinking and is "
                                + "being omitted; the call will succeed without it.",
                        topP, MIN_TOP_P_WITH_THINKING);
                return;
            }
            requestBuilder.topP(topP);
        });
    }

    /**
     * Reports, at most once per distinct signature, that this provider is not honouring a configured sampling
     * parameter as given.
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
     *            parameter and value, the key that decides whether this has already been said
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
     * {@link AnthropicConfig#getTimeout()} default applies unchanged — a true no-op for the common case. The
     * single-{@link java.time.Duration} {@code timeout(...)} sets the overall request ceiling; connect/read/write
     * fall back to the client defaults via the SDK's {@code applyDefaults}.
     */
    private RequestOptions perRequestOptions(LlmModel modelConfig) {
        return modelConfig.getRequestTimeout().map(timeout -> RequestOptions.builder().timeout(timeout).build())
                .orElse(null);
    }

    @Override
    public String getProviderName() {
        return "Anthropic";
    }

    @Override
    public Optional<String> getDefaultModelName() {
        return Optional.of(config.getModel());
    }

    @Override
    public void close() throws Exception {
        if (client instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    /**
     * Converts Anthropic response to aimon LlmResponse.
     *
     * @param result
     *            The Anthropic message result
     * @param providerName
     *            this client's provider name, resolved once by the caller and stamped onto every captured trace
     * @return The aimon LlmResponse
     */
    private LlmResponse convertResponse(com.anthropic.models.messages.Message result, String providerName) {
        // Check stop reason for potential issues
        result.stopReason().ifPresent(stopReason -> {
            if (StopReason.MAX_TOKENS.equals(stopReason)) {
                log.warn("Anthropic response was truncated due to max_tokens limit");
            } else if (StopReason.REFUSAL.equals(stopReason)) {
                log.warn("Anthropic model refused to generate a response");
            }
        });

        List<ContentBlock> contentBlocks = result.content();
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            throw new LlmClientException("No content blocks in Anthropic response");
        }

        StringBuilder textContent = new StringBuilder();
        List<ToolUse> toolUses = new ArrayList<>();
        // The block sequence as the provider emitted it, which is what decides each thinking block's anchor. Both the
        // capture predicate and the ordering rule live in AnthropicOutputBlocks, shared with the streaming path.
        List<AnthropicOutputBlocks.Block> orderedBlocks = new ArrayList<>();

        for (ContentBlock block : contentBlocks) {
            if (block.isText()) {
                textContent.append(block.asText().text());
                orderedBlocks.add(AnthropicOutputBlocks.Block.text());
            } else if (block.isToolUse()) {
                ToolUseBlock toolUseBlock = block.asToolUse();
                Map<String, Object> input = extractToolInput(toolUseBlock);
                toolUses.add(ToolUse.of(toolUseBlock.id(), toolUseBlock.name(), input));
                orderedBlocks.add(AnthropicOutputBlocks.Block.toolUse(toolUseBlock.id()));
            } else if (block.isThinking()) {
                orderedBlocks.add(
                        AnthropicOutputBlocks.Block.thinking(AnthropicReasoningTraces.payloadOf(block.asThinking())));
            } else if (block.isRedactedThinking()) {
                // Not a variant of "some other block type we can skip": filtering on the thinking type alone is the
                // vendor-documented way to break the multi-turn protocol, because a partially dropped thinking
                // sequence is rejected outright.
                orderedBlocks.add(AnthropicOutputBlocks.Block
                        .thinking(AnthropicReasoningTraces.payloadOf(block.asRedactedThinking())));
            }
        }

        // Extract token usage
        TokenUsage tokenUsage = extractTokenUsage(result);

        // Map the SDK stop reason to the provider-neutral enum so aimon-core can detect truncation (max_tokens)
        // without knowing Anthropic's raw vocabulary.
        final at.aimon.core.llm.StopReason neutralStopReason = result.stopReason()
                .map(reason -> AnthropicStopReasons.fromWire(reason.asString()))
                .orElse(at.aimon.core.llm.StopReason.UNKNOWN);

        final List<ReasoningTrace> traces = AnthropicOutputBlocks.resolve(orderedBlocks, providerName);
        return LlmResponse.of(textContent.toString(), toolUses, tokenUsage, neutralStopReason)
                .withReasoningTraces(traces);
    }

    /**
     * Extracts tool input parameters from a ToolUseBlock.
     *
     * @param toolUseBlock
     *            The tool use block
     * @return Map of input parameters
     */
    private Map<String, Object> extractToolInput(ToolUseBlock toolUseBlock) {
        JsonValue inputJson = toolUseBlock._input();
        if (inputJson == null) {
            return Map.of();
        }

        // Use Jackson TypeReference to convert JsonValue to Map
        TypeReference<Map<String, Object>> typeRef = new TypeReference<>() {
        };
        Map<String, Object> result = inputJson.convert(typeRef);
        return result != null ? result : Map.of();
    }

    /**
     * Extracts token usage from Anthropic response.
     *
     * @param result
     *            The Anthropic message result
     * @return The token usage (never null)
     */
    private TokenUsage extractTokenUsage(com.anthropic.models.messages.Message result) {
        try {
            Usage usage = result.usage();
            int inputTokens = (int) usage.inputTokens();
            int outputTokens = (int) usage.outputTokens();
            int totalTokens = inputTokens + outputTokens;
            // Thinking tokens are billed as output tokens, so they are contained in outputTokens rather than added
            // to it — the fourth counter reports the breakdown and changes no total.
            return TokenUsage.of(inputTokens, outputTokens, totalTokens, AnthropicUsages.thinkingTokens(usage));
        } catch (Exception e) {
            log.debug("Could not extract token usage: {}", e.getMessage());
            return TokenUsage.empty();
        }
    }
}
