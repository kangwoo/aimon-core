package at.aimon.core.tracing;

/**
 * Exports completed {@link TraceSpan}s to an external tracing backend, separately from {@link TraceSpanStore}.
 *
 * <p>
 * External modules (e.g. {@code aimon-tracing-otlp}) map spans to OpenTelemetry GenAI semantic conventions so traces
 * can
 * be viewed in Jaeger / Tempo / Phoenix / Langfuse. {@link #export(TraceSpan)} must be non-blocking and never throw.
 */
public interface SpanExporter {

    /**
     * Exports a completed span. Non-blocking; never throws.
     *
     * @param span
     *            the span to export (must not be null)
     */
    void export(TraceSpan span);

    /**
     * Flushes any buffered spans. No-op by default.
     */
    default void flush() {
        // no-op
    }

    /**
     * Returns a no-op exporter that discards all spans.
     *
     * @return a no-op {@link SpanExporter}
     */
    static SpanExporter noop() {
        return span -> {
            // discard
        };
    }
}
