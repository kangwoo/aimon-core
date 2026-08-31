package at.aimon.core.llm.usage;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.TokenUsage;

/**
 * Sink for attributed LLM usage events.
 *
 * <p>
 * A recorder receives one event per completed LLM call with the provider identity, the model that was invoked, the
 * token usage returned by the provider, and the {@link LlmCallMetadata} tags attached at the call site. Recorders are
 * stateless from the caller's perspective — they may aggregate in memory, emit to a metrics pipeline (Prometheus,
 * OpenTelemetry), or persist to a database.
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
     *            The model identifier actually invoked (may be null or empty if unknown)
     * @param usage
     *            The token usage returned by the provider (must not be null, may be {@link TokenUsage#empty()})
     * @param metadata
     *            The attribution metadata attached at the call site (must not be null, may be
     *            {@link LlmCallMetadata#empty()})
     */
    void record(String provider, String model, TokenUsage usage, LlmCallMetadata metadata);
}
