package at.aimon.core.agent.compact;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import at.aimon.core.agent.session.SessionId;

/**
 * In-memory {@link CompactionFailureStore} backed by an LRU-bounded map.
 *
 * <p>
 * The default for single-instance deployments — counters live in process memory only. The map is bounded so that
 * long-running services that handle many short-lived sessions do not accumulate state indefinitely; eldest entries
 * are evicted in LRU order once {@link #DEFAULT_MAX_TRACKED_SESSIONS} is exceeded. Eviction merely "forgets" the
 * counter for a stale session, which is acceptable because the worst-case effect is letting AUTO compaction try
 * again.
 *
 * <p>
 * Thread-safe.
 */
public final class InMemoryCompactionFailureStore implements CompactionFailureStore {

    public static final int DEFAULT_MAX_TRACKED_SESSIONS = 1024;

    private final Map<SessionId, AtomicInteger> counters;

    public InMemoryCompactionFailureStore() {
        this(DEFAULT_MAX_TRACKED_SESSIONS);
    }

    public InMemoryCompactionFailureStore(int maxTrackedSessions) {
        if (maxTrackedSessions < 1) {
            throw new IllegalArgumentException("maxTrackedSessions must be >= 1, got: " + maxTrackedSessions);
        }
        this.counters = Collections.synchronizedMap(new BoundedLruMap<>(maxTrackedSessions));
    }

    @Override
    public int get(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        final AtomicInteger counter = counters.get(sessionId);
        return counter == null ? 0 : counter.get();
    }

    @Override
    public int recordFailure(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        synchronized (counters) {
            return counters.computeIfAbsent(sessionId, id -> new AtomicInteger()).incrementAndGet();
        }
    }

    @Override
    public void reset(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        final AtomicInteger counter = counters.get(sessionId);
        if (counter != null) {
            counter.set(0);
        }
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
