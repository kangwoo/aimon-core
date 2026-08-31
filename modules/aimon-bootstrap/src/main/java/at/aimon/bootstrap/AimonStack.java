package at.aimon.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.runtime.AgentRuntimeResolver;
import at.aimon.bootstrap.runtime.SchedulingLifecycle;
import at.aimon.bootstrap.spec.AgentDescriptor;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.scheduling.SchedulingEngine;
import at.aimon.core.skill.policy.pending.PendingTurnReaper;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.session.routing.SessionRouter;

/**
 * A running AIMON deployment — one object, holding everything {@link AimonStackBuilder} assembled.
 *
 * <p>
 * The reason this type exists rather than a bag of separately-published components is teardown. The stack's
 * fourteen-odd closeables must be shut down in one specific order (see {@link TeardownPhase}), and most adjacent
 * pairs in that order have <b>no dependency edge</b> between them — the constraint is a runtime one (this thread
 * must stop producing before that consumer goes away), not a reference one. Any container that derives
 * destruction order from its dependency graph will therefore produce a different, plausible-looking, wrong
 * order. Owning the order here and exposing exactly one closeable is what makes the surrounding framework's
 * choice irrelevant.
 *
 * <p>
 * So the integration contract for any container is one line: <b>close the stack, nothing else</b>. Everything
 * reachable through the accessors below is borrowed. Closing a borrowed component — the router, an agent
 * runtime, the scheduling engine — pulls it out from under the ordered teardown that is about to close it
 * properly.
 *
 * <h2>Starting is separable; stopping is not</h2>
 *
 * <p>
 * The asymmetry is deliberate. Start-up is exposed in two pieces — {@link #startRuntimes()} and
 * {@link #startScheduling()} — because a host with a listening socket has to open it between them, and only that
 * host knows when. Shutdown stays one call, because the ordering constraint there runs through fourteen
 * resources rather than two and no host could reproduce it. {@link #stopScheduling()} is the single exception,
 * and it is an addition to {@link #close()} rather than a step a caller becomes responsible for: closing a stack
 * whose scheduler is still running stops it, in the right place, exactly as before.
 *
 * <h2>close() is public and no-arg on purpose</h2>
 *
 * <p>
 * Spring infers a destroy method by looking for a public no-arg {@code close()} or {@code shutdown()}. Naming
 * this method {@code stop()}, or giving it a timeout parameter, would silently disable that inference: the
 * context would shut down, the method would never run, and the JVM would keep a scheduler thread, a shell, and a
 * file system alive with no error anywhere. The drain timeout is therefore configured on the spec
 * ({@code SessionSpec.drainTimeout}) rather than passed here.
 */
public final class AimonStack implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AimonStack.class);

    private final AimonStackSpec spec;
    private final TeardownRegistry teardown;
    private final SessionRouter sessionRouter;
    private final OrcaAgentExecutor agentExecutor;
    private final AgentRuntimeRegistry agentRuntimeRegistry;
    private final SchedulingLifecycle schedulingLifecycle;
    private final SessionRecordStore sessionRecordStore;
    private final MessageQueueManager messageQueueManager;
    private final PendingTurnRegistry pendingTurnRegistry;
    private final PendingTurnReaper pendingTurnReaper;
    private final Map<AgentRuntimeId, VirtualFileSystem> fileSystems;
    private final AgentRuntimeId primaryRuntimeId;
    private final Map<AgentRuntimeId, OrcaAgentRuntime> runtimes;
    private final List<AgentDescriptor> agentDescriptors;
    private final AgentRuntimeResolver agentRuntimeResolver;
    private final RuntimeDegradations degradations;
    private final AtomicBoolean runtimesStarted = new AtomicBoolean();

    @SuppressWarnings("checkstyle:ParameterNumber")
    AimonStack(AimonStackSpec spec, TeardownRegistry teardown, SessionRouter sessionRouter,
            OrcaAgentExecutor agentExecutor, AgentRuntimeRegistry agentRuntimeRegistry,
            SchedulingLifecycle schedulingLifecycle, SessionRecordStore sessionRecordStore,
            MessageQueueManager messageQueueManager, PendingTurnRegistry pendingTurnRegistry,
            PendingTurnReaper pendingTurnReaper, Map<AgentRuntimeId, VirtualFileSystem> fileSystems,
            AgentRuntimeId primaryRuntimeId, Map<AgentRuntimeId, OrcaAgentRuntime> runtimes,
            List<AgentDescriptor> agentDescriptors, AgentRuntimeResolver agentRuntimeResolver,
            RuntimeDegradations degradations) {
        this.spec = Objects.requireNonNull(spec, "spec must not be null");
        this.teardown = Objects.requireNonNull(teardown, "teardown must not be null");
        this.sessionRouter = Objects.requireNonNull(sessionRouter, "sessionRouter must not be null");
        this.agentExecutor = Objects.requireNonNull(agentExecutor, "agentExecutor must not be null");
        this.agentRuntimeRegistry = Objects.requireNonNull(agentRuntimeRegistry,
                "agentRuntimeRegistry must not be null");
        this.schedulingLifecycle = schedulingLifecycle; // nullable — scheduling is optional
        this.sessionRecordStore = Objects.requireNonNull(sessionRecordStore, "sessionRecordStore must not be null");
        this.messageQueueManager = Objects.requireNonNull(messageQueueManager, "messageQueueManager must not be null");
        this.pendingTurnRegistry = Objects.requireNonNull(pendingTurnRegistry, "pendingTurnRegistry must not be null");
        this.pendingTurnReaper = Objects.requireNonNull(pendingTurnReaper, "pendingTurnReaper must not be null");
        this.fileSystems = Map.copyOf(Objects.requireNonNull(fileSystems, "fileSystems must not be null"));
        this.primaryRuntimeId = Objects.requireNonNull(primaryRuntimeId, "primaryRuntimeId must not be null");
        this.runtimes = Map.copyOf(runtimes);
        this.agentDescriptors = List
                .copyOf(Objects.requireNonNull(agentDescriptors, "agentDescriptors must not be null"));
        this.agentRuntimeResolver = Objects.requireNonNull(agentRuntimeResolver,
                "agentRuntimeResolver must not be null");
        this.degradations = Objects.requireNonNull(degradations, "degradations must not be null");
    }

    /**
     * Convenience for {@code AimonStackBuilder.build(spec)} — assembles and starts.
     *
     * @param spec
     *            the description to build from (must not be null)
     * @return a running stack the caller owns and must {@link #close()}
     */
    public static AimonStack from(AimonStackSpec spec) {
        return AimonStackBuilder.build(spec);
    }

    /**
     * Convenience for {@code AimonStackBuilder.assemble(spec)} — assembles without starting.
     *
     * <p>
     * For hosts that own the moment an application begins serving; see
     * {@link AimonStackBuilder#assemble(AimonStackSpec)} for what is and is not true of the result.
     *
     * @param spec
     *            the description to build from (must not be null)
     * @return an assembled but unstarted stack the caller owns and must {@link #close()}
     */
    public static AimonStack assembled(AimonStackSpec spec) {
        return AimonStackBuilder.assemble(spec);
    }

    /**
     * Returns the spec this stack was built from.
     *
     * @return the spec, never null
     */
    public AimonStackSpec spec() {
        return spec;
    }

    /**
     * Returns the entry point for running turns — resolves a session id to its live handle, opening one if this
     * node does not already hold it.
     *
     * @return the router, never null; borrowed, do not close
     */
    public SessionRouter sessionRouter() {
        return sessionRouter;
    }

    /**
     * Returns the shared executor every session runs its ReAct loop through.
     *
     * @return the executor, never null; borrowed, do not close
     */
    public OrcaAgentExecutor agentExecutor() {
        return agentExecutor;
    }

    /**
     * Returns the registry the scheduling engine and the session opener both resolve runtime ids against.
     *
     * @return the registry, never null; borrowed, do not close
     */
    public AgentRuntimeRegistry agentRuntimeRegistry() {
        return agentRuntimeRegistry;
    }

    /**
     * Returns the scheduling engine, when scheduling was enabled.
     *
     * @return the engine, or empty — in which case {@link #degradations()} carries a {@code scheduling} entry
     */
    public Optional<SchedulingEngine> schedulingEngine() {
        return Optional.ofNullable(schedulingLifecycle).map(SchedulingLifecycle::engine);
    }

    /**
     * Returns the durable session store — transcripts, session totals, budget overrides.
     *
     * @return the store, never null; borrowed, do not close
     */
    public SessionRecordStore sessionRecordStore() {
        return sessionRecordStore;
    }

    /**
     * Returns the manager backing auto-enqueue of inputs that arrive while a turn is running.
     *
     * @return the manager, never null; borrowed, do not close
     */
    public MessageQueueManager messageQueueManager() {
        return messageQueueManager;
    }

    /**
     * Returns the registry holding turns suspended awaiting a skill-approval answer.
     *
     * <p>
     * This is front-end surface, not an internal detail: a turn that hit {@code ASK} is parked here until
     * something outside the stack answers it, so whatever the deployment's approval UI is — the CLI's
     * {@code /pending}, {@code /approve}, {@code /deny}, a REST endpoint, a Slack button — it reads and resolves
     * turns through this registry. A stack whose {@code SkillApprovalSpec} never returns {@code ASK} simply never
     * puts anything in it.
     *
     * @return the registry, never null; borrowed, do not close
     */
    public PendingTurnRegistry pendingTurnRegistry() {
        return pendingTurnRegistry;
    }

    /**
     * Returns the file system one runtime reads and writes through.
     *
     * <p>
     * Per runtime, not per stack: with {@code FileSystemSpec.localAt} or a factory, each runtime gets its own
     * instance, and two ids that differ only in discriminator are two tenants that must not see each other's
     * files. A {@code supplied} instance is the exception — every id answers with the same object, which is why
     * that form records a degradation once more than one runtime exists.
     *
     * <p>
     * Whether the stack closes these depends on how they were specified: a supplied instance stays the caller's,
     * and ones the stack created are closed during teardown.
     *
     * @param agentRuntimeId
     *            the runtime to answer for
     * @return the file system, or empty when this stack did not stand up that runtime
     */
    public Optional<VirtualFileSystem> fileSystem(AgentRuntimeId agentRuntimeId) {
        return Optional.ofNullable(fileSystems.get(agentRuntimeId));
    }

    /**
     * Returns the id of the agent runtime sessions bind to when the caller does not name one.
     *
     * @return the primary runtime id, never null
     */
    public AgentRuntimeId primaryRuntimeId() {
        return primaryRuntimeId;
    }

    /**
     * Returns the runtime for a given id.
     *
     * @param agentRuntimeId
     *            the id to look up
     * @return the runtime, or empty when this stack did not stand one up for that id
     */
    public Optional<OrcaAgentRuntime> runtime(AgentRuntimeId agentRuntimeId) {
        return Optional.ofNullable(runtimes.get(agentRuntimeId));
    }

    /**
     * Returns every runtime this stack owns, keyed by id.
     *
     * @return an immutable map; the runtimes are borrowed, do not close them
     */
    public Map<AgentRuntimeId, OrcaAgentRuntime> runtimes() {
        return runtimes;
    }

    /**
     * Returns what each configured agent was built from — ref, bundle name, loaded bundle and properties.
     *
     * <p>
     * One entry per agent in the spec, in declaration order, and none of them carries a discriminator: this
     * answers "which agents does this deployment have", which is a finite question, and not "which runtimes exist
     * right now", which includes every tenant seen since startup and is {@link AgentRuntimeResolver#trackedIds()}.
     *
     * <p>
     * These are the same descriptors {@link at.aimon.bootstrap.spec.AimonAgentCustomizer} was asked about, so a
     * host listing agents sees exactly the values its customizers matched on rather than a second rendering of
     * the configuration that could disagree with them.
     *
     * @return an immutable list, never empty
     */
    public List<AgentDescriptor> agentDescriptors() {
        return agentDescriptors;
    }

    /**
     * Returns the resolver that hands out runtimes by id, creating one per discriminator on first use.
     *
     * <p>
     * This is how a caller reaches a runtime for a tenant, and the only supported way: {@link #runtime} and
     * {@link #runtimes()} answer for the agents this stack stood up at startup, which is a fixed set that does
     * not include anything created since. The same is true of {@link #fileSystem(AgentRuntimeId)} — a tenant's
     * file system belongs to its runtime and is closed with it, so it is not in the stack's map.
     *
     * <p>
     * Acquire a lease for the duration of the work and close it; until it is closed, the runtime cannot be
     * reclaimed.
     *
     * <p>
     * Available from assembly, but not useful before {@link #startRuntimes()}: until then the registry is empty,
     * so a declared agent's id resolves to nothing. The resolver refuses it with an {@code IllegalStateException}
     * rather than building a second runtime the registration would go on to replace — this stack's runtimes are
     * already built and waiting to be registered, and there is no such thing as a declared id this resolver
     * should create.
     *
     * @return the resolver, never null
     */
    public AgentRuntimeResolver agentRuntimes() {
        return agentRuntimeResolver;
    }

    /**
     * Returns the capabilities this stack does not have.
     *
     * @return the degradations, never null and possibly empty
     */
    public RuntimeDegradations degradations() {
        return degradations;
    }

    /**
     * Starts everything, in the order a process with no listening socket wants: runtimes first, then scheduling.
     *
     * <p>
     * This is what {@link #from(AimonStackSpec)} calls, and what a host with an inbound port should <i>not</i>
     * call — it has to open that port between the two halves. See {@link #startRuntimes()}.
     *
     * @return this stack, so an assemble-and-start reads as one expression
     */
    public AimonStack start() {
        startRuntimes();
        startScheduling();
        return this;
    }

    /**
     * Registers this stack's runtimes and starts the background sweepers — the point at which the stack becomes
     * able to serve a turn.
     *
     * <p>
     * Before this runs, the {@link AgentRuntimeRegistry} is empty: a session cannot resolve the runtime it is
     * bound to, and neither can a schedule. That is the property worth having, because it makes "assembled" and
     * "serving" two moments a host can put its own work between. The two halves of {@link #start()} are separate
     * for the same reason — a deployment with an HTTP listener wants runtimes registered <b>before</b> the socket
     * opens and the scheduler started <b>after</b>, so that a cron firing during start-up neither fails to
     * resolve a runtime nor produces work the front end cannot yet serve.
     *
     * <p>
     * Idempotent, and safe to call on a stack that never gets {@link #startScheduling()}.
     *
     * @return this stack
     */
    public AimonStack startRuntimes() {
        if (!runtimesStarted.compareAndSet(false, true)) {
            return this;
        }
        runtimes.values().forEach(agentRuntimeRegistry::register);
        // Both are daemon sweepers belonging to the serving tier rather than to scheduling: the reaper expires
        // turns parked on an approval prompt, and the resolver's sweeper reclaims idle tenant runtimes. Neither
        // has anything to do until a turn has run, so they start with the runtimes and not before them.
        pendingTurnReaper.start();
        agentRuntimeResolver.start();
        log.info("AIMON stack started: agent runtime(s) {} registered", runtimes.keySet());
        return this;
    }

    /**
     * Starts the scheduling engine, if this stack has one.
     *
     * <p>
     * Deliberately after {@link #startRuntimes()} rather than part of it: a schedule that fires resolves its
     * {@code boundRuntimeId} through the registry, so an engine started first would spend its first moments
     * failing to find runtimes that are about to exist.
     *
     * <p>
     * Idempotent, a no-op when scheduling is disabled, and refuses to restart an engine that has been stopped.
     *
     * @return this stack
     */
    public AimonStack startScheduling() {
        if (schedulingLifecycle != null) {
            schedulingLifecycle.start();
        }
        return this;
    }

    /**
     * Stops the scheduling engine, leaving the rest of the stack able to finish the turns it is running.
     *
     * <p>
     * The point of stopping it separately is that it is the one component that <i>creates</i> work: everything
     * else in the stack answers a request that already arrived, so stopping the scheduler first means the drain
     * that follows is finite. Whoever calls this does not have to call it — {@link #close()} stops the engine too
     * — but a host that closes its inbound traffic in phases wants this at the same point it stops accepting.
     *
     * <p>
     * Takes no timeout, and cannot: {@code SchedulingEngine} offers only start and close, and its close waits for
     * periods fixed inside the scheduler. A parameter here would be one this stack could only discard.
     *
     * <p>
     * Idempotent, and a no-op when scheduling is disabled.
     *
     * @return this stack
     */
    public AimonStack stopScheduling() {
        if (schedulingLifecycle != null) {
            schedulingLifecycle.stop();
        }
        return this;
    }

    /**
     * Returns whether {@link #startRuntimes()} has run.
     *
     * @return {@code true} once the runtimes are registered
     */
    public boolean isStarted() {
        return runtimesStarted.get();
    }

    /**
     * Answers whether the stack can serve a turn right now, and why not when it cannot.
     *
     * <p>
     * Cheap and local by design — no LLM call, no database round-trip. See {@link HealthReport}.
     *
     * @return a point-in-time report
     */
    public HealthReport health() {
        // Every runtime, not just the primary: with several agents, a session bound to the second one fails just
        // as completely as one bound to the first, and a report that only looked at the primary would call that
        // stack healthy.
        final List<AgentRuntimeId> missing = runtimes.keySet().stream()
                .filter(id -> agentRuntimeRegistry.get(id).isEmpty()).collect(Collectors.toList());
        // "Not yet" and "no longer" are the same check and very different incidents — one is a host that has not
        // called start(), the other is a registry something has emptied under a running stack.
        final String missingDetail = missing.isEmpty()
                ? null
                : (runtimesStarted.get()
                        ? "No longer registered: "
                        : "Not registered yet — startRuntimes() has not" + " run: ") + missing;
        final boolean schedulingStopped = schedulingLifecycle != null && schedulingLifecycle.isStopped();
        return HealthReport.builder(degradations)
                .check("not-closed", !teardown.isClosed(), teardown.isClosed() ? "The stack has been closed" : null)
                .check("agent-runtime-registered", missing.isEmpty(), missingDetail)
                .check("scheduling-running", !schedulingStopped,
                        schedulingStopped ? "The scheduling engine has been stopped; no schedule will fire" : null)
                // Not a degradation: degradations are what the stack was built without and are frozen at build
                // time, whereas this is a capacity limit that a stack can cross and come back from.
                .check("agent-runtime-capacity", !agentRuntimeResolver.isSaturated(), agentRuntimeCapacityDetail())
                .build();
    }

    /**
     * Builds the capacity detail line, present whether or not the check passes.
     *
     * <p>
     * A passing check normally carries no detail. This one does, and that is the point: the cumulative counters
     * are what an operator came here to read, and they are <b>metrics, not a verdict</b>. This check used to be
     * {@code exhaustionCount() == 0}, which never returns to true — one transient refusal at 03:00 pinned the
     * whole report to {@link HealthReport.Status#DOWN} for the life of the process, and an orchestrator reading
     * it would hold a recovered pod out of rotation forever. The check now asks whether the resolver is refusing
     * <i>now</i>; the history stays here, where it informs without latching.
     */
    private String agentRuntimeCapacityDetail() {
        final long exhaustions = agentRuntimeResolver.exhaustionCount();
        final long provisionFailures = agentRuntimeResolver.provisionFailureCount();
        final StringBuilder detail = new StringBuilder().append(agentRuntimeResolver.trackedCount()).append(" of ")
                .append(agentRuntimeResolver.maxEntries()).append(" tenant runtime slot(s) in use");
        if (agentRuntimeResolver.isSaturated()) {
            detail.append(" and none is idle, so the next new tenant is refused; raise maxEntries or shorten the"
                    + " idle TTL");
        }
        if (exhaustions > 0) {
            detail.append("; ").append(exhaustions).append(" request(s) refused since startup");
        }
        if (provisionFailures > 0) {
            detail.append("; ").append(provisionFailures).append(" runtime(s) failed to build since startup");
        }
        return detail.toString();
    }

    /**
     * Returns the shutdown order this stack will follow, one line per resource, for logging or a diagnostic
     * endpoint.
     *
     * <p>
     * Worth printing at startup in any deployment that has ever had a shutdown hang: it is the only place the
     * order is visible without reading {@link TeardownPhase}'s source.
     *
     * @return the entries, in the exact order {@link #close()} will close them
     */
    public List<String> teardownPlan() {
        final List<String> plan = new ArrayList<>();
        int index = 1;
        for (TeardownRegistry.Entry entry : teardown.entries()) {
            plan.add(index++ + ". " + entry);
        }
        return plan;
    }

    /**
     * Returns whether {@link #close()} has already run.
     *
     * @return {@code true} once closed
     */
    public boolean isClosed() {
        return teardown.isClosed();
    }

    /**
     * Enrolls a caller-owned resource in this stack's ordered shutdown.
     *
     * <p>
     * The stack cannot build everything a front end needs — a script engine pool, a hook hot-reloader, a memory
     * derivation queue are all constructed <i>from</i> the stack's executor and runtime, so they cannot be passed
     * in through the spec. But they still have to close in the right place relative to what the stack owns: a
     * script engine pool must outlive the runtime whose tools borrow it, and a hot-reloader must stop before the
     * shell it dispatches through. Closing them in a {@code finally} around {@link #close()} cannot express
     * either constraint, because both of those points are <i>inside</i> the stack's own sequence.
     *
     * <p>
     * So they join the same plan instead. Ordering follows {@link TeardownPhase} exactly as it does for the
     * stack's own resources — the phase constants for these front-end resources
     * ({@link TeardownPhase#SCRIPT_ENGINES}, {@link TeardownPhase#HOOK_HOT_RELOAD}, the memory phases) already
     * exist for this purpose. Within one phase, later registrations close first.
     *
     * <p>
     * "Enrolls" is not "hands over": the caller still decides what the resource is and when it was created. What
     * transfers is the closing.
     *
     * @param <T>
     *            the resource type
     * @param phase
     *            when to close it (must not be null)
     * @param label
     *            a short name for logging and {@link #teardownPlan()} (must not be null)
     * @param resource
     *            the resource; {@code null} is accepted and ignored, so an optional component needs no
     *            surrounding {@code if}
     * @return {@code resource}, so the call can wrap a constructor expression
     * @throws IllegalStateException
     *             if the stack is already closed — a resource registered then would never be closed at all, so
     *             this fails loudly rather than leaking silently
     */
    public <T extends AutoCloseable> T own(TeardownPhase phase, String label, T resource) {
        return teardown.ownIfPresent(phase, label, resource);
    }

    /**
     * Shuts the whole stack down in {@link TeardownPhase} order.
     *
     * <p>
     * Every registered resource is closed even if an earlier one throws; the failures are collected and rethrown
     * together as one {@link at.aimon.bootstrap.exception.AimonTeardownException} with each failure attached as a
     * suppressed exception. That is the opposite of the usual sequential-close idiom, and deliberately so: a
     * throw partway through a shutdown sequence leaves every later resource open, which is how a process ends up
     * unable to exit because of one component that failed to stop.
     *
     * <p>
     * Idempotent — a second call does nothing. Containers that both register a shutdown hook and call a destroy
     * method are common enough that this cannot be left to chance.
     *
     * @throws at.aimon.bootstrap.exception.AimonTeardownException
     *             if any resource failed to close; every other resource was still closed
     */
    @Override
    public void close() {
        if (teardown.isClosed()) {
            return;
        }
        log.info("Shutting down AIMON stack ({} resource(s))", teardown.entries().size());
        teardown.closeAll();
    }

    @Override
    public String toString() {
        return "AimonStack[agent=" + primaryRuntimeId + ", resources=" + teardown.entries().size() + ", started="
                + runtimesStarted.get() + ", closed=" + teardown.isClosed() + ", degradations="
                + degradations.asList().size() + "]";
    }
}
