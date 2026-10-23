package at.aimon.core.llm.usage;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * Decorator that forwards every {@link LlmClient} call to a delegate and emits a
 * {@link LlmUsageRecorder#record(LlmUsageEvent) usage event} for it.
 *
 * <p>
 * This keeps provider implementations (e.g. {@code OpenAILlmClient}, {@code AnthropicLlmClient}) unaware of metering
 * while allowing any call site to attach {@link LlmCallMetadata} that is captured in the recorded event.
 *
 * <h2>What is recorded</h2>
 *
 * <p>
 * Every path — blocking or streaming, with or without a cancellation token — records the same thing, built from the
 * {@link LlmResponse}: its {@link TokenUsage} (prompt = the whole input context, cache counters when measured), the
 * {@link LlmResponse#getServiceTier() service tier} and {@link LlmResponse#getServedModel() model} the provider
 * reports,
 * the model the call asked for, and the call's start and completion time from this decorator's {@link Clock}. A
 * streaming call is recorded once, from its final response, never per chunk.
 *
 * <p>
 * A call that throws records nothing — with one exception, which is the one that can still cost money: when a
 * streaming call fails after the provider started answering (at least one chunk arrived), or a call is cut by its
 * cancellation token after it was sent (an {@link LlmCallCancelledException}), an
 * {@link LlmUsageEvent.Outcome#ABORTED ABORTED} event with no usage is recorded, so a recorder can count the call as
 * unpriced rather than not seeing it at all. The exception is rethrown unchanged.
 *
 * <p>
 * "Started answering" is observed through the stream sink, so it is the first chunk a provider <em>forwards</em>. A
 * stream that fails, without being cancelled, while the provider is still in a phase it does not forward (e.g.
 * Anthropic thinking with reasoning forwarding off) records nothing. That gap is deliberate: the alternative would
 * be counting every failed stream, including the ones the provider rejected before doing any work.
 *
 * <h2>Retries</h2>
 *
 * <p>
 * This decorator meters each call made <em>through it</em>. Wrapped around a provider client, beneath the framework's
 * retry loop ({@code LlmCallGateway}), it therefore records every attempt that produced a response or an abort — one
 * event per actual call. Retries a provider SDK makes internally (e.g. on a 429 or 5xx before any response) are not
 * visible here; they return no usage and are not billed.
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
    private final Clock clock;

    /**
     * Creates a metering decorator timed by the system UTC clock.
     *
     * @param delegate
     *            the underlying client (must not be null)
     * @param recorder
     *            the usage recorder (must not be null; use {@link LlmUsageRecorder#NOOP} to disable)
     */
    public MeteringLlmClient(LlmClient delegate, LlmUsageRecorder recorder) {
        this(delegate, recorder, Clock.systemUTC());
    }

    /**
     * Creates a metering decorator.
     *
     * @param delegate
     *            the underlying client (must not be null)
     * @param recorder
     *            the usage recorder (must not be null; use {@link LlmUsageRecorder#NOOP} to disable)
     * @param clock
     *            the clock that stamps each call's start and completion (must not be null)
     */
    public MeteringLlmClient(LlmClient delegate, LlmUsageRecorder recorder, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate cannot be null");
        this.recorder = Objects.requireNonNull(recorder, "Recorder cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
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
        return metered(modelConfig, metadata, LlmCancellation.none(), null,
                () -> delegate.sendMessage(systemPrompt, messages, tools, modelConfig, metadata));
    }

    @Override
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmCancellation cancellation) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        // Forward the cancellation token on the non-streaming path too; without this override the LlmClient default
        // would silently drop it (delegating to the non-cancellation overload).
        return metered(modelConfig, metadata, cancellation, null,
                () -> delegate.sendMessage(systemPromptParts, messages, tools, modelConfig, metadata, cancellation));
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        // Forward directly to the delegate's streaming path so providers with native streaming (OpenAI, Anthropic)
        // keep emitting token-level chunks. Without this override, the LlmClient default fallback would collapse
        // the response into a single TEXT_DELTA chunk, defeating streaming.
        final AtomicBoolean answered = new AtomicBoolean();
        final LlmStreamSink observed = observing(sink, answered);
        return metered(modelConfig, metadata, LlmCancellation.none(), answered, () -> delegate
                .sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata, options, observed));
    }

    @Override
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
            LlmStreamSink sink, LlmCancellation cancellation) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        // Forward the cancellation token so provider streaming can be aborted mid-flight.
        final AtomicBoolean answered = new AtomicBoolean();
        final LlmStreamSink observed = observing(sink, answered);
        return metered(modelConfig, metadata, cancellation, answered,
                () -> delegate.sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata, options,
                        observed, cancellation));
    }

    /**
     * Runs one call and records it.
     *
     * @param answered
     *            set once a stream chunk arrived, or null for a blocking call
     */
    private LlmResponse metered(LlmModel modelConfig, LlmCallMetadata metadata, LlmCancellation cancellation,
            AtomicBoolean answered, Supplier<LlmResponse> call) {
        // A token already tripped at entry makes the provider refuse to send, so nothing can be billed: such a call
        // must not be recorded as aborted.
        final boolean cancelledBeforeSend = cancellation.isCancelled();
        final Instant startedAt = clock.instant();
        final LlmResponse response;
        try {
            response = call.get();
        } catch (RuntimeException e) {
            final boolean streamStarted = answered != null && answered.get();
            // Only the cancellation itself counts: a 4xx or validation failure that races a tripping token was never
            // a call the provider worked on.
            final boolean cutByToken = !cancelledBeforeSend && e instanceof LlmCallCancelledException;
            if (streamStarted || cutByToken) {
                record(LlmUsageEvent.builder().provider(delegate.getProviderName())
                        .requestedModel(modelName(modelConfig)).startedAt(startedAt).completedAt(completedAt(startedAt))
                        .metadata(metadata).outcome(LlmUsageEvent.Outcome.ABORTED));
            }
            throw e;
        }
        record(LlmUsageEvent.builder().provider(delegate.getProviderName()).requestedModel(modelName(modelConfig))
                .servedModel(response.getServedModel().orElse(null)).usage(response.getTokenUsage())
                .serviceTier(response.getServiceTier().orElse(null)).startedAt(startedAt)
                .completedAt(completedAt(startedAt)).metadata(metadata));
        return response;
    }

    private void record(LlmUsageEvent.Builder event) {
        try {
            recorder.record(event.build());
        } catch (RuntimeException e) {
            log.warn("Failed to record LLM usage: {}", e.getMessage(), e);
        }
    }

    /** A clock stepped backwards (e.g. NTP) must not fail the event: clamp to the start. */
    private Instant completedAt(Instant startedAt) {
        final Instant now = clock.instant();
        return now.isBefore(startedAt) ? startedAt : now;
    }

    private static LlmStreamSink observing(LlmStreamSink sink, AtomicBoolean answered) {
        Objects.requireNonNull(sink, "Sink cannot be null");
        return chunk -> {
            answered.set(true);
            sink.accept(chunk);
        };
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public Optional<String> getDefaultModelName() {
        return delegate.getDefaultModelName();
    }

    /**
     * The model this call actually ran against: the request's override, else the client's own default.
     *
     * <p>
     * The delegate's default is consulted because {@code getProviderName()} no longer carries a model, so the
     * usage key's {@code model} field would otherwise be null for every request that did not override — and the
     * provider label no longer makes up for it. {@code null} stays the terminal value: {@link LlmUsageKey} accepts
     * a null model, and a client with no default genuinely has nothing to report.
     */
    private String modelName(LlmModel modelConfig) {
        final Optional<String> requested = modelConfig == null ? Optional.empty() : modelConfig.getName();
        return requested.or(delegate::getDefaultModelName).orElse(null);
    }
}
