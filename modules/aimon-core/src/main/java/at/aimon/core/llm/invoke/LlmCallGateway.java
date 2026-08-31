package at.aimon.core.llm.invoke;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
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
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.exception.LlmRateLimitedException;
import at.aimon.core.llm.retry.LlmFallbackPolicy;
import at.aimon.core.llm.retry.LlmRetryPolicy;
import at.aimon.core.llm.streaming.BufferingStreamSink;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamTarget;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.llm.streaming.StreamingRetryListener;

/**
 * Wraps an {@link LlmClient} with retry, model-fallback, and prompt-too-long recovery semantics, mirroring the
 * {@link LlmClient#sendMessage(String, List, List, LlmModel)} signature so it can be swapped in wherever a plain
 * {@link LlmClient} is consumed.
 *
 * <h2>Precedence</h2>
 *
 * <p>
 * When the wrapped client throws, the gateway applies the following precedence:
 * <ol>
 * <li><strong>{@link LlmPromptTooLongException}:</strong> defer to the injected {@link PromptTooLongHandler} (if any).
 * <ul>
 * <li>{@link HandlerOutcome#RETRY} — re-execute the same call with the same model. The attempt counter is
 * <em>not</em> incremented, because the handler is expected to have shrunk the request. Consecutive handler-driven
 * retries are capped by {@link #getMaxPromptTooLongHandlerRetries()} to avoid infinite loops.
 * <li>{@link HandlerOutcome#ABORT} or {@link Optional#empty()} — rethrow the original exception.
 * <li>Handler is {@code null} — rethrow the original exception (prompt-too-long is unrecoverable by default).
 * </ul>
 * <li><strong>Other {@link LlmClientException}:</strong>
 * <ul>
 * <li><strong>Escalate to the next model</strong> when either (a) the failure is <em>activating</em> (see
 * {@link LlmFallbackPolicy#isActivating(LlmClientException)}) and the count of consecutive activating failures on the
 * current model has reached {@link LlmFallbackPolicy#getConsecutiveFailureThreshold()}, or (b) the current model has no
 * retry budget left (last-resort fallback). Escalation uses
 * {@link LlmFallbackPolicy#nextModel(LlmModel, LlmClientException)}; if a next model exists the gateway switches to it
 * and resets the attempt counter to {@code 1} and the consecutive-activation counter to {@code 0}. With the default
 * threshold of {@code 1}, an activating failure escalates on its first occurrence — the historical behavior.
 * <li>Otherwise, if {@link LlmRetryPolicy#isRetryable(LlmClientException)} is {@code true} and the current attempt
 * number is strictly below {@link LlmRetryPolicy#getMaxAttempts()} → sleep and retry with the same model. The sleep
 * duration prefers a server-supplied {@code Retry-After} hint when the failure carries one (see
 * {@link LlmRateLimitedException#getRetryAfter()}, clamped to {@link #getMaxRetryAfter()}), and otherwise falls back to
 * {@link LlmRetryPolicy#computeDelay(int, Random)}. The attempt counter is incremented.
 * <li>Otherwise → rethrow.
 * </ul>
 * </ol>
 *
 * <p>
 * Previous exceptions encountered during a single gateway call are attached as
 * {@link Throwable#addSuppressed(Throwable)} to the terminal exception so that observers can reconstruct the full
 * history of a failed call chain.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * The gateway itself is stateless (all state lives on the stack of a single {@code sendMessage} call). It is safe to
 * share across threads <em>provided</em> the injected {@link LlmClient}, {@link PromptTooLongHandler}, and
 * {@link Sleeper} are themselves thread-safe. The supplied {@link Random} is used on the calling thread only; for
 * multi-threaded use inject {@link java.util.concurrent.ThreadLocalRandom#current()} via a fresh {@link Random} per
 * call — {@link Random#nextDouble()} is synchronised and safe.
 *
 * @param <M>
 *            the transcript-buffer type exposed to {@link PromptTooLongHandler}. Use {@link Void} when no memory is
 *            wired.
 */
public final class LlmCallGateway<M> {

    /**
     * Default cap on consecutive handler-driven retries for a single gateway call. Each {@link HandlerOutcome#RETRY}
     * consumes one slot; the gateway aborts with the original exception once the cap is reached.
     */
    public static final int DEFAULT_MAX_PROMPT_TOO_LONG_HANDLER_RETRIES = 3;

    /**
     * Default upper bound applied to a server-supplied {@code Retry-After} hint. Honouring the hint verbatim would let
     * a
     * hostile or misconfigured provider pin a worker thread for an unbounded period, so the gateway clamps the hint to
     * this ceiling. Set a larger value via {@link Builder#maxRetryAfter(Duration)} when the provider is trusted.
     */
    public static final Duration DEFAULT_MAX_RETRY_AFTER = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(LlmCallGateway.class);

    private final LlmClient client;
    private final LlmRetryPolicy retryPolicy;
    private final LlmFallbackPolicy fallbackPolicy;
    private final PromptTooLongHandler<M> promptTooLongHandler;
    private final Supplier<M> memorySnapshotSupplier;
    private final Random random;
    private final Sleeper sleeper;
    private final int maxPromptTooLongHandlerRetries;
    private final Duration maxRetryAfter;

    private LlmCallGateway(Builder<M> builder) {
        this.client = Objects.requireNonNull(builder.client, "client must not be null");
        this.retryPolicy = Objects.requireNonNull(builder.retryPolicy, "retryPolicy must not be null");
        this.fallbackPolicy = Objects.requireNonNull(builder.fallbackPolicy, "fallbackPolicy must not be null");
        this.promptTooLongHandler = builder.promptTooLongHandler;
        this.memorySnapshotSupplier = builder.memorySnapshotSupplier;
        this.random = builder.random != null ? builder.random : new Random();
        this.sleeper = builder.sleeper != null ? builder.sleeper : Sleeper.threadSleep();
        if (builder.maxPromptTooLongHandlerRetries < 1) {
            throw new IllegalArgumentException(
                    "maxPromptTooLongHandlerRetries must be >= 1, got: " + builder.maxPromptTooLongHandlerRetries);
        }
        this.maxPromptTooLongHandlerRetries = builder.maxPromptTooLongHandlerRetries;
        this.maxRetryAfter = Objects.requireNonNull(builder.maxRetryAfter, "maxRetryAfter must not be null");
        if (this.maxRetryAfter.isNegative()) {
            throw new IllegalArgumentException("maxRetryAfter must be non-negative, got: " + this.maxRetryAfter);
        }
    }

    /**
     * Creates a new builder.
     *
     * @param <M>
     *            the transcript-buffer type
     * @return a new {@link Builder} (never {@code null})
     */
    public static <M> Builder<M> builder() {
        return new Builder<>();
    }

    /**
     * Creates a pass-through gateway for {@code client}: the default retry policy
     * ({@link LlmRetryPolicy#defaultPolicy()}), no model fallback ({@link LlmFallbackPolicy#none()}), and no
     * prompt-too-long handler — so {@link LlmPromptTooLongException} rethrows unchanged. This is the canonical
     * "resilient but otherwise plain" gateway shared by the agent and subagent executors; centralising it here keeps
     * their resilience semantics identical by construction instead of relying on duplicated private factories that can
     * drift.
     *
     * @param <M>
     *            the transcript-buffer type; irrelevant here since no {@link PromptTooLongHandler} is wired (use
     *            {@link Void} or the caller's memory type)
     * @param client
     *            the client to wrap (must not be {@code null})
     * @return a new pass-through gateway (never {@code null})
     * @throws NullPointerException
     *             if {@code client} is {@code null}
     */
    public static <M> LlmCallGateway<M> withDefaultRetry(LlmClient client) {
        return LlmCallGateway.<M>builder().client(client).retryPolicy(LlmRetryPolicy.defaultPolicy())
                .fallbackPolicy(LlmFallbackPolicy.none()).build();
    }

    /**
     * Returns the wrapped client.
     *
     * @return the wrapped {@link LlmClient} (never {@code null})
     */
    public LlmClient getClient() {
        return client;
    }

    /**
     * Returns the retry policy.
     *
     * @return the retry policy (never {@code null})
     */
    public LlmRetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    /**
     * Returns the fallback policy.
     *
     * @return the fallback policy (never {@code null})
     */
    public LlmFallbackPolicy getFallbackPolicy() {
        return fallbackPolicy;
    }

    /**
     * Returns the prompt-too-long handler, if one was injected.
     *
     * @return an {@link Optional} carrying the handler, or {@link Optional#empty()} when no handler was configured.
     *         Never {@code null}.
     */
    public Optional<PromptTooLongHandler<M>> getPromptTooLongHandler() {
        return Optional.ofNullable(promptTooLongHandler);
    }

    /**
     * Returns the cap on consecutive handler-driven retries.
     *
     * @return a positive integer
     */
    public int getMaxPromptTooLongHandlerRetries() {
        return maxPromptTooLongHandlerRetries;
    }

    /**
     * Returns the ceiling applied to server-supplied {@code Retry-After} hints before sleeping.
     *
     * @return a non-negative duration (never {@code null})
     */
    public Duration getMaxRetryAfter() {
        return maxRetryAfter;
    }

    /**
     * Sends a message through the wrapped client, applying retry, fallback, and prompt-too-long recovery policies per
     * the precedence documented on {@link LlmCallGateway}. Mirrors
     * {@link LlmClient#sendMessage(String, List, List, LlmModel)} exactly.
     *
     * @param systemPrompt
     *            the system prompt (must not be {@code null})
     * @param messages
     *            the conversation history (must not be {@code null}; may be empty)
     * @param tools
     *            the available tools (must not be {@code null}; may be empty)
     * @param modelConfig
     *            the initial model configuration (must not be {@code null})
     * @return the successful {@link LlmResponse} (never {@code null})
     * @throws LlmClientException
     *             if every attempt (across retries and fallbacks) fails and the terminal error is non-recoverable
     * @throws NullPointerException
     *             if any argument is {@code null}
     */
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
            LlmModel modelConfig) {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        Objects.requireNonNull(modelConfig, "modelConfig must not be null");

        LlmModel currentModel = modelConfig;
        int attempt = 1;
        int handlerRetries = 0;
        int consecutiveActivatingFailures = 0;
        LlmClientException lastException = null;

        while (true) {
            try {
                log.debug("Invoking LLM: model={}, attempt={}", currentModel, attempt);
                return client.sendMessage(systemPrompt, messages, tools, currentModel);

            } catch (LlmCallCancelledException ex) {
                // Terminal: the caller cancelled — never retry or fall back. This legacy String-prompt overload does
                // not forward a cancellation token, so a cancellation can only surface here if a decorated client
                // raises one on its own; guarding it keeps the path symmetric with the parts-aware overload.
                throw ex;
            } catch (LlmPromptTooLongException ex) {
                lastException = attachSuppressed(ex, lastException);

                if (promptTooLongHandler == null) {
                    log.debug("No PromptTooLongHandler registered; propagating LlmPromptTooLongException");
                    throw lastException;
                }
                if (handlerRetries >= maxPromptTooLongHandlerRetries) {
                    log.warn("PromptTooLongHandler retry cap reached ({}); propagating",
                            maxPromptTooLongHandlerRetries);
                    throw lastException;
                }

                final PromptTooLongEvent<M> event = PromptTooLongEvent.<M>builder().exception(ex).model(currentModel)
                        .attempt(attempt).memorySnapshot(snapshotMemory()).build();

                final Optional<HandlerOutcome> outcome = promptTooLongHandler.handle(event);
                if (outcome.isEmpty() || outcome.get() == HandlerOutcome.ABORT) {
                    log.debug("PromptTooLongHandler returned {} — propagating original exception",
                            outcome.map(Enum::name).orElse("empty"));
                    throw lastException;
                }

                handlerRetries++;
                log.debug("PromptTooLongHandler returned RETRY (handlerRetries={}), retrying same model/attempt",
                        handlerRetries);
                // Do NOT increment attempt — handler-driven retries are orthogonal to provider-side retries.

            } catch (LlmClientException ex) {
                lastException = attachSuppressed(ex, lastException);

                final boolean activating = fallbackPolicy.isActivating(ex);
                consecutiveActivatingFailures = activating ? consecutiveActivatingFailures + 1 : 0;
                final boolean canRetrySameModel = retryPolicy.isRetryable(ex) && attempt < retryPolicy.getMaxAttempts();
                final boolean thresholdReached = activating
                        && consecutiveActivatingFailures >= fallbackPolicy.getConsecutiveFailureThreshold();

                // Escalate to the next model when the consecutive-activation threshold is reached, or as a last
                // resort when the current model has exhausted its retry budget.
                if (thresholdReached || !canRetrySameModel) {
                    final Optional<LlmModel> next = fallbackPolicy.nextModel(currentModel, ex);
                    if (next.isPresent()) {
                        log.debug("Fallback activated: {} -> {} (consecutiveActivating={}, threshold={})", currentModel,
                                next.get(), consecutiveActivatingFailures,
                                fallbackPolicy.getConsecutiveFailureThreshold());
                        currentModel = next.get();
                        attempt = 1;
                        handlerRetries = 0;
                        consecutiveActivatingFailures = 0;
                        continue;
                    }
                }

                if (canRetrySameModel) {
                    final Duration delay = resolveRetryDelay(ex, attempt);
                    log.debug("Retryable failure on attempt {}/{}: sleeping {} before next attempt", attempt,
                            retryPolicy.getMaxAttempts(), delay);
                    sleepQuietly(delay, lastException);
                    attempt++;
                    continue;
                }

                log.debug("Non-recoverable failure (retryable={}, attempt={}/{}), propagating",
                        retryPolicy.isRetryable(ex), attempt, retryPolicy.getMaxAttempts());
                throw lastException;
            }
        }
    }

    /**
     * Sends a message through the wrapped client using a structured {@link SystemPromptParts} prompt, applying retry,
     * fallback, and prompt-too-long recovery policies per the precedence documented on {@link LlmCallGateway}. Mirrors
     * {@link LlmClient#sendMessage(SystemPromptParts, List, List, LlmModel, LlmCallMetadata)} exactly.
     *
     * <p>
     * This parts-aware overload preserves the structured prompt across attempts so that providers that attach
     * provider-specific caching hints at part seams continue to do so across retries and fallbacks.
     *
     * @param systemPromptParts
     *            the structured system prompt (must not be {@code null})
     * @param messages
     *            the conversation history (must not be {@code null}; may be empty)
     * @param tools
     *            the available tools (must not be {@code null}; may be empty)
     * @param modelConfig
     *            the initial model configuration (must not be {@code null})
     * @param metadata
     *            usage attribution metadata (must not be {@code null}; use {@link LlmCallMetadata#empty()} if none)
     * @return the successful {@link LlmResponse} (never {@code null})
     * @throws LlmClientException
     *             if every attempt (across retries and fallbacks) fails and the terminal error is non-recoverable
     * @throws NullPointerException
     *             if any argument is {@code null}
     */
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
        return sendMessage(systemPromptParts, messages, tools, modelConfig, metadata, LlmCancellation.none());
    }

    /**
     * Cancellation-aware variant of {@link #sendMessage(SystemPromptParts, List, List, LlmModel, LlmCallMetadata)}.
     *
     * <p>
     * The {@code cancellation} token is forwarded to the underlying {@link LlmClient} so a provider that supports
     * active
     * abort can cancel the in-flight HTTP call. The gateway itself honours the token at two points: it short-circuits
     * at
     * the top of every attempt (so no new attempt or fallback escalation starts once cancellation is requested), and it
     * treats a {@link LlmCallCancelledException} as <b>terminal</b> — it is never retried or used to trigger fallback.
     *
     * @param cancellation
     *            cooperative cancellation token (must not be {@code null}; use {@link LlmCancellation#none()} to opt
     *            out)
     * @throws LlmCallCancelledException
     *             if cancellation is requested before or during the call
     */
    public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmCancellation cancellation) {
        Objects.requireNonNull(systemPromptParts, "systemPromptParts must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        Objects.requireNonNull(modelConfig, "modelConfig must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");

        LlmModel currentModel = modelConfig;
        int attempt = 1;
        int handlerRetries = 0;
        int consecutiveActivatingFailures = 0;
        LlmClientException lastException = null;

        while (true) {
            if (cancellation.isCancelled()) {
                throw cancelledBeforeAttempt(attempt, lastException);
            }
            try {
                log.debug("Invoking LLM (parts-aware): model={}, attempt={}", currentModel, attempt);
                return client.sendMessage(systemPromptParts, messages, tools, currentModel, metadata, cancellation);

            } catch (LlmCallCancelledException ex) {
                // Terminal: the caller cancelled — never retry or fall back.
                throw ex;
            } catch (LlmPromptTooLongException ex) {
                lastException = attachSuppressed(ex, lastException);

                if (promptTooLongHandler == null) {
                    log.debug("No PromptTooLongHandler registered; propagating LlmPromptTooLongException");
                    throw lastException;
                }
                if (handlerRetries >= maxPromptTooLongHandlerRetries) {
                    log.warn("PromptTooLongHandler retry cap reached ({}); propagating",
                            maxPromptTooLongHandlerRetries);
                    throw lastException;
                }

                final PromptTooLongEvent<M> event = PromptTooLongEvent.<M>builder().exception(ex).model(currentModel)
                        .attempt(attempt).memorySnapshot(snapshotMemory()).build();

                final Optional<HandlerOutcome> outcome = promptTooLongHandler.handle(event);
                if (outcome.isEmpty() || outcome.get() == HandlerOutcome.ABORT) {
                    log.debug("PromptTooLongHandler returned {} — propagating original exception",
                            outcome.map(Enum::name).orElse("empty"));
                    throw lastException;
                }

                handlerRetries++;
                log.debug("PromptTooLongHandler returned RETRY (handlerRetries={}), retrying same model/attempt",
                        handlerRetries);
                // Do NOT increment attempt — handler-driven retries are orthogonal to provider-side retries.

            } catch (LlmClientException ex) {
                lastException = attachSuppressed(ex, lastException);

                final boolean activating = fallbackPolicy.isActivating(ex);
                consecutiveActivatingFailures = activating ? consecutiveActivatingFailures + 1 : 0;
                final boolean canRetrySameModel = retryPolicy.isRetryable(ex) && attempt < retryPolicy.getMaxAttempts();
                final boolean thresholdReached = activating
                        && consecutiveActivatingFailures >= fallbackPolicy.getConsecutiveFailureThreshold();

                // Escalate to the next model when the consecutive-activation threshold is reached, or as a last
                // resort when the current model has exhausted its retry budget.
                if (thresholdReached || !canRetrySameModel) {
                    final Optional<LlmModel> next = fallbackPolicy.nextModel(currentModel, ex);
                    if (next.isPresent()) {
                        log.debug("Fallback activated: {} -> {} (consecutiveActivating={}, threshold={})", currentModel,
                                next.get(), consecutiveActivatingFailures,
                                fallbackPolicy.getConsecutiveFailureThreshold());
                        currentModel = next.get();
                        attempt = 1;
                        handlerRetries = 0;
                        consecutiveActivatingFailures = 0;
                        continue;
                    }
                }

                if (canRetrySameModel) {
                    // Mirror the streaming path: if cancellation was requested during the failing call, short-circuit
                    // before spending the (possibly long, e.g. Retry-After-driven) backoff sleep.
                    if (cancellation.isCancelled()) {
                        throw cancelledBeforeAttempt(attempt, lastException);
                    }
                    final Duration delay = resolveRetryDelay(ex, attempt);
                    log.debug("Retryable failure on attempt {}/{}: sleeping {} before next attempt", attempt,
                            retryPolicy.getMaxAttempts(), delay);
                    sleepQuietly(delay, lastException, cancellation);
                    attempt++;
                    continue;
                }

                log.debug("Non-recoverable failure (retryable={}, attempt={}/{}), propagating",
                        retryPolicy.isRetryable(ex), attempt, retryPolicy.getMaxAttempts());
                throw lastException;
            }
        }
    }

    /**
     * Streaming counterpart to
     * {@link #sendMessage(SystemPromptParts, List, List, LlmModel, LlmCallMetadata)}.
     *
     * <p>
     * Applies the same retry / fallback / prompt-too-long precedence as the non-streaming path. Two modes are
     * supported depending on {@link LlmStreamingOptions#isBufferUntilFirstSuccess()}:
     * <ul>
     * <li><b>Direct (default)</b> — chunks from each attempt flow straight to {@code outerSink}. If an attempt fails
     * and a new one starts, {@code retryListener} is invoked first so callers can clear partial UI state. This
     * preserves the Time-To-First-Token benefit at the cost of visible retry churn.</li>
     * <li><b>Buffered</b> — chunks from each attempt are isolated in an internal
     * {@link BufferingStreamSink}. On success they are flushed to {@code outerSink}; on retry they are discarded. The
     * outer sink therefore only ever sees chunks from a successful attempt.</li>
     * </ul>
     *
     * @param systemPromptParts
     *            structured system prompt (must not be {@code null})
     * @param messages
     *            conversation history (must not be {@code null}; may be empty)
     * @param tools
     *            available tools (must not be {@code null}; may be empty)
     * @param modelConfig
     *            initial model configuration (must not be {@code null})
     * @param metadata
     *            usage attribution metadata (must not be {@code null})
     * @param target
     *            the streaming-delivery target grouping the streaming options, the caller-supplied chunk consumer, and
     *            the optional retry listener (must not be {@code null})
     * @return the successful {@link LlmResponse}
     * @throws LlmClientException
     *             if every attempt fails and the terminal error is non-recoverable
     * @throws NullPointerException
     *             if any non-nullable argument is {@code null}
     */
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamTarget target) {
        return sendMessageStreaming(systemPromptParts, messages, tools, modelConfig, metadata, target,
                LlmCancellation.none());
    }

    /**
     * Cancellation-aware variant of
     * {@link #sendMessageStreaming(SystemPromptParts, List, List, LlmModel, LlmCallMetadata, LlmStreamTarget)}.
     *
     * <p>
     * The {@code cancellation} token is forwarded to the underlying {@link LlmClient}; a native-streaming provider
     * registers its {@code StreamResponse.close()} lever against it so a mid-stream cancellation aborts the HTTP
     * connection immediately. The gateway short-circuits at the top of every attempt and treats a
     * {@link LlmCallCancelledException} as terminal (never retried, never used to trigger fallback).
     *
     * @param cancellation
     *            cooperative cancellation token (must not be {@code null}; use {@link LlmCancellation#none()} to opt
     *            out)
     * @throws LlmCallCancelledException
     *             if cancellation is requested before or during the stream
     */
    public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
            List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamTarget target,
            LlmCancellation cancellation) {
        Objects.requireNonNull(systemPromptParts, "systemPromptParts must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        Objects.requireNonNull(modelConfig, "modelConfig must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        final LlmStreamingOptions options = target.getOptions();
        final LlmStreamSink outerSink = target.getSink();
        final StreamingRetryListener listener = target.getRetryListener();

        LlmModel currentModel = modelConfig;
        int attempt = 1;
        int handlerRetries = 0;
        int consecutiveActivatingFailures = 0;
        LlmClientException lastException = null;

        while (true) {
            if (cancellation.isCancelled()) {
                throw cancelledBeforeAttempt(attempt, lastException);
            }
            final BufferingStreamSink bufferedSink = options.isBufferUntilFirstSuccess()
                    ? new BufferingStreamSink(outerSink)
                    : null;
            final LlmStreamSink attemptSink = bufferedSink != null ? bufferedSink : outerSink;
            try {
                log.debug("Invoking LLM (streaming): model={}, attempt={}, buffered={}", currentModel, attempt,
                        bufferedSink != null);
                final LlmResponse response = client.sendMessageStreaming(systemPromptParts, messages, tools,
                        currentModel, metadata, options, attemptSink, cancellation);
                if (bufferedSink != null) {
                    bufferedSink.flush();
                }
                return response;

            } catch (LlmCallCancelledException ex) {
                // Terminal: the caller cancelled — discard any buffered partial and never retry or fall back.
                if (bufferedSink != null) {
                    bufferedSink.abort();
                }
                throw ex;
            } catch (LlmPromptTooLongException ex) {
                if (bufferedSink != null) {
                    bufferedSink.abort();
                }
                lastException = attachSuppressed(ex, lastException);

                if (promptTooLongHandler == null) {
                    log.debug("No PromptTooLongHandler registered; propagating LlmPromptTooLongException");
                    throw lastException;
                }
                if (handlerRetries >= maxPromptTooLongHandlerRetries) {
                    log.warn("PromptTooLongHandler retry cap reached ({}); propagating",
                            maxPromptTooLongHandlerRetries);
                    throw lastException;
                }

                final PromptTooLongEvent<M> event = PromptTooLongEvent.<M>builder().exception(ex).model(currentModel)
                        .attempt(attempt).memorySnapshot(snapshotMemory()).build();

                final Optional<HandlerOutcome> outcome = promptTooLongHandler.handle(event);
                if (outcome.isEmpty() || outcome.get() == HandlerOutcome.ABORT) {
                    log.debug("PromptTooLongHandler returned {} — propagating original exception",
                            outcome.map(Enum::name).orElse("empty"));
                    throw lastException;
                }

                handlerRetries++;
                log.debug("PromptTooLongHandler returned RETRY (handlerRetries={}), retrying same model/attempt",
                        handlerRetries);
                // Handler-driven retry reuses the same attempt index; no retryListener callback.

            } catch (LlmClientException ex) {
                if (bufferedSink != null) {
                    bufferedSink.abort();
                }
                lastException = attachSuppressed(ex, lastException);

                final boolean activating = fallbackPolicy.isActivating(ex);
                consecutiveActivatingFailures = activating ? consecutiveActivatingFailures + 1 : 0;
                final boolean canRetrySameModel = retryPolicy.isRetryable(ex) && attempt < retryPolicy.getMaxAttempts();
                final boolean thresholdReached = activating
                        && consecutiveActivatingFailures >= fallbackPolicy.getConsecutiveFailureThreshold();

                // Escalate to the next model when the consecutive-activation threshold is reached, or as a last
                // resort when the current model has exhausted its retry budget.
                if (thresholdReached || !canRetrySameModel) {
                    final Optional<LlmModel> next = fallbackPolicy.nextModel(currentModel, ex);
                    if (next.isPresent()) {
                        log.debug("Fallback activated (streaming): {} -> {} (consecutiveActivating={}, threshold={})",
                                currentModel, next.get(), consecutiveActivatingFailures,
                                fallbackPolicy.getConsecutiveFailureThreshold());
                        // Only notify when using direct mode — buffered mode never exposed chunks to the outer sink.
                        if (bufferedSink == null) {
                            listener.onRetry(attempt - 1, 0, "fallback_model");
                        }
                        currentModel = next.get();
                        attempt = 1;
                        handlerRetries = 0;
                        consecutiveActivatingFailures = 0;
                        continue;
                    }
                }

                if (canRetrySameModel) {
                    if (cancellation.isCancelled()) {
                        throw cancelledBeforeAttempt(attempt, lastException);
                    }
                    final Duration delay = resolveRetryDelay(ex, attempt);
                    log.debug("Retryable streaming failure on attempt {}/{}: sleeping {} before next attempt", attempt,
                            retryPolicy.getMaxAttempts(), delay);
                    sleepQuietly(delay, lastException, cancellation);
                    if (bufferedSink == null) {
                        listener.onRetry(attempt - 1, attempt, retryReasonFor(ex));
                    }
                    attempt++;
                    continue;
                }

                log.debug("Non-recoverable streaming failure (retryable={}, attempt={}/{}), propagating",
                        retryPolicy.isRetryable(ex), attempt, retryPolicy.getMaxAttempts());
                throw lastException;
            }
        }
    }

    private static String retryReasonFor(LlmClientException ex) {
        final String cls = ex.getClass().getSimpleName();
        if (cls.isEmpty()) {
            return "retry";
        }
        return cls;
    }

    /**
     * Builds the terminal {@link LlmCallCancelledException} thrown when the gateway short-circuits because cancellation
     * was requested before an attempt (or before a retry backoff). The most recent provider failure, if any, is
     * attached
     * as a suppressed exception so diagnostics are not lost.
     *
     * @param attempt
     *            the 1-based attempt index that was about to run
     * @param previous
     *            the last provider failure seen so far (may be {@code null})
     * @return the exception to throw (never {@code null})
     */
    private static LlmCallCancelledException cancelledBeforeAttempt(int attempt, LlmClientException previous) {
        final LlmCallCancelledException cancelled = new LlmCallCancelledException(
                "LLM call cancelled before attempt " + attempt);
        if (previous != null) {
            cancelled.addSuppressed(previous);
        }
        return cancelled;
    }

    /**
     * Resolves the delay to sleep before the next retry. When {@code ex} carries a server-supplied {@code Retry-After}
     * hint (only {@link LlmRateLimitedException} does), that hint is honoured — clamped to {@link #getMaxRetryAfter()}
     * so a hostile or misconfigured provider cannot pin the worker thread indefinitely. Otherwise the policy's
     * exponential-backoff-with-jitter delay is used.
     *
     * @param ex
     *            the retryable failure that triggered this backoff
     * @param attemptNumber
     *            the 1-based retry index passed to {@link LlmRetryPolicy#computeDelay(int, Random)} when no server hint
     *            is present
     * @return the delay to sleep before the next attempt (never {@code null}, never negative)
     */
    private Duration resolveRetryDelay(LlmClientException ex, int attemptNumber) {
        final Optional<Duration> serverHint = serverRetryAfter(ex);
        if (serverHint.isPresent()) {
            final Duration hint = serverHint.get();
            final Duration capped = hint.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : hint;
            log.debug("Honoring server Retry-After hint {} (capped to {})", hint, capped);
            return capped;
        }
        return retryPolicy.computeDelay(attemptNumber, random);
    }

    /**
     * Extracts a non-negative {@code Retry-After} hint from {@code ex} when present. Only
     * {@link LlmRateLimitedException}
     * carries one; a negative hint is discarded so it cannot underflow the backoff. A zero hint is preserved (retry
     * immediately).
     */
    private static Optional<Duration> serverRetryAfter(LlmClientException ex) {
        if (ex instanceof LlmRateLimitedException rle) {
            return rle.getRetryAfter().filter(d -> !d.isNegative());
        }
        return Optional.empty();
    }

    private M snapshotMemory() {
        if (memorySnapshotSupplier == null) {
            return null;
        }
        try {
            return memorySnapshotSupplier.get();
        } catch (RuntimeException e) {
            log.warn("memorySnapshotSupplier threw {}; passing null snapshot to handler", e.toString());
            return null;
        }
    }

    /**
     * Attaches {@code previous} to {@code current} via {@link Throwable#addSuppressed(Throwable)} so that the final
     * terminal exception preserves the full history of prior attempts.
     */
    private static LlmClientException attachSuppressed(LlmClientException current, LlmClientException previous) {
        if (previous != null && previous != current) {
            current.addSuppressed(previous);
        }
        return current;
    }

    /**
     * Sleeps for the given duration. If the current thread is interrupted, the interrupt flag is restored and the most
     * recent exception is rethrown with the {@link InterruptedException} attached as suppressed. This ensures a
     * cancelled call fails fast with the observable provider error, not with a bare {@code InterruptedException}.
     */
    private void sleepQuietly(Duration duration, LlmClientException latest) {
        sleepQuietly(duration, latest, LlmCancellation.none());
    }

    /**
     * Cancellation-aware variant of {@link #sleepQuietly(Duration, LlmClientException)}: the backoff wakes early if
     * {@code cancellation} trips during the sleep, so a trip arriving mid-backoff (e.g. a long {@code Retry-After}) is
     * observed on the next loop iteration rather than only after the full delay elapses. Interrupt handling is
     * unchanged.
     */
    private void sleepQuietly(Duration duration, LlmClientException latest, LlmCancellation cancellation) {
        try {
            sleeper.sleep(duration, cancellation);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (latest != null) {
                latest.addSuppressed(ie);
                throw latest;
            }
            throw new LlmClientException("Interrupted while sleeping for retry backoff", ie);
        }
    }

    /**
     * Mutable builder for {@link LlmCallGateway}. Not thread-safe.
     *
     * @param <M>
     *            the transcript-buffer type
     */
    public static final class Builder<M> {
        private LlmClient client;
        private LlmRetryPolicy retryPolicy;
        private LlmFallbackPolicy fallbackPolicy = LlmFallbackPolicy.none();
        private PromptTooLongHandler<M> promptTooLongHandler;
        private Supplier<M> memorySnapshotSupplier;
        private Random random;
        private Sleeper sleeper;
        private int maxPromptTooLongHandlerRetries = DEFAULT_MAX_PROMPT_TOO_LONG_HANDLER_RETRIES;
        private Duration maxRetryAfter = DEFAULT_MAX_RETRY_AFTER;

        private Builder() {
        }

        /**
         * Sets the wrapped {@link LlmClient}.
         *
         * @param client
         *            the wrapped client (must not be {@code null})
         * @return this builder
         */
        public Builder<M> client(LlmClient client) {
            this.client = Objects.requireNonNull(client, "client must not be null");
            return this;
        }

        /**
         * Sets the retry policy.
         *
         * @param retryPolicy
         *            the retry policy (must not be {@code null})
         * @return this builder
         */
        public Builder<M> retryPolicy(LlmRetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
            return this;
        }

        /**
         * Sets the fallback policy. Defaults to {@link LlmFallbackPolicy#none()} when not specified.
         *
         * @param fallbackPolicy
         *            the fallback policy (must not be {@code null})
         * @return this builder
         */
        public Builder<M> fallbackPolicy(LlmFallbackPolicy fallbackPolicy) {
            this.fallbackPolicy = Objects.requireNonNull(fallbackPolicy, "fallbackPolicy must not be null");
            return this;
        }

        /**
         * Sets the prompt-too-long handler. When omitted, the gateway treats {@link LlmPromptTooLongException} as
         * unrecoverable and rethrows it.
         *
         * @param promptTooLongHandler
         *            the handler (may be {@code null})
         * @return this builder
         */
        public Builder<M> promptTooLongHandler(PromptTooLongHandler<M> promptTooLongHandler) {
            this.promptTooLongHandler = promptTooLongHandler;
            return this;
        }

        /**
         * Sets the supplier that produces a transcript-buffer snapshot when a {@link LlmPromptTooLongException}
         * occurs. When omitted, the event's snapshot is empty.
         *
         * @param memorySnapshotSupplier
         *            the supplier (may be {@code null})
         * @return this builder
         */
        public Builder<M> memorySnapshotSupplier(Supplier<M> memorySnapshotSupplier) {
            this.memorySnapshotSupplier = memorySnapshotSupplier;
            return this;
        }

        /**
         * Injects a {@link Random} source used for jitter in {@link LlmRetryPolicy#computeDelay(int, Random)}.
         * Tests use this to make backoff deterministic.
         *
         * @param random
         *            the random source (may be {@code null} to use a fresh {@link Random})
         * @return this builder
         */
        public Builder<M> random(Random random) {
            this.random = random;
            return this;
        }

        /**
         * Injects a {@link Sleeper} used for backoff. Tests use a recording implementation that returns immediately.
         *
         * @param sleeper
         *            the sleeper (may be {@code null} to use {@link Sleeper#threadSleep()})
         * @return this builder
         */
        public Builder<M> sleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        /**
         * Sets the cap on consecutive handler-driven retries.
         *
         * @param maxPromptTooLongHandlerRetries
         *            the cap (must be {@code >= 1})
         * @return this builder
         */
        public Builder<M> maxPromptTooLongHandlerRetries(int maxPromptTooLongHandlerRetries) {
            this.maxPromptTooLongHandlerRetries = maxPromptTooLongHandlerRetries;
            return this;
        }

        /**
         * Sets the ceiling applied to server-supplied {@code Retry-After} hints. Defaults to
         * {@link #DEFAULT_MAX_RETRY_AFTER}. A hint larger than this is clamped down; a smaller hint is honoured as-is.
         *
         * @param maxRetryAfter
         *            the ceiling (must not be {@code null}, must be non-negative)
         * @return this builder
         * @throws NullPointerException
         *             if {@code maxRetryAfter} is {@code null}
         */
        public Builder<M> maxRetryAfter(Duration maxRetryAfter) {
            this.maxRetryAfter = Objects.requireNonNull(maxRetryAfter, "maxRetryAfter must not be null");
            return this;
        }

        /**
         * Builds the {@link LlmCallGateway}.
         *
         * @return an immutable gateway (never {@code null})
         * @throws NullPointerException
         *             if {@code client}, {@code retryPolicy}, or {@code fallbackPolicy} were not set
         * @throws IllegalArgumentException
         *             if {@code maxPromptTooLongHandlerRetries < 1}
         */
        public LlmCallGateway<M> build() {
            return new LlmCallGateway<>(this);
        }
    }
}
