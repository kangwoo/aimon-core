package at.aimon.core.llms.anthropic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ThinkingDialect;
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
     * Ceiling on how many distinct divergences either register remembers.
     *
     * <p>
     * For {@link #reportedDivergences} the distinct values come from configuration — an agent definition's
     * frontmatter, a starter property — so in any real deployment the count is the number of agents, not the number of
     * requests. The cap exists because this client outlives every request that passes through it, and a caller that
     * generates model configs programmatically would otherwise grow the set without bound. Past the cap the client
     * stops reporting rather than stops remembering: by then it has already emitted 32 warnings, and a deployment that
     * diverges in 32 distinct ways has a configuration problem that a log line is the wrong instrument for.
     *
     * <p>
     * {@link #recurringDivergences} is bounded by the same number for the same reason, and its keys are narrower
     * still — they name a <em>condition</em> rather than a value, so the realistic count is single digits.
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
     * Occurrence counts for divergences that are <strong>not</strong> configuration facts.
     *
     * <p>
     * {@link #reportDivergence} says a thing once because what it describes was set once, in a config file, and
     * repeating it every ReAct iteration would be noise. Four of the conditions this client reports are not like
     * that: a stream that loses a {@code signature_delta}, a stored payload this build cannot parse, a trace anchored
     * to a tool use that is gone, a trace authored by another provider. Those are properties of the traffic, they can
     * start happening halfway through a process, and their signatures are constant — so once-per-signature would
     * report the first occurrence and then be silent for every one after it, which is the same silence this reporting
     * exists to remove.
     *
     * <p>
     * So they are counted instead and reported on the 1st, 10th, 100th … occurrence, with the count in the message.
     * A single warning still means "this happened once"; a run where every block is being dropped says so.
     */
    private final Map<String, AtomicLong> recurringDivergences = new ConcurrentHashMap<>();

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
                this::reportRecurringDivergence, config.getThinkingDisplay().isPresent());

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
        // Resolved once and used three times: the name that goes on the wire is the name whose capabilities are
        // looked up, and a LlmModel override is what makes that a per-request question rather than a per-client one.
        final String modelName = modelConfig.getName().orElse(config.getModel());
        // One descriptor for both decisions it governs -- which sampling parameters may be set, and which thinking
        // dialect this model speaks. Two look-ups could not disagree today, but they would be two places to change.
        final ModelCapabilities capabilities = capabilitiesFor(modelName);
        final Optional<ThinkingConfigParam> thinking = resolveThinking(modelConfig, maxTokens, capabilities, modelName);

        MessageCreateParams.Builder requestBuilder = MessageCreateParams.builder().model(modelName)
                .maxTokens((long) maxTokens);

        thinking.ifPresent(requestBuilder::thinking);
        // The parameter's own shape decides whether an effort accompanies it, rather than the configured mode: a mode
        // the model contradicts has already been translated by resolveThinking, and output_config belongs to the
        // dialect that actually reached the request.
        if (thinking.filter(ThinkingConfigParam::isAdaptive).isPresent()) {
            AnthropicThinkingBudgets.effortFor(intendedEffort(modelConfig))
                    .ifPresent(effort -> requestBuilder.outputConfig(OutputConfig.builder().effort(effort).build()));
        }

        applySamplingParameters(requestBuilder, modelConfig, modelName, capabilities, thinking.isPresent());

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
        reportIfReplayIsOffWhileThinking(thinking);
        final List<Message> outbound = config.isReplayThinkingBlocks()
                ? messages
                : AnthropicMessageConverter.withoutReasoningTraces(messages);
        requestBuilder.messages(converter.convertMessages(outbound, providerName, this::reportRecurringDivergence));

        // Add tools if provided
        if (!tools.isEmpty()) {
            List<ToolUnion> anthropicTools = converter.convertTools(tools);
            requestBuilder.tools(anthropicTools);
        }

        return requestBuilder.build();
    }

    /**
     * Warns that this request asks for thinking and then throws the result away.
     *
     * <p>
     * <strong>This method previously predicted a rejection, and that was wrong.</strong> It was written from two
     * documented sentences — passing the blocks back is <em>required within a tool-use turn</em>, and <em>the final
     * assistant turn of a thinking-enabled request must begin with a thinking block</em> — which together say that
     * stripping them must 400 under {@link AnthropicThinkingMode#EXTENDED}. A live call says otherwise: the request
     * is accepted, and the following turn still thinks. The vendor's third sentence, the one about mid-turn conflicts
     * degrading gracefully rather than erroring, is the one that governs. {@code AnthropicThinkingLiveTest} pins it,
     * and the design's §9 U-10 records the measurement.
     *
     * <p>
     * So the warning stays but says something different, and applies to <strong>both</strong> dialects rather than
     * only to {@code EXTENDED} — the reason is now a cost rather than a rule, and the cost does not care which
     * dialect asked. What this configuration buys is thinking <em>within</em> a turn; what it gives up is thinking
     * that survives one, which is the entire point of the feature this class implements. The tokens are billed
     * either way.
     *
     * <p>
     * <strong>The remedy is mode-dependent, and that is not a stylistic split.</strong> Under {@code EXTENDED},
     * {@link AnthropicThinkingMode#OFF} is a real escape: the models that speak that dialect accept the
     * {@code temperature} this client then sends. Under {@code ADAPTIVE} it is an escape in every case but one, and
     * naming that case exactly is what this sentence is for. Every adaptive-only model rejects a non-default
     * {@code temperature}, and turning thinking off moves that decision from the thinking gate to
     * {@link ModelCapabilities#supportsSamplingParameters()} — which answers correctly for the built-in rows and
     * fail-open for a name nothing describes. Fail-open only costs something when there is a value to send: this
     * client manufactures none, so a renamed deployment with nothing configured omits the parameter anyway and
     * succeeds. The 400 needs <em>both</em> halves — an undescribed model <em>and</em> somebody having set a
     * temperature, which is either an operator's setting or a subagent turn, since
     * {@code SubagentLlmDefaults.resolveModel} always puts one on the request.
     *
     * <p>
     * This is the message's third wording. The first predicted a rejection the server does not produce; the second
     * survived D-3 unchanged and so over-claimed the opposite way, telling an operator that turning thinking off
     * "makes every request fail" on a renamed deployment where in the common case it simply works. A warning that
     * names a failure the caller will not see teaches them to distrust the next one, which is the fault the whole
     * paragraph above exists to avoid.
     *
     * <p>
     * It is still worth saying, because nothing else in the system would: no status code, no error, and a transcript
     * that looks exactly like a working one. It is not, however, a mistake — it is the documented escape from the
     * preserved-thinking prefix check, and an operator who set it deliberately should read this as confirmation
     * rather than as a correction.
     */
    private void reportIfReplayIsOffWhileThinking(Optional<ThinkingConfigParam> thinking) {
        if (thinking.isEmpty() || config.isReplayThinkingBlocks()) {
            return;
        }
        final String shared = "This request asks for {} thinking and replayThinkingBlocks(false) discards the blocks "
                + "it returns, so the model re-derives its reasoning every turn and the thinking tokens are billed "
                + "again each time. The request is not rejected — thinking simply does not survive a tool call. If "
                + "that is deliberate (it is the documented escape from the preserved-thinking prefix check) nothing "
                + "needs doing";
        // Keyed and branched on the dialect that reached the request rather than on the configured mode, because the
        // remedy is a property of the dialect and the two can now differ: a mode the model contradicts is translated
        // before it gets here, and an EXTENDED-mode request that came out adaptive needs the adaptive caveat. The
        // mode is still what the message names, since that is the setting an operator would go and change.
        final AnthropicThinkingMode mode = config.getThinkingMode();
        if (!thinking.get().isAdaptive()) {
            reportDivergence("replayOffWhileThinking@" + ThinkingDialect.BUDGETED,
                    shared + "; if the tokens are not wanted either, set thinkingMode({}).", mode,
                    AnthropicThinkingMode.OFF);
            return;
        }
        reportDivergence("replayOffWhileThinking@" + ThinkingDialect.ADAPTIVE,
                shared + ". Note one caveat before reaching for thinkingMode(OFF) on this dialect: adaptive-only "
                        + "models refuse a non-default temperature, and with thinking off it is the capability "
                        + "registry that decides whether this client omits it. For a model no registry names — a "
                        + "renamed gateway deployment — that check falls open, so any request carrying a temperature "
                        + "fails outright instead of merely costing tokens. That means a set temperature or a "
                        + "subagent turn, which always carries one; with nothing configured the parameter is not "
                        + "sent and the request succeeds. Declaring the deployment's real name in the capability "
                        + "registry removes the caveat.",
                mode);
    }

    /**
     * Resolves the {@code thinking} parameter this request carries, or empty for none.
     *
     * <p>
     * {@link AnthropicThinkingMode#OFF} and {@link at.aimon.core.llm.ReasoningEffort#NONE} both mean "send nothing",
     * uniformly across both dialects. The alternative for the second — {@code thinking: {"type": "disabled"}} — is
     * itself rejected by several models, and omission cannot 400. The cost, stated rather than hidden: on a model
     * where thinking is on by default, {@code NONE} does not turn it off.
     *
     * <p>
     * <strong>Both of those are decided before the dialect is, and the order is load-bearing.</strong> Neither state
     * puts a {@code thinking} parameter on the wire, so neither can earn the 400 the dialect look-up exists to
     * prevent, and asking the table about a request that carries no thinking would produce a warning about a
     * question nobody needed answered — including under {@link AnthropicThinkingMode#AUTO}, where a caller who set
     * {@code NONE} has already said what they want.
     */
    private Optional<ThinkingConfigParam> resolveThinking(LlmModel modelConfig, int maxTokens,
            ModelCapabilities capabilities, String modelName) {
        final AnthropicThinkingMode mode = config.getThinkingMode();
        final ReasoningEffort effort = requestedEffort(modelConfig).orElse(null);
        if (mode == AnthropicThinkingMode.OFF) {
            reportInertEffort(effort);
            reportInertDisplay();
            return Optional.empty();
        }
        if (effort == ReasoningEffort.NONE) {
            return Optional.empty();
        }
        final Optional<ThinkingDialect> dialect = resolveDialect(mode, capabilities.thinkingDialect(), modelName);
        if (dialect.isEmpty()) {
            return Optional.empty();
        }
        if (dialect.get() == ThinkingDialect.ADAPTIVE) {
            final ThinkingConfigAdaptive.Builder adaptive = ThinkingConfigAdaptive.builder();
            // putAdditionalProperty rather than a typed setter because this SDK version does not model `display` --
            // ThinkingConfigAdaptive carries `type` and nothing else. It is the SDK's own escape hatch
            // (@JsonAnySetter/@JsonAnyGetter) and the one this repository already uses to write unmodelled request
            // fields; when the SDK grows the field, one line changes here.
            config.getThinkingDisplay().ifPresent(
                    display -> adaptive.putAdditionalProperty("display", JsonValue.from(display.wireValue())));
            return Optional.of(ThinkingConfigParam.ofAdaptive(adaptive.build()));
        }
        reportDisplayOnBudgetedDialect();
        return resolveExtendedThinking(effort, maxTokens);
    }

    /**
     * Which dialect this request actually speaks, or empty when it carries no {@code thinking} parameter at all.
     *
     * <p>
     * The whole of the mode × dialect table. Most of it needs no explanation — an operator's named mode against a
     * model that agrees, or against a model nothing describes, is what this client has always done, and the second of
     * those is what keeps a model outside the table byte-identical to yesterday. The two cases that are new:
     *
     * <ul>
     * <li><strong>The mode contradicts a known dialect.</strong> Honouring it is a <em>certain</em> HTTP 400 — both
     * shapes are rejected by the model that speaks the other — and omitting the parameter throws away the thinking
     * that was asked for. So the request is translated to the dialect the model speaks and the substitution is
     * reported. The intent survives because both dialects are spellings of the same neutral
     * {@link ReasoningEffort}, which {@link AnthropicThinkingBudgets} maps in either direction.
     * <li><strong>{@link AnthropicThinkingMode#AUTO} against a model nothing describes.</strong> Nothing is sent,
     * because there is no dialect to guess and a guess is a 400 half the time. Reported, unlike every other silent
     * case here, because {@code AUTO} is the one mode that asked the table a question rather than answering it.
     * </ul>
     *
     * <p>
     * This does not violate {@link ModelCapabilities#acceptedReasoningEfforts()}'s standing rule — <em>omitted and
     * reported, never raised to meet it</em>. There, omitting leaves the server's own default in force and the call
     * succeeds; here, honouring the operator literally is a failed turn, so the two situations do not compare.
     *
     * @param mode
     *            the operator's configured mode
     * @param known
     *            the dialect the capability registry resolved, possibly {@link ThinkingDialect#UNKNOWN}
     * @param modelName
     *            the resolved model name, for the warning text and its signature
     */
    private Optional<ThinkingDialect> resolveDialect(AnthropicThinkingMode mode, ThinkingDialect known,
            String modelName) {
        return switch (mode) {
            // OFF never reaches here -- resolveThinking answers it before asking -- but the switch is total, and this
            // is the same answer that early return gives.
            case OFF -> Optional.empty();
            case AUTO -> resolveAutoDialect(known, modelName);
            case EXTENDED -> resolveNamedDialect(mode, ThinkingDialect.BUDGETED, known, modelName);
            case ADAPTIVE -> resolveNamedDialect(mode, ThinkingDialect.ADAPTIVE, known, modelName);
        };
    }

    private Optional<ThinkingDialect> resolveAutoDialect(ThinkingDialect known, String modelName) {
        if (known != ThinkingDialect.UNKNOWN) {
            return Optional.of(known);
        }
        // Failure mode 3 of the design, and the wording is the whole mitigation: an operator reading "no thinking
        // parameter" as "the model is not thinking" would be wrong on exactly the models this matters most for, since
        // several of the current generation think by default and their blocks are captured either way.
        reportDivergence("thinkingDialectUnknown@" + modelName,
                "thinkingMode {} asks the capability registry which thinking dialect {} speaks and no row describes "
                        + "that name, so no thinking parameter is being sent and this request is the one "
                        + "thinkingMode({}) would have produced. That is a statement about the parameter, not about "
                        + "the model: one whose thinking is on by default still thinks, and its blocks are still "
                        + "captured and replayed. Register the deployment's real name in the capability registry to "
                        + "make {} answerable for it.",
                AnthropicThinkingMode.AUTO, modelName, AnthropicThinkingMode.OFF, AnthropicThinkingMode.AUTO);
        return Optional.empty();
    }

    private Optional<ThinkingDialect> resolveNamedDialect(AnthropicThinkingMode mode, ThinkingDialect named,
            ThinkingDialect known, String modelName) {
        if (known == ThinkingDialect.UNKNOWN || known == named) {
            return Optional.of(named);
        }
        // One signature shape for both directions of the translation, carrying the model name for the same reason
        // the sampling signatures do: one client has one mode, but LlmModel can override the name, so the same mode
        // against two models is two pieces of news rather than one repeated.
        final String signature = "thinkingDialectTranslated=" + mode + "->" + known + "@" + modelName;
        final String shared = "thinkingMode {} asks for the {} thinking dialect and {} speaks {}, which rejects the "
                + "other one with HTTP 400; the request is being translated to {} so the turn succeeds instead of "
                + "failing";
        final Integer configuredBudget = config.getThinkingBudgetTokens();
        if (configuredBudget == null) {
            reportDivergence(signature,
                    shared + ", carrying the same reasoning intent. Set thinkingMode({}) to have the dialect decided "
                            + "per model rather than reported, or correct the capability row if this model really "
                            + "speaks {}.",
                    mode, named, modelName, known, known, AnthropicThinkingMode.AUTO, named);
            return Optional.of(known);
        }
        // The lossiest corner in the whole change, and it gets one warning naming both numbers rather than a silent
        // substitution: a token count has no counterpart in a dialect where the model manages its own budget.
        reportDivergence(signature,
                shared + ". The configured thinking budget of {} tokens has no counterpart there — the model manages "
                        + "its own budget in that dialect — so the intent is carried as the nearest effort rung, {}. "
                        + "Set thinkingMode({}) to have the dialect decided per model rather than reported.",
                mode, named, modelName, known, known, configuredBudget,
                AnthropicThinkingBudgets.nearestEffort(configuredBudget), AnthropicThinkingMode.AUTO);
        return Optional.of(known);
    }

    /**
     * The neutral rung this request's thinking intent spells, or {@code null} when nobody stated one.
     *
     * <p>
     * Usually just the call's {@link ReasoningEffort}. It differs in exactly one shape, and
     * {@link AnthropicConfig} is what makes that true: an explicit {@code thinkingBudgetTokens} is accepted only
     * under {@link AnthropicThinkingMode#EXTENDED}, so a configured budget can reach an <em>adaptive</em> request
     * only by having been translated there — and at that point the number cannot go on the wire and the rung nearest
     * it is what carries the intent. Everywhere else this reads a {@code null} budget and returns the call's effort
     * unchanged, which is what keeps an untranslated request byte-identical.
     *
     * <p>
     * The precedence matches the budget dialect's own — an operator who named a number meant that number, so it wins
     * over a rung set on the call. It is reported in both dialects, by different sentences:
     * {@link #resolveExtendedThinking}'s own warning when the number is sent, and the translation warning naming both
     * the number and the rung when it is not.
     */
    private ReasoningEffort intendedEffort(LlmModel modelConfig) {
        final Integer configuredBudget = config.getThinkingBudgetTokens();
        if (configuredBudget != null) {
            return AnthropicThinkingBudgets.nearestEffort(configuredBudget);
        }
        return requestedEffort(modelConfig).orElse(null);
    }

    /**
     * The effort somebody configured for this request: the call's {@link LlmModel} first, then the client config.
     *
     * <p>
     * One helper because there are two readers — {@link #resolveThinking} decides whether the request carries
     * thinking at all, {@link #intendedEffort} supplies the rung a translation warning names — and a precedence
     * applied in one of them and not the other is a gate and a warning disagreeing about the same request. It is the
     * shape {@code OpenAiRequestParameters.requestedEffort(modelConfig, config)} has on the other provider, and it
     * has it for the same reason: {@code reasoningEffort} is one shared configuration key, so it resolves the same
     * way whichever client reads it. The two are deliberately not shared code — two lines over two unrelated config
     * types in two modules — and what keeps them in step is a test on each provider asserting the same precedence.
     */
    private Optional<ReasoningEffort> requestedEffort(LlmModel modelConfig) {
        return modelConfig.getReasoningEffort().or(config::getReasoningEffort);
    }

    /**
     * Says once that a configured effort reaches nothing, because this client sends no thinking parameter at all.
     *
     * <p>
     * {@code reasoningEffort} is settable deployment-wide from both configuration surfaces, and
     * {@link AnthropicThinkingMode#OFF} is the shipped default — so "make it think harder" is a reasonable thing to
     * write and, on its own, does nothing here. That is the <em>configured and never read</em> state this repository
     * refuses to leave silent, and a divergence report is the instrument this client already owns for it. Not a
     * refusal: the remedy is a <em>second</em> key, and failing the boot of a deployment for a combination whose fix
     * is another setting turns valid configuration into a startup failure.
     *
     * <p>
     * {@link ReasoningEffort#NONE} is excluded, and that exclusion is the whole of the condition's correctness.
     * {@code NONE} under {@code OFF} is not an inconsistency — both mean "send no thinking parameter", and
     * {@link #resolveThinking} would answer the same way for either — so telling that operator to turn thinking on
     * would be advice in the wrong direction.
     */
    private void reportInertEffort(ReasoningEffort effort) {
        if (effort == null || effort == ReasoningEffort.NONE) {
            return;
        }
        reportDivergence("reasoningEffortWithThinkingOff=" + effort,
                "reasoningEffort {} is configured but thinkingMode is {}, so this request carries no thinking "
                        + "parameter and the effort reaches nothing. Set the thinking mode ({}, {} or {}) to act on "
                        + "it — note that a model whose thinking is on by default still thinks regardless.",
                effort, AnthropicThinkingMode.OFF, AnthropicThinkingMode.AUTO, AnthropicThinkingMode.ADAPTIVE,
                AnthropicThinkingMode.EXTENDED);
    }

    /**
     * Says once that {@code thinkingDisplay} is inert under {@link AnthropicThinkingMode#OFF}.
     *
     * <p>
     * The same shape and the same reasoning as {@link #reportInertEffort}: with no {@code thinking} parameter on the
     * request there is no {@code display} to carry and no {@code thinking_delta} to forward, so an operator who set
     * the key sees nothing at all and has no other signal that the two settings disagree. Not a refusal, for that
     * method's reason — the remedy is a second key, and failing a boot over a combination whose fix is another
     * setting turns valid configuration into a startup failure.
     *
     * <p>
     * Reported through {@link #reportDivergence} rather than {@link #reportRecurringDivergence} because this is a
     * property of the configuration, not of the traffic: the condition is constant for the life of the process, so
     * saying it once is saying it completely.
     *
     * <p>
     * <strong>There is a fourth inert pair and it is deliberately not reported here.</strong> A display configured
     * alongside {@link ReasoningEffort#NONE} also reaches nothing — {@link #resolveThinking} returns on that value
     * before this method runs — and that silence is the same exclusion {@link #reportInertEffort} makes for the same
     * value, for the same reason. {@code NONE} is the operator saying <em>do not reason</em>, so there is no
     * deliberation for a display to show, and advising them to turn thinking on would be advice in the wrong
     * direction. The three conditions this client does report are ones where the two settings disagree; this one is
     * a pair that agrees.
     */
    private void reportInertDisplay() {
        if (config.getThinkingDisplay().isEmpty()) {
            return;
        }
        reportDivergence("thinkingDisplayWithThinkingOff=" + config.getThinkingDisplay().get(),
                "thinkingDisplay {} is configured but thinkingMode is {}, so this request carries no thinking "
                        + "parameter, no display reaches the server and no reasoning text reaches the stream. Set the "
                        + "thinking mode ({}, {} or {}) to act on it.",
                config.getThinkingDisplay().get(), AnthropicThinkingMode.OFF, AnthropicThinkingMode.ADAPTIVE,
                AnthropicThinkingMode.EXTENDED, AnthropicThinkingMode.AUTO);
    }

    /**
     * Says once that {@code thinkingDisplay} put nothing on a budgeted request, while still doing its other half.
     *
     * <p>
     * The half that works is the forwarding: on this dialect {@code thinking_delta} events already arrive and the key
     * is what lets them reach the sink, so a watcher <em>does</em> see thinking text. The half that does not is the
     * word itself — {@code ThinkingConfigEnabled} is not given a {@code display} sibling (see
     * {@link AnthropicThinkingDisplay}), so the {@code summarized} the operator wrote reached nothing. Without this
     * line, working output would read as proof the field went out.
     */
    private void reportDisplayOnBudgetedDialect() {
        if (config.getThinkingDisplay().isEmpty()) {
            return;
        }
        reportDivergence("thinkingDisplayOnBudgetedDialect=" + config.getThinkingDisplay().get(),
                "thinkingDisplay {} is configured but this request speaks the {} dialect, which is not given a "
                        + "`display` field, so the value itself reaches nothing. Thinking text still streams — the "
                        + "budgeted dialect emits it regardless and the key is what forwards it — so the visible "
                        + "output is not evidence that the field went out.",
                config.getThinkingDisplay().get(), ThinkingDialect.BUDGETED);
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
     * Applies {@code temperature} and {@code top_p}, or omits them — because the model refuses them, or because
     * thinking is on.
     *
     * <p>
     * <strong>Two gates, nested in this order and not the other one.</strong> The outer gate is a model fact:
     * {@link ModelCapabilities#supportsSamplingParameters()} is {@code false} for the Claude models that answer 400 to
     * {@code temperature} at any non-default value and to {@code top_p} at <em>any</em> value including {@code 1.0},
     * so for them neither setter is called at all. The inner gate is the older thinking rule, which governs the models
     * that do accept sampling: {@code temperature} and {@code top_k} are incompatible with thinking, and {@code top_p}
     * is accepted only between 0.95 and 1. Reversing the two would send {@code top_p: 0.98} to an always-thinking
     * model that refuses it, which is a measured 400.
     *
     * <p>
     * Nothing here branches on the model <em>name</em>; the name is carried only so a warning can say which model
     * dropped the value. The one thing that decides is the descriptor the registry resolved.
     *
     * <p>
     * Omission means the setter is <strong>never called</strong>. It cannot be expressed as passing null: measured
     * 2026-09-09, {@code "temperature": null} is a 400 (<em>"Input should be a valid number"</em>) on a model that
     * accepts {@code temperature: 0.5} as readily as on one that refuses it — a schema rule, not a capability one.
     *
     * <p>
     * Every omission is reported at WARN, because the observable outcome is a request that <em>succeeds with settings
     * other than the ones configured</em>: no status code, and nothing else in the system that would tell an operator.
     * Nothing is silently substituted with a "safe" value, and a request nobody put a value on stays silent by
     * construction rather than by a special case. {@code top_k} needs no handling — {@link LlmModel} has no such
     * field.
     */
    private void applySamplingParameters(MessageCreateParams.Builder requestBuilder, LlmModel modelConfig,
            String modelName, ModelCapabilities capabilities, boolean thinkingRequested) {
        final Optional<Double> callTemperature = modelConfig.getTemperature();
        final Optional<Double> temperature = callTemperature.or(config::getTemperature);
        final Optional<Double> topP = modelConfig.getTopP();

        if (!capabilities.supportsSamplingParameters()) {
            // Suppression beats the thinking wording even when thinking is on: both statements are true there, and
            // this one is the useful half, because turning thinking off would not make the model take the value.
            reportSuppressedSampling("temperature", temperature, modelName);
            reportSuppressedSampling("topP", topP, modelName);
            return;
        }

        if (thinkingRequested) {
            // Two wordings, because the two cases are different news. "temperature 0.7" names something the caller
            // chose on this call; the client's own configured value names something set once for every agent bound to
            // this provider, and an operator reading the first sentence would go looking for it on the wrong request.
            temperature.ifPresent(requested -> {
                if (callTemperature.isPresent()) {
                    reportDivergence("temperatureOmittedForThinking=" + requested,
                            "temperature {} is incompatible with Anthropic thinking and is being omitted; the call "
                                    + "will succeed with the provider's default sampling.",
                            requested);
                } else {
                    reportDivergence("clientTemperatureOmittedForThinking=" + requested,
                            "No temperature was set on this call, so this client's configured {} would have applied; "
                                    + "it is incompatible with Anthropic thinking and is being omitted, and the call "
                                    + "will succeed with the provider's default sampling.",
                            requested);
                }
            });
        } else {
            temperature.ifPresent(requested -> {
                double clamped = requested;
                if (clamped < 0.0 || clamped > 1.0) {
                    clamped = Math.max(0.0, Math.min(1.0, clamped));
                    reportDivergence("temperature=" + requested,
                            "Temperature {} is outside Anthropic's range [0.0, 1.0]; sending {} instead. "
                                    + "The call will succeed with different sampling than was configured.",
                            requested, clamped);
                }
                requestBuilder.temperature(clamped);
            });
        }

        topP.ifPresent(value -> {
            if (thinkingRequested && (value < MIN_TOP_P_WITH_THINKING || value > 1.0)) {
                reportDivergence("topPOmittedForThinking=" + value,
                        "topP {} is outside the [{}, 1.0] window Anthropic accepts alongside extended thinking and is "
                                + "being omitted; the call will succeed without it.",
                        value, MIN_TOP_P_WITH_THINKING);
                return;
            }
            requestBuilder.topP(value);
        });
    }

    /**
     * Reports one sampling value dropped because the resolved model does not accept sampling parameters.
     *
     * <p>
     * The signature carries the model name as well as the value, matching the OpenAI client's convention: one client
     * has one config but {@link LlmModel} can override the name, so the same value against two models is two pieces of
     * news rather than one repeated.
     *
     * @param name
     *            the parameter as the caller spells it
     * @param value
     *            the configured value, or empty when nobody set one — in which case nothing is said
     * @param modelName
     *            the resolved model name, for the warning text
     */
    private void reportSuppressedSampling(String name, Optional<Double> value, String modelName) {
        value.ifPresent(v -> reportDivergence(name + "=" + v + "@" + modelName,
                "{} {} is set on this request but {} does not accept sampling parameters; it is being omitted and the "
                        + "call will succeed without it.",
                name, v, modelName));
    }

    /**
     * Resolves the model's capabilities, degrading to {@link ModelCapabilities#unknown()} if the registry misbehaves.
     *
     * <p>
     * The registry is caller-supplied, and {@link #buildRequest} — this method's only caller — runs <em>outside</em>
     * the streaming path's try-with-resources: an exception escaping here would bypass both the exception mapper and
     * the cancellation classification. Swallowing it applies this SPI's own fail-open rule to the SPI itself, so a
     * third-party bug costs a warning rather than the request.
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
     * Reports a divergence that is a property of the <em>traffic</em> rather than of the configuration, on the 1st,
     * 10th, 100th … occurrence.
     *
     * <p>
     * {@link #reportDivergence}'s once-per-signature rule is right for a value someone typed into an agent definition
     * and wrong for a dropped thinking block: those signatures are constant, so once-only would describe the first
     * occurrence and then say nothing while the condition ran for the rest of the process. The count in the message is
     * the point — one line means it happened once, and a line saying "occurrence 100" means the feature is off.
     *
     * @param signature
     *            the condition, the key that decides whether this has already been said
     * @param message
     *            SLF4J-formatted message; one extra {@code {}} placeholder is appended for the count past the first
     * @param args
     *            values for the message placeholders
     */
    private void reportRecurringDivergence(String signature, String message, Object... args) {
        final AtomicLong counter = recurringDivergences.get(signature);
        if (counter == null && recurringDivergences.size() >= MAX_REPORTED_DIVERGENCES) {
            return;
        }
        final long count = recurringDivergences.computeIfAbsent(signature, key -> new AtomicLong()).incrementAndGet();
        if (!isReportableOccurrence(count)) {
            return;
        }
        if (count == 1) {
            log.warn(message, args);
            return;
        }
        final Object[] withCount = Arrays.copyOf(args, args.length + 1);
        withCount[args.length] = count;
        log.warn(message + " (occurrence {}; this condition is reported at 1, 10, 100 … so the gaps are silent)",
                withCount);
    }

    /**
     * Whether this occurrence is one of the ones that gets a line: 1, 10, 100, 1000 …
     *
     * <p>
     * Computed by dividing rather than by multiplying up to the count, because {@code threshold *= 10} overflows to a
     * negative on a long-lived process and a loop guarded on {@code <= count} would then never end.
     */
    private static boolean isReportableOccurrence(long count) {
        if (count <= 0) {
            return false;
        }
        long remaining = count;
        while (remaining % 10 == 0) {
            remaining /= 10;
        }
        return remaining == 1;
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
