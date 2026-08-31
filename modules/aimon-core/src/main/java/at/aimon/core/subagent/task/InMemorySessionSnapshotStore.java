package at.aimon.core.subagent.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

/**
 * In-memory reference implementation of {@link SessionSnapshotStore}, backed by an LRU-bounded map.
 *
 * <p>
 * Keeps each task's owner-tagged session snapshot ({@link ResumableSession}) in a per-node map. Because
 * {@link SessionSnapshot} is an immutable value object, the stored reference is safe to share without copying.
 * This is the default, single-node implementation; a scale-out deployment supplies a shared/persistent implementation
 * so a snapshot saved on one node is resumable on another.
 *
 * <p>
 * <b>Bounded.</b> A resumable transcript is a full ReAct message history, and the store is agent-scoped (it lives for
 * the whole lifetime of an {@code AgentRuntime}, which survives across sessions). An unbounded map would
 * therefore let a long-running agent that fans out many {@code Task} subagents accumulate transcripts until it exhausts
 * the heap. The map is capped at {@link #DEFAULT_MAX_SNAPSHOTS}: once exceeded, the least-recently-used snapshot is
 * evicted. Eviction merely makes a stale task no longer resumable ({@code Task(resume=...)} reports "no resumable task
 * found"), which is acceptable. This mirrors the codebase's other bounded in-memory stores (e.g.
 * {@code InMemoryCompactionFailureStore}, {@code InMemoryTraceSpanStore}).
 *
 * <p>
 * Thread-safe: {@code save}/{@code load}/{@code evict} synchronize on the backing map, so a subagent thread saving a
 * terminal snapshot may safely race with a parent agent loading it to resume.
 */
public final class InMemorySessionSnapshotStore implements SessionSnapshotStore {

    /**
     * Default maximum number of resumable snapshots retained per store before the least-recently-used one is evicted.
     */
    public static final int DEFAULT_MAX_SNAPSHOTS = 256;

    private final Map<String, ResumableSession> snapshots;

    /**
     * Creates a store bounded to {@link #DEFAULT_MAX_SNAPSHOTS} snapshots.
     */
    public InMemorySessionSnapshotStore() {
        this(DEFAULT_MAX_SNAPSHOTS);
    }

    /**
     * Creates a store bounded to the given number of snapshots.
     *
     * @param maxSnapshots
     *            the maximum number of resumable snapshots to retain (must be {@code >= 1})
     * @throws IllegalArgumentException
     *             if {@code maxSnapshots < 1}
     */
    public InMemorySessionSnapshotStore(int maxSnapshots) {
        if (maxSnapshots < 1) {
            throw new IllegalArgumentException("maxSnapshots must be >= 1, got: " + maxSnapshots);
        }
        this.snapshots = Collections.synchronizedMap(new BoundedLruMap<>(maxSnapshots));
    }

    @Override
    public void save(String taskId, String subagentName, AgentRuntimeId agentRuntimeId, SessionSnapshot snapshot) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(subagentName, "subagentName cannot be null");
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        snapshots.put(taskId, ResumableSession.of(subagentName, agentRuntimeId, snapshot));
    }

    @Override
    public Optional<ResumableSession> load(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        return Optional.ofNullable(snapshots.get(taskId));
    }

    @Override
    public void evict(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        snapshots.remove(taskId);
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
