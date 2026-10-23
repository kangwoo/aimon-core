package at.aimon.core.llm.usage;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.TokenUsage;

/**
 * Sink for attributed LLM usage events.
 *
 * <p>
 * A recorder receives one event per metered LLM call with the provider identity, the model that was invoked, the
 * token usage returned by the provider, and the {@link LlmCallMetadata} tags attached at the call site. Recorders are
 * stateless from the caller's perspective — they may aggregate in memory, emit to a metrics pipeline (Prometheus,
 * OpenTelemetry), or persist to a database.
 *
 * <p>
 * Two entry points exist. {@link MeteringLlmClient} calls {@link #record(LlmUsageEvent)}, whose event also carries
 * the applied service tier, the prompt-cache counters, the served model and the call's timing. Its default
 * implementation forwards completed calls to the original four-argument {@link #record(String, String, TokenUsage,
 * LlmCallMetadata)}, with the measured tier and cache counters written into the metadata's tags (see
 * {@link LlmUsageEvent#toRecordingMetadata()}), so an existing recorder keeps working unchanged. A recorder that needs
 * the typed values, or wants to see {@link LlmUsageEvent.Outcome#ABORTED aborted} calls, overrides the event method.
 *
 * <p>
 * Following the project's multi-instance design principle, the core module ships an in-memory reference
 * implementation ({@link InMemoryLlmUsageRecorder}); alternative backends are provided as separate modules or
 * wire-ups.
 *
 * <p>
 * Implementations must be thread-safe.
 */
public interface LlmUsageRecorder {

    /**
     * A no-op recorder that discards every event. Useful as a default when metering is not wired up.
     */
    LlmUsageRecorder NOOP = (provider, model, usage, metadata) -> {
    };

    /**
     * Records a single LLM call.
     *
     * @param provider
     *            The provider name (e.g. "OpenAI", "Anthropic Claude") — must not be null
     * @param model
     *            The model identifier the call asked for (may be null or empty if unknown)
     * @param usage
     *            The token usage returned by the provider (must not be null, may be {@link TokenUsage#empty()})
     * @param metadata
     *            The attribution metadata attached at the call site (must not be null, may be
     *            {@link LlmCallMetadata#empty()})
     */
    void record(String provider, String model, TokenUsage usage, LlmCallMetadata metadata);

    /**
     * Records a single metered LLM call with everything the provider reported about it.
     *
     * <p>
     * The default implementation keeps recorders written against the four-argument method working: a
     * {@link LlmUsageEvent.Outcome#COMPLETED completed} call is forwarded as
     * {@code record(provider, requestedModel, usage, event.toRecordingMetadata())}, and an
     * {@link LlmUsageEvent.Outcome#ABORTED aborted} call — which has no usage to report — is dropped, exactly as a
     * failed call always was. Override this method to receive the typed values and aborted calls.
     *
     * @param event
     *            the call (must not be null)
     */
    default void record(LlmUsageEvent event) {
        if (event.getOutcome() != LlmUsageEvent.Outcome.COMPLETED) {
            return;
        }
        record(event.getProvider(), event.getRequestedModel().orElse(null), event.getUsage().orElseThrow(),
                event.toRecordingMetadata());
    }
}
