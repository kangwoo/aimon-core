package at.aimon.core.tracing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stores and queries completed {@link TraceSpan}s. Application-scoped.
 *
 * <p>
 * Multi-instance ready: the in-memory implementation ({@code InMemoryTraceSpanStore}) is the default and reference;
 * scale-out deployments swap in a backing store (e.g. {@code aimon-tracing-postgres}) without refactoring callers.
 *
 * <p>
 * {@link #record(TraceSpan)} is fire-and-forget from the caller's perspective and must be non-blocking; implementations
 * must be thread-safe.
 */
public interface TraceSpanStore {

    /**
     * Records a completed span. Non-blocking; never throws.
     *
     * @param span
     *            the span to record (must not be null)
     */
    void record(TraceSpan span);

    /**
     * Looks up a span by its id.
     *
     * @param spanId
     *            the span id (must not be null)
     * @return the span, or empty if unknown
     */
    Optional<TraceSpan> get(String spanId);

    /**
     * Returns all spans of one trace (one turn), for tree reconstruction.
     *
     * @param traceId
     *            the trace id (must not be null)
     * @return the spans of the trace (never null; empty if none)
     */
    List<TraceSpan> byTrace(String traceId);

    /**
     * Returns all spans of one session, across its turns.
     *
     * @param sessionId
     *            the session id (must not be null)
     * @return the spans of the session (never null; empty if none)
     */
    List<TraceSpan> bySession(String sessionId);

    /**
     * Deletes spans whose end time is before {@code cutoff} (retention).
     *
     * @param cutoff
     *            the cutoff instant (must not be null)
     */
    void deleteOlderThan(Instant cutoff);
}
