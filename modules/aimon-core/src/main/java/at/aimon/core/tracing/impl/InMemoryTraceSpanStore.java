package at.aimon.core.tracing.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.TraceSpanStore;

/**
 * In-memory {@link TraceSpanStore} — the framework default and reference implementation.
 *
 * <p>
 * Thread-safe via {@link ConcurrentHashMap}. Bounded by a configurable maximum span count; when exceeded, the
 * oldest-recorded spans are evicted (FIFO) so unbounded agent activity cannot exhaust memory. Suitable for CLI
 * debugging; production deployments swap in a persistent backing store.
 */
public final class InMemoryTraceSpanStore implements TraceSpanStore {

    /** Default maximum number of retained spans. */
    public static final int DEFAULT_MAX_SPANS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(InMemoryTraceSpanStore.class);

    private final int maxSpans;
    private final Map<String, TraceSpan> byId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> byTraceId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> bySessionId = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();

    /**
     * Creates a store bounded to {@link #DEFAULT_MAX_SPANS} spans.
     */
    public InMemoryTraceSpanStore() {
        this(DEFAULT_MAX_SPANS);
    }

    /**
     * @param maxSpans
     *            the maximum number of retained spans (must be positive)
     */
    public InMemoryTraceSpanStore(int maxSpans) {
        if (maxSpans <= 0) {
            throw new IllegalArgumentException("maxSpans must be positive, got: " + maxSpans);
        }
        this.maxSpans = maxSpans;
    }

    @Override
    public void record(TraceSpan span) {
        if (span == null) {
            return;
        }
        try {
            final String spanId = span.getSpanId();
            if (byId.put(spanId, span) == null) {
                insertionOrder.add(spanId);
            }
            byTraceId.computeIfAbsent(span.getTraceId(), k -> ConcurrentHashMap.newKeySet()).add(spanId);
            bySessionId.computeIfAbsent(span.getSessionId(), k -> ConcurrentHashMap.newKeySet()).add(spanId);
            evictWhileOverCapacity();
        } catch (RuntimeException e) {
            log.warn("Failed to record trace span (ignored): {}", e.getMessage());
        }
    }

    @Override
    public Optional<TraceSpan> get(String spanId) {
        Objects.requireNonNull(spanId, "spanId cannot be null");
        return Optional.ofNullable(byId.get(spanId));
    }

    @Override
    public List<TraceSpan> byTrace(String traceId) {
        Objects.requireNonNull(traceId, "traceId cannot be null");
        return resolveSorted(byTraceId.get(traceId));
    }

    @Override
    public List<TraceSpan> bySession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        return resolveSorted(bySessionId.get(sessionId));
    }

    @Override
    public void deleteOlderThan(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff cannot be null");
        for (final TraceSpan span : new ArrayList<>(byId.values())) {
            final Instant end = span.getEndTime().orElse(span.getStartTime());
            if (end.isBefore(cutoff)) {
                final String spanId = span.getSpanId();
                if (dropIndexes(spanId)) {
                    // Arbitrary (non-head) removal: the O(n) queue scan is unavoidable, but this is a rare sweep.
                    insertionOrder.remove(spanId);
                }
            }
        }
    }

    private List<TraceSpan> resolveSorted(Set<String> spanIds) {
        if (spanIds == null || spanIds.isEmpty()) {
            return List.of();
        }
        final List<TraceSpan> result = new ArrayList<>();
        for (final String id : spanIds) {
            final TraceSpan span = byId.get(id);
            if (span != null) {
                result.add(span);
            }
        }
        result.sort(Comparator.comparing(TraceSpan::getStartTime));
        return result;
    }

    /**
     * FIFO eviction. The oldest id is taken off the queue via {@link ConcurrentLinkedQueue#poll() poll()}, so only the
     * id indexes need cleanup — no O(n) {@code remove(Object)} scan of the queue. Eviction is best-effort under
     * concurrent {@code record()} calls (two threads may each evict once when only one is over capacity); the bound is
     * never exceeded for long and no data is corrupted.
     */
    private void evictWhileOverCapacity() {
        while (byId.size() > maxSpans) {
            final String oldest = insertionOrder.poll();
            if (oldest == null) {
                return;
            }
            dropIndexes(oldest);
        }
    }

    /**
     * Removes a span from {@code byId} and the trace/session indexes (but not the insertion-order queue).
     *
     * @return {@code true} if the span was present
     */
    private boolean dropIndexes(String spanId) {
        final TraceSpan removed = byId.remove(spanId);
        if (removed != null) {
            dropFromIndex(byTraceId, removed.getTraceId(), spanId);
            dropFromIndex(bySessionId, removed.getSessionId(), spanId);
        }
        return removed != null;
    }

    private static void dropFromIndex(Map<String, Set<String>> index, String key, String spanId) {
        index.computeIfPresent(key, (k, ids) -> {
            ids.remove(spanId);
            return ids.isEmpty() ? null : ids;
        });
    }
}
