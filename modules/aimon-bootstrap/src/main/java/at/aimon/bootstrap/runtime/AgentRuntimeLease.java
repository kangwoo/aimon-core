package at.aimon.bootstrap.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;

/**
 * A borrowed agent runtime, and the promise that it will not be closed while it is held.
 *
 * <p>
 * Eviction and use are the same problem seen from two ends. A tenant runtime has to be reclaimed or the process
 * accumulates one per customer forever; it must not be reclaimed while a turn is running through it, because
 * {@code AgentRuntime.close()} takes down the MCP clients and tool registry that turn is in the middle of using.
 * Idle time alone cannot tell those apart — a runtime whose last request was an hour ago looks identical to one
 * serving a request that has been waiting an hour on a slow tool.
 *
 * <p>
 * So the resolver counts holders instead of guessing, and this is the count. A lease is handed out by
 * {@link AgentRuntimeResolver#acquire(AgentRuntimeId)} and returned by {@link #close()}; only a runtime with no
 * leases can be evicted. Use it in try-with-resources and give it the exact scope of the work:
 *
 * <pre>
 * {@code
 * try (AgentRuntimeLease lease = resolver.acquire(id)) {
 *     runTurn(lease.runtime());
 * }
 * }
 * </pre>
 *
 * <p>
 * A leaked lease is not a crash — it is a runtime that is never evicted, which the resolver's entry count
 * eventually shows and {@code max-entries} eventually enforces.
 *
 * <p>
 * {@link #close()} is idempotent and never throws. Releasing does not close the runtime: the resolver decides
 * that later, from the idle clock this release starts.
 */
public final class AgentRuntimeLease implements AutoCloseable {

    private final AgentRuntimeId agentRuntimeId;
    private final AgentRuntime runtime;
    private final Runnable release;
    private final AtomicBoolean released = new AtomicBoolean(false);

    private AgentRuntimeLease(AgentRuntimeId agentRuntimeId, AgentRuntime runtime, Runnable release) {
        this.agentRuntimeId = Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.release = Objects.requireNonNull(release, "release must not be null");
    }

    /**
     * Creates a lease that decrements a holder count when closed.
     *
     * @param agentRuntimeId
     *            the id being held (must not be null)
     * @param runtime
     *            the runtime (must not be null)
     * @param release
     *            what to run exactly once on close (must not be null)
     * @return a counted lease
     */
    static AgentRuntimeLease counted(AgentRuntimeId agentRuntimeId, AgentRuntime runtime, Runnable release) {
        return new AgentRuntimeLease(agentRuntimeId, runtime, release);
    }

    /**
     * Creates a lease over a runtime that is never evicted, so releasing it does nothing.
     *
     * <p>
     * This is what an id enumerated at startup gets. Callers cannot tell the two apart, which is the point: code
     * that runs a turn should not have to know whether it is talking to a configured agent or a tenant, and
     * having it branch is how one of the two branches stops releasing.
     *
     * @param agentRuntimeId
     *            the id being held (must not be null)
     * @param runtime
     *            the runtime (must not be null)
     * @return a lease whose close is a no-op
     */
    static AgentRuntimeLease pinned(AgentRuntimeId agentRuntimeId, AgentRuntime runtime) {
        return new AgentRuntimeLease(agentRuntimeId, runtime, () -> {
        });
    }

    /**
     * Returns the id this lease is held against.
     *
     * @return the runtime id, never null
     */
    public AgentRuntimeId id() {
        return agentRuntimeId;
    }

    /**
     * Returns the borrowed runtime.
     *
     * <p>
     * Borrowed, not owned: do not close it. The resolver closes it when the last lease is released and the idle
     * TTL passes, and closing it here would leave the resolver handing the same closed object to the next
     * caller.
     *
     * @return the runtime, never null
     */
    public AgentRuntime runtime() {
        return runtime;
    }

    /**
     * Returns whether this lease has already been released.
     *
     * @return {@code true} once {@link #close()} has run
     */
    public boolean isReleased() {
        return released.get();
    }

    /**
     * Releases the lease. Idempotent, and never throws.
     */
    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            release.run();
        }
    }

    @Override
    public String toString() {
        return "AgentRuntimeLease[" + agentRuntimeId + ", released=" + released.get() + "]";
    }
}
