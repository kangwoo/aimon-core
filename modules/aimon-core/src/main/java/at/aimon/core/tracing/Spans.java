package at.aimon.core.tracing;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.LlmCallMetadata;

/**
 * Fail-safe helpers for opening child spans, rotating the span parent carried by {@link LlmCallMetadata}, and
 * recording on a span that is already open.
 *
 * <p>
 * Every site that nests a span under the one a caller handed it does the same three things: read the parent from the
 * metadata's reserved tags, start the child without letting a misbehaving {@link Tracer} break execution, and enrich
 * the metadata so what runs next nests under the new span. The main-agent executor, the subagent executor and the
 * subagent execution manager all do it, so it lives here once.
 *
 * <p>
 * The {@code *Safely} recording helpers ({@link #setAttributeSafely}, {@link #setOutputsSafely},
 * {@link #errorSafely(Tracer.Span, String)}, {@link #interruptedSafely}, {@link #closeSafely}) give the same promise
 * for a span that is already open: a {@link Tracer.Span} that throws cannot fail the work it records, nor replace that
 * work's own exception from a {@code finally}. They catch {@link RuntimeException} only — an {@link Error} propagates,
 * as it does from {@link #startChildSafely}. The first failure of each operation is logged at WARN and later ones at
 * DEBUG, so a tracer broken for good does not flood the log once per call.
 *
 * <p>
 * Stateless apart from that logging bookkeeping; all methods are static.
 */
public final class Spans {

    private static final Logger log = LoggerFactory.getLogger(Spans.class);

    /** The span operations whose failure has been logged at WARN once: at most one entry per operation name. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private Spans() {
    }

    /**
     * Starts a child of the span {@code parentCarrier} points at, or returns empty when it points at none (tracing
     * off, or a call that was never enriched) — so no orphan span is ever produced.
     *
     * @param tracer
     *            the tracer (must not be null)
     * @param parentCarrier
     *            the metadata carrying the parent span in its reserved tags (must not be null)
     * @param type
     *            the span type (must not be null)
     * @param name
     *            the span name (must not be null)
     * @param inputs
     *            an input snapshot, or null
     * @return the started span, or empty when {@code parentCarrier} names no parent
     */
    public static Optional<Tracer.Span> startChildOf(Tracer tracer, LlmCallMetadata parentCarrier, SpanType type,
            String name, Map<String, Object> inputs) {
        Objects.requireNonNull(tracer, "tracer cannot be null");
        Objects.requireNonNull(parentCarrier, "parentCarrier cannot be null");
        return SpanContext.readFrom(parentCarrier).map(parent -> startChildSafely(tracer, parent, type, name, inputs));
    }

    /**
     * Like {@link #startChildOf(Tracer, LlmCallMetadata, SpanType, String, Map)}, but builds the input snapshot only
     * once a parent is found — so a caller whose inputs cost something to assemble pays nothing when tracing is off.
     *
     * @param tracer
     *            the tracer (must not be null)
     * @param parentCarrier
     *            the metadata carrying the parent span in its reserved tags (must not be null)
     * @param type
     *            the span type (must not be null)
     * @param name
     *            the span name (must not be null)
     * @param inputs
     *            supplies the input snapshot (must not be null; may supply null); called at most once
     * @return the started span, or empty when {@code parentCarrier} names no parent
     */
    public static Optional<Tracer.Span> startChildOfLazily(Tracer tracer, LlmCallMetadata parentCarrier, SpanType type,
            String name, Supplier<Map<String, Object>> inputs) {
        Objects.requireNonNull(tracer, "tracer cannot be null");
        Objects.requireNonNull(parentCarrier, "parentCarrier cannot be null");
        Objects.requireNonNull(inputs, "inputs cannot be null");
        return SpanContext.readFrom(parentCarrier)
                .map(parent -> startChildSafely(tracer, parent, type, name, inputs.get()));
    }

    /**
     * Starts a root span, falling back to a no-op span when {@code tracer} throws, so tracing can never break the run
     * the root stands for.
     *
     * @param tracer
     *            the tracer (must not be null)
     * @param sessionId
     *            the group key the trace is recorded under (must not be null)
     * @param type
     *            the span type (must not be null)
     * @param name
     *            the span name (must not be null)
     * @param inputs
     *            an input snapshot, or null
     * @return the started span, or a no-op span when the tracer failed (never null)
     */
    public static Tracer.Span startRootSafely(Tracer tracer, String sessionId, SpanType type, String name,
            Map<String, Object> inputs) {
        try {
            return tracer.startRoot(sessionId, type, name, inputs);
        } catch (RuntimeException e) {
            log.warn("Tracer.startRoot failed; using a no-op span: {}", e.getMessage());
            return Tracer.noop().startRoot(sessionId, type, name, inputs);
        }
    }

    /**
     * Starts a child span, falling back to a no-op span when {@code tracer} throws, so tracing can never break agent
     * execution.
     *
     * @param tracer
     *            the tracer (must not be null)
     * @param parent
     *            the parent context (must not be null)
     * @param type
     *            the span type (must not be null)
     * @param name
     *            the span name (must not be null)
     * @param inputs
     *            an input snapshot, or null
     * @return the started span, or a no-op span when the tracer failed (never null)
     */
    public static Tracer.Span startChildSafely(Tracer tracer, SpanContext parent, SpanType type, String name,
            Map<String, Object> inputs) {
        try {
            return tracer.startChild(parent, type, name, inputs);
        } catch (RuntimeException e) {
            log.warn("Tracer.startChild failed; using a no-op span: {}", e.getMessage());
            return Tracer.noop().startChild(parent, type, name, inputs);
        }
    }

    /**
     * Returns {@code base} rotated so that what runs downstream nests under {@code span}. A failing {@code enrich}
     * leaves {@code base} as it was: the downstream spans then attach one level higher, and execution is unaffected.
     *
     * @param span
     *            the span to nest under (must not be null)
     * @param base
     *            the metadata to rotate (must not be null)
     * @return the rotated metadata, or {@code base} itself when the span is a no-op or enriching failed (never null)
     */
    public static LlmCallMetadata enrichSafely(Tracer.Span span, LlmCallMetadata base) {
        Objects.requireNonNull(base, "base cannot be null");
        try {
            return span.enrich(base);
        } catch (RuntimeException e) {
            log.warn("Tracer.Span.enrich failed; keeping the parent span: {}", e.getMessage());
            return base;
        }
    }

    /**
     * Sets an attribute, swallowing a failing span ({@linkplain Spans class documentation}).
     *
     * @param span
     *            the span or its attribute view (must not be null)
     * @param key
     *            the attribute key (must not be null)
     * @param value
     *            the attribute value (must not be null)
     */
    public static void setAttributeSafely(SpanAttributes span, String key, String value) {
        Objects.requireNonNull(span, "span cannot be null");
        try {
            span.setAttribute(key, value);
        } catch (RuntimeException e) {
            failed("setAttribute", e);
        }
    }

    /**
     * Like {@link #setAttributeSafely}, but a failure is logged at DEBUG only, every time — for an attribute set on a
     * hot path (a streaming call's first-chunk time) where even one WARN per process would be noise.
     *
     * @param span
     *            the span or its attribute view (must not be null)
     * @param key
     *            the attribute key (must not be null)
     * @param value
     *            the attribute value (must not be null)
     */
    public static void setAttributeQuietly(SpanAttributes span, String key, String value) {
        Objects.requireNonNull(span, "span cannot be null");
        try {
            span.setAttribute(key, value);
        } catch (RuntimeException e) {
            log.debug("Setting span attribute '{}' failed: {}", key, e.getMessage());
        }
    }

    /**
     * Sets the span's outputs, swallowing a failing span ({@linkplain Spans class documentation}).
     *
     * @param span
     *            the span (must not be null)
     * @param outputs
     *            the output snapshot (may be null)
     */
    public static void setOutputsSafely(Tracer.Span span, Object outputs) {
        Objects.requireNonNull(span, "span cannot be null");
        try {
            span.setOutputs(outputs);
        } catch (RuntimeException e) {
            failed("setOutputs", e);
        }
    }

    /**
     * Marks the span failed with a message, swallowing a failing span ({@linkplain Spans class documentation}).
     *
     * @param span
     *            the span (must not be null)
     * @param message
     *            the error message (may be null)
     */
    public static void errorSafely(Tracer.Span span, String message) {
        Objects.requireNonNull(span, "span cannot be null");
        try {
            span.error(message);
        } catch (RuntimeException e) {
            failed("error", e);
        }
    }

    /**
     * Marks the span failed with a throwable, swallowing a failing span ({@linkplain Spans class documentation}).
     *
     * @param span
     *            the span (must not be null)
     * @param failure
     *            what the work threw (must not be null)
     */
    public static void errorSafely(Tracer.Span span, Throwable failure) {
        Objects.requireNonNull(span, "span cannot be null");
        try {
            span.error(failure);
        } catch (RuntimeException e) {
            failed("error", e);
        }
    }

    /**
     * Marks the span interrupted, swallowing a failing span ({@linkplain Spans class documentation}).
     *
     * @param span
     *            the span (must not be null)
     */
    public static void interruptedSafely(Tracer.Span span) {
        Objects.requireNonNull(span, "span cannot be null");
        try {
            span.interrupted();
        } catch (RuntimeException e) {
            failed("interrupted", e);
        }
    }

    /**
     * Closes the span, swallowing a failing span ({@linkplain Spans class documentation}) — so a {@code finally} that
     * closes it cannot replace the exception the work itself threw.
     *
     * @param span
     *            the span (must not be null)
     */
    public static void closeSafely(Tracer.Span span) {
        Objects.requireNonNull(span, "span cannot be null");
        try {
            span.close();
        } catch (RuntimeException e) {
            failed("close", e);
        }
    }

    private static void failed(String operation, RuntimeException e) {
        if (WARNED.add(operation)) {
            log.warn("Tracer.Span.{} failed: {} (further failures of this operation are logged at DEBUG)", operation,
                    e.getMessage());
        } else {
            log.debug("Tracer.Span.{} failed: {}", operation, e.getMessage());
        }
    }

    /**
     * Returns metadata that carries only where {@code metadata} sits in the span tree: its group key
     * ({@link LlmCallMetadata#getTraceId()}) and the two reserved span tags. Every attribution field — component,
     * feature, principal, other tags — is left out, so a callee that merges it with its own defaults (caller fields
     * win) keeps its own attribution and only learns its parent span.
     *
     * @param metadata
     *            the metadata to read (must not be null)
     * @return the carrier, or empty when {@code metadata} names no parent span (tracing off, or never enriched)
     */
    public static Optional<LlmCallMetadata> traceCarrier(LlmCallMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata cannot be null");
        final Map<String, String> tags = metadata.getTags();
        final String traceId = tags.get(SpanContext.TAG_TRACE_ID);
        final String parentSpanId = tags.get(SpanContext.TAG_PARENT_SPAN_ID);
        if (traceId == null || parentSpanId == null) {
            return Optional.empty();
        }
        final LlmCallMetadata.Builder carrier = LlmCallMetadata.builder()
                .tags(Map.of(SpanContext.TAG_TRACE_ID, traceId, SpanContext.TAG_PARENT_SPAN_ID, parentSpanId));
        metadata.getTraceId().ifPresent(carrier::traceId);
        return Optional.of(carrier.build());
    }
}
