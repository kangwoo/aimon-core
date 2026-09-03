package at.aimon.session.routing.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Ticker;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionFactory;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.OpenAttributes;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.exception.ConflictingAgentException;
import at.aimon.core.agent.session.exception.IdempotencyConflictException;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.PutResult;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.ClaimResult;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionStore;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.session.routing.ClusterSessionStatus;
import at.aimon.session.routing.LiveSessionCache;
import at.aimon.session.routing.LiveSessionCache.SessionEntry;
import at.aimon.session.routing.LiveSessionOpener;
import at.aimon.session.routing.SessionRouter;
import at.aimon.session.routing.SubmitDisposition;
import at.aimon.session.routing.SubmitRequest;
import at.aimon.session.routing.metrics.SessionMetrics;

/**
 * Default {@link SessionRouter} implementation that wires the in-memory and SPI components together to
 * realize design §7.1's submit flow on a single node, with cross-node hooks for distributed deployments.
 *
 * <p>
 * The manager is intentionally stateless beyond the per-session collaborators: the session store, inbox,
 * signal bus, and idempotency store are accessed exclusively through their SPIs. Local-only state — session cache,
 * in-process event
 * publisher, signal subscriptions, and per-session idle-sweep scheduler — is owned and closed at
 * {@link #close()}.
 *
 * <p>
 * <strong>Out of scope for {@link #close()}:</strong> the {@code AgentRuntime} is agent-scoped and shared
 * across sessions; closing this manager closes cached live sessions but does <strong>not</strong> close those
 * runtimes (and must not — other agents or live sessions may still hold references). Closing
 * {@code AgentRuntime} (and the {@code McpClientManager} / other {@code AgentScoped} resources it owns)
 * is the application bootstrap's responsibility, typically via {@code OrcaAgentRuntimeManager#destroyRuntime}
 * at shutdown. See {@link at.aimon.session.routing.LiveSessionOpener} and
 * {@code docs/design/agent-execution/agent-runtime-scope.md}.
 */
public final class DefaultSessionRouter implements SessionRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultSessionRouter.class);

    /**
     * Sentinel context id for {@code RejectedAt} events emitted before a real {@link AgentRuntimeId} is
     * available (e.g., when a queued message conflicts with the agent currently bound to the session). Carrying a
     * non-null id keeps the event schema uniform; callers should treat this value as "not a real context".
     */
    private static final AgentRuntimeId WEB_REJECTED_SENTINEL = AgentRuntimeId.of("agent:web-rejected");

    /**
     * Sentinel context id for terminal {@code InterruptedAt} events synthesized by the manager during eviction /
     * shutdown when no live session context is available. Same intent as {@link #WEB_REJECTED_SENTINEL}.
     */
    private static final AgentRuntimeId WEB_EVICTED_SENTINEL = AgentRuntimeId.of("agent:web-evicted");

    /**
     * Holder election, the durable record, and the agent binding behind one door. Node-scoped — see
     * {@code SessionStore}: this instance is built for this manager alone and must not be shared with a second
     * one.
     */
    private final SessionStore store;

    private final SessionSignalBus signalBus;
    private final SessionInbox inbox;
    private final IdempotencyStore idempotencyStore;
    private final String nodeId;
    private final Duration lockLease;
    private final Duration idempotencyPrimaryTtl;
    private final Duration idempotencySecondaryTtl;
    private final Duration idempotencyForwardTtl;
    private final Duration releaseInterruptTimeout;
    private final SessionMetrics metrics;

    /**
     * Session-scoped skill approvals to drop when a session goes away. Optional — {@code null} when the
     * deployment does not cache approvals per session. Borrowed, never closed: the store outlives this manager
     * and serves the agent runtimes too.
     */
    private final SessionApprovalStore sessionApprovalStore;

    private final LiveSessionCache sessionCache;
    private final InProcessEventPublisher eventPublisher;

    private final StatusProjection statusProjection = new StatusProjection();
    private final AtomicLong statusSeq = new AtomicLong();
    private final Duration statusHeartbeatInterval;
    private volatile boolean statusBroadcastEnabled;

    /**
     * General-purpose pool: idle sweeps, the holder-loss sweep, {@code STATUS} heartbeats and every forwarded-turn
     * poll.
     * Its load grows with the number of live sessions, which is exactly why lease renewal does not run here.
     */
    private final ScheduledExecutorService scheduler;

    /**
     * Lease renewal only, on a small pool of its own.
     *
     * <p>
     * Renewal has a property none of the other periodic work has: being late is indistinguishable from having lost the
     * lease. A tick that is merely queued behind a slow idle sweep or a burst of forward polls looks, from the store's
     * side, exactly like a holder that stopped renewing — and the lease budget is only two missed ticks wide (see
     * {@code SessionRouterBuilder#DEFAULT_LOCK_LEASE}). Sharing the pool would make that budget a function of
     * unrelated load, so renewal gets threads of its own and the shared pool cannot spend it.
     *
     * <p>
     * <b>Why more than one thread.</b> Since Stage 3b a lease lives as long as its session rather than as long as a
     * turn, so the population renewing here is every session this node <em>holds</em> — up to
     * {@code SessionRouterBuilder#DEFAULT_MAX_CACHED_SESSIONS} of them — not the handful running a turn right now.
     * Each tick is a store round-trip, plus a second one for the idempotency touch, and on one thread those serialize:
     * a store that answers in single-digit milliseconds is enough to fill a whole extend interval at that scale, after
     * which ticks slip and leases are lost for no reason but queueing. A tick must not queue behind an unrelated
     * session's tick either, and that is what a single thread guarantees it does.
     *
     * <p>
     * Sizing this pool is not on its own sufficient: nothing that can block for an unbounded time may run on it at
     * all. That rule is what {@link #onLeaseLost} exists to keep — see its note on why the reaction to a lost lease is
     * handed to {@link #turnExecutor}.
     */
    private final ScheduledExecutorService leaseScheduler;

    private final ExecutorService relayDispatcher;
    private final ExecutorService turnExecutor;
    private final LeaseRenewer leaseRenewer;

    /**
     * Recovers turns whose holder died mid-flight.
     *
     * <p>
     * <b>Silently opt-in.</b> The sweeper's population is the idempotency store, so it only ever sees turns submitted
     * <em>with</em> an idempotency key: {@link #checkIdempotency} returns {@code IdempotencyDecision.empty()} for a
     * keyless submission, which reserves nothing and therefore leaves nothing for the sweeper to notice going stale. A
     * keyless turn whose holder is lost is recovered by no mechanism at all — its caller waits out the forward
     * deadline.
     * Deployments that want holder-loss recovery must submit keys.
     */
    private final HolderLossSweeper holderLossSweeper;

    private final ScheduledFuture<?> idleSweepTask;
    private final ScheduledFuture<?> holderSweepTask;

    private final AtomicLong turnSeq = new AtomicLong();
    private final AtomicInteger inFlightTurns = new AtomicInteger();
    private final AtomicBoolean acceptingSubmits = new AtomicBoolean(true);

    /**
     * Set once {@link #closeGracefully} stops waiting and starts interrupting: the grace window is over and no further
     * turn on this node can succeed.
     *
     * <p>
     * Distinct from {@link #acceptingSubmits}, and the distinction is what keeps a graceful close graceful.
     * {@code acceptingSubmits=false} only means "take no <em>new</em> work" — the pass already running is counted in
     * {@link #inFlightTurns}, so {@code closeGracefully} is still waiting for it and its queued messages must be run,
     * not
     * failed. This flag means the waiting is finished, and so is the only point at which a queued message that was
     * already
     * taken out of the at-most-once inbox has to be answered with {@code NOT_HOLDER} instead.
     */
    private final AtomicBoolean shutdownForced = new AtomicBoolean();

    // @formatter:off
    private final ConcurrentMap<SessionId, SessionSignalBus.Subscription> subscriptions
            = new ConcurrentHashMap<>();
    // @formatter:on

    /**
     * Turns this node forwarded to a peer and still owes its caller an answer for, addressed by the {@link TurnId} the
     * holder runs them under. Resolved by the {@code TURN_RESULT} rail, by the polling fallback, by a local drain that
     * happened to pick the message up here, or — as a last resort — by the forward deadline.
     */
    private final ConcurrentMap<TurnId, PendingForward> forwardsByTurn = new ConcurrentHashMap<>();

    /**
     * Secondary index over the same entries, for the submission that has no turn of its own: a retry collapsed onto an
     * in-flight turn was never told that turn's id, so its idempotency key is the only address it can be reached at.
     */
    private final ConcurrentMap<String, PendingForward> forwardsByKey = new ConcurrentHashMap<>();

    /**
     * Sessions whose inbox is known to hold work this node has not collected yet — the doorbell. A membership set
     * rather than a per-session flag object so it stays bounded: an entry exists only between the delivery that
     * rang it and the collect that consumes it.
     */
    private final Set<SessionId> doorbellPending = ConcurrentHashMap.newKeySet();

    /**
     * Sessions whose doorbell this node took on as the holder and has neither answered nor passed on — see
     * {@link #handOverForDrain}.
     *
     * <p>
     * Only the node that was the holder when a doorbell rang may relay it. An unconditional relay would let two
     * draining
     * nodes bounce the same announcement off each other for as long as both stay subscribed; keyed on having been the
     * holder, each doorbell yields at most one relay, and answering it with a collect discharges the debt instead.
     *
     * <p>
     * Recorded when the doorbell rings rather than when the hand-off runs, because the lease can go back in between —
     * an
     * idle-TTL eviction, a peer's yield, a pass that simply ended — and after that "I owed this one" and "never mine"
     * are indistinguishable.
     */
    private final Set<SessionId> doorbellRelayOwed = ConcurrentHashMap.newKeySet();

    /**
     * The session leases this node currently holds, one per open session (design §7.4).
     *
     * <p>
     * Before Stage 3b there was no such map: a lease was a local of {@code submit} that {@link #runTurnLoop}'s
     * {@code finally} released, so between two turns of one session nobody held it and the next turn re-elected
     * from scratch. Holding it for the session's lifetime is what makes a session worth caching — the second turn skips
     * the election entirely — and it is also what makes this map the answer to "am I the holder?", a question the
     * backend can no longer be asked cheaply because it would say yes to <em>this</em> node twice over.
     *
     * <p>
     * Entries are put by {@link #installLease} and removed by {@link #returnLease}, which is driven by the session
     * closing rather than by the turn ending.
     */
    private final ConcurrentMap<SessionId, HeldLease> heldLeases = new ConcurrentHashMap<>();

    /**
     * Sessions with a turn (or a drain pass, or a delete) running on this node — the turn gate.
     *
     * <p>
     * This is the exclusion the turn-scoped lease used to provide as a side effect. When every turn re-acquired the
     * lease, a second submission for the same session was refused by the backend even though the holder was this
     * very node; now that the lease spans the session, the backend would happily let two local turns run against one
     * session, which {@code LiveSession} does not survive. So the per-session critical section moves in here.
     *
     * <p>
     * The gate does more than serialize turns: it is also what makes "reuse the held lease" safe. Deciding to reuse is
     * a
     * check-then-act against {@link #heldLeases}, and the only actor that could invalidate the check mid-flight is a
     * session close returning the lease — which takes this same gate, and declines when a turn holds it (see
     * {@link #returnLeaseWhenIdle}). Membership rather than a lock object so it stays bounded, and so a gate is never
     * left behind for a session nobody is talking to.
     */
    private final Set<SessionId> activeTurns = ConcurrentHashMap.newKeySet();

    /**
     * Sessions somebody asked for the lease back on while the turn gate was held — the lease-return doorbell.
     *
     * <p>
     * {@link #returnLeaseWhenIdle} declines when the gate is taken, on the reasoning that whoever holds it will apply
     * the same rule on the way out. That holds only while the holder still has its lease decision <em>ahead</em> of it.
     * A turn that has already run {@code returnLeaseIfUnowned} — and so decided to keep the lease, because the session
     * was still cached at the time — and has not yet reached {@link #endTurn} answers the ask with a decision taken
     * before the ask existed. The close that arrived in between is then honored by nobody, and the session stays
     * pinned to this node until the lease expires: a yield the holder never obeys, a {@code releaseSession} that
     * returns without releasing, an idle-TTL sweep that closes the session and leaves its lease behind.
     *
     * <p>
     * The mark is what the gate holder re-reads after releasing, so one of the two threads always applies the rule to
     * the state as it stands rather than as it stood.
     */
    private final Set<SessionId> leaseReturnPending = ConcurrentHashMap.newKeySet();

    /**
     * The live session each turn running on this node is working through, for as long as it runs.
     *
     * <p>
     * Redundant with {@link LiveSessionCache#peek} in the ordinary case and the only way to find the session in the
     * case that matters. An eviction takes the entry out of the cache immediately and defers only the {@code close()}
     * when a turn has it pinned (see {@link LiveSessionCache}'s pinning contract), so between a mid-turn eviction and
     * that turn's unpin the cache cannot name a session that is very much still running. Every path that has to
     * <em>stop</em> that turn — a lost lease, a peer's yield, a delete, an admin interrupt, shutdown — would otherwise
     * look it up through the cache, find nothing, and silently do nothing to the one turn it most needed to reach.
     *
     * <p>
     * Bounded by the turn gate rather than by size: {@link #activeTurns} admits one turn per session per node, so this
     * map holds at most one entry per session with work running here, and each is removed by the same {@code finally}
     * that leaves the gate.
     */
    private final ConcurrentMap<SessionId, LiveSession> turnSessions = new ConcurrentHashMap<>();

    /** Cadence of the {@code TURN_RESULT} polling fallback; see {@link #pollForward}. */
    private final long forwardPollIntervalMs;

    /**
     * Canonical metrics-aware constructor for the {@link LiveSessionFactory} path. Delegates to the
     * {@link LiveSessionOpener} variant via {@code adapt(...)}.
     *
     * <p>
     * Exposed for {@link at.aimon.session.routing.builder.SessionRouterBuilder}; application code should go
     * through {@link at.aimon.session.routing.SessionRouter#builder()} so {@code DISTRIBUTED}-mode invariants
     * and {@code nodeId} are validated.
     */
    public DefaultSessionRouter(LiveSessionFactory sessionFactory, SessionRouterConfig config) {
        this(adapt(sessionFactory), config);
    }

    /**
     * Canonical metrics-aware constructor for the {@link LiveSessionOpener} path.
     *
     * <p>
     * Exposed for {@link at.aimon.session.routing.builder.SessionRouterBuilder}; application code should go
     * through {@link at.aimon.session.routing.SessionRouter#builder()} so {@code DISTRIBUTED}-mode invariants
     * and {@code nodeId} are validated.
     */
    public DefaultSessionRouter(LiveSessionOpener sessionOpener, SessionRouterConfig config) {
        Objects.requireNonNull(sessionOpener, "sessionOpener must not be null");
        Objects.requireNonNull(config, "config must not be null");
        final SessionStore store = config.store();
        final SessionSignalBus signalBus = config.signalBus();
        final IdempotencyStore idempotencyStore = config.idempotencyStore();
        final String nodeId = config.nodeId();
        final Duration idleTtl = config.idleTtl();
        final int maxCachedSessions = config.maxCachedSessions();
        final Duration lockExtendInterval = config.lockExtendInterval();
        final Duration holderLossSweepInterval = config.holderLossSweepInterval();
        final SessionMetrics metrics = config.metrics();

        this.store = Objects.requireNonNull(store, "store must not be null");
        this.signalBus = Objects.requireNonNull(signalBus, "signalBus must not be null");
        this.inbox = Objects.requireNonNull(config.inbox(), "inbox must not be null");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore must not be null");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.lockLease = Objects.requireNonNull(config.lockLease(), "lockLease must not be null");
        this.idempotencyPrimaryTtl = Objects.requireNonNull(config.idempotencyPrimaryTtl(), "idempotencyPrimaryTtl");
        this.idempotencySecondaryTtl = Objects.requireNonNull(config.idempotencySecondaryTtl(),
                "idempotencySecondaryTtl");
        this.idempotencyForwardTtl = Objects.requireNonNull(config.idempotencyForwardTtl(), "idempotencyForwardTtl");
        this.releaseInterruptTimeout = Objects.requireNonNull(config.releaseInterruptTimeout(),
                "releaseInterruptTimeout");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.sessionApprovalStore = config.sessionApprovalStore();

        // The close listener is how a held lease gets back to the cluster: every way a session can end — idle TTL, LRU,
        // an explicit release, an EVICT signal, shutdown — ends in a close, and none of them knows about leases.
        this.sessionCache = new LiveSessionCache(sessionOpener, idleTtl, maxCachedSessions, Ticker.systemTicker(),
                metrics, this::returnLeaseWhenIdle);
        this.eventPublisher = new InProcessEventPublisher();

        final int schedulerSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.scheduler = Executors.newScheduledThreadPool(schedulerSize, namedFactory("web-session-sched"));
        // Renewal ticks are I/O-bound round-trips against the lease store and nothing else — see the field's note on
        // why they may never queue behind each other, and onLeaseLost's on why nothing blocking runs here. Sized from
        // CPU count even though the population renewing here is maxCachedSessions: the two knobs move independently,
        // which is what SessionRouterBuilder#maxCachedSessions warns about when that cap is raised far past its
        // default.
        final int leaseSchedulerSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.leaseScheduler = Executors.newScheduledThreadPool(leaseSchedulerSize, namedFactory("web-session-lease"));
        this.relayDispatcher = Executors.newSingleThreadExecutor(namedFactory("web-session-relay"));
        // Unbounded by choice, and bounded in practice by the turn gate rather than by the pool: activeTurns admits one
        // turn per session per node, so the live thread count tracks concurrently active sessions — itself capped by
        // maxCachedSessions — and not the arrival rate. Capping it here would not cap that; it would put lease teardown
        // behind a queue of LLM round-trips, which is the one thing dispatchLeaseTeardown picks this pool to avoid.
        this.turnExecutor = Executors.newCachedThreadPool(namedFactory("web-session-turn"));

        Objects.requireNonNull(lockExtendInterval, "lockExtendInterval must not be null");
        this.statusHeartbeatInterval = Objects.requireNonNull(config.statusHeartbeatInterval(),
                "statusHeartbeatInterval must not be null");
        this.leaseRenewer = new LeaseRenewer(store, leaseScheduler, lockExtendInterval, this.lockLease, metrics);
        this.holderLossSweeper = HolderLossSweeper.builder().store(idempotencyStore).eventSink(this::emitRecoveryFrame)
                .announcer(this::announceHolderLost).scheduler(scheduler).sweepInterval(holderLossSweepInterval)
                .secondaryTtl(this.idempotencySecondaryTtl).metrics(metrics).build();

        // Half the secondary TTL, so an IN_FLIGHT reservation that quietly expires is noticed within one period of it
        // doing so rather than only at the much longer forward deadline. Floored at a second: a deployment that
        // configures a very short secondary TTL should not turn the fallback into a hot loop against the store.
        this.forwardPollIntervalMs = Math.max(1_000L, this.idempotencySecondaryTtl.toMillis() / 2);

        final long idleSweepMs = Math.max(1_000L, idleTtl.toMillis() / 2);
        this.idleSweepTask = scheduler.scheduleAtFixedRate(this::sweepIdleSessions, idleSweepMs, idleSweepMs,
                TimeUnit.MILLISECONDS);
        this.holderSweepTask = holderLossSweeper.start();
    }

    @Override
    public SubmitDisposition submit(SubmitRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!acceptingSubmits.get()) {
            throw new IllegalStateException("SessionRouter is shutting down — refusing new submit");
        }

        final SessionId convId = request.getSessionId();
        final String requestedAgent = request.getAgentRef();

        // Subscribe before any I/O, not after the disposition is known (design §7.1 F0). The moment this submission
        // touches the inbox a peer can drain it and announce the result, and a subscription armed after that point
        // misses the announcement entirely — leaving the caller's future to the polling fallback at best, and to the
        // forward deadline when the submission carried no idempotency key. Subscribing unconditionally also covers the
        // node that wins the lease, which needs the rail for INTERRUPT and EVICT.
        ensureSubscribedQuietly(convId, "submit");

        precheckAgentBinding(convId, requestedAgent);

        // Names this submission attempt, and only this attempt. It is deliberately NOT the lease holder id: the lease
        // is
        // claimed as the bare nodeId below. The two identities were one string until Stage 3b, and the split is what
        // lets
        // each side have the lifetime it needs — the lease a node-wide one it can outlive a turn with and answer "am I
        // the holder?" from, the reservation a per-attempt one. Shrinking the reservation to nodeId would open an ABA
        // on
        // a single key within one node: attempt 1 reserves K and stalls, the sweeper resets K after the secondary TTL,
        // a
        // retry re-reserves K as attempt 2 here, and attempt 1's late compareAndReset(K, nodeId) then erases attempt
        // 2's
        // live reservation, after which the next retry executes twice. The suffix is what makes that CAS fail.
        final String reserverId = nodeId + "/" + Thread.currentThread().getName() + "/" + turnSeq.incrementAndGet();
        // Issued before any disposition branches so the caller gets a name for what it submitted whether the turn runs
        // here, waits in the inbox, or replays from the idempotency cache. The id travels with the work: into the inbox
        // envelope, onto every relayed event, and back through interrupt(sessionId, turnId, reason).
        final TurnId turnId = TurnId.generate();

        final IdempotencyDecision idem = checkIdempotency(request, reserverId);
        if (idem.replayResult != null) {
            final SubmitDisposition outcome = SubmitDisposition.executedLocally(turnId,
                    CompletableFuture.completedFuture(idem.replayResult));
            recordOutcome(outcome);
            return outcome;
        }
        if (idem.queuedSyntheticId != null) {
            // Collapsed onto an attempt that is still running — here or on a peer. There is no second turn to wait for
            // and no inbox entry to point at, so the answer can only arrive addressed by the shared idempotency key.
            // The disposition reports the pending forward's turn id, which is this attempt's own id unless an earlier
            // submission of the same key is already outstanding on this node, in which case both share its future.
            final PendingForward pending = registerForward(convId, turnId, request.getIdempotencyKey().orElse(null));
            final SubmitDisposition outcome = SubmitDisposition.forwarded(pending.turnId, idem.queuedSyntheticId,
                    pending.future);
            recordOutcome(outcome);
            return outcome;
        }

        // Gate first, lease second (design §7.4). The lease no longer answers "is a turn already running here?" — this
        // node holds it for the whole session — so that question goes to the node-local turn gate, and asking it before
        // the store saves a round trip on the busiest path there is. A gate miss can only mean a turn on this node, so
        // forwarding is unconditionally right: the running turn's post-turn re-collect picks the message up, and a
        // message that arrives too late for that leaves the doorbell ringing past the end of the turn.
        final long acquireStart = System.nanoTime();
        if (!tryBeginTurn(convId)) {
            // Reported as a rejected acquire although no lease was attempted. To an operator the two are the same event
            // — this node did not get to run the submission — and distinguishing them would make the rejection rate
            // read as zero on a single-node deployment, where the gate is now the only thing that ever refuses.
            safeMetric(() -> metrics.onLockAcquireRejected(elapsedSince(acquireStart)), "onLockAcquireRejected");
            return forwardToInbox(request, turnId, idem);
        }

        boolean turnStarted = false;
        try {
            final HeldLease held = holdOrClaim(convId, requestedAgent, idem, acquireStart);
            if (held == null) {
                return forwardToInbox(request, turnId, idem);
            }

            final CompletableFuture<AgentExecutionResult> future = new CompletableFuture<>();
            inFlightTurns.incrementAndGet();
            // closeGracefully sets acceptingSubmits=false then waits for inFlightTurns to drain. We must observe a
            // post-increment view of the gate so a submit that started before the gate flipped does not race the
            // executor's shutdownNow. AtomicBoolean.get() is a volatile read, ordered after the AtomicInteger.cas.
            if (!acceptingSubmits.get()) {
                inFlightTurns.decrementAndGet();
                if (idem.acquiredKey != null) {
                    safeCompareAndReset(idem.acquiredKey, reserverId);
                }
                returnLeaseIfUnowned(convId, held);
                throw new IllegalStateException("SessionRouter is shutting down — refusing new submit");
            }
            try {
                turnExecutor.execute(() -> {
                    try {
                        runTurnLoop(request, turnId, held, idem, future);
                    } finally {
                        inFlightTurns.decrementAndGet();
                    }
                });
            } catch (RuntimeException e) {
                // The task never started, so runTurnLoop's finally will never run: this is the only place that can undo
                // the increment and the lease. Leaving either behind is worse than the rejection itself — a stuck
                // inFlightTurns makes every subsequent closeGracefully time out, and a lease nobody renews pins the
                // session to this node until it expires. Releasing here is safe precisely because no turn is
                // executing; the gate goes back in the finally below, which is what stops the session from being
                // refused forever.
                inFlightTurns.decrementAndGet();
                if (idem.acquiredKey != null) {
                    safeCompareAndReset(idem.acquiredKey, reserverId);
                }
                returnLeaseIfUnowned(convId, held);
                throw e;
            }
            turnStarted = true;
            final SubmitDisposition outcome = SubmitDisposition.executedLocally(turnId, future);
            recordOutcome(outcome);
            return outcome;
        } finally {
            if (!turnStarted) {
                endTurnAndSettleLeaseReturn(convId);
            }
        }
    }

    /**
     * Decides whether this node runs the submitted turn, and under which lease. Called with the session's turn
     * gate
     * held, so the answer cannot go stale between the decision and the turn.
     *
     * @return the lease to run under — the session's existing one when this node is already the holder, a freshly
     *         claimed one otherwise — or {@code null} when the session belongs to a peer and the submission must
     *         be
     *         forwarded
     */
    private HeldLease holdOrClaim(SessionId convId, String requestedAgent, IdempotencyDecision idem,
            long acquireStart) {
        final HeldLease reused = heldLease(convId);
        if (reused != null) {
            // No claim, and so no binding work either: the claim that won this lease already validated the requested
            // agent against the record and wrote it if the session was unbound. Nothing can have changed it since,
            // because only a holder may write it and this node has been the holder throughout. submit's advisory
            // precheckAgentBinding is therefore authoritative on this path, not merely advisory.
            safeMetric(() -> metrics.onLockAcquireSucceeded(elapsedSince(acquireStart)), "onLockAcquireSucceeded");
            return reused;
        }
        final ClaimResult claimed;
        try {
            // The bare nodeId, not the per-attempt reserverId: exclusion comes from the fencing token, which the
            // backend
            // mints fresh per acquisition, so a node-wide holder id costs nothing there —
            // InMemorySessionLeaseStore
            // and both SQL backends admit a second acquire from the same node exactly as they would a different node's,
            // on expiry alone. What it buys is that a turn, a drain pass and a delete on this node are
            // indistinguishable
            // by holder id, which is the premise "am I the holder?" needs.
            claimed = store.claim(convId, requestedAgent, nodeId, lockLease);
        } catch (RuntimeException e) {
            if (idem.acquiredKey != null) {
                safeCompareAndReset(idem.acquiredKey, idem.reserverId);
            }
            throw e;
        }
        final Duration acquireLatency = elapsedSince(acquireStart);
        if (claimed instanceof ClaimResult.HeldElsewhere) {
            safeMetric(() -> metrics.onLockAcquireRejected(acquireLatency), "onLockAcquireRejected");
            return null;
        }
        // The election succeeded in both remaining branches — AgentConflict won the lease and handed it straight back —
        // so the latency is reported the same way for both.
        safeMetric(() -> metrics.onLockAcquireSucceeded(acquireLatency), "onLockAcquireSucceeded");
        if (claimed instanceof ClaimResult.AgentConflict conflict) {
            // The pre-check above missed it: either a peer wrote the binding in between, or this is the session's
            // first turn and two nodes raced for it. Only one can win, and claim has already returned this node's
            // lease.
            // The reservation has to go back too — unlike the HeldElsewhere branch there is no inbox message to inherit
            // it, and leaving it IN_FLIGHT would make the client's retry collapse onto a turn that will never run.
            if (idem.acquiredKey != null) {
                safeCompareAndReset(idem.acquiredKey, idem.reserverId);
            }
            throw new ConflictingAgentException(convId, conflict.getRequestedAgentRef(), conflict.getBoundAgentRef());
        }
        return installLease(convId, ((ClaimResult.Acquired) claimed).getLease());
    }

    /**
     * The whole "this node is not going to run it" path: hand the reservation to whoever does, queue the message,
     * report
     * the outcome.
     */
    private SubmitDisposition forwardToInbox(SubmitRequest request, TurnId turnId, IdempotencyDecision idem) {
        if (idem.acquiredKey != null) {
            // Hand the reservation over rather than dropping it. deliverToInbox carries the same key into the
            // InboundMessage, and the holder calls markDone on it when it drains the message — but markDone only
            // updates an entry that still exists, so deleting the key here would both leave the result uncached and let
            // a client retry during the queue wait be treated as a first arrival and execute twice. The holder is
            // cleared because no node is executing this turn yet; see IdempotencyStore#releaseHolder.
            safeReleaseHolder(idem.acquiredKey, idem.reserverId);
        }
        final SubmitDisposition outcome = deliverToInbox(request, turnId);
        recordOutcome(outcome);
        return outcome;
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    /**
     * Reject a submission whose {@code agentRef} disagrees with the session's persisted binding, before any
     * reservation or election work is done.
     *
     * <p>
     * Advisory, not authoritative — {@code SessionStore#claim} is what actually settles the binding, and it does
     * so
     * while holding the lease. This read exists for the submissions that never reach {@code claim}'s verdict: one that
     * loses the election is forwarded to the inbox, and without a pre-check a conflicting {@code agentRef} would sit
     * there
     * until some holder drained it and refused it, long after this call returned a perfectly healthy-looking
     * disposition.
     * Failing here turns that into an immediate {@link ConflictingAgentException} for the caller.
     *
     * <p>
     * Unlike the cache this replaces, the read is unconditional. The cache made the common case cheaper but had to be
     * invalidated from four places, and a stale positive entry outliving a peer's {@code deleteSession} was enough
     * to
     * reject every later submit on this node with a conflict that no longer existed. One record read per submit is not
     * worth that.
     *
     * @param sessionId
     *            the session being submitted to
     * @param requestedAgentRef
     *            the agent the submission names
     */
    private void precheckAgentBinding(SessionId sessionId, String requestedAgentRef) {
        final Optional<String> bound = store.load(sessionId).flatMap(SessionRecordView::getAgentRef);
        if (bound.isPresent() && !bound.get().equals(requestedAgentRef)) {
            throw new ConflictingAgentException(sessionId, requestedAgentRef, bound.get());
        }
    }

    private void recordOutcome(SubmitDisposition outcome) {
        safeMetric(() -> metrics.onSubmitOutcome(outcome.getKind()), "onSubmitOutcome");
    }

    private static void safeMetric(Runnable hook, String name) {
        try {
            hook.run();
        } catch (Exception e) {
            log.warn("SessionMetrics.{} threw: {}", name, e.toString());
        }
    }

    /**
     * Best-effort idempotency cleanup. The store is a remote dependency (e.g., Redis), so a failing
     * {@code compareAndReset} must not shadow the original error or leak a {@link CompletableFuture}. Callers must
     * complete the user-visible future before invoking this helper.
     */
    private void safeCompareAndReset(String key, String reserverId) {
        try {
            idempotencyStore.compareAndReset(key, reserverId);
        } catch (Exception e) {
            log.warn("idempotencyStore.compareAndReset threw for key {}: {}", key, e.toString());
        }
    }

    /**
     * Best-effort hand-off of a reserved key to the node that will actually run the turn. Best-effort for the same
     * reason as {@link #safeCompareAndReset}: a store outage must not stop the forward, which is the part the caller is
     * waiting on. Losing the hand-off degrades to the pre-existing behaviour — a retry may execute twice and the result
     * is not replayable — rather than failing the submit.
     */
    private void safeReleaseHolder(String key, String reserverId) {
        try {
            idempotencyStore.releaseHolder(key, reserverId, idempotencyForwardTtl);
        } catch (Exception e) {
            log.warn("idempotencyStore.releaseHolder threw for key {}: {}", key, e.toString());
        }
    }

    /**
     * {@link #safeReleaseHolder}'s counterpart on the receiving side: put this node's name on the reservation of a
     * message it has collected and is about to run, and bind it into {@code held}'s touch slot so the lease renewer
     * keeps it alive.
     *
     * <p>
     * Without this a forwarded turn is the one kind that dies unannounced. {@code forwardToInbox} clears the holder so
     * the message can wait in the inbox without the holder-loss sweeper mistaking a healthy queued turn for a lost one
     * — but that also means nothing names the node that eventually runs it, and {@code findStaleInFlight} only reports
     * entries that name someone. So a node that crashed mid-turn on a <em>drained</em> message produced no
     * {@code HOLDER_LOST}, and its caller waited out the whole {@code idempotencyForwardTtl} for an answer nobody was
     * going to give. Re-arming the entry on the secondary TTL here is what makes that death look like any other: the
     * touches stop, the sweeper sees it, and the caller is failed in seconds rather than minutes.
     *
     * <p>
     * <b>A message whose take-over fails still runs.</b> It is already out of the at-most-once inbox — no successor
     * will ever collect it — so refusing it here would destroy work that nothing else can recover, which is the same
     * reasoning that keeps {@link #drain} going after a turn throws. There are <b>four</b> ways to lose, and none of
     * them is a reason not to run the message: a {@code DONE} entry is somebody's cached answer, one with a holder is
     * an attempt executing elsewhere, an absent or lapsed one is a reservation whose forward has already given up,
     * and the store can simply throw.
     *
     * <p>
     * <b>Those four are not one outcome.</b> The first three are the store <em>answering</em>, and the answer is that
     * the entry is not this caller's — {@link Takeover#refused()}. The fourth is the store saying nothing at all, and
     * treating silence as a refusal is what {@link Takeover#unknown()} exists to prevent: a caller that could not read
     * the entry has learned nothing about who owns it, so it keeps doing what it did before this method existed
     * (see {@link #announceTurnResult}, which is where the distinction is spent).
     *
     * <p>
     * What losing costs is stated precisely, because two things ride on the take-over rather than one. The turn then
     * runs against an entry this node is not named on, so <b>the holder-loss sweeper cannot see this node die for it
     * </b> — the caller falls back to the forward deadline, exactly where every drained message was before this method
     * existed. And <b>on a refusal only</b>, its result is withheld from the idempotency cache, because writing over
     * an entry the store has just identified as somebody else's is the harm rather than the safety. The caller is
     * still answered over the rail in every case. So the invariant this method restores holds only where it wins: a
     * turn may still run holderless, and {@link IdempotencyStore#findStaleInFlight}'s exclusion of holderless entries
     * does cost coverage for exactly those turns.
     *
     * <p>
     * The opening submission of a pass never reaches here — {@link #drain} skips it, since its entry already names
     * this node from submit time and the take-over could only refuse it.
     *
     * @param convId
     *            the session being drained, for logging
     * @param key
     *            the message's idempotency key, or {@code null} when it has none
     * @param held
     *            the lease whose touch slot the reservation is bound into on success
     * @return what this attempt learned (never null)
     */
    private Takeover takeOverReservation(SessionId convId, String key, HeldLease held) {
        if (key == null) {
            return Takeover.notAttempted();
        }
        // Same shape as submit's, and per-attempt for the same reason: it is the identity the renewer touches with and
        // the sweeper resets against, so two attempts at one key must never share one.
        final String reserverId = nodeId + "/" + Thread.currentThread().getName() + "/" + turnSeq.incrementAndGet();
        final boolean taken;
        try {
            taken = idempotencyStore.acquireHolder(key, reserverId, idempotencySecondaryTtl);
        } catch (Exception e) {
            // Best-effort like every other idempotency call on this path: a store outage must cost the turn its fast
            // failure detection, not cost it its execution — and not cost it its cached result either, which is why
            // this is unknown() rather than refused().
            log.warn("idempotencyStore.acquireHolder threw for key {} on session {}: {}", key, convId, e.toString());
            return Takeover.unknown();
        }
        if (!taken) {
            return Takeover.refused();
        }
        held.getTouchSlot().bind(key, reserverId);
        return Takeover.won(reserverId);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Session-lifetime lease and the turn gate (design §7.4)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Claims the right to run work on {@code sessionId} on this node.
     *
     * @return {@code false} when a turn, a drain pass or a delete is already running here, in which case the caller
     *         must
     *         touch neither the session nor the lease, and must not call {@link #endTurn}
     */
    private boolean tryBeginTurn(SessionId sessionId) {
        return activeTurns.add(sessionId);
    }

    /** Releases the gate taken by a successful {@link #tryBeginTurn}. */
    private void endTurn(SessionId sessionId) {
        activeTurns.remove(sessionId);
    }

    /**
     * Releases the gate and then honors any lease return that was declined while this thread held it.
     *
     * <p>
     * Every gate holder that touches the lease releases through here rather than through {@link #endTurn} directly.
     * The re-read has to happen <em>after</em> the release, because {@link #returnLeaseWhenIdle} takes the gate itself:
     * checking first would find the mark and then decline on the gate this thread has not let go of yet.
     *
     * <p>
     * This does not recurse without bound. A second pass can only be triggered by a mark published while this thread
     * held the gate, and the pass clears the mark as its first act inside the gate; a mark published after that races
     * a gate this thread no longer holds and is honored by its own caller. Once the lease is gone the first guard in
     * {@link #returnLeaseWhenIdle} returns immediately, so the worst case is one extra pass that finds nothing.
     */
    private void endTurnAndSettleLeaseReturn(SessionId sessionId) {
        endTurn(sessionId);
        if (leaseReturnPending.contains(sessionId)) {
            returnLeaseWhenIdle(sessionId);
        }
    }

    /**
     * The live session a stop request has to aim at on this node, or {@code null} when there is none.
     *
     * <p>
     * Prefers the running turn's own session over the cached entry because that is the case the cache gets wrong: a
     * mid-turn eviction removes the entry while the turn keeps running on the session it pinned (see
     * {@link #turnSessions}). The two are the same object whenever both are present — the turn pinned the entry the
     * cache is handing back — so the order only decides which one answers, never which session gets interrupted.
     *
     * @param sessionId
     *            the session to find a live session for
     * @return the session, or {@code null} when this node has neither a running turn nor a cached entry
     */
    private LiveSession localSession(SessionId sessionId) {
        final LiveSession running = turnSessions.get(sessionId);
        if (running != null) {
            return running;
        }
        return sessionCache.peek(sessionId).map(SessionEntry::getSession).orElse(null);
    }

    /**
     * Stops whatever is running on {@code sessionId} here, session-wide.
     *
     * @return {@code true} when there was a live session to interrupt, so a caller that needs to wait for the turn to
     *         unwind knows whether it has anything to wait for
     */
    private boolean interruptLocal(SessionId sessionId, InterruptReason reason) {
        final LiveSession session = localSession(sessionId);
        if (session == null) {
            return false;
        }
        safeInterrupt(session, reason);
        return true;
    }

    /**
     * Stops one named turn on {@code sessionId} here, leaving a turn that has already moved on untouched.
     *
     * @return {@code true} when there was a live session to interrupt; the interrupt may still be dropped by the
     *         session itself when the named turn is no longer the active one
     */
    private boolean interruptLocal(SessionId sessionId, TurnId turnId, InterruptReason reason) {
        final LiveSession session = localSession(sessionId);
        if (session == null) {
            return false;
        }
        safeInterrupt(session, turnId, reason);
        return true;
    }

    /**
     * The lease this node holds for {@code sessionId}, or {@code null} when it holds none it may still use.
     *
     * <p>
     * A lease whose renewal was refused is reported as absent but deliberately left in the map: removing it is the
     * returning path's job, and a reader that removed it too would race that path into releasing twice.
     */
    private HeldLease heldLease(SessionId sessionId) {
        final HeldLease held = heldLeases.get(sessionId);
        return held != null && held.isUsable() ? held : null;
    }

    /**
     * Takes ownership of a freshly won lease: records it as this node's, then starts renewing it.
     *
     * <p>
     * The renewal is attached afterwards rather than passed in because its failure callback has to name the lease it is
     * failing for — {@link #onLeaseLost} must not interrupt a turn that is by then running under the <em>next</em>
     * lease
     * for the same session.
     */
    private HeldLease installLease(SessionId sessionId, SessionLease lease) {
        final HeldLease held = new HeldLease(lease, new IdempotencyTouchSlot(idempotencyStore));
        heldLeases.put(sessionId, held);
        held.attachRenewal(leaseRenewer.start(lease, held.getTouchSlot(), () -> onLeaseLost(sessionId, held)));
        return held;
    }

    /** Hands {@code held} back to the cluster, if this call is the one that gets to. Idempotent. */
    private void returnLease(SessionId sessionId, HeldLease held) {
        if (!held.beginReturn()) {
            return;
        }
        // Value-based removal: a newer lease for the same session has to survive this. It cannot exist yet on any
        // path that holds the gate, but the lease-lost path does not hold it, and a map that forgot the live lease
        // would
        // leave it renewed by nobody and the session held by a node that no longer knows it.
        heldLeases.remove(sessionId, held);
        try {
            store.release(held.getLease());
        } catch (Exception e) {
            log.warn("Lease release threw for session {}: {}", sessionId, e.toString());
        }
    }

    /**
     * Returns {@code held} unless a live session is still legitimately holding it. Caller must hold the turn gate.
     *
     * <p>
     * The rule the whole lease lifetime reduces to: a lease exists to protect one session's writes, so it goes back the
     * moment there is no session left to protect — and not one moment before.
     */
    private void returnLeaseIfUnowned(SessionId sessionId, HeldLease held) {
        if (held.isUsable() && sessionCache.peek(sessionId).isPresent()) {
            return;
        }
        returnLease(sessionId, held);
    }

    /**
     * The {@link LiveSessionCache} close listener: the live session that was holding this session is gone, so the
     * cluster gets its lease back. This is the path that returns the lease on idle-TTL and LRU eviction, on
     * {@code releaseSession}, on a remote {@code EVICT}, and on shutdown — none of which knows a lease exists.
     *
     * <p>
     * Also the one lease path that has to take the turn gate itself, and the only one allowed to walk away without
     * finishing the job. Declining is safe rather than a leak: a turn holding the gate has either just taken this lease
     * over legitimately — the close it is racing is the eviction of an entry it no longer uses — or will apply the same
     * rule before the gate is free again, because {@link #leaseReturnPending} is marked first and the gate is released
     * through {@link #endTurnAndSettleLeaseReturn}.
     */
    private void returnLeaseWhenIdle(SessionId sessionId) {
        if (heldLeases.get(sessionId) == null) {
            leaseReturnPending.remove(sessionId);
            return;
        }
        // Published before the gate is attempted and cleared once inside it, so a holder on its way out either sees
        // the mark or loses the gate to this thread. The interleaving where neither happens — the ask arriving after
        // the holder's lease decision and before its endTurn — is the one this ordering removes. Both happening costs
        // one extra pass that finds nothing to return.
        leaseReturnPending.add(sessionId);
        if (!tryBeginTurn(sessionId)) {
            log.debug("Lease return for session {} left to the running turn", sessionId);
            return;
        }
        try {
            leaseReturnPending.remove(sessionId);
            final HeldLease held = heldLeases.get(sessionId);
            if (held != null) {
                returnLeaseIfUnowned(sessionId, held);
            }
        } finally {
            endTurnAndSettleLeaseReturn(sessionId);
        }
        // A message may have arrived while every node that heard the doorbell found this session held. Now that it
        // is not, ring again — this is what makes a submission that lost the election to a dying lease converge.
        rerunDoorbellIfRung(sessionId);
    }

    /**
     * Gives a session up because a peer asked for it: drop the live session and, once no turn is using it, hand the
     * lease
     * back.
     *
     * <p>
     * Before Stage 3b a peer only had to <em>wait</em>. It published {@code INTERRUPT(SESSION_RELEASED)}, the holder's
     * turn stopped, and the turn's own {@code finally} released the lease; the peer's next retry took it. Now that the
     * lease belongs to the session, a stopped turn gives up nothing — a peer's {@code deleteSession} would
     * broadcast
     * its yield request to a holder that complied immediately and still spend its entire budget waiting, then fail.
     * Hence the request has its own kind, {@link SessionSignal.SignalKind#YIELD}: "stop the turn" and "give the
     * session up" are different asks, and only the second one ends with this method.
     *
     * <p>
     * Eviction is the whole mechanism: it closes the session, and the close is what returns the lease. A pinned entry
     * defers that close to the running turn's unpin, so a yield never closes a session mid-turn — it costs the peer the
     * remainder of one turn, which is the shortest correct answer available.
     *
     * <p>
     * The explicit return afterwards covers the case eviction cannot: a lease held with no cached session, where
     * nothing
     * closes and so nothing would fire.
     */
    private void yieldSession(SessionId sessionId) {
        sessionCache.evict(sessionId);
        returnLeaseWhenIdle(sessionId);
    }

    /**
     * A renewal was refused: the backend has given this session to somebody else. Stop the local turn, drop the
     * session, hand the dead lease back.
     *
     * <p>
     * Marking it lost first is what stops the interrupted turn's own {@code finally} — or a submission arriving in the
     * same millisecond — from reusing a lease this node no longer owns.
     *
     * <p>
     * The interrupt goes through {@link #interruptLocal} rather than the cache because the turn this has to stop is
     * exactly the turn the cache can lose track of. A session evicted mid-turn — a peer's yield, an LRU drop — leaves
     * the map while the turn runs on, so looking the victim up by cache entry would drop {@code LEASE_LOST} in the one
     * state where it is not advisory: a turn still writing history under a lease the backend has already reassigned.
     */
    private void onLeaseLost(SessionId sessionId, HeldLease held) {
        held.markLost();
        interruptLocal(sessionId, InterruptReason.LEASE_LOST);
        // Everything above is a flag write and a non-blocking signal, so it stays on the renewal thread where the
        // ordering above is guaranteed. Everything below is handed off, because none of it is bounded: evicting closes
        // the session, closing fires OnSessionEnd hooks — arbitrary deployment code, a shell command as easily as
        // anything — and the return that follows is another round-trip to the store. Run here, one session's teardown
        // hook would hold a renewal thread for its whole duration and every session sharing that thread would lose its
        // lease behind it: a single bad hook turning one lost lease into a node-wide cascade of them.
        dispatchLeaseTeardown(sessionId, () -> {
            // Evicting closes the session, and the close is what returns the lease — except when the entry is pinned by
            // the turn we just interrupted (the close, and so the return, is deferred to its unpin) or when there is no
            // cached entry at all (nothing closes, so nothing fires). The explicit attempt below covers the second
            // case.
            sessionCache.evict(sessionId);
            returnLeaseWhenIdle(sessionId);
        });
    }

    /**
     * Runs the blocking half of a lost lease's teardown somewhere that is not the renewal pool.
     *
     * <p>
     * {@link #turnExecutor} rather than {@link #scheduler}: it is unbounded, so a teardown never waits on a queue, and
     * a hook that blocks for a minute costs one thread instead of a share of the pool that also carries the idle sweep,
     * the holder-loss sweep, STATUS heartbeats and every forwarded-turn poll.
     *
     * <p>
     * The rejection fallback runs the work inline, accepting the stall the hand-off exists to avoid. Rejection means
     * {@code closeGracefully} has already shut the executor down, and at that point the alternative to a late renewal
     * tick is a lease that is never handed back at all — {@code closeAll} has been and gone, so nothing else will do
     * it.
     */
    private void dispatchLeaseTeardown(SessionId sessionId, Runnable teardown) {
        final Runnable guarded = () -> {
            try {
                teardown.run();
            } catch (Exception e) {
                log.warn("Lease teardown threw for session {}: {}", sessionId, e.toString());
            }
        };
        try {
            turnExecutor.execute(guarded);
        } catch (RejectedExecutionException e) {
            log.debug("Lease teardown for session {} ran inline: executor is shutting down", sessionId);
            guarded.run();
        }
    }

    /**
     * Runs every turn this node is the holder for: the submitted one plus whatever the inbox hands over while the lease
     * is
     * held.
     *
     * @param request
     *            the submission that won the lease
     * @param turnId
     *            the id issued for {@code request} in {@link #submit}; stamped onto the self-message so the turn the
     *            caller was told about is the turn that actually runs. Queued messages carry their own id.
     * @param held
     *            the session lease this turn runs under — either won for it or inherited from the session's
     *            previous
     *            turn. <b>Not</b> released here: it lives as long as the session does (design §7.4), and the one case
     *            this method has to put it back is the one where no session ever took it over.
     * @param idem
     *            the idempotency decision taken at submit time
     * @param future
     *            completed with the result of {@code request}'s own turn
     */
    private void runTurnLoop(SubmitRequest request, TurnId turnId, HeldLease held, IdempotencyDecision idem,
            CompletableFuture<AgentExecutionResult> future) {
        final SessionId convId = request.getSessionId();
        final String requestedAgent = request.getAgentRef();
        ScheduledFuture<?> statusTask = null;
        SessionEntry entry = null;
        // Declared out here so the failure path can see what it is about to lose: whatever is still in this queue has
        // already been taken out of the at-most-once inbox.
        final Deque<InboundMessage> pendingQueue = new ArrayDeque<>();

        try {
            // This node holds the lease, so it is the one that must act on INTERRUPT and EVICT. Arm the rail before the
            // session opens rather than waiting for a client to call events(): the two are independent, and a turn
            // nobody is streaming still has to be stoppable. Non-fatal on failure — a control-plane outage should not
            // cost the caller their answer, though remote interrupts will not land until the next turn re-subscribes.
            ensureSubscribedQuietly(convId, "holding a turn");

            // Bound for this turn and cleared at the end of it. The renewal schedule belongs to the lease, which now
            // outlives the turn, so a schedule that captured the key would go on refreshing a finished reservation
            // while
            // the reservation of the turn actually running went stale under the holder-loss sweeper.
            held.getTouchSlot().bind(idem.acquiredKey, idem.reserverId);

            // No binding work here: the claim that produced this lease already validated the requested agent against
            // the
            // record and wrote it when the session was unbound, while holding the lease. This used to be a
            // validate-then-record pair at this point, which was the wrong place for it — the validation had already
            // happened once in submit, before the election, and the record could be written by a node that had not yet
            // earned the right to.
            final AgentRuntimeId agentRuntimeId = request.getContextDiscriminator()
                    .map(d -> AgentRuntimeId.fromName(requestedAgent, d))
                    .orElseGet(() -> AgentRuntimeId.fromName(requestedAgent));
            // Pinned, not merely opened: idle-TTL and LRU eviction run on a sweeper thread that cannot see this turn,
            // and closing the session underneath it corrupts the conversation history. A pinned session is never idle;
            // the size cap can still drop it from the map, but not close it. The pin is released in the finally block
            // below, which also lets such a deferred eviction complete.
            entry = sessionCache.acquire(convId, agentRuntimeId, request.getOptions(), request.getOpenAttributes());
            final LiveSession session = entry.getSession();
            // Published before the first message runs and withdrawn in the finally: from here until then, this session
            // has to be reachable by anything that needs to stop it even after an eviction takes the cache entry away.
            turnSessions.put(convId, session);

            if (statusBroadcastEnabled) {
                publishStatusSnapshot(convId, session);
                final long heartbeatMs = Math.max(1_000L, statusHeartbeatInterval.toMillis());
                statusTask = scheduler.scheduleAtFixedRate(() -> publishStatusSnapshot(convId, session), heartbeatMs,
                        heartbeatMs, TimeUnit.MILLISECONDS);
            }

            final List<InboundMessage> orphans = collectPending(convId);
            final InboundMessage selfMessage = InboundMessage.builder()
                    .id(InboundMessageId.of("local-" + UUID.randomUUID())).sessionId(convId).agentRef(requestedAgent)
                    .userInput(request.getUserInput()).priority(request.getPriority()).turnId(turnId)
                    .idempotencyKey(request.getIdempotencyKey().orElse(null)).initiator(request.getInitiator())
                    .deliveredAt(Instant.now()).submitOptions(request.getSubmitOptions()).build();
            final List<InboundMessage> initial = new ArrayList<>(orphans.size() + 1);
            initial.addAll(orphans);
            initial.add(selfMessage);
            sortByPriorityThenFifo(initial);
            pendingQueue.addAll(initial);

            final DrainOutcome outcome = drain(convId, requestedAgent, session, pendingQueue, selfMessage,
                    idem.reserverId, held);

            if (outcome.selfFailure != null) {
                future.completeExceptionally(outcome.selfFailure);
                if (idem.acquiredKey != null) {
                    safeCompareAndReset(idem.acquiredKey, idem.reserverId);
                }
            } else if (outcome.selfResult != null) {
                future.complete(outcome.selfResult);
            } else {
                future.completeExceptionally(
                        new IllegalStateException("Turn loop terminated without producing a result"));
            }
        } catch (RuntimeException e) {
            log.warn("Turn loop failed for session {}: {}", convId, e.toString());
            // Always resolve the user-visible future first; idempotency cleanup must not leak it on transient
            // backend failures.
            future.completeExceptionally(e);
            if (idem.acquiredKey != null) {
                safeCompareAndReset(idem.acquiredKey, idem.reserverId);
            }
            failUndrained(convId, pendingQueue, TurnResultPayload.Failure.Code.FAILED,
                    "holder failed before this message could run: " + e);
        } finally {
            if (entry != null) {
                // Value-based so a later turn's registration is never removed by this one's exit.
                turnSessions.remove(convId, entry.getSession());
            }
            if (statusTask != null) {
                statusTask.cancel(false);
            }
            // Nothing left to keep alive under the secondary TTL: this turn's reservation is either done or reset, and
            // the lease's renewal schedule outlives the turn.
            held.getTouchSlot().clear();
            if (entry != null) {
                // Final snapshot while still the lease holder, so remote projections converge to the post-turn (IDLE)
                // state instead of lingering on the last in-flight RUNNING snapshot. Still before unpin, so the session
                // is guaranteed alive here even if it was evicted mid-turn.
                publishStatusSnapshot(convId, entry.getSession());
                // Inside the gate, and before the lease decision below. Unpinning is what performs a mid-turn
                // eviction's deferred close, so until it returns this session may still have a live session — and
                // once it returns, whether one is left is exactly what decides the lease.
                entry.unpin();
            }
            // Deliberately inside the gate, on every exit. This is the only path that hands the lease back when the
            // session never opened or was evicted mid-turn, and releasing it after endTurn would let the next
            // submission reuse it, miss the cache, and open a second live session for this session while the first
            // one is still closing — two live sessions writing one history.
            //
            // A deferred close performed by the unpin above already tried this same route through
            // returnLeaseWhenIdle and declined, because this thread holds the gate. That is not a leak: this is the
            // rule it declined to apply, applied by the thread it left it to. A close that lands after this line is
            // too late to be covered that way, which is what endTurnAndSettleLeaseReturn exists to catch.
            returnLeaseIfUnowned(convId, held);
            endTurnAndSettleLeaseReturn(convId);
            rerunDoorbellIfRung(convId);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Holder-side drain (design §7.1 F3/F4/F6)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Runs every message in {@code pendingQueue} as its own serialized turn, re-collecting from the inbox after each
     * one
     * until nothing is left.
     *
     * <p>
     * <b>{@code submitAsync}, never {@code offerAsync}.</b> Injecting a queued message into the running turn would be
     * cheaper, but {@code offerAsync} answers with a {@code SubmitOutcome} whose result stage is null when the input is
     * merely queued — a mid-turn injected message can never produce an {@link AgentExecutionResult} of its own, so the
     * future the submitting node handed its caller could never be closed. Priority therefore governs queue
     * <em>position</em> here and nothing else; mid-turn injection stays exclusive to the local {@code offerAsync} path,
     * whose caller knowingly gets no result stage.
     *
     * <p>
     * A turn that throws is terminal for its own message only. The pass continues, because every sibling message has
     * already been removed from the at-most-once inbox and aborting would silently destroy them.
     *
     * @param convId
     *            the session being drained
     * @param boundAgent
     *            the {@code agentRef} the open session belongs to; messages naming a different one are refused
     * @param session
     *            the pinned session to run turns on
     * @param pendingQueue
     *            messages to run, already priority-sorted; drained in place
     * @param selfMessage
     *            the submission that opened this pass, or {@code null} when draining purely on a peer's behalf
     * @param selfReserverId
     *            the identity {@code selfMessage}'s reservation is already held under — minted at submit time and
     *            bound into the touch slot by {@link #runTurnLoop} — or {@code null} when it carried no key. This pass
     *            cannot take that reservation over (it is held, so {@link #takeOverReservation} refuses it), so
     *            without being told, the one message whose result the caller is waiting on is the one this pass would
     *            decline to cache.
     * @param held
     *            the lease this pass runs under, whose idempotency touch slot each message's reservation is bound into
     *            for as long as that message is executing
     * @return which result (or failure) belongs to {@code selfMessage} (never null)
     */
    private DrainOutcome drain(SessionId convId, String boundAgent, LiveSession session,
            Deque<InboundMessage> pendingQueue, InboundMessage selfMessage, String selfReserverId, HeldLease held) {
        AgentExecutionResult selfResult = null;
        Throwable selfFailure = null;

        while (!pendingQueue.isEmpty()) {
            if (shutdownForced.get()) {
                // The grace window is over: closeGracefully has stopped waiting and interrupted the sessions, so a turn
                // started now would be torn down mid-flight and reported as though its input had failed. Starting is
                // refused instead — but refusing is not enough on its own, because these messages are already out of
                // the at-most-once inbox and no successor will ever collect them. Their submitters are told the node
                // stopped being the holder, which is the one answer that distinguishes "resubmit this, it never ran"
                // from a genuine failure.
                final IllegalStateException notHolder = new IllegalStateException("node " + nodeId
                        + " stopped holding session " + convId.value() + " before this message could run");
                if (selfMessage != null && selfFailure == null && pendingQueue.contains(selfMessage)) {
                    selfFailure = notHolder;
                }
                failUndrained(convId, pendingQueue, TurnResultPayload.Failure.Code.NOT_HOLDER, notHolder.getMessage());
                break;
            }
            final InboundMessage next = pendingQueue.poll();
            // One relay per turn, not per loop: a relay carries exactly one TurnId, so a shared one would stamp every
            // queued turn's frames with the first turn's id — precisely the mis-attribution the stamp exists to
            // prevent. A queued message written by an older build carries no id, so mint one; the frames are then
            // self-consistent even though the submitter never learned that id.
            final TurnId messageTurnId = next.getTurnId().orElseGet(TurnId::generate);
            final String idempotencyKey = next.getIdempotencyKey().orElse(null);

            if (!next.getAgentRef().equals(boundAgent)) {
                rejectConflictingMessage(convId, next, boundAgent, messageTurnId, idempotencyKey);
                continue;
            }

            // After the agent check and before the turn: from here on this node is the one executing that
            // reservation, so it is the one whose death has to be visible. Not attempted for the submission that
            // opened this pass — its entry already names this node, so the take-over could only refuse it — and see
            // takeOverReservation for the three verdicts the attempt itself can come back with.
            final Takeover takeover = next == selfMessage
                    ? Takeover.alreadyHeldBySubmit(selfReserverId)
                    : takeOverReservation(convId, idempotencyKey, held);
            // Non-null only when this pass won the take-over: the binding to undo and the reservation to reset are
            // this loop's, whereas the opening submission's belong to runTurnLoop for the length of the whole pass.
            final String takenReserverId = takeover.takenReserverId();

            AgentExecutionResult turnResult = null;
            RuntimeException turnFailure = null;
            try {
                try (SessionEventRelay relay = new SessionEventRelay(convId, messageTurnId, eventPublisher, signalBus,
                        nodeId, relayDispatcher)) {
                    turnResult = session.submitAsync(messageTurnId, next.getUserInput(), next.getSubmitOptions(), relay)
                            .toCompletableFuture().join();
                } catch (RuntimeException e) {
                    turnFailure = e;
                }

                if (turnResult != null) {
                    announceTurnResult(convId, messageTurnId, idempotencyKey, takeover.mayCacheResult(), turnResult);
                    if (next == selfMessage && selfResult == null) {
                        selfResult = turnResult;
                    }
                } else {
                    log.warn("Turn {} on session {} failed: {}", messageTurnId, convId, turnFailure.toString());
                    if (takenReserverId != null) {
                        // announceTurnFailure frees the key with discardReservation, which by contract refuses an
                        // entry that has a holder — and the take-over is what gave this one a holder. Same outcome,
                        // matched on the identity actually recorded: the entry goes, so the client's retry re-executes
                        // instead of collapsing onto a dead attempt. Before the announcement for the reason stated
                        // there — a retry that arrives the instant it is failed must find the key already free.
                        safeCompareAndReset(idempotencyKey, takenReserverId);
                    }
                    announceTurnFailure(convId, messageTurnId, idempotencyKey, TurnResultPayload.Failure.Code.FAILED,
                            String.valueOf(turnFailure));
                    if (next == selfMessage && selfFailure == null) {
                        selfFailure = turnFailure;
                    }
                }
            } finally {
                // After the announcement, not before: until markDone or the reset above lands, the entry is still an
                // IN_FLIGHT one naming this node, and an unbound one of those is what the sweeper reads as a lost
                // holder. Unbinding only this key leaves the opening submission's own binding — which must survive the
                // whole pass — in place.
                if (takenReserverId != null) {
                    held.getTouchSlot().unbind(idempotencyKey);
                }
            }

            if (!acceptingSubmits.get()) {
                // Draining: take nothing *new* out of the at-most-once inbox. Collecting here would make this node the
                // only one that can run those messages at the moment it is least able to, and would let each re-collect
                // stretch the shutdown by another turn. Left in the inbox they stay every peer's to run, and the
                // doorbell this pass's finally re-rings is what tells a peer to look — see handOverForDrain.
                continue;
            }
            final List<InboundMessage> extra = collectPending(convId);
            if (!extra.isEmpty()) {
                final List<InboundMessage> merged = new ArrayList<>(pendingQueue.size() + extra.size());
                merged.addAll(pendingQueue);
                merged.addAll(extra);
                sortByPriorityThenFifo(merged);
                pendingQueue.clear();
                pendingQueue.addAll(merged);
            }
        }
        return new DrainOutcome(selfResult, selfFailure);
    }

    /**
     * Drains messages for a session this node was not already running: the doorbell path (design §7.1 F3).
     *
     * <p>
     * The gap this closes is narrow and permanent without it. A submission that loses the election is picked up by the
     * holder's post-turn re-collect — unless it lands after that re-collect and before the holder releases the lease,
     * in
     * which case the holder is gone and no other node has any reason to look. Nobody would collect the message until
     * the
     * session's next submission, which may never come.
     *
     * <p>
     * This is a genuine holder for the duration: the lease is renewed and the session is pinned exactly as for a
     * submitted turn. It is also a genuine <em>session</em> holder afterwards — a drain pass that opens a session keeps
     * the lease for it, so the session's next submission lands on a node that already holds it.
     *
     * @param convId
     *            the session to drain
     * @param held
     *            the session lease to drain under, and the turn gate: this method releases the gate in its
     *            {@code finally}, and the lease only if no session took it over
     */
    private void runDrainOnly(SessionId convId, HeldLease held) {
        SessionEntry entry = null;
        final Deque<InboundMessage> pendingQueue = new ArrayDeque<>();

        try {
            ensureSubscribedQuietly(convId, "draining the inbox");

            final List<InboundMessage> initial = new ArrayList<>(collectPending(convId));
            if (initial.isEmpty()) {
                // The common case: several nodes heard the same doorbell, or the holder's own re-collect got there
                // first. Opening a session to run nothing would be pure cost.
                return;
            }
            sortByPriorityThenFifo(initial);
            pendingQueue.addAll(initial);

            // The session's existing binding decides which agent to open, not the first message's claim about it:
            // a message naming a different agent must be refused, not allowed to open a session under its own name.
            // An unbound session can still have a queued message — a holder that crashed between winning the lease
            // and recording the binding — so fall back to the first message and record it.
            //
            // This is why the pass acquires rather than claims. A claim would need an agentRef up front, and the only
            // candidate is inside a message that cannot be read before the session is held; a claim that then
            // answered AgentConflict would hand the lease back with those messages already out of the at-most-once
            // inbox.
            // Acquiring first, then asking the fenced record view to bind-if-unbound, is the same decision taken in an
            // order this path can actually follow: provision answers "who owns this" and "record my candidate if
            // nobody owns it yet" as one write, and it returns the binding that is actually there rather than the one
            // we offered.
            final InboundMessage first = pendingQueue.peek();
            final String boundAgent = store.records().provision(convId, first.getAgentRef()).getAgentRef()
                    .orElseThrow(() -> new IllegalStateException(
                            "Session " + convId + " is still unbound after provisioning it with a candidate."));

            // Which runtime of that agent is the message's to say, though — unlike which agent. Nothing durable records
            // a discriminator, so a holder that ignored the envelope's could only open the bare agent:<ref> runtime,
            // and that is a different runtime with different tools and hooks rather than a plainer version of the right
            // one (see InboundMessage#getContextDiscriminator). Read from the same message the binding candidate came
            // from, because they describe one submission.
            //
            // Skipped when that message names some other agent: drain refuses such a message below, and its
            // discriminator was issued against that agent's runtimes, so pairing it with this session's agent would
            // name a runtime nobody registered — an open failure in place of the orderly refusal.
            final Optional<String> discriminator = first.getAgentRef().equals(boundAgent)
                    ? first.getContextDiscriminator()
                    : Optional.empty();

            // LiveSessionOptions and OpenAttributes still do not travel — design §7.1 F2 leaves them out of the
            // envelope — so a drain-only open uses defaults for both. Like the discriminator they are read on cache
            // miss only, so this differs from the submitting node's intent solely when no node in the cluster had the
            // session open.
            entry = sessionCache.acquire(convId,
                    discriminator.map(d -> AgentRuntimeId.fromName(boundAgent, d))
                            .orElseGet(() -> AgentRuntimeId.fromName(boundAgent)),
                    LiveSessionOptions.defaults(), OpenAttributes.empty());
            final LiveSession session = entry.getSession();
            // A drain pass runs other nodes' messages, but it runs them here, so it is as interruptible as a submitted
            // turn and needs the same reachability after a mid-pass eviction. See runTurnLoop.
            turnSessions.put(convId, session);

            // No submission of this pass's own to bind into the lease's idempotency touch slot — every message here
            // is some other node's. drain binds each one's reservation for the length of its own turn, which is what
            // makes a crash mid-pass reach the waiting caller as HOLDER_LOST rather than as a forward-TTL timeout.
            drain(convId, boundAgent, session, pendingQueue, null, null, held);
        } catch (RuntimeException e) {
            log.warn("Inbox drain failed for session {}: {}", convId, e.toString());
            failUndrained(convId, pendingQueue, TurnResultPayload.Failure.Code.FAILED,
                    "holder failed before this message could run: " + e);
        } finally {
            // Symmetric with runTurnLoop, and defence in depth rather than a fix: drain's per-message finally already
            // unbinds everything a drain-only pass can bind. That is an invariant of code far from here, though, and
            // the cost of restating it is one call — without it, a binding added outside that finally would leak one
            // map entry per drained keyed message for the life of the lease, on a doorbell-only workload nothing else
            // would clear.
            held.getTouchSlot().clear();
            if (entry != null) {
                turnSessions.remove(convId, entry.getSession());
                publishStatusSnapshot(convId, entry.getSession());
                entry.unpin();
            }
            // The overwhelmingly common exit is the one where no session was opened at all: the inbox was already
            // empty, so there is nothing for this lease to protect. Handing it straight back is what keeps a doorbell
            // that everybody heard from parking the session on whichever node happened to lose the race.
            //
            // Unpin first, then this, then endTurn — see runTurnLoop's finally for why that order is load-bearing.
            returnLeaseIfUnowned(convId, held);
            endTurnAndSettleLeaseReturn(convId);
            rerunDoorbellIfRung(convId);
        }
    }

    /**
     * Collect the inbox, clearing the doorbell first.
     *
     * <p>
     * The order matters and only in this direction: clearing before the read means a message delivered between the two
     * leaves the doorbell set, costing at most one extra empty pass. Clearing after the read would erase the notice for
     * a message this pass never saw, and nothing would ring again.
     */
    private List<InboundMessage> collectPending(SessionId sessionId) {
        doorbellPending.remove(sessionId);
        // Answering the doorbell discharges any debt to relay it: whatever this collect returns is now this node's to
        // run or to fail, and what it does not return is not in the inbox for a peer to find either.
        doorbellRelayOwed.remove(sessionId);
        return inbox.collect(sessionId, QueuedInputPriority.LATER);
    }

    /**
     * Note that {@code sessionId}'s inbox has work, and try to drain it on a worker thread.
     *
     * <p>
     * Rung by the {@code MESSAGE_ENQUEUED} receiver and directly by {@link #deliverToInbox} — {@link #onSignal} drops
     * self-origin signals, so a node that publishes the doorbell never hears it, which on a single-node deployment
     * means
     * nobody does.
     */
    private void ringDoorbell(SessionId sessionId) {
        // Recorded before the drain check, not after: a doorbell that arrives while a turn is still finishing has to
        // survive until that turn's finally re-rings it, which is the path the hand-off below converges through.
        doorbellPending.add(sessionId);
        if (heldLease(sessionId) != null) {
            // This node is the holder, so answering this doorbell is its job — and passing it on, if it stops being the
            // holder first. Written here rather than inside the hand-off because the lease can go back in between (an
            // idle-TTL eviction, a peer's yield); by then nothing tells "I owed this one" from "never mine", and only
            // the first may relay.
            doorbellRelayOwed.add(sessionId);
        }
        if (!acceptingSubmits.get()) {
            handOverForDrain(sessionId);
            return;
        }
        try {
            turnExecutor.execute(() -> {
                inFlightTurns.incrementAndGet();
                try {
                    if (acceptingSubmits.get()) {
                        tryDrainOnce(sessionId);
                    } else {
                        // Draining started between the ring and this task. Same answer as the synchronous check above:
                        // do not start a pass that closeGracefully is about to tear down, hand the session over
                        // instead.
                        handOverForDrain(sessionId);
                    }
                } finally {
                    inFlightTurns.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            // The executor is already down, so there is no worker thread left to hand over on: do it here. By this
            // point closeGracefully is past waitForDrain, so this thread is a signal-receiving or session-closing one
            // and blocking it briefly on a lease release is the same cost the caller would have paid anyway.
            log.debug("Doorbell for session {} arrived after the turn executor shut down — handing over inline",
                    sessionId);
            handOverForDrain(sessionId);
        }
    }

    /**
     * Re-ring if a message arrived during the window between a pass's last collect and its lease release. Every node
     * that
     * heard the doorbell in that window found the session still held and gave up, so without this the message
     * waits
     * for
     * the session's next submission. Converges: the pass this schedules clears the doorbell before its own
     * collect,
     * so a spurious re-ring costs one empty pass and stops.
     */
    private void rerunDoorbellIfRung(SessionId sessionId) {
        if (doorbellPending.contains(sessionId)) {
            ringDoorbell(sessionId);
        }
    }

    /**
     * Give a session up because this node is draining, so a peer that is still serving traffic collects its inbox.
     *
     * <p>
     * Without this, a doorbell that arrives once {@code closeGracefully} has flipped {@link #acceptingSubmits} reaches
     * nobody at all — a worse outcome than the lease TTL the design worried about. The drain pass is skipped here, and
     * every peer that heard the same {@code MESSAGE_ENQUEUED} already found the session held by <em>this</em> node
     * and gave up. Nothing re-rings, so the message waits for the session's next submission — which may never come
     * —
     * while the origin's forward waits out the whole {@code idempotencyForwardTtl}.
     *
     * <p>
     * <b>Why a hand-off rather than the {@code NOT_HOLDER} answer design §7.3 asked for.</b> Failing the queued turn
     * would resolve the origin's forward, but the message is still <em>in</em> the inbox and would then be run by
     * whoever collects it next: a caller told its input never ran, retrying it, and a turn that runs anyway — a
     * duplicate execution invented by the very signal meant to prevent one. {@code NOT_HOLDER} is honest only about
     * messages already taken out of the at-most-once inbox, which nobody else can ever run; that is the one place
     * {@link #drain} uses it, and only once the grace window is over. Here the truthful answer is to stop holding the
     * session and say so on the rail that means "somebody collect this".
     *
     * <p>
     * Terminates, and relays once. {@link #yieldSession} ends in {@link #returnLeaseWhenIdle}, whose
     * {@link #rerunDoorbellIfRung} re-enters this method — one level down, with the lease already gone, so that pass
     * publishes and clears {@link #doorbellRelayOwed}; the outer call then finds the debt discharged and returns. A
     * node
     * that owes nothing returns at the first check, which is what keeps two draining nodes from volleying the same
     * doorbell.
     */
    private void handOverForDrain(SessionId sessionId) {
        if (!doorbellRelayOwed.contains(sessionId)) {
            // Never this node's doorbell to answer, so not its doorbell to pass on either — and taking a lease in order
            // to drain is the one thing draining forbids. Whoever does hold the session heard the same
            // announcement, or will hear the one their own lease return re-rings.
            return;
        }
        if (heldLease(sessionId) != null) {
            yieldSession(sessionId);
            if (heldLease(sessionId) != null) {
                // A turn is still running under this lease, so the return was declined. Its own finally applies the
                // same rule and comes back through rerunDoorbellIfRung: the hand-off is deferred, not lost.
                log.debug("Hand-off of session {} deferred to the turn still holding it", sessionId);
                return;
            }
            if (!doorbellRelayOwed.contains(sessionId)) {
                // The nested pass got there first. Re-read rather than trust the check at the top of this method: the
                // return above runs rerunDoorbellIfRung, which re-enters here one level down and clears the debt on a
                // successful publish. Falling through would ring the same doorbell twice, and the peer that answers the
                // second one pays for an empty collect on a session it has already been handed.
                return;
            }
        }
        republishDoorbell(sessionId);
    }

    /**
     * Announce again that {@code sessionId}'s inbox has work, now that this node has stopped holding it.
     *
     * <p>
     * The payload {@link #deliverToInbox} attaches is deliberately omitted. The receiver reads only the session id
     * out of the envelope, and this node could not name the message even if it wanted to — it never collected it. The
     * doorbell means "collect this session", never "run this message".
     *
     * <p>
     * Normally the first peer to hear this is the origin of the message itself: it is subscribed for as long as it is
     * waiting on the forward. So the session is re-claimed within a signal round-trip instead of after the whole
     * lease TTL, which is the outcome design §7.3 asked {@code NOT_HOLDER} for.
     */
    private void republishDoorbell(SessionId sessionId) {
        try {
            signalBus.publish(SessionSignal.builder().sessionId(sessionId)
                    .kind(SessionSignal.SignalKind.MESSAGE_ENQUEUED).originNodeId(nodeId).build());
        } catch (Exception e) {
            // Both marks are left standing on failure, so the next lease return tries again: a doorbell nobody hears is
            // the entire failure mode this path exists to prevent.
            log.warn("Re-ringing the doorbell for session {} failed: {}", sessionId, e.toString());
            return;
        }
        doorbellRelayOwed.remove(sessionId);
        // Cleared only after a successful publish, and only here: a draining node will not collect again, so the notice
        // has stopped being its to answer.
        doorbellPending.remove(sessionId);
    }

    /**
     * Drop this node's note that a session's inbox has unanswered work, because the session it refers to has just
     * been released, deleted or evicted out from under it.
     *
     * <p>
     * Both marks promise a message somebody still has to collect, and by the time any of those three has run the
     * inbox has been purged — here on the local paths, on the originating node for an {@code EVICT} — so the promise
     * is void. What is left costs an empty drain pass on the next lease return, or — for {@link #doorbellRelayOwed} —
     * hands a peer a session that no longer exists. Marks also outlive the id itself, so a later session reusing it
     * would inherit a notice about messages that were never its own.
     *
     * <p>
     * Called after the purge at every call site, which leaves the same narrow window the delete path already accepts:
     * a message delivered between the purge and this call loses its local notice. Its own {@code MESSAGE_ENQUEUED} is
     * what rings again — through {@link #onSignal} when a peer sent it — and the session's next submission collects it
     * either way, because every turn re-collects before it starts.
     *
     * @param sessionId
     *            the session whose doorbell marks are no longer worth keeping
     */
    private void forgetDoorbell(SessionId sessionId) {
        doorbellPending.remove(sessionId);
        doorbellRelayOwed.remove(sessionId);
    }

    /**
     * Whether this node still holds an unanswered doorbell notice for {@code sessionId}. Visible for testing:
     * once the session a notice refers to is gone, the notice has no behaviour of its own left to observe — an empty
     * drain pass looks exactly like no pass at all — so {@link #forgetDoorbell} can only be pinned by reading the
     * marks.
     *
     * @param sessionId
     *            the session to inspect
     * @return {@code true} when either doorbell mark still names it
     */
    boolean hasDoorbellNotice(SessionId sessionId) {
        return doorbellPending.contains(sessionId) || doorbellRelayOwed.contains(sessionId);
    }

    private void tryDrainOnce(SessionId sessionId) {
        if (!tryBeginTurn(sessionId)) {
            // A turn is running on this node, which is a strictly better drain than this pass would be: its post-turn
            // re-collect takes the same messages without opening anything, and if the message lands after that collect
            // the doorbell outlives the turn and rings again.
            return;
        }
        final HeldLease held;
        try {
            held = holdOrAcquire(sessionId);
        } catch (RuntimeException e) {
            log.warn("Doorbell lease acquire failed for session {}: {}", sessionId, e.toString());
            endTurnAndSettleLeaseReturn(sessionId);
            return;
        }
        if (held == null) {
            // A peer is holding the session, and its post-turn re-collect will pick this up. If the message
            // arrived
            // too late for that, the doorbell outlives their pass and they re-ring on release.
            endTurnAndSettleLeaseReturn(sessionId);
            return;
        }
        // Takes over both the lease and the gate, and releases the gate in its own finally.
        runDrainOnly(sessionId, held);
    }

    /**
     * The drain path's counterpart to {@link #holdOrClaim}: reuse the session's lease, or acquire one.
     *
     * <p>
     * Acquires rather than claims, because the agent to claim for is inside a message that cannot be read before the
     * session is held — see {@link #runDrainOnly} for the full argument.
     *
     * @return the lease to drain under, or {@code null} when a peer holds the session
     */
    private HeldLease holdOrAcquire(SessionId sessionId) {
        final HeldLease reused = heldLease(sessionId);
        if (reused != null) {
            return reused;
        }
        // The bare nodeId, same as submit's claim: a drain pass and a turn on this node are deliberately no longer
        // distinguishable by holder id. The per-acquisition fencing token is what keeps them exclusive.
        return store.acquire(sessionId, nodeId, lockLease).map(lease -> installLease(sessionId, lease)).orElse(null);
    }

    /**
     * Publish a turn's result and hand it to whoever is waiting for it.
     *
     * <p>
     * {@code markDone} first, then the broadcast (design §7.1 F6): the store is what a node that misses the broadcast
     * falls back to, so announcing first would advertise a result that a holder crash could still erase. Never throws —
     * a control-plane failure must not abort a drain pass whose remaining messages are already out of the inbox.
     *
     * <p>
     * <b>The cache write is conditional; the delivery is not.</b> {@code markDone} matches on the key alone in every
     * backend, so a node that does not hold the reservation would silently replace whatever does hold it — and a drain
     * pass reaches here for messages whose take-over was <em>refused</em>, which is to say for entries the store
     * identified as somebody else's. Overwriting a {@code DONE} entry replaces an answer a client has already been
     * given with one it will never see, and any later replay of that key then returns the wrong one; overwriting a
     * live attempt's reservation puts this turn's answer where that attempt's caller will read it. Neither is this
     * turn's to write, so it does not. The rest of the method still runs: this execution really did happen and its
     * result is really an answer for that key, so the caller waiting on it — here or on a peer — is answered as
     * usual, and only the durable, replayable copy is withheld. A retry then re-executes instead of replaying, which
     * is the correct outcome when the answer on file belongs to somebody else.
     *
     * <p>
     * <b>Only a refusal withholds it.</b> A take-over that could not read the store learned nothing about who owns
     * the entry, and silence is not a refusal, so that path writes exactly as it did before the take-over existed. The
     * difference is not cosmetic: an unwritten entry left by a successful turn stays {@code IN_FLIGHT} with no holder
     * for the whole {@code idempotencyForwardTtl} — invisible to the holder-loss sweeper, which by contract skips
     * holderless entries — so a node that missed the rail polls it for five minutes and then times out over a turn
     * that succeeded minutes earlier, while every retry in that window attaches to a reservation nobody is running.
     * That is strictly worse than a {@code markDone} that is attempted and fails, which leaves the same entry but
     * costs nothing extra to try.
     *
     * @param mayCacheResult
     *            {@code false} only when the store answered that this key's entry belongs to another attempt; see
     *            {@link Takeover}
     */
    private void announceTurnResult(SessionId convId, TurnId turnId, String idempotencyKey, boolean mayCacheResult,
            AgentExecutionResult result) {
        if (idempotencyKey != null && mayCacheResult) {
            try {
                idempotencyStore.markDone(idempotencyKey, result);
            } catch (Exception e) {
                // Still announced below: the waiting caller getting its answer beats withholding it because the
                // recovery copy could not be written. What is lost is only the fallback, so a node that misses the
                // broadcast now waits out its forward deadline instead of polling the result out of the store.
                log.warn("markDone failed for key {} on session {}: {}", idempotencyKey, convId, e.toString());
            }
        }
        publishTurnOutcome(convId, TurnResultPayload.toPayload(turnId, idempotencyKey, result));
        resolveForward(turnId, idempotencyKey, result, null);
    }

    /**
     * Publish a terminal non-result for a turn and fail whoever is waiting for it. No <em>result</em> is written to the
     * idempotency store: a failed attempt must stay replayable, and caching the failure would make every retry of that
     * key inherit it.
     *
     * <p>
     * Staying replayable takes an active step, though, which is the {@link #discardReservation} below. The key is not
     * free merely because no failure was cached — it is still carrying the holderless reservation
     * {@code safeReleaseHolder} left when the submission was forwarded, and {@link #checkIdempotency} answers
     * {@code alreadyInFlight} to any entry that is not {@code DONE}. Left standing, that reservation makes the
     * client's retry collapse onto the very attempt this method is announcing dead and then wait out
     * {@code idempotencyForwardTtl} for an answer that will never come — the same defect {@link #holdOrClaim} avoids
     * by resetting the key on its {@code AgentConflict} branch, reached by a different route. The message is already
     * out of the at-most-once inbox by the time anything gets here, so no node can still run it and nothing is lost by
     * freeing the key.
     *
     * <p>
     * Either address may be null but not both — see {@link TurnResultPayload}. The local {@code resolveForward} is not
     * an optimization: {@link #onSignal} drops signals this node published, so on a single-node deployment, and
     * whenever
     * the announcer is also the node whose caller is waiting, the broadcast alone reaches nobody.
     *
     * @param turnId
     *            the failed turn, or null when the announcer cannot name it (holder-loss recovery)
     * @param idempotencyKey
     *            the submission's key, or null when it had none
     */
    private void announceTurnFailure(SessionId convId, TurnId turnId, String idempotencyKey,
            TurnResultPayload.Failure.Code code, String message) {
        if (turnId == null && idempotencyKey == null) {
            // Unaddressable rather than corrupt: an inbox entry written before the turn stamp existed and submitted
            // without an idempotency key has neither address, so no index on any node can find its caller. Publishing
            // it would put a payload on the rail that every receiver discards.
            log.debug("Not announcing {} on session {} — the turn has no turn id and no idempotency key: {}", code,
                    convId, message);
            return;
        }
        // Before the announcement, so a caller that retries the instant it is failed finds the key already free. The
        // reverse order leaves a window in which the retry is told to collapse onto the attempt it was just told died.
        if (idempotencyKey != null) {
            safeDiscardReservation(idempotencyKey);
        }
        publishTurnOutcome(convId, TurnResultPayload.toFailurePayload(turnId, idempotencyKey, code, message));
        resolveForward(turnId, idempotencyKey, null,
                new IllegalStateException(describeTurn(turnId, idempotencyKey) + " " + code + ": " + message));
    }

    /**
     * Best-effort release of a failed turn's reservation, for the same reason as {@link #safeCompareAndReset}: the
     * store is a remote dependency, and losing this must cost the key its promptness rather than cost the caller its
     * answer. The degraded outcome is the pre-existing one — a retry that waits out the forward TTL — so the
     * announcement below must happen either way.
     *
     * <p>
     * A {@code false} return is the ordinary case rather than a problem: holder-loss recovery has already deleted the
     * entry by the time it announces, a keyed turn that ran on its submitting node never had a holderless reservation
     * to begin with, and both leave nothing here to discard.
     */
    private void safeDiscardReservation(String key) {
        try {
            idempotencyStore.discardReservation(key);
        } catch (Exception e) {
            log.warn("idempotencyStore.discardReservation threw for key {}: {}", key, e.toString());
        }
    }

    /**
     * Answer the callers waiting on a turn whose holder was declared lost — the announcing half of holder-loss
     * recovery,
     * called by {@link HolderLossSweeper} on the node that won the reservation reset.
     *
     * <p>
     * It lives here rather than in the sweeper because the rail and the forward registry do, and it is a turn-scoped
     * failure rather than the {@code EVICT} this replaced: the session itself is not going away — a successor may
     * already hold it and be running the next turn — so evicting it would tear down a live session and complete the
     * event streams of subscribers who have a working session. Only this one attempt is dead.
     *
     * <p>
     * The turn id is null because no surviving node knows it: the reservation records its holder but not the turn, and
     * the id lived in the inbox envelope the dead holder consumed. The key is address enough — a swept entry always has
     * one, since a submission without an idempotency key never reserves anything for the sweeper to find.
     *
     * @param convId
     *            the session whose holder was lost (must not be null)
     * @param idempotencyKey
     *            the reservation the lost holder was executing under (must not be null)
     * @param lostHolderId
     *            the holder id that stopped renewing, for the caller-visible detail (must not be null)
     */
    private void announceHolderLost(SessionId convId, String idempotencyKey, String lostHolderId) {
        announceTurnFailure(convId, null, idempotencyKey, TurnResultPayload.Failure.Code.HOLDER_LOST,
                "holder " + lostHolderId + " stopped renewing its reservation on session " + convId.value()
                        + " and was declared lost");
    }

    /**
     * Deliver a frame the {@link HolderLossSweeper} produced to the session's subscribers on <em>every</em> node,
     * not
     * just the one that happened to run the sweep — the sink half of holder-loss recovery, next to the announcing half
     * above.
     *
     * <p>
     * A caller waiting on the lost turn is failed by the {@code TURN_RESULT} announcement, but a subscriber merely
     * watching the session is not a caller: before this, a stream on any node but the sweeper's simply went quiet,
     * with the last frame it ever received being whatever the dead holder had relayed. The sweep is the only evidence
     * that will ever arrive, so it has to travel.
     *
     * <p>
     * The two deliveries are guarded separately because they fail for unrelated reasons: a bus outage must not cost
     * on-node subscribers the frame, and a publisher fault must not swallow the broadcast.
     *
     * <p>
     * The relayed frame carries <b>no turn stamp</b> — {@link AgentExecutionEventPayload#toUnstampedPayload} explains
     * why
     * the sweeper cannot supply one. A receiver reads that as "turn unknown" and delivers session-wide, so a
     * subscriber on a successor node can see a {@code HOLDER_LOST} frame for a turn other than the one it is watching.
     * That is the accepted cost of the news arriving at all, and a much smaller one than the stream-ending
     * {@code EVICT}
     * this replaced; anything a caller must act on still travels turn-addressed on the {@code TURN_RESULT} rail.
     */
    private void emitRecoveryFrame(SessionId convId, AgentExecutionEvent event) {
        try {
            eventPublisher.emit(convId, event);
        } catch (Exception e) {
            log.warn("Local recovery-frame emit failed for session {}: {}", convId, e.toString());
        }
        final Map<String, Object> payload = AgentExecutionEventPayload.toUnstampedPayload(event);
        if (payload == null) {
            // Unrecognized subtype — the codec declines it rather than publishing something no peer can decode. Local
            // delivery above already happened.
            return;
        }
        try {
            signalBus.publish(SessionSignal.builder().sessionId(convId).kind(SessionSignal.SignalKind.EVENT)
                    .originNodeId(nodeId).payload(payload).build());
        } catch (Exception e) {
            log.warn("Recovery-frame EVENT publish failed for session {}: {}", convId, e.toString());
        }
    }

    /**
     * How a turn is named in the exception a waiting caller receives: by turn id when the announcer knew it, by
     * idempotency key otherwise.
     */
    private static String describeTurn(TurnId turnId, String idempotencyKey) {
        return turnId != null
                ? "Forwarded turn " + turnId.value()
                : "Forwarded turn under idempotency key " + idempotencyKey;
    }

    private void publishTurnOutcome(SessionId convId, Map<String, Object> payload) {
        try {
            signalBus.publish(SessionSignal.builder().sessionId(convId).kind(SessionSignal.SignalKind.TURN_RESULT)
                    .originNodeId(nodeId).payload(payload).build());
        } catch (Exception e) {
            log.warn("TURN_RESULT publish failed for session {}: {}", convId, e.toString());
        }
    }

    /**
     * Refuse a queued message that names a different agent than the session is bound to, and tell the node that
     * queued it. Before the {@code TURN_RESULT} rail this only emitted a local {@code RejectedAt} — an event on the
     * holder, where the submitter is not listening — so the submitting caller waited forever for a turn that had
     * already
     * been declined.
     */
    private void rejectConflictingMessage(SessionId convId, InboundMessage message, String boundAgent, TurnId turnId,
            String idempotencyKey) {
        eventPublisher.emit(convId,
                at.aimon.core.agent.stream.RejectedAt.builder().timestamp(Instant.now())
                        .agentRuntimeId(WEB_REJECTED_SENTINEL).iteration(0)
                        .reason(at.aimon.core.agent.stream.RejectReason.CONFLICTING_AGENT)
                        .requestedAgent(message.getAgentRef()).existingAgent(boundAgent)
                        .inboxId(message.getId().map(InboundMessageId::value).orElse("<unknown>")).build());
        announceTurnFailure(convId, turnId, idempotencyKey, TurnResultPayload.Failure.Code.REJECTED, "session "
                + convId.value() + " is bound to agent '" + boundAgent + "', not '" + message.getAgentRef() + "'");
    }

    /**
     * Fail every message left in a queue the holder can no longer run. Those messages are already out of the
     * at-most-once inbox, so nothing will retry them and the nodes waiting on them would otherwise sit until their
     * forward deadlines.
     *
     * @param code
     *            {@code FAILED} when the pass itself broke, {@code NOT_HOLDER} when this node merely stopped being the
     *            holder before the messages could run — the caller's only way to tell a bad input from a node that went
     *            away, and so worth carrying even though both end the same futures
     */
    private void failUndrained(SessionId convId, Deque<InboundMessage> pendingQueue,
            TurnResultPayload.Failure.Code code, String reason) {
        InboundMessage next;
        while ((next = pendingQueue.poll()) != null) {
            // An unstamped envelope leaves the key as the only address. It used to be given a freshly generated turn id
            // instead, which matched no index anywhere and made the announcement a broadcast to nobody while looking
            // like a real one.
            announceTurnFailure(convId, next.getTurnId().orElse(null), next.getIdempotencyKey().orElse(null), code,
                    reason);
        }
    }

    /** Which of a drain pass's turns belonged to the submission that opened it, if any. */
    private static final class DrainOutcome {
        private final AgentExecutionResult selfResult;
        private final Throwable selfFailure;

        private DrainOutcome(AgentExecutionResult selfResult, Throwable selfFailure) {
            this.selfResult = selfResult;
            this.selfFailure = selfFailure;
        }
    }

    /**
     * Hands a submission that lost the lock to the inbox, so whichever node holds the session drains it.
     *
     * <p>
     * The turn id is stamped into the envelope rather than minted by the holder: the caller was already told this id,
     * so
     * an interrupt or event subscription naming it has to match the turn the holder eventually runs — even though that
     * turn runs on a different node, minutes later.
     *
     * @param request
     *            the submission to queue
     * @param turnId
     *            the id issued for it in {@link #submit}
     * @return a {@code FORWARDED} disposition carrying both ids and the future the holder's outcome will complete
     */
    private SubmitDisposition deliverToInbox(SubmitRequest request, TurnId turnId) {
        final SessionId convId = request.getSessionId();
        // Registered before the message exists anywhere a peer can see it. The reverse order loses the race it looks
        // immune to: an inbox delivery followed by a peer that drains and announces within microseconds would resolve a
        // turn this node has not started waiting for yet, and the announcement does not come twice.
        final PendingForward pending = registerForward(convId, turnId, request.getIdempotencyKey().orElse(null));
        final InboundMessage message = InboundMessage.builder().sessionId(convId).agentRef(request.getAgentRef())
                .contextDiscriminator(request.getContextDiscriminator().orElse(null)).userInput(request.getUserInput())
                .priority(request.getPriority()).turnId(pending.turnId)
                .idempotencyKey(request.getIdempotencyKey().orElse(null)).initiator(request.getInitiator())
                .deliveredAt(Instant.now()).submitOptions(request.getSubmitOptions()).build();
        final InboundMessageId id;
        try {
            id = inbox.deliver(message);
        } catch (RuntimeException e) {
            // The message never reached the inbox, so no holder will ever run it: fail the future now instead of
            // leaving it to time out at the forward deadline.
            failForward(pending, e);
            throw e;
        }
        signalBus.publish(SessionSignal.builder().sessionId(convId).kind(SessionSignal.SignalKind.MESSAGE_ENQUEUED)
                .originNodeId(nodeId).payload(Map.of("inboxId", id.value(), "turnId", pending.turnId.value())).build());
        // Ring this node's own doorbell too: onSignal drops self-origin signals, so on a single-node deployment — and
        // whenever the session's holder is this very node — the published signal alone reaches nobody.
        ringDoorbell(convId);
        return SubmitDisposition.forwarded(pending.turnId, id, pending.future);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Forwarded-turn bookkeeping (design §7.1 F0/F2/F6/F7)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Start waiting for a turn this node will not run itself.
     *
     * <p>
     * Keyed by turn id, and additionally by idempotency key when there is one. If a submission with that key is already
     * outstanding here, its entry is <em>reused</em> rather than replaced: the same key means the same input hash and
     * so
     * the same answer, and two entries would leave whichever one lost the index unresolvable.
     *
     * @param sessionId
     *            the session the turn belongs to (so eviction and delete can fail it)
     * @param turnId
     *            the id the holder will run the turn under
     * @param idempotencyKey
     *            the submission's key, or {@code null} when it had none — such a forward is resolvable only by the
     *            {@code TURN_RESULT} rail or the deadline, because there is nothing durable to poll
     * @return the entry to wait on (never null)
     */
    private PendingForward registerForward(SessionId sessionId, TurnId turnId, String idempotencyKey) {
        final PendingForward candidate = new PendingForward(sessionId, turnId, idempotencyKey,
                System.nanoTime() + idempotencyForwardTtl.toNanos());
        if (idempotencyKey != null) {
            final PendingForward existing = forwardsByKey.putIfAbsent(idempotencyKey, candidate);
            if (existing != null) {
                return existing;
            }
        }
        forwardsByTurn.put(turnId, candidate);
        try {
            candidate.pollTask = scheduler.scheduleAtFixedRate(() -> pollForward(candidate), forwardPollIntervalMs,
                    forwardPollIntervalMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Shutting down. closeGracefully fails every outstanding forward, so the caller is still answered.
            log.debug("Forward poll not scheduled for turn {} — scheduler is shutting down", turnId);
        }
        return candidate;
    }

    /**
     * The fallback that makes the {@code TURN_RESULT} rail optional rather than load-bearing (design §7.1 F7).
     *
     * <p>
     * The rail is a fire-and-forget broadcast with no replay, and a peer running an older build rejects the whole
     * signal
     * because it decodes the kind with {@code SignalKind.valueOf}. Either way the announcement is simply never heard,
     * so
     * a forward that has an idempotency key re-reads the store instead of trusting the broadcast:
     *
     * <ul>
     * <li>{@code DONE} — the turn ran somewhere; complete from the cached result.
     * <li>absent — the reservation expired or was reset. Nothing will produce a result now: {@code markDone} only
     * updates an entry that still exists, so this is terminal rather than "not yet".
     * <li>{@code IN_FLIGHT} — still queued or still running; keep waiting until the forward deadline.
     * </ul>
     *
     * <p>
     * A store failure is not treated as absence — it leaves the entry pending so a blip does not fail a turn that is
     * running fine, with the deadline as the backstop.
     */
    private void pollForward(PendingForward pending) {
        if (pending.future.isDone()) {
            unregisterForward(pending);
            return;
        }
        if (pending.idempotencyKey != null) {
            final Optional<IdempotencyEntry> found;
            try {
                found = idempotencyStore.find(pending.idempotencyKey);
            } catch (Exception e) {
                log.warn("Forward poll failed for key {}: {}", pending.idempotencyKey, e.toString());
                return;
            }
            if (found.isEmpty()) {
                failForward(pending, new IllegalStateException("Forwarded turn " + pending.turnId.value()
                        + " lost its idempotency reservation before any node produced a result"));
                return;
            }
            final IdempotencyEntry entry = found.get();
            if (entry.getStatus() == IdempotencyEntry.Status.DONE) {
                final Optional<AgentExecutionResult> cached = entry.getResult();
                if (cached.isPresent()) {
                    completeForward(pending, cached.get());
                } else {
                    failForward(pending, new IllegalStateException(
                            "Forwarded turn " + pending.turnId.value() + " was marked done without a cached result"));
                }
                return;
            }
        }
        if (System.nanoTime() - pending.deadlineNanos >= 0) {
            failForward(pending, new TimeoutException("Forwarded turn " + pending.turnId.value() + " on session "
                    + pending.sessionId.value() + " produced no result within " + idempotencyForwardTtl));
            return;
        }
        // Nobody has answered yet, so ask again — but only while the message is still sitting in the inbox. The
        // holder it was queued for may be gone: its lease then expires on its own TTL, but a dead node cannot run the
        // lease-return path that re-rings the doorbell (#republishDoorbell), and every peer that heard the original
        // MESSAGE_ENQUEUED found the session held and gave up. Without a retry from somewhere, the message waits for
        // the session's next submission — which may never come — while the caller here waits out
        // idempotencyForwardTtl for a turn no node is running. The node holding the future is the right one to retry
        // from: it is by definition not the holder, it is the one being hurt, and its interest ends when the forward
        // resolves, so the retry stops on its own.
        //
        // The inbox check is what keeps this to the case it can actually help. An uncollected message is the one thing
        // a drain pass can pick up; once some node has collected it the message is out of the at-most-once inbox and
        // only that node can produce its result, so re-ringing finds nothing and buys a wasted pass — the same as
        // against a healthy holder mid-turn. (A collected message whose holder then dies is not this path's to
        // recover either, but it is no longer unrecovered: the pass that collected it names itself on the reservation
        // before running it — see takeOverReservation — so the sweeper reports that death as HOLDER_LOST instead of
        // leaving the caller here to its deadline.)
        // The check also keeps this path from resurrecting a session a peer deleted: delete purges the inbox, so the
        // retry goes quiet even in the window before that peer's EVICT arrives to fail this forward outright.
        //
        // A failing inbox read rings anyway. Being unable to see the queue is not evidence the queue is empty, and
        // the cost of guessing wrong here is one drain pass against a lease its holder is still renewing.
        //
        // ringDoorbell rather than tryDrainOnce: this is the scheduler thread, and a drain pass runs whole turns on
        // the thread that starts it. ringDoorbell hands that to the turn executor and returns.
        if (!mayHaveQueuedWork(pending.sessionId)) {
            return;
        }
        safeMetric(metrics::onForwardDoorbellRerung, "onForwardDoorbellRerung");
        ringDoorbell(pending.sessionId);
    }

    /**
     * Whether {@code sessionId}'s inbox is worth re-announcing — {@code true} when it holds at least one uncollected
     * message, and also when the inbox cannot say.
     *
     * @param sessionId
     *            the session whose queue to check
     * @return {@code false} only on a definite answer of "nothing queued"
     */
    private boolean mayHaveQueuedWork(SessionId sessionId) {
        try {
            return !inbox.isEmpty(sessionId);
        } catch (Exception e) {
            log.warn("Inbox emptiness check failed for session {}, re-ringing anyway: {}", sessionId, e.toString());
            return true;
        }
    }

    /**
     * Hand a terminal outcome to whatever this node is waiting for under either address.
     *
     * <p>
     * Both lookups run and both may hit distinct entries: the forwarded submission is addressed by turn, while a retry
     * collapsed onto it shares only the key. Resolving one and not the other is what strands the retry.
     *
     * @param turnId
     *            the turn the outcome belongs to (may be null)
     * @param idempotencyKey
     *            the turn's idempotency key, if it had one (may be null)
     * @param result
     *            the result, or {@code null} when {@code failure} is given
     * @param failure
     *            why no result is coming, or {@code null} when {@code result} is given
     */
    private void resolveForward(TurnId turnId, String idempotencyKey, AgentExecutionResult result, Throwable failure) {
        final PendingForward byTurn = turnId == null ? null : forwardsByTurn.get(turnId);
        final PendingForward byKey = idempotencyKey == null ? null : forwardsByKey.get(idempotencyKey);
        if (byTurn != null) {
            applyOutcome(byTurn, result, failure);
        }
        if (byKey != null && byKey != byTurn) {
            applyOutcome(byKey, result, failure);
        }
    }

    private void applyOutcome(PendingForward pending, AgentExecutionResult result, Throwable failure) {
        if (result != null) {
            completeForward(pending, result);
        } else {
            failForward(pending, failure);
        }
    }

    private void completeForward(PendingForward pending, AgentExecutionResult result) {
        unregisterForward(pending);
        if (pending.future.complete(result)) {
            log.debug("Forwarded turn {} on session {} completed", pending.turnId, pending.sessionId);
        }
    }

    private void failForward(PendingForward pending, Throwable cause) {
        unregisterForward(pending);
        if (pending.future.completeExceptionally(cause)) {
            log.debug("Forwarded turn {} on session {} failed: {}", pending.turnId, pending.sessionId,
                    cause.toString());
        }
    }

    /**
     * Withdraws {@code pending} from the lookup maps, before its future is settled rather than after.
     *
     * <p>
     * The order is what makes an idempotent retry mean what it says. A caller that learns its forward failed and
     * retries with the same key hits {@link #registerForward}, which deliberately adopts an existing entry for the key
     * so a duplicate submission waits on the original rather than queueing a second copy of the message. Settling
     * first would leave a settled entry adoptable for as long as the unregister takes: the retry would be handed the
     * previous attempt's outcome — a failure it was retrying <em>because</em> of — while the message it queued runs on
     * the holder regardless.
     *
     * <p>
     * Withdrawing first buys the invariant that makes adoption mean one thing: a submission that has <em>observed</em>
     * an outcome for this key can no longer adopt the entry that produced it, because observing it means the settle
     * happened, and the settle is ordered after the withdrawal. What can still adopt is a duplicate submitted before
     * anyone saw an outcome — which is the case adoption exists for, and which correctly shares the one result.
     *
     * <p>
     * This is the same rule the durable side already follows: the idempotency reservation is released before the
     * failure goes out on the rail, never after, so that a caller told "failed" is a caller that can retry. This map is
     * the in-memory half of that promise and now keeps it on the same side of the announcement.
     */
    private void unregisterForward(PendingForward pending) {
        forwardsByTurn.remove(pending.turnId, pending);
        if (pending.idempotencyKey != null) {
            forwardsByKey.remove(pending.idempotencyKey, pending);
        }
        final ScheduledFuture<?> task = pending.pollTask;
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * Fail every forward outstanding for one session. Called when the session goes away underneath the
     * waiting callers — a local release or delete, or the {@code EVICT} that tells this node a peer did one. The queued
     * message is purged with the inbox in all three cases, so no holder will ever run it and the future would otherwise
     * sit until the forward deadline.
     */
    private void failForwardsFor(SessionId sessionId, String reason) {
        for (PendingForward pending : forwardsByTurn.values()) {
            if (pending.sessionId.equals(sessionId)) {
                failForward(pending, new IllegalStateException(reason));
            }
        }
    }

    /**
     * What this node is waiting on for one forwarded submission. Not a builder-style value object: it is short-lived
     * mutable bookkeeping (the poll task handle is assigned after construction) that never leaves this class.
     */
    private static final class PendingForward {
        private final SessionId sessionId;
        private final TurnId turnId;
        private final String idempotencyKey;
        private final long deadlineNanos;
        private final CompletableFuture<AgentExecutionResult> future = new CompletableFuture<>();
        private volatile ScheduledFuture<?> pollTask;

        private PendingForward(SessionId sessionId, TurnId turnId, String idempotencyKey, long deadlineNanos) {
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.idempotencyKey = idempotencyKey;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private IdempotencyDecision checkIdempotency(SubmitRequest request, String reserverId) {
        final Optional<String> keyOpt = request.getIdempotencyKey();
        if (keyOpt.isEmpty()) {
            return IdempotencyDecision.empty();
        }
        final String key = keyOpt.get();
        final String inputHash = sha256(request.getUserInput());
        final Instant now = Instant.now();
        final IdempotencyEntry candidate = IdempotencyEntry.builder().key(key).sessionId(request.getSessionId())
                .inputHash(inputHash).status(IdempotencyEntry.Status.IN_FLIGHT).holderId(reserverId).createdAt(now)
                .lastTouchedAt(now).build();

        final PutResult put = idempotencyStore.putIfAbsent(key, candidate, idempotencySecondaryTtl);
        if (put.getKind() == PutResult.Kind.INSERTED) {
            return IdempotencyDecision.acquired(key, reserverId);
        }
        final IdempotencyEntry existing = put.getCurrent().orElseThrow();
        if (!existing.getInputHash().equals(inputHash)) {
            throw new IdempotencyConflictException(key,
                    "Idempotency key reused with different input (key=" + key + ")");
        }
        if (existing.getStatus() == IdempotencyEntry.Status.DONE) {
            return IdempotencyDecision.replay(existing.getResult().orElseThrow());
        }
        return IdempotencyDecision.alreadyInFlight(InboundMessageId.of("idem-" + key));
    }

    @Override
    public Flow.Publisher<AgentExecutionEvent> events(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        ensureSubscribed(sessionId);
        return eventPublisher.publisherFor(sessionId);
    }

    /**
     * Subscribe this node to {@code sessionId}'s signal rail, unless it already is.
     *
     * <p>
     * A node needs the rail for two independent reasons, and both must arm it:
     *
     * <ul>
     * <li><strong>Observer</strong> — a client called {@link #events(SessionId)} here and expects relayed
     * {@code EVENT}s and a terminal {@code onComplete} when a peer evicts.
     * <li><strong>Holder</strong> — this node runs turns for the session, so it is the only node that can act on
     * {@code INTERRUPT} (stop the running turn) and {@code EVICT} (drop the session and the cached agent binding).
     * <li><strong>Submitter</strong> — this node accepted a submission and owes its caller an answer. If the lock turns
     * out to be held elsewhere it needs {@code TURN_RESULT} to hear how the turn ended, which is why {@link #submit}
     * arms the rail before it knows which of the two it is going to be.
     * </ul>
     *
     * <p>
     * Only the first was wired originally, which made cross-node control silently depend on a coincidence: it worked
     * when the client happened to stream from the same node it submitted to, and did nothing when it did not. A
     * fire-and-forget or scheduled turn, an inbox message drained by a node nobody is watching, or a load balancer
     * without sticky routing all produced a holder that ignored every {@code INTERRUPT} — a stop button that does not
     * stop — and a {@code deleteSession} on a peer that could not make the holder yield the lock, failing after
     * its bounded retry budget.
     *
     * <p>
     * Idempotent and cheap after the first call: the map lookup short-circuits, so the bus round-trip happens once per
     * session per node. Subscriptions are released in {@link #releaseSession(SessionId)},
     * {@link #deleteSession(SessionId)} and {@link #closeGracefully(Duration)} — deliberately <em>not</em>
     * when a session is idle-evicted. A node that drops the rail while still holding a cached {@code agentRef} binding
     * would never see the {@code EVICT} that invalidates it, which is exactly the stale-binding defect the
     * receiver-side
     * {@code EVICT} handler exists to prevent (the binding cache is positive-only and has no TTL).
     *
     * @param sessionId
     *            the session whose rail this node must hear (must not be null)
     */
    private void ensureSubscribed(SessionId sessionId) {
        subscriptions.computeIfAbsent(sessionId, id -> signalBus.subscribe(id, this::onSignal));
    }

    /**
     * {@link #ensureSubscribed} for the paths that must not fail because the control plane is down: a bus outage should
     * cost observability, not the caller's answer. Cross-node interrupts and prompt turn-result delivery are lost until
     * something re-subscribes; the forwarded-turn polling fallback covers the latter.
     *
     * @param sessionId
     *            the session whose rail this node wants
     * @param why
     *            what this node needs the rail for, for the log line
     */
    private void ensureSubscribedQuietly(SessionId sessionId, String why) {
        try {
            ensureSubscribed(sessionId);
        } catch (RuntimeException e) {
            log.warn("Signal subscribe failed for session {} while {}; remote INTERRUPT/EVICT/TURN_RESULT will "
                    + "not be observed: {}", sessionId, why, e.toString());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Resolution order: if this node has the session cached it returns the live local {@code status()}
     * ({@code LOCAL_HOLDER}); otherwise it returns the last snapshot the holder broadcast on the {@code STATUS} rail
     * and
     * this node folded into its {@link StatusProjection} ({@code REMOTE_PROJECTION}); otherwise {@code UNKNOWN}. The
     * projection is only populated for sessions this node is subscribed to — which happens when a client calls
     * {@link #events(SessionId)} here <em>or</em> when this node runs a turn — and only when holder-side
     * {@code STATUS} broadcast is enabled via {@link #setStatusBroadcastEnabled(boolean)}. A node that has neither
     * observed nor served the session honestly reports {@code UNKNOWN}.
     */
    @Override
    public ClusterSessionStatus status(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final Optional<SessionEntry> local = sessionCache.peek(sessionId);
        if (local.isPresent()) {
            return ClusterSessionStatus.localHolder(local.get().getSession().status(), nodeId);
        }
        return statusProjection.lookup(sessionId)
                .map(remote -> ClusterSessionStatus.remote(remote.status(), remote.originNodeId(), remote.observedAt()))
                .orElseGet(() -> ClusterSessionStatus.unknown(sessionId));
    }

    /**
     * Enables or disables holder-side {@code STATUS} snapshot broadcast for cluster-wide observability. Disabled by
     * default: enabling emits a small {@code STATUS} signal at turn start, on a heartbeat ({@code lockExtendInterval}
     * cadence) while a turn runs, and at turn end. Keep this off until every node in the cluster runs a build that
     * understands {@link SessionSignal.SignalKind#STATUS}; flip it on cluster-wide afterwards so a rolling deploy
     * never broadcasts a signal kind an older node would reject.
     *
     * @param enabled
     *            whether to broadcast status snapshots from this node
     */
    public void setStatusBroadcastEnabled(boolean enabled) {
        this.statusBroadcastEnabled = enabled;
    }

    private void publishStatusSnapshot(SessionId sessionId, LiveSession session) {
        if (!statusBroadcastEnabled) {
            return;
        }
        final LiveSessionStatus snapshot;
        try {
            snapshot = session.status();
        } catch (RuntimeException e) {
            log.warn("session.status() threw for session {}: {}", sessionId, e.toString());
            return;
        }
        try {
            signalBus.publish(SessionSignal.builder().sessionId(sessionId).kind(SessionSignal.SignalKind.STATUS)
                    .originNodeId(nodeId)
                    .payload(StatusSnapshotPayload.toPayload(snapshot, statusSeq.incrementAndGet(), Instant.now()))
                    .build());
        } catch (Exception e) {
            log.warn("STATUS publish failed for session {}: {}", sessionId, e.toString());
        }
    }

    private void onSignal(SessionSignal signal) {
        if (nodeId.equals(signal.getOriginNodeId())) {
            return;
        }
        final SessionId convId = signal.getSessionId();
        switch (signal.getKind()) {
            case INTERRUPT -> {
                final Object reasonObj = signal.getPayload().get("reason");
                final InterruptReason reason = parseReason(reasonObj);
                // An unaddressed INTERRUPT stops whatever is running (admin stop, eviction, delete); an addressed one
                // stops only its own turn and is dropped if that turn already finished. A peer on an older build sends
                // no turnId, so absence has to keep meaning session-scoped rather than "turn unknown → ignore".
                final Optional<TurnId> target = parseTurnId(signal.getPayload().get("turnId"));
                if (target.isPresent()) {
                    interruptLocal(convId, target.get(), reason);
                } else {
                    interruptLocal(convId, reason);
                }
                // Compatibility shim (removable once no node predates SignalKind.YIELD): a peer on an older build asks
                // for a session by interrupting it with this reason and nothing else. Keep reading that as a yield
                // request. A current peer sends YIELD *and* this companion, so both arrive and both yield — harmless,
                // since yieldSession is idempotent.
                if (target.isEmpty() && reason == InterruptReason.SESSION_RELEASED) {
                    yieldSession(convId);
                }
            }
            case YIELD -> {
                // Stop the turn first: a yield that waited for the turn to end on its own would cost the asking peer an
                // unbounded wait, which is the failure the interrupt-only channel had. SESSION_RELEASED is the reason
                // because from the running turn's side that is exactly what happened.
                interruptLocal(convId, InterruptReason.SESSION_RELEASED);
                // Deliberately none of EVICT's terminal work: the session still exists and is about to run
                // somewhere else, so queued inbox messages stay queued for the next holder, forwarded futures stay
                // pending, subscribers keep their streams, and approvals survive the move.
                yieldSession(convId);
            }
            case EVICT -> {
                final Object reasonObj = signal.getPayload().get("reason");
                final InterruptReason reason = parseReason(reasonObj);
                sessionCache.evict(convId);
                // Nothing to invalidate for the agent binding: it is read from the record on every submit now. This
                // handler used to also drop a node-local positive cache of it, which was load-bearing — after a peer's
                // deleteSession the record was gone, so a stale entry could never be corrected and every later
                // submit naming a different agent on this node was rejected with a conflict that no longer existed.
                // Approvals are node-local, so a peer's deleteSession cannot reach the copy cached here. Without
                // this the entries outlive the session they were granted in, and a later session reusing the
                // same SessionId inherits them silently. Purging on a mere releaseSession origin costs at
                // most one extra prompt.
                purgeSessionApprovals(convId);
                statusProjection.remove(convId);
                // The peer that evicted also purged the inbox, so anything this node forwarded there is gone and no
                // holder will ever run it. Fail those callers here rather than leaving them to their deadlines, and
                // drop the doorbell notice for the same reason: it announces messages that peer has already removed.
                failForwardsFor(convId, "session " + convId.value() + " was released or deleted on node "
                        + signal.getOriginNodeId() + " before this turn ran");
                forgetDoorbell(convId);
                emitTerminalInterrupt(convId, reason);
                eventPublisher.complete(convId);
            }
            case MESSAGE_ENQUEUED -> ringDoorbell(convId);
            case TURN_RESULT -> TurnResultPayload.fromPayload(signal.getPayload()).ifPresent(this::applyTurnOutcome);
            // Session-wide on purpose. The payload carries a turn stamp for a receiver that one day demultiplexes
            // per turn, but a holder-loss recovery frame carries none — no surviving node knows the lost turn — so
            // reading the stamp as a delivery filter would silence exactly the frame that matters most.
            case EVENT -> AgentExecutionEventPayload.fromPayload(signal.getPayload())
                    .ifPresent(event -> eventPublisher.emit(convId, event));
            case STATUS -> StatusSnapshotPayload.fromPayload(convId, signal.getPayload())
                    .ifPresent(decoded -> statusProjection.apply(convId, decoded.status(), signal.getOriginNodeId(),
                            decoded.observedAt(), decoded.seq()));
            default -> {
                /* unknown — ignore */ }
        }
    }

    /**
     * Apply a peer's {@code TURN_RESULT} to whatever this node is waiting on. A signal for a turn nobody here submitted
     * is simply not found in either index and costs a map lookup — the rail is a broadcast, so most nodes see most
     * announcements as noise.
     */
    private void applyTurnOutcome(TurnResultPayload.Decoded decoded) {
        final TurnId turnId = decoded.turnId().orElse(null);
        final String idempotencyKey = decoded.idempotencyKey().orElse(null);
        final Optional<AgentExecutionResult> result = decoded.result();
        if (result.isPresent()) {
            resolveForward(turnId, idempotencyKey, result.get(), null);
            return;
        }
        final String detail = decoded.failure().map(f -> f.code() + ": " + f.message()).orElse("no result");
        resolveForward(turnId, idempotencyKey, null,
                new IllegalStateException(describeTurn(turnId, idempotencyKey) + " " + detail));
    }

    @Override
    public void interrupt(SessionId sessionId, InterruptReason reason) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        interruptLocal(sessionId, reason);
        signalBus.publish(SessionSignal.builder().sessionId(sessionId).kind(SessionSignal.SignalKind.INTERRUPT)
                .originNodeId(nodeId).payload(Map.of("reason", reason.name())).build());
    }

    @Override
    public void interrupt(SessionId sessionId, TurnId turnId, InterruptReason reason) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(turnId, "turnId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        interruptLocal(sessionId, turnId, reason);
        signalBus.publish(SessionSignal.builder().sessionId(sessionId).kind(SessionSignal.SignalKind.INTERRUPT)
                .originNodeId(nodeId).payload(Map.of("reason", reason.name(), "turnId", turnId.value())).build());
    }

    @Override
    public void releaseSession(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        // Reaches a turn whose cache entry an earlier eviction already took away, which is also the case that most
        // needs the wait below: that turn is still appending to the history this release is about to close.
        if (interruptLocal(sessionId, InterruptReason.SESSION_RELEASED)) {
            // Give the in-flight turn the full configured budget to yield. A short cap (e.g. 50 ms) risks
            // closing the session mid-turn and corrupting conversation history when the interrupt takes longer.
            try {
                Thread.sleep(releaseInterruptTimeout.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        sessionCache.evict(sessionId);
        try {
            inbox.purge(sessionId);
        } catch (Exception e) {
            log.warn("Inbox purge threw for session {}: {}", sessionId, e.toString());
        }
        // After the purge, so the messages backing these futures are provably gone rather than racing the purge.
        failForwardsFor(sessionId, "session " + sessionId.value() + " was released before this turn ran");
        purgeSessionApprovals(sessionId);
        forgetDoorbell(sessionId);
        eventPublisher.complete(sessionId);
        final SessionSignalBus.Subscription sub = subscriptions.remove(sessionId);
        if (sub != null) {
            try {
                sub.close();
            } catch (Exception e) {
                log.warn("Subscription close threw for session {}: {}", sessionId, e.toString());
            }
        }
        signalBus.publish(SessionSignal.builder().sessionId(sessionId).kind(SessionSignal.SignalKind.EVICT)
                .originNodeId(nodeId).payload(Map.of("reason", InterruptReason.SESSION_RELEASED.name())).build());
    }

    @Override
    public void deleteSession(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (!acceptingSubmits.get()) {
            throw new IllegalStateException("SessionRouter is shutting down — refusing deleteSession");
        }

        // Both halves of holdership, in this order: the local turn gate first, because a turn on this node keeps the
        // lease across turns now, and waiting on the lease while a local turn holds it would be waiting for something
        // that is not going to happen.
        //
        // The gate is taken outside the try so that everything after it — including the lease wait, which times out by
        // throwing — leaves through the finally. Acquiring the lease inside the guarded region is the whole point: a
        // delete that cannot get the lease within releaseInterruptTimeout has to give the gate back, or the session is
        // permanently unusable on this node (nothing clears activeTurns but endTurn) and every retry of the delete
        // fails on the gate instead of the lease — including the retry that would have succeeded once the peer
        // finished.
        // Whether the record is actually gone, which decides what the finally owes a doorbell that rang meanwhile. Set
        // at the point of no return rather than on the way out: once the row is deleted the session is gone however the
        // remaining statements end.
        boolean recordDeleted = false;
        beginTurnForDelete(sessionId);
        try {
            final HeldLease borrowed = heldLease(sessionId);
            final HeldLease held = borrowed != null
                    ? borrowed
                    : installLease(sessionId, awaitLeaseForDelete(sessionId));
            try {
                sessionCache.evict(sessionId);
                try {
                    inbox.purge(sessionId);
                } catch (Exception e) {
                    log.warn("Inbox purge threw for session {}: {}", sessionId, e.toString());
                }
                failForwardsFor(sessionId, "session " + sessionId.value() + " was deleted before this turn ran");
                // Before the repository row goes: if the delete fails we have dropped approvals for a session that
                // still exists (one extra prompt), whereas purging afterwards would leak them whenever delete throws.
                purgeSessionApprovals(sessionId);
                try {
                    // Through the fenced view, not the raw repository: the lease acquired above is what authorises the
                    // delete, and routing it here makes the store prove the lease is still current at the moment of the
                    // write. A delete that lost its lease to a peer mid-way now fails instead of erasing history the
                    // new
                    // holder is actively appending to.
                    store.records().delete(sessionId);
                } catch (Exception e) {
                    log.error("Repository delete failed for session {}: {}", sessionId, e.toString(), e);
                    throw e;
                }
                recordDeleted = true;
                emitTerminalInterrupt(sessionId, InterruptReason.SESSION_RELEASED);
                eventPublisher.complete(sessionId);
                final SessionSignalBus.Subscription sub = subscriptions.remove(sessionId);
                if (sub != null) {
                    try {
                        sub.close();
                    } catch (Exception e) {
                        log.warn("Subscription close threw for session {}: {}", sessionId, e.toString());
                    }
                }
                signalBus.publish(SessionSignal.builder().sessionId(sessionId).kind(SessionSignal.SignalKind.EVICT)
                        .originNodeId(nodeId).payload(Map.of("reason", InterruptReason.SESSION_RELEASED.name()))
                        .build());
            } finally {
                // Unconditionally, even for a borrowed lease and even if a pinned entry's close is still pending: the
                // session no longer exists, so there is nothing left for the lease to protect. Nested inside the gate
                // so the release happens before the gate is given back — a submission slipping in between would run a
                // turn under a lease this node is about to hand back.
                returnLease(sessionId, held);
            }
        } finally {
            endTurnAndSettleLeaseReturn(sessionId);
            // A delete holds the turn gate for its whole duration, and a doorbell that rings against a taken gate is
            // dropped by tryDrainOnce on the understanding that whoever holds the gate will re-ring on the way out —
            // which is what every other gate holder does and what this one did not. Peers that heard the same
            // MESSAGE_ENQUEUED found the session held here and gave up, so nothing else was ever going to look.
            if (recordDeleted) {
                // Nothing left to drain into: the record is gone and the inbox was purged above. A message that landed
                // in the window between that purge and here is deliberately left where it is rather than re-rung —
                // a pass would provision the record back (see runDrainOnly) and run a turn for a session the caller
                // asked to delete, possibly after the EVICT below already told its submitter the turn never ran.
                // Clearing the marks instead keeps a leftover notice from costing an empty pass on whatever session
                // next reuses this id.
                forgetDoorbell(sessionId);
            } else {
                // The delete did not happen — it timed out waiting for the lease, or the fenced write was refused. The
                // session and its queued messages are exactly as they were, so the swallowed doorbell is owed an
                // answer like any other.
                rerunDoorbellIfRung(sessionId);
            }
        }
    }

    /**
     * Drops the session's cached skill approvals, if a {@link SessionApprovalStore} is wired.
     *
     * <p>
     * Never propagates: a failing approval cache must not abort a release or a delete. Dropping entries only ever costs
     * the user another prompt — the underlying {@code SkillInvocationPolicy} is consulted again on a miss — so failing
     * open here is safe, while failing the delete would strand the session.
     *
     * <p>
     * Not called on idle-TTL eviction: that path drops the live session but leaves the session intact, so its
     * approvals should survive the user coming back to it.
     *
     * @param sessionId
     *            the session whose approvals are dropped
     */
    private void purgeSessionApprovals(SessionId sessionId) {
        if (sessionApprovalStore == null) {
            return;
        }
        try {
            sessionApprovalStore.invalidate(sessionId);
        } catch (Exception e) {
            log.warn("Approval purge threw for session {}: {}", sessionId, e.toString());
        }
    }

    /**
     * Takes the local turn gate for a delete: try once, on contention broadcast a {@code YIELD} so the running turn
     * yields, then retry up to the same bounded budget the lease wait uses.
     *
     * <p>
     * Since Stage 3b this is the half of holdership that a local turn actually contends for. The lease is no longer
     * released at turn end, so a delete that waited only on the lease would wait for something the turn is not going to
     * do — it would time out against a turn that finished cleanly seconds earlier.
     */
    private void beginTurnForDelete(SessionId sessionId) {
        if (tryBeginTurn(sessionId)) {
            return;
        }
        broadcastYield(sessionId);
        awaitWithBackoff(sessionId, () -> tryBeginTurn(sessionId) ? Optional.of(Boolean.TRUE) : Optional.empty());
    }

    /**
     * Acquire the session lease for delete when this node does not already hold one: try once, on contention
     * broadcast a {@code YIELD} so the current holder hands it over, then retry up to a small bounded budget. Closes
     * design §7.7 — only the holder may delete history.
     *
     * <p>
     * Acquires rather than claims: the record is about to be removed, so which agent it is bound to is not a question
     * worth asking, and a delete must not be refused because the caller happened to name a different one.
     *
     * <p>
     * Only reached when {@link #heldLease(SessionId)} came back empty, so the contention here is always with a
     * <em>peer</em>. A lease this node already holds is borrowed instead — self-contending would deadlock against
     * ourselves for the whole budget, since holding the turn gate is what stops anybody here from returning it.
     */
    private SessionLease awaitLeaseForDelete(SessionId sessionId) {
        final Optional<SessionLease> first = store.acquire(sessionId, nodeId, lockLease);
        if (first.isPresent()) {
            return first.get();
        }
        broadcastYield(sessionId);
        return awaitWithBackoff(sessionId, () -> store.acquire(sessionId, nodeId, lockLease));
    }

    /**
     * Asks whoever holds this session to hand it over, wherever they are: a {@code YIELD} for peers, a direct
     * interrupt for a turn running here. Both are needed because a delete takes leases under the bare node id and
     * therefore contends with a local turn exactly as it does with a remote one.
     *
     * <p>
     * The local half stops only the turn. It deliberately does not evict — the caller is holding the turn gate and is
     * about to delete the session outright, so dropping the live session here would race that teardown for no gain.
     *
     * <p>
     * The legacy {@code INTERRUPT(SESSION_RELEASED)} goes out alongside the {@code YIELD} because a peer that predates
     * {@link SessionSignal.SignalKind#YIELD} cannot decode it and drops the whole signal — without the companion,
     * a rolling upgrade would leave old holders deaf to yield requests and every delete against them would time out.
     * Removable once no node in the cluster predates that kind; the receive-side half of the shim is in
     * {@link #onSignal}.
     */
    private void broadcastYield(SessionId sessionId) {
        signalBus.publish(SessionSignal.builder().sessionId(sessionId).kind(SessionSignal.SignalKind.YIELD)
                .originNodeId(nodeId).build());
        signalBus.publish(SessionSignal.builder().sessionId(sessionId).kind(SessionSignal.SignalKind.INTERRUPT)
                .originNodeId(nodeId).payload(Map.of("reason", InterruptReason.SESSION_RELEASED.name())).build());
        interruptLocal(sessionId, InterruptReason.SESSION_RELEASED);
    }

    /**
     * Retries {@code attempt} on an exponential backoff until {@code releaseInterruptTimeout} runs out.
     *
     * <p>
     * Shared by the gate wait and the lease wait so the timeout message is worded once. Which of the two halves ran out
     * is deliberately not distinguished: to the caller both mean the same thing — somebody else is still running this
     * session and did not yield — and the remedy (retry the delete) is the same either way.
     *
     * @throws IllegalStateException
     *             when the budget expires, or when this thread is interrupted while waiting
     */
    private <T> T awaitWithBackoff(SessionId sessionId, Supplier<Optional<T>> attempt) {
        final long deadline = System.nanoTime() + releaseInterruptTimeout.toNanos();
        long backoffMs = Math.max(20L, releaseInterruptTimeout.toMillis() / 8);
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to acquire session " + sessionId, ie);
            }
            final Optional<T> got = attempt.get();
            if (got.isPresent()) {
                return got.get();
            }
            backoffMs = Math.min(backoffMs * 2, 200L);
        }
        throw new IllegalStateException(
                "Could not acquire session " + sessionId + " for deleteSession within " + releaseInterruptTimeout);
    }

    @Override
    public void close() {
        closeGracefully(Duration.ZERO);
    }

    @Override
    public boolean closeGracefully(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        // Idempotent: if already closed/closing, just no-op.
        if (!acceptingSubmits.compareAndSet(true, false)) {
            return inFlightTurns.get() == 0;
        }

        final boolean drained = waitForDrain(timeout);
        if (!drained) {
            log.warn("closeGracefully: {} in-flight turn(s) did not drain within {}; interrupting and forcing close",
                    inFlightTurns.get(), timeout);
            // Before the interrupt, not after: a drain pass that reads this between two of its queued messages must see
            // the forced state rather than start a turn into a session that is about to be interrupted from under it.
            shutdownForced.set(true);
            interruptAllActiveSessions(InterruptReason.SYSTEM_SHUTDOWN);
            // Brief grace so interrupted turns can publish their terminal events before the executors are torn down.
            final boolean drainedAfterInterrupt = waitForDrain(
                    Duration.ofMillis(Math.min(500L, timeout.toMillis() + 1L)));
            if (!drainedAfterInterrupt) {
                // Deliberately NOT releasing those sessions' leases. A turn that has not reached runTurnLoop's
                // finally may still be inside an LLM call or a tool, and releasing its lease would let a peer node
                // start a second turn on the same session — the exact interleaving the lease exists to prevent.
                // Letting it lapse costs peers one lockLease of latency and keeps the invariant intact.
                //
                // Since Stage 3b the pin enforces this rather than the absence of a release call: closeAll below
                // invalidates every entry, but a still-running turn's entry is pinned, so its close — and with it the
                // lease return — is deferred until that turn's own finally unpins. Idle sessions, whose leases nobody
                // is using, are closed and returned right there.
                log.warn("closeGracefully: {} turn(s) still running after interrupt; their session leases are left "
                        + "to expire so no peer starts a concurrent turn", inFlightTurns.get());
            }
        }

        idleSweepTask.cancel(false);
        holderSweepTask.cancel(false);
        // Before the scheduler goes down, because the poll tasks that would otherwise resolve these are about to be
        // cancelled. A future nobody can ever complete is worse than one that fails at shutdown.
        for (PendingForward pending : forwardsByTurn.values()) {
            failForward(pending,
                    new IllegalStateException(
                            "SessionRouter on node " + nodeId + " shut down before this forwarded turn produced a "
                                    + "result; its result may still be recoverable from the idempotency store"));
        }
        for (Map.Entry<SessionId, SessionSignalBus.Subscription> e : subscriptions.entrySet()) {
            try {
                e.getValue().close();
            } catch (Exception ex) {
                log.warn("Subscription close threw on shutdown: {}", ex.toString());
            }
        }
        subscriptions.clear();
        sessionCache.closeAll();
        eventPublisher.close();
        scheduler.shutdownNow();
        leaseScheduler.shutdownNow();
        relayDispatcher.shutdownNow();
        turnExecutor.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
            leaseScheduler.awaitTermination(2, TimeUnit.SECONDS);
            relayDispatcher.awaitTermination(2, TimeUnit.SECONDS);
            turnExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return drained;
    }

    private boolean waitForDrain(Duration timeout) {
        if (timeout.isZero()) {
            return inFlightTurns.get() == 0;
        }
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlightTurns.get() > 0) {
            if (System.nanoTime() >= deadline) {
                return inFlightTurns.get() == 0;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Trips every turn running on this node so shutdown's second {@code waitForDrain} can reach zero.
     *
     * <p>
     * Both sources have to be walked, and only their union is complete. The cache holds sessions with no turn running
     * (idle, nothing to interrupt but harmless to try); {@link #turnSessions} holds the running turns including the
     * ones a mid-turn eviction already took out of the cache — miss those and shutdown interrupts everything except
     * the work it is actually waiting on, then times out on it. Identity-deduped so the overlap, which is the common
     * case, is not interrupted twice.
     *
     * <p>
     * Each interrupt only starts the unwind: {@code runTurnLoop}'s finally leaves the turn gate and decrements the
     * in-flight counter. The lease is not released there; it goes back when {@code closeAll} closes the session.
     */
    private void interruptAllActiveSessions(InterruptReason reason) {
        final Set<LiveSession> interrupted = Collections.newSetFromMap(new IdentityHashMap<>());
        sessionCache.forEachSession(session -> {
            if (interrupted.add(session)) {
                safeInterrupt(session, reason);
            }
        });
        turnSessions.values().forEach(session -> {
            if (interrupted.add(session)) {
                safeInterrupt(session, reason);
            }
        });
    }

    private void sweepIdleSessions() {
        try {
            sessionCache.sweep();
        } catch (Exception e) {
            log.warn("Idle session sweep threw: {}", e.toString());
        }
    }

    private static void safeInterrupt(LiveSession session, InterruptReason reason) {
        try {
            session.interrupt(reason);
        } catch (Exception e) {
            log.warn("session.interrupt threw: {}", e.toString());
        }
    }

    /**
     * Turn-addressed variant. The session itself decides whether {@code turnId} names the turn it is running, so a
     * stale
     * request — the turn settled while the signal was in flight — lands as a no-op instead of cancelling its successor.
     */
    private static void safeInterrupt(LiveSession session, TurnId turnId, InterruptReason reason) {
        try {
            session.interrupt(turnId, reason);
        } catch (Exception e) {
            log.warn("session.interrupt({}) threw: {}", turnId, e.toString());
        }
    }

    /**
     * Synthesize a terminal {@link at.aimon.core.agent.stream.InterruptedAt} on {@code sessionId}'s local
     * publisher. Called by the EVICT signal receiver so cross-node subscribers always see {@code InterruptedAt(reason)}
     * before {@code onComplete}, matching the local-release flow ("같은 방식으로 정리"). When the holder's session was
     * actively running, the relay-forwarded EVENT may also carry an {@code InterruptedAt}; subscribers may then observe
     * the event twice, but the alternative — racing EVICT past EVENT and dropping the relayed event — is worse.
     */
    private void emitTerminalInterrupt(SessionId sessionId, InterruptReason reason) {
        try {
            eventPublisher.emit(sessionId, at.aimon.core.agent.stream.InterruptedAt.builder().timestamp(Instant.now())
                    .agentRuntimeId(WEB_EVICTED_SENTINEL).iteration(0).reason(reason).iterationIndex(0).build());
        } catch (Exception e) {
            log.warn("Failed to emit terminal InterruptedAt for session {}: {}", sessionId, e.toString());
        }
    }

    private static InterruptReason parseReason(Object raw) {
        if (raw instanceof InterruptReason r) {
            return r;
        }
        if (raw instanceof String s) {
            try {
                return InterruptReason.valueOf(s);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return InterruptReason.USER_SIGINT;
    }

    /**
     * Reads an optional turn address off a signal payload. Unlike {@link #parseReason} there is no safe default: a
     * missing or unusable value means the sender did not address a turn, and inventing one would cancel a turn nobody
     * named.
     */
    private static Optional<TurnId> parseTurnId(Object raw) {
        if (raw instanceof TurnId t) {
            return Optional.of(t);
        }
        if (raw instanceof String s && !s.isBlank()) {
            return Optional.of(TurnId.of(s));
        }
        return Optional.empty();
    }

    private static void sortByPriorityThenFifo(List<InboundMessage> list) {
        list.sort(Comparator.<InboundMessage, Integer>comparing(m -> m.getPriority().ordinal())
                .thenComparing(InboundMessage::getDeliveredAt));
    }

    private static String sha256(String input) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static LiveSessionOpener adapt(LiveSessionFactory sessionFactory) {
        Objects.requireNonNull(sessionFactory, "sessionFactory must not be null");
        return (id, agentRuntimeId, options, openAttributes) -> sessionFactory.open(id, agentRuntimeId.agentName(),
                options);
    }

    private static java.util.concurrent.ThreadFactory namedFactory(String prefix) {
        final AtomicLong counter = new AtomicLong();
        return r -> {
            final Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Why {@link #drain} is or is not this message's reservation-holder — either what one
     * {@link #takeOverReservation} attempt learned, or the fact that no attempt was made.
     *
     * <p>
     * Five factories, two bits:
     *
     * <table border="1">
     * <caption>What each factory produces</caption>
     * <tr>
     * <th>Factory</th>
     * <th>{@link #takenReserverId()}</th>
     * <th>{@link #mayCacheResult()}</th>
     * <th>Reached when</th>
     * </tr>
     * <tr>
     * <td>{@link #won(String)}</td>
     * <td>the id</td>
     * <td>true</td>
     * <td>{@code acquireHolder} returned true</td>
     * </tr>
     * <tr>
     * <td>{@link #refused()}</td>
     * <td>null</td>
     * <td><b>false</b></td>
     * <td>{@code acquireHolder} returned false</td>
     * </tr>
     * <tr>
     * <td>{@link #unknown()}</td>
     * <td>null</td>
     * <td>true</td>
     * <td>{@code acquireHolder} threw</td>
     * </tr>
     * <tr>
     * <td>{@link #notAttempted()}</td>
     * <td>null</td>
     * <td>true</td>
     * <td>a non-self message with no key</td>
     * </tr>
     * <tr>
     * <td>{@link #alreadyHeldBySubmit(String)}</td>
     * <td>null</td>
     * <td>its argument is non-null</td>
     * <td>the
     * submission that opened the pass</td>
     * </tr>
     * </table>
     *
     * <p>
     * <b>An id means this loop may undo something</b>, and only {@code won} yields one: it is the identity to unbind
     * from the touch slot and, on failure, to reset. The opening submission's reservation is held by this node too,
     * but by {@code runTurnLoop} rather than by this loop, which is why it has a factory of its own rather than
     * reusing {@code won} — see {@link #alreadyHeldBySubmit(String)}.
     *
     * <p>
     * <b>Withholding the cache write needs the store to have said so</b>, which is {@code refused} and nothing else
     * that can be observed. {@code alreadyHeldBySubmit(null)} also reads false, but that argument is null only for a
     * submission with no idempotency key at all, and {@code announceTurnResult} short-circuits on the null key before
     * ever consulting this bit — so the two cases where the bit is false are not two cases in practice.
     *
     * <p>
     * Collapsing {@code unknown} into {@code refused} is the bug this type exists to make hard to reintroduce: a
     * single transient failure on {@code acquireHolder} would otherwise leave a successful turn's result uncached and
     * its key holderless for the forward TTL.
     */
    private static final class Takeover {

        private final String reserverId;
        private final boolean mayCacheResult;

        private Takeover(String reserverId, boolean mayCacheResult) {
            this.reserverId = reserverId;
            this.mayCacheResult = mayCacheResult;
        }

        /** This pass named itself on the reservation and bound it into the touch slot. */
        static Takeover won(String reserverId) {
            return new Takeover(Objects.requireNonNull(reserverId, "reserverId must not be null"), true);
        }

        /** The store answered: the entry is {@code DONE}, held by another attempt, or gone. */
        static Takeover refused() {
            return new Takeover(null, false);
        }

        /** The store could not be read, so nothing is known about the entry either way. */
        static Takeover unknown() {
            return new Takeover(null, true);
        }

        /** No key to take over. */
        static Takeover notAttempted() {
            return new Takeover(null, true);
        }

        /**
         * The opening submission of a drain pass, whose reservation this node has held since submit time — so there
         * is nothing to take over and nothing that could refuse it.
         *
         * <p>
         * <b>Why not {@code won(selfReserverId)}.</b> One reason, not the two originally claimed here. Yielding an id
         * would have {@link #drain}'s per-message {@code finally} unbind that reservation when the submission's own
         * turn ends — but {@code runTurnLoop} keeps it bound for the whole pass, and deliberately: if
         * {@code markDone} fails, the entry is still {@code IN_FLIGHT} under this node's name after a turn that
         * succeeded, and something has to go on saying this node is alive while the pass runs the rest of its queue.
         * Unbinding early leaves it unrefreshed, and a peer's sweeper then reports a lost holder for a turn that
         * finished. {@code theOpeningSubmissionsBindingOutlivesItsOwnTurn} is the guard on exactly that.
         *
         * <p>
         * The failure-path reset is <em>not</em> a second reason, and claiming it was overstated the case. Yielding
         * an id would also make the drain loop {@code compareAndReset} this key before announcing the failure, which
         * is the order {@link #announceTurnFailure} asks for and strictly better than what happens now —
         * {@code runTurnLoop} frees it only after the whole pass, so a retry arriving in between is told to collapse
         * onto the attempt it was just told died. That window predates this branch and is registered in the design's
         * §14; keeping the reset where it is, is a scope decision rather than a correctness one.
         *
         * @param selfReserverId
         *            the identity {@code runTurnLoop} reserved and bound, or {@code null} when the submission carried
         *            no key
         */
        static Takeover alreadyHeldBySubmit(String selfReserverId) {
            return new Takeover(null, selfReserverId != null);
        }

        String takenReserverId() {
            return reserverId;
        }

        boolean mayCacheResult() {
            return mayCacheResult;
        }
    }

    /**
     * What {@link #checkIdempotency} decided, and — when it reserved a key — under whose name.
     *
     * <p>
     * {@code reserverId} is non-null exactly when {@code acquiredKey} is, and travels with the decision so every later
     * {@code compareAndReset} / {@code touch} of that key names the attempt that reserved it. Before Stage 3b those
     * call
     * sites read the id back off the session lease, which only worked while one string served as both identities.
     */
    private static final class IdempotencyDecision {
        final String acquiredKey;
        final String reserverId;
        final AgentExecutionResult replayResult;
        final InboundMessageId queuedSyntheticId;

        private IdempotencyDecision(String acquiredKey, String reserverId, AgentExecutionResult replayResult,
                InboundMessageId queuedSyntheticId) {
            this.acquiredKey = acquiredKey;
            this.reserverId = reserverId;
            this.replayResult = replayResult;
            this.queuedSyntheticId = queuedSyntheticId;
        }

        static IdempotencyDecision empty() {
            return new IdempotencyDecision(null, null, null, null);
        }

        static IdempotencyDecision acquired(String key, String reserverId) {
            return new IdempotencyDecision(key, reserverId, null, null);
        }

        static IdempotencyDecision replay(AgentExecutionResult result) {
            return new IdempotencyDecision(null, null, result, null);
        }

        static IdempotencyDecision alreadyInFlight(InboundMessageId syntheticId) {
            return new IdempotencyDecision(null, null, null, syntheticId);
        }
    }

}
