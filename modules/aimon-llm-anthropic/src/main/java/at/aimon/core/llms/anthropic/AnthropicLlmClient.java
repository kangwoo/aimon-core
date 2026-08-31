package at.aimon.core.llms.anthropic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
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
            MessageCreateParams request = buildRequest(systemPrompt, messages, tools, modelConfig);

            // Call Anthropic API. When the caller set a per-request timeout, pass it through as a
            // RequestOptions override; otherwise keep the single-argument overload so the client-wide default timeout
            // applies unchanged (zero behaviour change for the common case).
            final RequestOptions requestOptions = perRequestOptions(modelConfig);
            com.anthropic.models.messages.Message result = requestOptions == null
                    ? client.messages().create(request)
                    : client.messages().create(request, requestOptions);

            // Convert response
            return convertResponse(result);

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

        final MessageCreateParams request = buildRequest(systemPromptParts.concatenated(), messages, tools,
                modelConfig);
        final ChunkAggregator aggregator = new ChunkAggregator();
        final AnthropicStreamingMapper mapper = new AnthropicStreamingMapper(sink, aggregator);

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
     */
    private MessageCreateParams buildRequest(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        final double requested = modelConfig.getTemperature().orElse(config.getTemperature());
        double temperature = requested;
        if (temperature < 0.0 || temperature > 1.0) {
            temperature = Math.max(0.0, Math.min(1.0, temperature));
            reportDivergence("temperature=" + requested,
                    "Temperature {} is outside Anthropic's range [0.0, 1.0]; sending {} instead. "
                            + "The call will succeed with different sampling than was configured.",
                    requested, temperature);
        }

        MessageCreateParams.Builder requestBuilder = MessageCreateParams.builder()
                .model(modelConfig.getName().orElse(config.getModel()))
                .maxTokens((long) modelConfig.getMaxTokens().orElse(config.getMaxTokens())).temperature(temperature);

        // Set system prompt via dedicated parameter (Anthropic-specific)
        requestBuilder.system(systemPrompt);

        // Add optional parameters
        modelConfig.getTopP().ifPresent(requestBuilder::topP);

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

        // Convert and add messages
        requestBuilder.messages(converter.convertMessages(messages));

        // Add tools if provided
        if (!tools.isEmpty()) {
            List<ToolUnion> anthropicTools = converter.convertTools(tools);
            requestBuilder.tools(anthropicTools);
        }

        return requestBuilder.build();
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
        return "Anthropic (" + config.getModel() + ")";
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
     * @return The aimon LlmResponse
     */
    private LlmResponse convertResponse(com.anthropic.models.messages.Message result) {
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

        for (ContentBlock block : contentBlocks) {
            if (block.isText()) {
                textContent.append(block.asText().text());
            } else if (block.isToolUse()) {
                ToolUseBlock toolUseBlock = block.asToolUse();
                Map<String, Object> input = extractToolInput(toolUseBlock);
                toolUses.add(ToolUse.of(toolUseBlock.id(), toolUseBlock.name(), input));
            }
            // Ignore other block types (ThinkingBlock, RedactedThinkingBlock, etc.)
        }

        // Extract token usage
        TokenUsage tokenUsage = extractTokenUsage(result);

        // Map the SDK stop reason to the provider-neutral enum so aimon-core can detect truncation (max_tokens)
        // without knowing Anthropic's raw vocabulary.
        final at.aimon.core.llm.StopReason neutralStopReason = result.stopReason()
                .map(reason -> AnthropicStopReasons.fromWire(reason.asString()))
                .orElse(at.aimon.core.llm.StopReason.UNKNOWN);

        return LlmResponse.of(textContent.toString(), toolUses, tokenUsage, neutralStopReason);
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
            return TokenUsage.of(inputTokens, outputTokens, totalTokens);
        } catch (Exception e) {
            log.debug("Could not extract token usage: {}", e.getMessage());
            return TokenUsage.empty();
        }
    }
}
