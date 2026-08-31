package at.aimon.core.llm.usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.TokenUsage;

/**
 * In-memory {@link LlmUsageRecorder} that aggregates token usage by {@link LlmUsageKey}.
 *
 * <p>
 * Serves as the default reference implementation per the project's multi-instance design principle: swap for a
 * Prometheus/OpenTelemetry/DB-backed recorder without touching business code.
 *
 * <p>
 * Thread-safe. Aggregation state is held in a {@link ConcurrentHashMap} of {@link AtomicReference}s, with per-key
 * updates performed via {@code compareAndSet} to avoid locks on the hot path.
 */
public final class InMemoryLlmUsageRecorder implements LlmUsageRecorder {

    private final ConcurrentHashMap<LlmUsageKey, AtomicReference<Aggregate>> aggregates = new ConcurrentHashMap<>();

    @Override
    public void record(String provider, String model, TokenUsage usage, LlmCallMetadata metadata) {
        Objects.requireNonNull(provider, "Provider cannot be null");
        Objects.requireNonNull(usage, "Usage cannot be null");
        Objects.requireNonNull(metadata, "Metadata cannot be null");

        final LlmUsageKey key = LlmUsageKey.of(provider, model, metadata);
        final AtomicReference<Aggregate> ref = aggregates.computeIfAbsent(key,
                k -> new AtomicReference<>(Aggregate.EMPTY));

        Aggregate current;
        Aggregate next;
        do {
            current = ref.get();
            next = current.add(usage);
        } while (!ref.compareAndSet(current, next));
    }

    /**
     * Returns an immutable snapshot of all aggregated usage.
     *
     * @return a list of snapshots (never null, may be empty)
     */
    public List<LlmUsageSnapshot> snapshot() {
        final List<LlmUsageSnapshot> result = new ArrayList<>(aggregates.size());
        for (Map.Entry<LlmUsageKey, AtomicReference<Aggregate>> entry : aggregates.entrySet()) {
            final Aggregate agg = entry.getValue().get();
            result.add(new LlmUsageSnapshot(entry.getKey(), agg.callCount, agg.totalUsage));
        }
        return List.copyOf(result);
    }

    /**
     * Clears all aggregated usage. Intended for tests.
     */
    public void reset() {
        aggregates.clear();
    }

    private static final class Aggregate {
        private static final Aggregate EMPTY = new Aggregate(0L, TokenUsage.empty());

        private final long callCount;
        private final TokenUsage totalUsage;

        private Aggregate(long callCount, TokenUsage totalUsage) {
            this.callCount = callCount;
            this.totalUsage = totalUsage;
        }

        private Aggregate add(TokenUsage delta) {
            return new Aggregate(callCount + 1, totalUsage.add(delta));
        }
    }
}
