package at.aimon.core.agent;

/**
 * Memoized lookup that returns the {@link AgentEnvironmentSnapshot} for a given {@link AgentRuntime}.
 *
 * <p>
 * Implementations MAY compute the snapshot lazily but <b>must</b> return the same instance (reference equality) for
 * the same {@link AgentRuntimeId}. Since the agent runtime is agent-scoped, the snapshot is shared by
 * every session and every {@code LiveSession} running against that agent — callers can treat it as a stable,
 * collect-once
 * fact across every turn of the ReAct loop.
 *
 * <p>
 * Implementations should be thread-safe: multiple threads may invoke {@link #get(AgentRuntime)} concurrently
 * for the same id, and the collection must happen at most once per id.
 *
 * @see AgentEnvironmentSnapshot
 * @see DefaultAgentEnvironmentSnapshotProvider
 */
public interface AgentEnvironmentSnapshotProvider {

    /**
     * Returns the memoized snapshot for the given agent runtime.
     *
     * <p>
     * If no entry has been cached yet for the agent runtime's id, the implementation collects one and caches it.
     * Subsequent calls for the same id must return the same instance.
     *
     * <p>
     * Implementations that memoize under {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent
     * computeIfAbsent} must keep the collection function non-blocking and self-contained — invoking another
     * memoizing call for the same key from inside the collector risks deadlock or
     * {@link IllegalStateException "Recursive update"} failures.
     *
     * @param context
     *            the agent runtime (must not be null)
     * @return the memoized {@link AgentEnvironmentSnapshot} for this execution (never null)
     */
    AgentEnvironmentSnapshot get(AgentRuntime context);

    /**
     * Explicitly evicts the cached entry for the given id, if any.
     *
     * <p>
     * Most callers do not need to invoke this method — entries disappear when their owning provider is discarded. It
     * exists for tests and for long-lived deployments that reuse ids and need a way to force a fresh snapshot.
     *
     * @param id
     *            the agent runtime id to invalidate (must not be null)
     * @throws NullPointerException
     *             if {@code id} is null
     */
    void invalidate(AgentRuntimeId id);
}
