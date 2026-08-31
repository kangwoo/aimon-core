package at.aimon.core.workflow.impl;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.workflow.StepKey;
import at.aimon.core.workflow.StepOutcome;
import at.aimon.core.workflow.StepResultCache;

/**
 * A {@link StepResultCache} decorator that confines {@link #load(StepKey) loads} to a single agent runtime
 * (design §5.3), mirroring {@code ScopedSessionSnapshotStore} on the resume plane.
 *
 * <p>
 * The owning {@link AgentRuntimeId} is already part of a {@link StepKey}, so a shared backend isolates agents
 * structurally — a foreign context yields a different key. This decorator adds defense in depth: it refuses to even
 * query the delegate for a key whose context differs from (or is absent versus) the bound context, so one agent cannot
 * probe another's outcomes on a shared/persistent backend by crafting a foreign key. {@code save} and {@code evict}
 * delegate unchanged — the run tags each outcome with its own context at save time; {@code load} is the cross-agent
 * read path that needs confining.
 */
public final class ScopedStepResultCache implements StepResultCache {

    private final StepResultCache delegate;
    private final AgentRuntimeId agentRuntimeId;

    /**
     * Creates a cache whose loads are confined to a single agent runtime.
     *
     * @param delegate
     *            the underlying cache every operation is delegated to (must not be null)
     * @param agentRuntimeId
     *            the agent runtime loads are confined to (must not be null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public ScopedStepResultCache(StepResultCache delegate, AgentRuntimeId agentRuntimeId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.agentRuntimeId = Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
    }

    /**
     * Wraps {@code delegate} so its loads are confined to {@code agentRuntimeId} when present, or returns
     * {@code delegate}
     * unchanged when it is empty (preserving unscoped behaviour for embeddings / tests that carry no execution
     * context).
     *
     * @param delegate
     *            the underlying cache (must not be null)
     * @param agentRuntimeId
     *            the context to confine to, or empty to pass through unscoped (the {@link Optional} must not be null)
     * @return a scoped cache, or {@code delegate} when {@code agentRuntimeId} is empty
     */
    public static StepResultCache scopeOrPassThrough(StepResultCache delegate,
            Optional<AgentRuntimeId> agentRuntimeId) {
        Objects.requireNonNull(delegate, "delegate cannot be null");
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId optional cannot be null");
        return agentRuntimeId.<StepResultCache>map(id -> new ScopedStepResultCache(delegate, id)).orElse(delegate);
    }

    @Override
    public Optional<StepOutcome> load(StepKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        // Authorize the read: only touch the delegate when the key belongs to this context. A foreign-context or
        // untagged (unverifiable) key is hidden as a miss, so a caller can never read another agent's outcome.
        if (!agentRuntimeId.equals(key.agentRuntimeId().orElse(null))) {
            return Optional.empty();
        }
        return delegate.load(key);
    }

    @Override
    public void save(StepKey key, StepOutcome outcome) {
        delegate.save(key, outcome);
    }

    @Override
    public void evict(StepKey key) {
        delegate.evict(key);
    }
}
