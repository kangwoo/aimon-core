package at.aimon.core.agent;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Default in-memory, thread-safe {@link AgentEnvironmentSnapshotProvider} implementation.
 *
 * <p>
 * The actual collection of {@link AgentEnvironmentSnapshot} data is delegated to a caller-supplied
 * {@link Function Function&lt;AgentRuntime, AgentEnvironmentSnapshot&gt;}. This keeps the provider free of any
 * hardcoded
 * system lookups (e.g. {@code System.getProperty}) and lets tests inject deterministic or counting collectors.
 *
 * <p>
 * Memoization is backed by a {@link ConcurrentHashMap} keyed by {@link AgentRuntimeId}. The implementation
 * relies on {@link ConcurrentMap#computeIfAbsent(Object, Function) computeIfAbsent} so that concurrent {@code get}
 * calls for the same id atomically invoke the collector <b>at most once</b>.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * Function<AgentRuntime, AgentEnvironmentSnapshot> collector = ctx -> AgentEnvironmentSnapshot.builder()
 *         .workingDirectory(System.getProperty("user.dir")).currentDate(Instant.now())
 *         .environment(Environment.createDefault()).build();
 *
 * AgentEnvironmentSnapshotProvider provider = new DefaultAgentEnvironmentSnapshotProvider(collector);
 * AgentEnvironmentSnapshot snapshot = provider.get(agentRuntime);
 * }</pre>
 */
public final class DefaultAgentEnvironmentSnapshotProvider implements AgentEnvironmentSnapshotProvider {

    private final Function<AgentRuntime, AgentEnvironmentSnapshot> collector;
    private final ConcurrentMap<AgentRuntimeId, AgentEnvironmentSnapshot> cache = new ConcurrentHashMap<>();

    /**
     * Creates a new provider that uses the given collector to gather the snapshot on first lookup.
     *
     * <p>
     * The collector is invoked from inside {@link ConcurrentMap#computeIfAbsent(Object, Function) computeIfAbsent},
     * so it must be non-blocking and must not recursively invoke {@link #get(AgentRuntime)} or
     * {@link #invalidate(AgentRuntimeId)} on this provider for the same key — doing so risks deadlock or
     * an {@link IllegalStateException "Recursive update"} failure.
     *
     * @param collector
     *            function invoked at most once per {@link AgentRuntimeId} to produce its
     *            {@link AgentEnvironmentSnapshot} (must not be null; must not return null)
     * @throws NullPointerException
     *             if {@code collector} is null
     */
    public DefaultAgentEnvironmentSnapshotProvider(Function<AgentRuntime, AgentEnvironmentSnapshot> collector) {
        this.collector = Objects.requireNonNull(collector, "collector must not be null");
    }

    @Override
    public AgentEnvironmentSnapshot get(AgentRuntime context) {
        Objects.requireNonNull(context, "context must not be null");
        final AgentRuntimeId id = Objects.requireNonNull(context.getId(), "context.getId() must not return null");
        return cache.computeIfAbsent(id, key -> {
            final AgentEnvironmentSnapshot collected = collector.apply(context);
            if (collected == null) {
                throw new IllegalStateException("Collector returned null AgentEnvironmentSnapshot for id=" + key);
            }
            return collected;
        });
    }

    /**
     * Explicitly evicts the cached entry for the given id, if any.
     *
     * <p>
     * Most callers do not need to invoke this method — entries disappear when their owning provider is discarded. It
     * exists
     * for tests and for long-lived deployments that reuse ids and need a way to force a fresh snapshot.
     *
     * @param id
     *            the agent runtime id to invalidate (must not be null)
     * @throws NullPointerException
     *             if {@code id} is null
     */
    @Override
    public void invalidate(AgentRuntimeId id) {
        Objects.requireNonNull(id, "id must not be null");
        cache.remove(id);
    }

    /**
     * Returns the current number of cached entries.
     *
     * <p>
     * Intended for observability and tests; the count may change as concurrent {@code get}/{@code invalidate} calls
     * interleave.
     *
     * @return the number of memoized snapshots
     */
    public int size() {
        return cache.size();
    }
}
