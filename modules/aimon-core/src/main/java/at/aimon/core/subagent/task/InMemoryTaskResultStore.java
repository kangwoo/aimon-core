package at.aimon.core.subagent.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory reference implementation of {@link TaskResultStore}, backed by an LRU-bounded map.
 *
 * <p>
 * This is the default, single-node implementation and the direct successor to the node-local
 * {@code CompletableFuture} holder background results used to live in. It keeps the same locality — a result saved on
 * this node is readable only on this node and does not survive a restart — but behind the SPI, so a scale-out
 * deployment swaps in {@link VfsTaskResultStore} without touching a caller.
 *
 * <p>
 * <b>Bounded.</b> The store is agent-scoped: it lives for the whole lifetime of an {@code AgentRuntime}, which survives
 * across sessions. An unbounded map would let a long-running agent that fans out many {@code Task} subagents accumulate
 * results until it exhausts the heap — precisely the leak the old holder had, since nothing ever called its
 * {@code removeTask}. The map is capped at {@link #DEFAULT_MAX_RESULTS}; once exceeded, the least-recently-used result
 * is evicted and the task reads back as one that produced no result. This mirrors
 * {@link InMemorySessionSnapshotStore}, which bounds the transcripts saved under the same task ids.
 *
 * <p>
 * Thread-safe: {@code save}/{@code load}/{@code evict} synchronize on the backing map, so a subagent thread saving a
 * terminal result may safely race with a parent agent polling for it.
 */
public final class InMemoryTaskResultStore implements TaskResultStore {

    /** Default maximum number of task results retained per store before the least-recently-used one is evicted. */
    public static final int DEFAULT_MAX_RESULTS = 256;

    private final Map<String, TaskResult> results;

    /** Creates a store bounded to {@link #DEFAULT_MAX_RESULTS} results. */
    public InMemoryTaskResultStore() {
        this(DEFAULT_MAX_RESULTS);
    }

    /**
     * Creates a store bounded to the given number of results.
     *
     * @param maxResults
     *            the maximum number of results to retain (must be {@code >= 1})
     * @throws IllegalArgumentException
     *             if {@code maxResults < 1}
     */
    public InMemoryTaskResultStore(int maxResults) {
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be >= 1, got: " + maxResults);
        }
        this.results = Collections.synchronizedMap(new BoundedLruMap<>(maxResults));
    }

    @Override
    public void save(String taskId, TaskResult result) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(result, "result cannot be null");
        results.put(taskId, result);
    }

    @Override
    public Optional<TaskResult> load(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        return Optional.ofNullable(results.get(taskId));
    }

    @Override
    public void evict(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        results.remove(taskId);
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
