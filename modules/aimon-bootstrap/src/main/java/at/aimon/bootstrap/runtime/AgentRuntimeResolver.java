package at.aimon.bootstrap.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.exception.AgentRuntimeExhaustedException;
import at.aimon.bootstrap.exception.AimonBootstrapException;
import at.aimon.bootstrap.exception.UnknownAgentRuntimeException;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeRegistry;

/**
 * Hands out agent runtimes by id, creating tenant ones on first use and reclaiming them when they go idle.
 *
 * <h2>Why the two halves of an id are answered differently</h2>
 *
 * <p>
 * An {@code AgentRuntimeId} has two axes and they have opposite properties. <b>Agents</b> are enumerated by
 * configuration: there are five of them, they are known before the first request, and standing them up at startup
 * is what makes a missing MCP server or an unresolvable tool a boot failure rather than a user-visible one three
 * hours later. <b>Tenants</b> cannot be enumerated at all — the set grows while the process runs — so there is
 * nothing to stand up in advance and the only possible moment is first use.
 *
 * <p>
 * That is the whole design. Ids the stack already registered are returned as-is, on a lease whose release does
 * nothing, because the stack owns them and closes them in its own teardown. Ids with a discriminator that nobody
 * has asked for yet are provisioned here, registered, counted, and eventually closed here. An id with <b>no</b>
 * discriminator that is not registered is refused ({@link UnknownAgentRuntimeException}): creating it would be
 * indistinguishable from a typo succeeding, and would give away the fail-fast guarantee that made agents eager in
 * the first place.
 *
 * <p>
 * One id is neither of those two cases: an id the stack <b>declared</b> and has not registered yet. It carries a
 * discriminator when the spec gave the agent one, so it is shaped exactly like a tenant id, but it is not one —
 * the stack has already built a runtime for it and will register that runtime when the host starts it. Building a
 * second here would put two live runtimes behind one id, and the stack's registration would then replace this
 * one without closing it. So a declared id is refused too, with an {@link IllegalStateException} that says the
 * stack has not started rather than that the agent is unknown. See {@link Builder#declaredIds(Set)}.
 *
 * <h2>Why a lease and not a lookup</h2>
 *
 * <p>
 * Reclaiming tenant runtimes is not optional — one file system, tool registry and MCP connection per customer,
 * held until restart, is a leak with a growth rate. But {@code AgentRuntime.close()} must not run while a turn is
 * using the runtime, and elapsed time cannot tell "last used an hour ago" from "has been running for an hour":
 * both look idle to a clock. So callers hold an {@link AgentRuntimeLease} for the duration of their work and only
 * a runtime with zero holders <i>and</i> an expired idle TTL is closed. Time alone never evicts anything.
 *
 * <p>
 * When {@code max-entries} is reached and nothing is idle, creation is refused with
 * {@link AgentRuntimeExhaustedException} rather than allowed to exceed the cap. The alternative fails every
 * tenant instead of the one that arrived last.
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * All methods are safe to call concurrently. Two requests for the same absent tenant produce <b>one</b> provision
 * call — the second waits for the first rather than building a second runtime, which would leave two runtimes
 * with the same id, only one of them registered, and turns running against both.
 */
public final class AgentRuntimeResolver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeResolver.class);

    /** Cap applied when the caller does not set one. */
    public static final int DEFAULT_MAX_ENTRIES = 500;

    /** Idle TTL applied when the caller does not set one. */
    public static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(30);

    private final AgentRuntimeRegistry registry;
    private final AgentRuntimeProvisioner provisioner;
    private final Set<AgentRuntimeId> declaredIds;
    private final AgentRuntimeEviction eviction;
    private final Duration idleTtl;
    private final Duration sweepInterval;
    private final int maxEntries;
    private final Clock clock;

    private final ReentrantLock lock = new ReentrantLock();
    /** Tenant runtimes this resolver created. Guarded by {@link #lock}. Eager ids are never in here. */
    private final Map<AgentRuntimeId, Entry> entries = new LinkedHashMap<>();
    private final AtomicLong exhaustions = new AtomicLong();
    private final AtomicLong provisionFailures = new AtomicLong();

    private ScheduledExecutorService sweeper;
    private ScheduledFuture<?> sweeperTask;
    private volatile boolean closed;

    private AgentRuntimeResolver(Builder builder) {
        this.registry = Objects.requireNonNull(builder.registry, "registry must not be null");
        this.provisioner = Objects.requireNonNull(builder.provisioner, "provisioner must not be null");
        this.declaredIds = Set.copyOf(builder.declaredIds);
        this.eviction = builder.eviction;
        this.idleTtl = builder.idleTtl;
        this.sweepInterval = builder.sweepInterval != null ? builder.sweepInterval : builder.idleTtl;
        this.maxEntries = builder.maxEntries;
        this.clock = builder.clock != null ? builder.clock : Clock.systemUTC();
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1, got: " + maxEntries);
        }
        if (idleTtl.isNegative()) {
            throw new IllegalArgumentException("idleTtl must not be negative, got: " + idleTtl);
        }
    }

    /**
     * Creates a builder.
     *
     * @param registry
     *            the registry lazily created runtimes are registered into and removed from — the same instance
     *            the scheduling engine and session opener read (must not be null)
     * @param provisioner
     *            builds a runtime for an id (must not be null)
     * @return a new builder
     */
    public static Builder builder(AgentRuntimeRegistry registry, AgentRuntimeProvisioner provisioner) {
        return new Builder(registry, provisioner);
    }

    /**
     * Returns a runtime for {@code agentRuntimeId}, creating it if it is a tenant nobody has used yet.
     *
     * <p>
     * The returned lease must be closed when the work is done — see {@link AgentRuntimeLease}. Until it is, the
     * runtime cannot be evicted.
     *
     * @param agentRuntimeId
     *            the id to resolve (must not be null)
     * @return a held lease, never null
     * @throws UnknownAgentRuntimeException
     *             if the id has no discriminator and no such agent was registered at startup
     * @throws AgentRuntimeExhaustedException
     *             if the tenant cap is reached and every existing tenant runtime is in use
     * @throws IllegalStateException
     *             if this resolver has been closed
     */
    public AgentRuntimeLease acquire(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
        while (true) {
            final AgentRuntimeLease existing = acquireExisting(agentRuntimeId);
            if (existing != null) {
                return existing;
            }
            final Entry mine = admit(agentRuntimeId);
            if (mine == null) {
                // Someone else won the slot between the two steps; go back and take a lease on theirs.
                continue;
            }
            final AgentRuntimeLease created = provision(mine);
            if (created != null) {
                return created;
            }
        }
    }

    /**
     * Takes a lease on something that already exists, or returns null when the id needs provisioning.
     */
    private AgentRuntimeLease acquireExisting(AgentRuntimeId agentRuntimeId) {
        final Entry entry;
        lock.lock();
        try {
            requireOpen();
            entry = entries.get(agentRuntimeId);
            if (entry == null) {
                // Registered but untracked means the stack stood it up at startup: pinned, never evicted, closed
                // by the stack's own teardown. Callers cannot tell this from a tenant lease, deliberately.
                final Optional<AgentRuntime> eager = registry.get(agentRuntimeId);
                if (eager.isPresent()) {
                    return AgentRuntimeLease.pinned(agentRuntimeId, eager.get());
                }
                return null;
            }
        } finally {
            lock.unlock();
        }

        // Outside the lock: this blocks when another thread is still provisioning the entry.
        final AgentRuntimeLease lease = entry.lease(this::onRelease);
        if (lease != null) {
            return lease;
        }
        // Retired while we waited. Drop it if it is still the mapped one, then let the caller retry.
        lock.lock();
        try {
            entries.remove(agentRuntimeId, entry);
        } finally {
            lock.unlock();
        }
        return null;
    }

    /**
     * Reserves the map slot for a new tenant runtime, or returns null when another thread got there first.
     */
    private Entry admit(AgentRuntimeId agentRuntimeId) {
        if (declaredIds.contains(agentRuntimeId)) {
            // Only reachable before the stack registered its runtimes (or after something unregistered one):
            // acquireExisting hands back a pinned lease for anything the registry holds, so a declared id never
            // gets this far on a started stack. Provisioning it would build a second runtime under an id the
            // stack already has one for, and the stack's own registration would then replace this one in the
            // registry without closing it — leaving a live runtime that cron re-fires and session bootstraps can
            // no longer reach while this resolver still leases and eventually closes it. Checked before the
            // discriminator rule below so the message names the real problem: the agent is configured, the stack
            // is simply not serving yet.
            throw new IllegalStateException("Cannot create " + agentRuntimeId + " on demand: it is one of the"
                    + " agents this stack declared, and the stack has not registered its runtimes yet. Call"
                    + " AimonStack.startRuntimes() (the Spring Boot starter does this as the web server starts)"
                    + " before resolving a declared agent.");
        }
        if (agentRuntimeId.discriminator().isEmpty()) {
            throw new UnknownAgentRuntimeException("No agent runtime is registered for " + agentRuntimeId
                    + ", and an id without a discriminator is never created on demand. Agents are enumerated in"
                    + " configuration and stood up at startup so that a missing one fails there; creating this"
                    + " would make a misspelled agent name look like a working deployment.");
        }
        // Before the capacity check, not after: an idle runtime occupying a slot is exactly what the cap is
        // meant to reclaim, and sweeping here is what turns "500 tenants seen today" into "500 at once".
        sweep();
        lock.lock();
        try {
            requireOpen();
            if (entries.containsKey(agentRuntimeId)) {
                return null;
            }
            if (entries.size() >= maxEntries) {
                exhaustions.incrementAndGet();
                // The held count is in the message because this throw is the only thing emitted at the instant of
                // a refusal, and without it the sentence reads identically in the two situations that ask for
                // opposite remedies. leasedCount() carries the same number, but it is a gauge: by the time
                // anything scrapes it, the entries a shorter TTL would have reclaimed have usually aged out on
                // their own and the evidence for that reading is gone.
                throw new AgentRuntimeExhaustedException("Cannot create " + agentRuntimeId + ": " + entries.size()
                        + " tenant runtime(s) are live, the cap is " + maxEntries + ", and none of them has been"
                        + " idle long enough to reclaim. " + countLeasedLocked() + " of the " + entries.size()
                        + " are held by a caller right now — when that is all of them this node is genuinely full"
                        + " and the cap is what to raise; when it is far fewer, the rest are alive on the idle TTL"
                        + " alone and a shorter TTL would have served this call. Either way the same call succeeds"
                        + " again as soon as a holder releases or an idle runtime ages out.");
            }
            final Entry entry = new Entry(agentRuntimeId);
            entries.put(agentRuntimeId, entry);
            return entry;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Builds the runtime for a slot this thread reserved, and returns a lease on it — or null when the entry was
     * invalidated while it was being built and the caller should start over.
     */
    private AgentRuntimeLease provision(Entry entry) {
        final ProvisionedAgentRuntime provisioned;
        try {
            provisioned = Objects.requireNonNull(provisioner.provision(entry.id),
                    "The agent runtime provisioner returned null for " + entry.id);
        } catch (RuntimeException e) {
            // Counted, not just rethrown: the caller sees this failure but nobody else does, and a tenant whose
            // runtime never builds looks identical to a tenant nobody asked for. The slot is dropped below, so
            // without a counter the only trace left of a systematically failing provisioner is the caller's stack.
            //
            // An unknown agent name is excluded. It reaches here because the name is only resolved during
            // provisioning, but it is a caller's typo — the equivalent of a 404 — and counting it would let a
            // client with a bad request drive the meter an operator pages on.
            if (!(e instanceof UnknownAgentRuntimeException)) {
                provisionFailures.incrementAndGet();
            }
            // Drop the slot before waking waiters, so the next request retries instead of finding a dead entry.
            // The failure is not cached beyond the callers already blocked on it.
            lock.lock();
            try {
                entries.remove(entry.id, entry);
            } finally {
                lock.unlock();
            }
            entry.fail(e);
            throw e;
        }

        final boolean current;
        lock.lock();
        try {
            current = !closed && entries.get(entry.id) == entry;
            if (current) {
                registry.register(provisioned.getRuntime());
            }
        } finally {
            lock.unlock();
        }

        if (!current) {
            // Invalidated (or the resolver closed) while we were building. Nothing may use this runtime — it was
            // never registered — so it is closed here rather than handed out.
            log.debug("Discarding runtime {} provisioned into a slot that no longer exists", entry.id);
            entry.abandon();
            provisioned.close();
            return null;
        }
        log.info("Provisioned tenant agent runtime {} ({} tenant runtime(s) live)", entry.id, trackedCount());
        return entry.settleAndLease(provisioned, this::onRelease);
    }

    private void onRelease(Entry entry) {
        final ProvisionedAgentRuntime doomed = entry.release(clock.instant());
        if (doomed != null) {
            // Invalidated while it was held: the last holder out closes the door.
            log.debug("Closing invalidated runtime {} released by its last holder", entry.id);
            doomed.close();
        }
    }

    /**
     * Drops a tenant runtime so the next request builds a fresh one.
     *
     * <p>
     * The two steps are deliberately split. The id is unregistered <b>immediately</b>, so nothing new can reach
     * the stale runtime; the runtime itself is closed only when the last current holder releases, because closing
     * it under a running turn takes down the MCP clients and tool registry that turn is using. For an idle
     * runtime both happen at once.
     *
     * <p>
     * Only affects runtimes this resolver created. Agents enumerated at startup are the stack's, and replacing
     * one is a restart, not a call — silently unregistering it here would leave the scheduling engine unable to
     * resolve its cron tasks with nothing to explain why.
     *
     * @param agentRuntimeId
     *            the id to invalidate (must not be null)
     * @return {@code true} if a tenant runtime was invalidated, {@code false} if this resolver did not have one
     */
    public boolean invalidate(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
        final Entry entry;
        lock.lock();
        try {
            entry = entries.remove(agentRuntimeId);
            if (entry != null) {
                registry.unregister(agentRuntimeId);
            }
        } finally {
            lock.unlock();
        }
        if (entry == null) {
            return false;
        }
        final ProvisionedAgentRuntime doomed = entry.retire();
        if (doomed != null) {
            doomed.close();
        } else {
            log.debug("Invalidated {} is still held; it will close when its last holder releases", agentRuntimeId);
        }
        return true;
    }

    /**
     * Closes every tenant runtime that has had no holders for longer than the idle TTL.
     *
     * <p>
     * Called automatically before each new tenant is created and, when a sweep interval is configured, on a
     * background thread — the latter matters because a deployment that stops receiving new tenants would
     * otherwise hold its last ones forever, which is the case the TTL exists for.
     *
     * @return how many runtimes were closed
     */
    public int sweep() {
        if (eviction == AgentRuntimeEviction.NEVER) {
            return 0;
        }
        final Instant deadline = clock.instant().minus(idleTtl);
        final List<ProvisionedAgentRuntime> doomed = new ArrayList<>();
        lock.lock();
        try {
            final Iterator<Map.Entry<AgentRuntimeId, Entry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                final Entry entry = it.next().getValue();
                final ProvisionedAgentRuntime evicted = entry.retireIfIdleSince(deadline);
                if (evicted != null) {
                    it.remove();
                    registry.unregister(entry.id);
                    doomed.add(evicted);
                }
            }
        } finally {
            lock.unlock();
        }
        // Outside the lock: closing a runtime shuts down MCP transports, and holding the lock through that would
        // stall every other tenant's request behind an unrelated eviction.
        doomed.forEach(ProvisionedAgentRuntime::close);
        if (!doomed.isEmpty()) {
            log.info("Evicted {} idle tenant agent runtime(s); {} remain", doomed.size(), trackedCount());
        }
        return doomed.size();
    }

    /**
     * Starts the background sweep, if this resolver evicts at all. No-op when already started or when eviction is
     * {@link AgentRuntimeEviction#NEVER}.
     */
    public void start() {
        if (eviction == AgentRuntimeEviction.NEVER) {
            return;
        }
        lock.lock();
        try {
            if (sweeperTask != null || closed) {
                return;
            }
            sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread thread = new Thread(r, "agent-runtime-sweeper");
                thread.setDaemon(true);
                return thread;
            });
            final long delayMs = Math.max(1L, sweepInterval.toMillis());
            sweeperTask = sweeper.scheduleWithFixedDelay(this::sweepQuietly, delayMs, delayMs, TimeUnit.MILLISECONDS);
            log.debug("Agent runtime sweeper started: interval={}, idleTtl={}", sweepInterval, idleTtl);
        } finally {
            lock.unlock();
        }
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            // A throw out of a scheduleWithFixedDelay task cancels the schedule silently, so nothing would ever
            // be evicted again and the only symptom would be memory.
            log.error("Agent runtime sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns how many tenant runtimes this resolver currently holds.
     *
     * @return the count, excluding the agents the stack stood up at startup
     */
    public int trackedCount() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns how many of those runtimes a caller is holding right now.
     *
     * <p>
     * The subset of {@link #trackedCount()} that is actually in use; the difference between the two is the number
     * of runtimes kept alive by nothing but the idle TTL. That difference is what a refusal turns on, and it is
     * why this is reported separately: at the cap with every entry leased, the node is genuinely full and the
     * remedy is a larger cap; at the cap with three leased and the rest merely inside a thirty-minute TTL, the
     * same refusal says the TTL is too long. The two readings ask for opposite changes, and neither
     * {@link #trackedCount()} nor {@link #isSaturated()} distinguishes them.
     *
     * <p>
     * "In use" is the lease's meaning of the phrase, not "running a turn": the usual holder is a cached live
     * session, which keeps its lease between turns because it can start one at any moment. So a reading pinned
     * near the cap says the slots are spoken for, not that they are busy, and the knob that frees them may be the
     * live session cache's own idle TTL rather than this resolver's — the two timers run in series.
     *
     * <p>
     * An entry still being provisioned counts as zero — it becomes one the instant it settles, in the same step
     * that hands its creator a lease.
     *
     * <p>
     * Point-in-time, like everything else here.
     *
     * @return the count of held runtimes, never greater than {@link #trackedCount()}
     */
    public int leasedCount() {
        lock.lock();
        try {
            return countLeasedLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Counts held entries. Callers must hold {@link #lock} — the order resolver lock then {@code Entry} monitor is
     * the one {@link #sweep()} takes, and taking it the other way round here would be the only place that could
     * deadlock against a release.
     */
    private int countLeasedLocked() {
        return (int) entries.values().stream().filter(Entry::isLeased).count();
    }

    /**
     * Returns the ids of the tenant runtimes this resolver currently holds, oldest first.
     *
     * @return an immutable snapshot
     */
    public Set<AgentRuntimeId> trackedIds() {
        lock.lock();
        try {
            return Collections.unmodifiableSet(new LinkedHashSet<>(entries.keySet()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns how many times a request was refused because the cap was reached with nothing idle.
     *
     * <p>
     * Non-zero means requests failed that a larger cap or a shorter TTL would have served. It is cumulative and
     * never decreases, so it is a <b>metric, not a health check</b> — a stack that was briefly saturated an hour
     * ago is serving fine now. The stack reports it as detail alongside its current saturation.
     *
     * @return the count since startup
     */
    public long exhaustionCount() {
        return exhaustions.get();
    }

    /**
     * Returns how many times building a tenant runtime threw.
     *
     * <p>
     * Counted separately from {@link #exhaustionCount()} because the remedies have nothing in common: exhaustion
     * is a cap to raise, a provision failure is a broken provisioner or a tenant whose configuration cannot be
     * satisfied. Like exhaustions this is cumulative, so it belongs on a meter rather than in a pass/fail check.
     *
     * <p>
     * {@link UnknownAgentRuntimeException} is not counted — an agent name nobody configured is a caller's typo,
     * and a metric that a bad request can drive is not one an operator can alert on.
     *
     * @return the count since startup
     */
    public long provisionFailureCount() {
        return provisionFailures.get();
    }

    /**
     * Returns the cap on concurrently held tenant runtimes.
     *
     * @return the configured maximum
     */
    public int maxEntries() {
        return maxEntries;
    }

    /**
     * Answers whether a request for a tenant this resolver does not already hold would be refused right now.
     *
     * <p>
     * This is the exact condition {@link #acquire} throws {@link AgentRuntimeExhaustedException} on — full
     * <i>and</i> nothing reclaimable — evaluated without retiring anything. Being at the cap is not enough on its
     * own: admission sweeps first, so a resolver sitting at 500 of 500 idle entries serves the next tenant
     * immediately. That distinction is the whole point of asking, because "at the cap" is the steady state of any
     * busy node with a long TTL, and a health check that failed on it would report a healthy node as down all day.
     *
     * <p>
     * Point-in-time, like everything else here: a holder can release the instant after this returns.
     *
     * @return {@code true} when the next new tenant would be refused
     */
    public boolean isSaturated() {
        final Instant deadline = clock.instant().minus(idleTtl);
        lock.lock();
        try {
            if (entries.size() < maxEntries) {
                return false;
            }
            if (eviction == AgentRuntimeEviction.NEVER) {
                return true;
            }
            // Same lock order as sweep(), which also holds this lock across an Entry monitor.
            return entries.values().stream().noneMatch(entry -> entry.isReclaimableAt(deadline));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes every tenant runtime this resolver created, held or not, and stops the background sweep.
     *
     * <p>
     * Held leases are not waited for. By the time this runs the stack has already drained its sessions (see
     * {@code TeardownPhase.SESSIONS}, which precedes the runtime phases), so a remaining holder is one that
     * outlived the sessions — waiting on it would hang shutdown rather than save anything.
     *
     * <p>
     * Idempotent.
     */
    @Override
    public void close() {
        final List<Entry> doomed;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            if (sweeperTask != null) {
                sweeperTask.cancel(false);
                sweeperTask = null;
            }
            if (sweeper != null) {
                sweeper.shutdownNow();
                sweeper = null;
            }
            doomed = new ArrayList<>(entries.values());
            entries.clear();
            doomed.forEach(entry -> registry.unregister(entry.id));
        } finally {
            lock.unlock();
        }
        for (Entry entry : doomed) {
            final ProvisionedAgentRuntime provisioned = entry.retireForShutdown();
            if (provisioned != null) {
                provisioned.close();
            }
        }
        log.debug("Agent runtime resolver closed after releasing {} tenant runtime(s)", doomed.size());
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The agent runtime resolver has been closed; the stack is shutting down"
                    + " and any runtime handed out now would be closed before it could be used.");
        }
    }

    @Override
    public String toString() {
        return "AgentRuntimeResolver[tracked=" + trackedCount() + "/" + maxEntries + ", eviction=" + eviction
                + ", idleTtl=" + idleTtl + ", closed=" + closed + "]";
    }

    /**
     * One tenant runtime and the state that decides whether it may be closed.
     *
     * <p>
     * Its own monitor guards everything here, and the resolver's lock is never taken while it is held — the
     * ordering is always resolver lock first — so a release cannot deadlock against a sweep.
     */
    private static final class Entry {

        private final AgentRuntimeId id;

        private ProvisionedAgentRuntime provisioned;
        private boolean settled;
        private boolean retired;
        private RuntimeException failure;
        private int holders;
        private Instant idleSince;

        Entry(AgentRuntimeId id) {
            this.id = id;
        }

        /**
         * Publishes the provisioned runtime and takes the creating thread's holder slot in one step.
         *
         * <p>
         * One step because two would leave the runtime momentarily settled with zero holders, which is precisely
         * the state a concurrent sweep evicts — the thread that just built it would find it already closed.
         */
        synchronized AgentRuntimeLease settleAndLease(ProvisionedAgentRuntime value, Consumer<Entry> release) {
            this.provisioned = value;
            this.settled = true;
            this.holders = 1;
            this.idleSince = null;
            notifyAll();
            return AgentRuntimeLease.counted(id, value.getRuntime(), () -> release.accept(this));
        }

        synchronized void fail(RuntimeException cause) {
            this.failure = cause;
            this.settled = true;
            this.retired = true;
            notifyAll();
        }

        synchronized void abandon() {
            this.retired = true;
            this.settled = true;
            notifyAll();
        }

        /**
         * Waits for provisioning to finish and takes a holder slot, or returns null if the entry was retired
         * meanwhile and the caller should start over.
         */
        synchronized AgentRuntimeLease lease(Consumer<Entry> release) {
            while (!settled && !retired) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AimonBootstrapException("Interrupted while waiting for " + id + " to be provisioned", e);
                }
            }
            if (failure != null) {
                throw new AimonBootstrapException("Another caller's attempt to provision " + id + " failed: "
                        + failure.getMessage() + ". Retry to attempt it again.", failure);
            }
            if (retired || provisioned == null) {
                return null;
            }
            holders++;
            idleSince = null;
            return AgentRuntimeLease.counted(id, provisioned.getRuntime(), () -> release.accept(this));
        }

        /**
         * Gives back a holder slot, and returns the runtime to close when this was the last holder of an entry
         * that had already been invalidated.
         */
        synchronized ProvisionedAgentRuntime release(Instant now) {
            holders--;
            idleSince = now;
            notifyAll();
            return retired && holders == 0 ? take() : null;
        }

        /** Answers whether a caller is holding this entry right now. */
        synchronized boolean isLeased() {
            return holders > 0;
        }

        /** Answers what {@link #retireIfIdleSince} would answer, without retiring anything. */
        synchronized boolean isReclaimableAt(Instant deadline) {
            return !retired && settled && holders == 0 && idleSince != null && !idleSince.isAfter(deadline);
        }

        /** Retires an idle entry, or returns null when it is in use, unfinished, or not idle long enough. */
        synchronized ProvisionedAgentRuntime retireIfIdleSince(Instant deadline) {
            if (retired || !settled || holders > 0 || idleSince == null || idleSince.isAfter(deadline)) {
                return null;
            }
            retired = true;
            notifyAll();
            return take();
        }

        /** Retires the entry now; returns null when holders remain, in which case the last release closes it. */
        synchronized ProvisionedAgentRuntime retire() {
            retired = true;
            notifyAll();
            return holders == 0 ? take() : null;
        }

        /** Retires the entry and surrenders the runtime regardless of holders. Shutdown only. */
        synchronized ProvisionedAgentRuntime retireForShutdown() {
            retired = true;
            notifyAll();
            return take();
        }

        private ProvisionedAgentRuntime take() {
            final ProvisionedAgentRuntime value = provisioned;
            provisioned = null;
            return value;
        }
    }

    /** Builder for {@link AgentRuntimeResolver}. */
    public static final class Builder {

        private final AgentRuntimeRegistry registry;
        private final AgentRuntimeProvisioner provisioner;
        private Set<AgentRuntimeId> declaredIds = Set.of();
        private AgentRuntimeEviction eviction = AgentRuntimeEviction.IDLE;
        private Duration idleTtl = DEFAULT_IDLE_TTL;
        private Duration sweepInterval;
        private int maxEntries = DEFAULT_MAX_ENTRIES;
        private Clock clock;

        private Builder(AgentRuntimeRegistry registry, AgentRuntimeProvisioner provisioner) {
            this.registry = registry;
            this.provisioner = provisioner;
        }

        /**
         * Names the ids the stack builds eagerly, so this resolver never creates a second runtime for one.
         *
         * <p>
         * Needed because a declared id is not distinguishable from a tenant id by shape — an agent given a
         * discriminator in the spec produces {@code agent:<name>:<discriminator>}, the same form a tenant uses —
         * and the registry, which is the only other thing that could tell them apart, is empty until the host
         * calls {@code AimonStack.startRuntimes()}. In that window a request for a declared id would otherwise be
         * provisioned here, and the stack's registration would then replace it silently.
         *
         * <p>
         * Defaults to empty, which suits a resolver that has no eager runtimes behind it. Ids passed here are
         * never counted against {@link #maxEntries(int)} and never evicted — this resolver does not own them.
         *
         * @param declaredIds
         *            the eagerly built ids (must not be null; copied)
         * @return this builder
         */
        public Builder declaredIds(Set<AgentRuntimeId> declaredIds) {
            this.declaredIds = Set.copyOf(Objects.requireNonNull(declaredIds, "declaredIds must not be null"));
            return this;
        }

        /**
         * Sets whether idle tenant runtimes are reclaimed. Defaults to {@link AgentRuntimeEviction#IDLE}.
         *
         * @param eviction
         *            the policy (must not be null)
         * @return this builder
         */
        public Builder eviction(AgentRuntimeEviction eviction) {
            this.eviction = Objects.requireNonNull(eviction, "eviction must not be null");
            return this;
        }

        /**
         * Sets how long a tenant runtime may sit with no holders before it is closed. Defaults to
         * {@link #DEFAULT_IDLE_TTL}.
         *
         * <p>
         * The clock starts at the release of the last lease, so this is time spent genuinely unused, not time
         * since creation.
         *
         * @param idleTtl
         *            the TTL (must not be null or negative)
         * @return this builder
         */
        public Builder idleTtl(Duration idleTtl) {
            this.idleTtl = Objects.requireNonNull(idleTtl, "idleTtl must not be null");
            return this;
        }

        /**
         * Sets how often the background sweep runs. Defaults to the idle TTL, which closes a runtime within
         * twice the TTL of its last use.
         *
         * @param sweepInterval
         *            the interval (must not be null)
         * @return this builder
         */
        public Builder sweepInterval(Duration sweepInterval) {
            this.sweepInterval = Objects.requireNonNull(sweepInterval, "sweepInterval must not be null");
            return this;
        }

        /**
         * Sets the cap on concurrently held tenant runtimes. Defaults to {@link #DEFAULT_MAX_ENTRIES}.
         *
         * <p>
         * Counts tenant runtimes only. The agents enumerated at startup are not subject to it — they are a fixed
         * set the deployment already sized for, and refusing one of them would make the cap able to break a
         * configured agent.
         *
         * @param maxEntries
         *            the cap (must be at least 1)
         * @return this builder
         */
        public Builder maxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
            return this;
        }

        /**
         * Overrides the clock used for idle timing. Defaults to {@link Clock#systemUTC()}.
         *
         * @param clock
         *            the clock (must not be null)
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            return this;
        }

        /**
         * Builds the resolver. Call {@link AgentRuntimeResolver#start()} to begin background sweeping.
         *
         * @return a new resolver the caller must close
         */
        public AgentRuntimeResolver build() {
            return new AgentRuntimeResolver(this);
        }
    }
}
