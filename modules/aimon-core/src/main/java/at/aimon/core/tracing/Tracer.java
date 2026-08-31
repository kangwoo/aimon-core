package at.aimon.core.tracing;

import java.util.Map;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.TokenUsage;

/**
 * Starts and ends {@link TraceSpan}s, assembling the per-turn span tree.
 *
 * <p>
 * <b>Fail-safe contract:</b> tracing must never break agent execution. Implementations swallow their own errors and the
 * {@link Span} lifecycle methods (notably {@link Span#close()}) never throw. The default {@link NoopTracer} is a
 * zero-overhead no-op so tracing-off has no behavioural effect.
 *
 * <p>
 * Span parenting is propagated explicitly via {@link SpanContext} (never thread-locals), so it stays correct across the
 * parallel tool dispatcher and async streaming sinks.
 */
public interface Tracer {

    /**
     * Returns the shared no-op tracer (records nothing, zero overhead). The framework default when tracing is disabled.
     *
     * @return the no-op {@link Tracer}
     */
    static Tracer noop() {
        return at.aimon.core.tracing.impl.NoopTracer.INSTANCE;
    }

    /**
     * Starts a root span for a new trace (one turn). The returned span's {@link Span#context() context} has its trace
     * id
     * equal to its span id and no parent.
     *
     * @param sessionId
     *            the session id (must not be null)
     * @param type
     *            the span type (typically {@link SpanType#TURN}; must not be null)
     * @param name
     *            a human-readable span name (must not be null)
     * @param inputs
     *            an input snapshot, or null
     * @return the started span (never null)
     */
    Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs);

    /**
     * Starts a child span nested under {@code parent}.
     *
     * @param parent
     *            the parent context (must not be null)
     * @param type
     *            the span type (must not be null)
     * @param name
     *            a human-readable span name (must not be null)
     * @param inputs
     *            an input snapshot, or null
     * @return the started span (never null)
     */
    Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs);

    /**
     * A live span handle. {@link #close()} finalizes and records the span. {@code AutoCloseable} enables
     * try-with-resources; {@link #close()} never throws.
     */
    interface Span extends AutoCloseable {

        /**
         * Returns the context to propagate to child spans / downstream calls.
         *
         * @return this span's context (never null)
         */
        SpanContext context();

        /**
         * Returns {@code base} enriched so that downstream LLM calls / sub-executions become children of <b>this</b>
         * span (by writing this span's {@link SpanContext} into reserved metadata tags). The no-op span returns
         * {@code base} unchanged, so tracing-off never mutates metadata.
         *
         * @param base
         *            the metadata to enrich (must not be null)
         * @return the enriched metadata (never null)
         */
        LlmCallMetadata enrich(LlmCallMetadata base);

        /**
         * Attaches an output snapshot.
         *
         * @param outputs
         *            the output snapshot (may be null)
         */
        void setOutputs(Object outputs);

        /**
         * Records token usage (LLM spans).
         *
         * @param usage
         *            the token usage (may be null)
         */
        void setTokenUsage(TokenUsage usage);

        /**
         * Records the model name (LLM spans).
         *
         * @param model
         *            the model name (may be null)
         */
        void setModel(String model);

        /**
         * Adds an attribute (e.g. iteration, principal, invokerType).
         *
         * @param key
         *            the attribute key (must not be null)
         * @param value
         *            the attribute value (must not be null)
         */
        void setAttribute(String key, String value);

        /**
         * Marks the span as failed with the given throwable.
         *
         * @param t
         *            the error (may be null)
         */
        void error(Throwable t);

        /**
         * Marks the span as failed with the given message (e.g. a tool that returned an error result rather than
         * throwing).
         *
         * @param message
         *            the error message (may be null)
         */
        void error(String message);

        /**
         * Marks the span as interrupted/cancelled.
         */
        void interrupted();

        /**
         * Finalizes the span (sets end time, records it). Idempotent; never throws.
         */
        @Override
        void close();
    }
}
