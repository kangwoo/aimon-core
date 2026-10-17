package at.aimon.core.tracing;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.LlmCallMetadata;

/**
 * Fail-safe helpers for opening child spans and rotating the span parent carried by {@link LlmCallMetadata}.
 *
 * <p>
 * Every site that nests a span under the one a caller handed it does the same three things: read the parent from the
 * metadata's reserved tags, start the child without letting a misbehaving {@link Tracer} break execution, and enrich
 * the metadata so what runs next nests under the new span. The main-agent executor, the subagent executor and the
 * subagent execution manager all do it, so it lives here once.
 *
 * <p>
 * Stateless; all methods are static.
 */
public final class Spans {

    private static final Logger log = LoggerFactory.getLogger(Spans.class);

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
}
