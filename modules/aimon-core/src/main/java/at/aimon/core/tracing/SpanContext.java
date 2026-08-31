package at.aimon.core.tracing;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.LlmCallMetadata;

/**
 * Lightweight, immutable propagation token that links a child span to its parent across execution boundaries.
 *
 * <p>
 * A {@code SpanContext} is propagated <b>explicitly as data</b> — never via thread-locals — because the framework
 * dispatches tools on worker threads ({@code ParallelToolDispatcher}) and streams responses on async sinks, both of
 * which would corrupt a thread-local parent stack.
 *
 * <p>
 * Id model (see the tracing design doc):
 *
 * <ul>
 * <li>{@code sessionId} — the session (= existing {@link LlmCallMetadata#getTraceId()} value). Groups many turns.
 * <li>{@code traceId} — one turn. Equals the turn root span id.
 * <li>{@code spanId} — this context's active span.
 * <li>{@code parentSpanId} — the span that contains {@code spanId} (null for a root).
 * </ul>
 *
 * <p>
 * The session id is carried by the existing {@link LlmCallMetadata#getTraceId()} field; the turn trace id and the
 * active
 * span id ride on reserved tags so the low-cardinality {@code component}/{@code parentComponent} usage-attribution
 * fields are left untouched.
 */
public final class SpanContext {

    /** Reserved {@link LlmCallMetadata} tag carrying the per-turn trace id. */
    public static final String TAG_TRACE_ID = "aimon.trace_id";

    /**
     * Reserved {@link LlmCallMetadata} tag carrying the active span id (= the parent of any span created downstream).
     */
    public static final String TAG_PARENT_SPAN_ID = "aimon.parent_span_id";

    private final String sessionId;
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;

    private SpanContext(String sessionId, String traceId, String spanId, String parentSpanId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null");
        this.traceId = Objects.requireNonNull(traceId, "traceId cannot be null");
        this.spanId = Objects.requireNonNull(spanId, "spanId cannot be null");
        this.parentSpanId = parentSpanId;
    }

    /**
     * Creates a root context for a new trace (turn). The trace id equals the root span id and there is no parent.
     *
     * @param sessionId
     *            the session id (must not be null)
     * @param rootSpanId
     *            the root span id, which also becomes the trace id (must not be null)
     * @return a root {@link SpanContext}
     */
    public static SpanContext root(String sessionId, String rootSpanId) {
        Objects.requireNonNull(rootSpanId, "rootSpanId cannot be null");
        return new SpanContext(sessionId, rootSpanId, rootSpanId, null);
    }

    /**
     * Creates a context from explicit ids.
     *
     * @param sessionId
     *            the session id (must not be null)
     * @param traceId
     *            the trace id (must not be null)
     * @param spanId
     *            the active span id (must not be null)
     * @param parentSpanId
     *            the parent span id, or null for a root
     * @return a {@link SpanContext}
     */
    public static SpanContext of(String sessionId, String traceId, String spanId, String parentSpanId) {
        return new SpanContext(sessionId, traceId, spanId, parentSpanId);
    }

    /**
     * Derives a child context that nests under this context's active span. The session and trace ids are inherited; the
     * child's parent becomes this context's span id.
     *
     * @param childSpanId
     *            the child span id (must not be null)
     * @return a child {@link SpanContext}
     */
    public SpanContext child(String childSpanId) {
        Objects.requireNonNull(childSpanId, "childSpanId cannot be null");
        return new SpanContext(sessionId, traceId, childSpanId, spanId);
    }

    /**
     * Writes this context into {@link LlmCallMetadata} so that a downstream LLM call (or sub-execution) attaches as a
     * <b>child of this context's active span</b>. The session id is assumed to already live in
     * {@link LlmCallMetadata#getTraceId()}; only the reserved trace/parent-span tags are added.
     *
     * @param base
     *            the metadata to enrich (must not be null)
     * @return a new metadata instance carrying the reserved span tags (never null)
     */
    public LlmCallMetadata writeInto(LlmCallMetadata base) {
        Objects.requireNonNull(base, "base metadata cannot be null");
        return base.withTags(Map.of(TAG_TRACE_ID, traceId, TAG_PARENT_SPAN_ID, spanId));
    }

    /**
     * Reads the <b>parent</b> context encoded in {@link LlmCallMetadata} reserved tags. A span created with the
     * returned
     * context nests under the {@code aimon.parent_span_id} written by the enriching caller.
     *
     * @param metadata
     *            the metadata to read (must not be null)
     * @return the parent context, or {@link Optional#empty()} if the reserved tags are absent (not yet enriched)
     */
    public static Optional<SpanContext> readFrom(LlmCallMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata cannot be null");
        final Map<String, String> tags = metadata.getTags();
        final String traceId = tags.get(TAG_TRACE_ID);
        final String parentSpanId = tags.get(TAG_PARENT_SPAN_ID);
        if (traceId == null || parentSpanId == null) {
            return Optional.empty();
        }
        final String sessionId = metadata.getTraceId().orElse("");
        // The encoded parent_span_id becomes the active span of the returned context; its own parent is unknown.
        return Optional.of(new SpanContext(sessionId, traceId, parentSpanId, null));
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public Optional<String> getParentSpanId() {
        return Optional.ofNullable(parentSpanId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SpanContext that = (SpanContext) o;
        return sessionId.equals(that.sessionId) && traceId.equals(that.traceId) && spanId.equals(that.spanId)
                && Objects.equals(parentSpanId, that.parentSpanId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, traceId, spanId, parentSpanId);
    }

    @Override
    public String toString() {
        return "SpanContext{sessionId='" + sessionId + "', traceId='" + traceId + "', spanId='" + spanId
                + "', parentSpanId='" + parentSpanId + "'}";
    }
}
