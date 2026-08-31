package at.aimon.core.workflow.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.workflow.StepKey;
import at.aimon.core.workflow.StepOutcome;
import at.aimon.core.workflow.StepResultCache;

/**
 * In-memory, node-local {@link StepResultCache} backed by an LRU-bounded map (clone of the bounding strategy in
 * {@code InMemorySessionSnapshotStore}).
 *
 * <p>
 * The default, single-node implementation and reference for the multi-instance seam. Since the runner can be
 * application-scoped and long-lived, an unbounded cache would let a process that runs many scripts accumulate outcomes
 * until it exhausts the heap. The map is capped at {@link #DEFAULT_MAX_STEPS}: once exceeded, the least-recently-used
 * outcome is evicted. Eviction merely makes that step non-resumable (it re-executes on the next run), which is
 * acceptable. A scale-out deployment supplies a shared/persistent implementation so a step cached on one node replays
 * on another.
 *
 * <p>
 * Thread-safe: {@code save}/{@code load}/{@code evict} synchronize on the backing map, so a run's fan-out workers may
 * save concurrently while a resuming run loads.
 */
public final class InMemoryStepResultCache implements StepResultCache {

    /** Default maximum number of memoized step outcomes retained before the least-recently-used one is evicted. */
    public static final int DEFAULT_MAX_STEPS = 256;

    private final Map<StepKey, StepOutcome> outcomes;

    /** Creates a cache bounded to {@link #DEFAULT_MAX_STEPS} outcomes. */
    public InMemoryStepResultCache() {
        this(DEFAULT_MAX_STEPS);
    }

    /**
     * Creates a cache bounded to the given number of outcomes.
     *
     * @param maxSteps
     *            the maximum number of memoized outcomes to retain (must be {@code >= 1})
     * @throws IllegalArgumentException
     *             if {@code maxSteps < 1}
     */
    public InMemoryStepResultCache(int maxSteps) {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be >= 1, got: " + maxSteps);
        }
        this.outcomes = Collections.synchronizedMap(new BoundedLruMap<>(maxSteps));
    }

    @Override
    public Optional<StepOutcome> load(StepKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        return Optional.ofNullable(outcomes.get(key));
    }

    @Override
    public void save(StepKey key, StepOutcome outcome) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(outcome, "outcome cannot be null");
        outcomes.put(key, outcome);
    }

    @Override
    public void evict(StepKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        outcomes.remove(key);
    }

    private static final class BoundedLruMap<K, V> extends LinkedHashMap<K, V> {

        private static final long serialVersionUID = 1L;

        private final int maxEntries;

        BoundedLruMap(int maxEntries) {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }
}
