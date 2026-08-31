package at.aimon.session.routing.builder;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import at.aimon.core.agent.session.LiveSessionFactory;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.DefaultSessionStore;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.session.routing.DeploymentMode;
import at.aimon.session.routing.LiveSessionOpener;
import at.aimon.session.routing.SessionRouter;
import at.aimon.session.routing.internal.DefaultSessionRouter;
import at.aimon.session.routing.internal.SessionRouterConfig;
import at.aimon.session.routing.metrics.SessionMetrics;

/**
 * Fluent builder for {@link SessionRouter} (design §11.2).
 *
 * <p>
 * Exactly one of {@link #sessionFactory(LiveSessionFactory)} or {@link #sessionOpener(LiveSessionOpener)} must be
 * configured — they are mutually exclusive. The factory path is the simple lambda-friendly way to wire a stateless
 * {@code LiveSessionFactory}; the opener path lets callers thread caller-domain attributes (tenant id, organization
 * unit, ...) from the {@code SubmitRequest} into the per-session context build via
 * {@link at.aimon.core.agent.session.OpenAttributes}.
 *
 * <p>
 * Other required collaborators: {@link SessionRecordStore}. SPIs ({@link SessionLeaseStore},
 * {@link SessionSignalBus}, {@link SessionInbox}, {@link IdempotencyStore}) are injected explicitly in
 * {@link DeploymentMode#DISTRIBUTED} mode and default to in-memory implementations in {@link DeploymentMode#SINGLE_NODE
 * SINGLE_NODE}.
 *
 * <p>
 * The repository and the lease store are taken as two separate backends and composed here into one
 * {@link SessionStore}. The builder does the composing rather than accepting a ready-made store because a store is
 * node-scoped while its two backends are shared: building it here makes "one store per manager" structural instead of
 * something callers have to remember. Two managers over the same Redis and the same repository each get their own
 * store,
 * which is exactly what fencing needs.
 *
 * <p>
 * Operational tunables ({@code idleTtl}, {@code maxCachedSessions}, {@code lockLease}, {@code lockExtendInterval},
 * {@code statusHeartbeatInterval}, {@code holderLossSweepInterval}, {@code idempotencyPrimaryTtl},
 * {@code idempotencySecondaryTtl}, {@code idempotencyForwardTtl}) carry sensible defaults. Internal components
 * ({@code LeaseRenewer}, {@code InProcessEventPublisher}, {@code LiveSessionCache}) are managed by the manager and
 * never injected from outside.
 *
 * <p>
 * In {@code DISTRIBUTED} mode the builder fails fast at {@link #build()} when any SPI is missing or {@code nodeId} is
 * unset — this prevents accidentally running with an in-memory SPI on a multi-node deployment.
 *
 * <p>
 * <strong>AgentRuntime ownership.</strong> The manager built here does <strong>not</strong> own the
 * {@code AgentRuntime}: neither the {@code sessionFactory(...)} adapter nor the {@code sessionOpener(...)}
 * path closes agent-scoped resources, and {@link SessionRouter#close()} only releases per-session
 * collaborators (cache, schedulers, subscriptions). The application bootstrap is responsible for registering each
 * agent's context once via {@code OrcaAgentRuntimeManager.getOrCreateRuntime(bundle, ...)} and for closing
 * it at shutdown via {@code destroyRuntime(id)} — otherwise MCP clients and other {@code AgentScoped} resources
 * leak. See {@link LiveSessionOpener} for the per-call contract and
 * {@code docs/design/agent-execution/agent-runtime-scope.md} for the full lifecycle.
 */
public final class SessionRouterBuilder {

    public static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(10);
    public static final int DEFAULT_MAX_CACHED_SESSIONS = 1000;

    /**
     * Default lease duration. Paired with {@link #DEFAULT_LOCK_EXTEND_INTERVAL} this is exactly <b>two missed renewal
     * ticks</b> of headroom: at 10s ticks a lease taken at {@code t} expires at {@code t+30s}, so the ticks due at
     * {@code t+10s} and {@code t+20s} may both fail to land and the third still saves the lease.
     *
     * <p>
     * That is the whole safety margin, and it is the reason renewal runs on a scheduler of its own (see
     * {@link #lockExtendInterval(Duration)}). Two ticks is generous against a lost packet or a slow round-trip to the
     * lease backend, and thin against a scheduler queue: work added to the manager's shared pool must not be allowed to
     * delay a renewal tick, because a queued tick and a stolen lease are indistinguishable from the renewer's side.
     */
    public static final Duration DEFAULT_LOCK_LEASE = Duration.ofSeconds(30);
    public static final Duration DEFAULT_LOCK_EXTEND_INTERVAL = Duration.ofSeconds(10);
    public static final Duration DEFAULT_HOLDER_LOSS_SWEEP_INTERVAL = Duration.ofSeconds(15);

    /**
     * Default {@code STATUS} heartbeat cadence. Numerically equal to {@link #DEFAULT_LOCK_EXTEND_INTERVAL} but no
     * longer derived from it — see {@link #statusHeartbeatInterval(Duration)}.
     */
    public static final Duration DEFAULT_STATUS_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    public static final Duration DEFAULT_IDEMPOTENCY_PRIMARY_TTL = Duration.ofHours(24);
    public static final Duration DEFAULT_IDEMPOTENCY_SECONDARY_TTL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_IDEMPOTENCY_FORWARD_TTL = Duration.ofMinutes(5);
    public static final Duration DEFAULT_RELEASE_INTERRUPT_TIMEOUT = Duration.ofSeconds(5);

    private LiveSessionFactory sessionFactory;
    private LiveSessionOpener sessionOpener;
    private SessionRecordStore sessionRecordStore;
    private SessionLeaseStore sessionLeaseStore;
    private SessionSignalBus signalBus;
    private SessionInbox sessionInbox;
    private IdempotencyStore idempotencyStore;
    private DeploymentMode mode = DeploymentMode.SINGLE_NODE;
    private String nodeId;
    private Duration idleTtl = DEFAULT_IDLE_TTL;
    private int maxCachedSessions = DEFAULT_MAX_CACHED_SESSIONS;
    private Duration lockLease = DEFAULT_LOCK_LEASE;
    private Duration lockExtendInterval = DEFAULT_LOCK_EXTEND_INTERVAL;
    private Duration statusHeartbeatInterval = DEFAULT_STATUS_HEARTBEAT_INTERVAL;
    private Duration holderLossSweepInterval = DEFAULT_HOLDER_LOSS_SWEEP_INTERVAL;
    private Duration idempotencyPrimaryTtl = DEFAULT_IDEMPOTENCY_PRIMARY_TTL;
    private Duration idempotencySecondaryTtl = DEFAULT_IDEMPOTENCY_SECONDARY_TTL;
    private Duration idempotencyForwardTtl = DEFAULT_IDEMPOTENCY_FORWARD_TTL;
    private Duration releaseInterruptTimeout = DEFAULT_RELEASE_INTERRUPT_TIMEOUT;
    private SessionMetrics metrics = SessionMetrics.NOOP;
    private boolean statusBroadcast;
    private SessionApprovalStore sessionApprovalStore;

    /**
     * Configures the manager with a stateless {@link LiveSessionFactory}. Mutually exclusive with
     * {@link #sessionOpener(LiveSessionOpener)} — {@link #build()} rejects both being set, and rejects neither being
     * set. The setter itself accepts any value (including {@code null}); enforcement happens at {@code build()} time.
     *
     * @param v
     *            the factory
     * @return this builder
     */
    public SessionRouterBuilder sessionFactory(LiveSessionFactory v) {
        this.sessionFactory = v;
        return this;
    }

    /**
     * Configures the manager with a caller-supplied {@link LiveSessionOpener} so the implementation can read
     * caller-domain attributes via {@link at.aimon.core.agent.session.OpenAttributes}. Mutually exclusive with
     * {@link #sessionFactory(LiveSessionFactory)} — see that method for the validation contract.
     *
     * @param v
     *            the opener
     * @return this builder
     */
    public SessionRouterBuilder sessionOpener(LiveSessionOpener v) {
        this.sessionOpener = v;
        return this;
    }

    /**
     * Sets the record store used to load and persist session records. Required.
     *
     * @param v
     *            the repository
     * @return this builder
     */
    public SessionRouterBuilder sessionRecordStore(SessionRecordStore v) {
        this.sessionRecordStore = v;
        return this;
    }

    /**
     * Sets the holder-election SPI. Required in {@link DeploymentMode#DISTRIBUTED}; defaults to in-memory in
     * {@link DeploymentMode#SINGLE_NODE SINGLE_NODE}.
     *
     * @param v
     *            the lease store
     * @return this builder
     */
    public SessionRouterBuilder sessionLeaseStore(SessionLeaseStore v) {
        this.sessionLeaseStore = v;
        return this;
    }

    /**
     * Sets the cross-node signal bus SPI used to wake holders and broadcast lifecycle events. Required in
     * {@link DeploymentMode#DISTRIBUTED}; defaults to in-memory in {@link DeploymentMode#SINGLE_NODE SINGLE_NODE}.
     *
     * @param v
     *            the signal bus
     * @return this builder
     */
    public SessionRouterBuilder signalBus(SessionSignalBus v) {
        this.signalBus = v;
        return this;
    }

    /**
     * Sets the session inbox SPI used to durably enqueue inbound messages so the lock holder can drain them.
     * Required in {@link DeploymentMode#DISTRIBUTED}; defaults to in-memory in
     * {@link DeploymentMode#SINGLE_NODE SINGLE_NODE}.
     *
     * @param v
     *            the inbox
     * @return this builder
     */
    public SessionRouterBuilder sessionInbox(SessionInbox v) {
        this.sessionInbox = v;
        return this;
    }

    /**
     * Sets the idempotency store SPI used to deduplicate inbound submissions across retries and nodes. Required in
     * {@link DeploymentMode#DISTRIBUTED}; defaults to in-memory in {@link DeploymentMode#SINGLE_NODE SINGLE_NODE}.
     *
     * @param v
     *            the idempotency store
     * @return this builder
     */
    public SessionRouterBuilder idempotencyStore(IdempotencyStore v) {
        this.idempotencyStore = v;
        return this;
    }

    /**
     * Sets the deployment mode. {@link DeploymentMode#SINGLE_NODE SINGLE_NODE} permits in-memory SPI defaults;
     * {@link DeploymentMode#DISTRIBUTED} requires explicit SPIs and a non-blank {@link #nodeId(String)}.
     *
     * @param v
     *            the mode
     * @return this builder
     */
    public SessionRouterBuilder mode(DeploymentMode v) {
        this.mode = v;
        return this;
    }

    /**
     * Sets the stable node identity used as the lock holder identifier. Required (non-blank) in
     * {@link DeploymentMode#DISTRIBUTED}; auto-generated in {@link DeploymentMode#SINGLE_NODE SINGLE_NODE} when unset.
     *
     * @param v
     *            the node id
     * @return this builder
     */
    public SessionRouterBuilder nodeId(String v) {
        this.nodeId = v;
        return this;
    }

    /**
     * Sets how long a cached session may remain idle before it is evicted and its lock released.
     *
     * @param v
     *            the idle TTL
     * @return this builder
     */
    public SessionRouterBuilder idleTtl(Duration v) {
        this.idleTtl = v;
        return this;
    }

    /**
     * Sets the upper bound on locally cached sessions. Eviction past this size releases the corresponding lock.
     *
     * <p>
     * <b>This also sets lease-renewal load, and the pool carrying it does not follow.</b> Since Stage 3b a lease lives
     * as long as its session rather than as long as a turn, so every session this node holds owns a
     * {@code scheduleAtFixedRate} tick of its own ({@code LeaseRenewer}), and each tick is a store round-trip plus an
     * idempotency touch. Those ticks run on a pool sized from {@code availableProcessors()} — a number that has nothing
     * to do with this one. The renewal pool's own note works the arithmetic out: past some population a store
     * answering in single-digit milliseconds is enough to fill a whole extend interval, after which ticks slip and
     * leases are lost for no reason but queueing, which is indistinguishable downstream from a lease that was stolen.
     * The shipped {@link #DEFAULT_MAX_CACHED_SESSIONS} sits inside that budget on an ordinary node. Raising this by an
     * order of magnitude — especially on a small or CPU-limited one — wants {@link #lockLease(Duration)} and
     * {@link #lockExtendInterval(Duration)} widened alongside it. The crossover point is not measured here.
     *
     * @param v
     *            the maximum cache size
     * @return this builder
     */
    public SessionRouterBuilder maxCachedSessions(int v) {
        this.maxCachedSessions = v;
        return this;
    }

    /**
     * Sets the lock lease duration. Must be strictly greater than {@link #lockExtendInterval(Duration)} so renewal can
     * keep the lease alive — {@link #build()} enforces that, where it used to be documented and unchecked.
     *
     * <p>
     * The ratio between the two is the number of consecutive renewal failures the lease survives; see
     * {@link #DEFAULT_LOCK_LEASE} for what the shipped 30s/10s pair buys.
     *
     * @param v
     *            the lease duration
     * @return this builder
     */
    public SessionRouterBuilder lockLease(Duration v) {
        this.lockLease = v;
        return this;
    }

    /**
     * Sets how often the lease renewer extends the lock. Must be strictly under {@link #lockLease(Duration)}, and in
     * practice well under it — the quotient is the renewal-failure budget.
     *
     * <p>
     * Renewal ticks run on a scheduler dedicated to renewal, separate from the pool that carries idle sweeps, the
     * holder-loss sweep, {@code STATUS} heartbeats and every forward poll. That separation is deliberate: a renewal
     * tick that is merely late looks exactly like a lease that was stolen, so renewal must not queue behind unrelated
     * work whose volume grows with the number of live sessions.
     *
     * <p>
     * That scheduler holds {@code max(2, cores / 2)} threads rather than one. A lease now lives as long as its session
     * instead of as long as a turn, so what renews there is every session this node holds, and each tick is a
     * round-trip into the lease backend — one thread both serializes those ticks and lets a single unresponsive
     * backend call starve every other session's renewal behind it.
     *
     * @param v
     *            the renewal interval
     * @return this builder
     */
    public SessionRouterBuilder lockExtendInterval(Duration v) {
        this.lockExtendInterval = v;
        return this;
    }

    /**
     * Sets the cadence of the holder-side {@code STATUS} snapshot heartbeat (see {@link #statusBroadcast(boolean)}).
     * Ignored unless status broadcast is enabled.
     *
     * <p>
     * This used to be an alias of {@link #lockExtendInterval(Duration)}, which conflated two unrelated decisions: how
     * much renewal-failure headroom the lease has, and how fresh a remote node's view of a running turn is. Tightening
     * observability silently tightened the lease budget, and lengthening the lease silently made status staler. They
     * are
     * now independent, and the default merely happens to be the same number.
     *
     * @param v
     *            the heartbeat cadence
     * @return this builder
     */
    public SessionRouterBuilder statusHeartbeatInterval(Duration v) {
        this.statusHeartbeatInterval = v;
        return this;
    }

    /**
     * Sets how often the holder-loss sweeper checks for stranded sessions whose lock holder has died.
     *
     * @param v
     *            the sweep interval
     * @return this builder
     */
    public SessionRouterBuilder holderLossSweepInterval(Duration v) {
        this.holderLossSweepInterval = v;
        return this;
    }

    /**
     * Sets the primary idempotency record TTL — how long a completed submission's result is kept for replay.
     *
     * @param v
     *            the primary TTL
     * @return this builder
     */
    public SessionRouterBuilder idempotencyPrimaryTtl(Duration v) {
        this.idempotencyPrimaryTtl = v;
        return this;
    }

    /**
     * Sets the secondary idempotency record TTL — how long an in-flight submission marker survives so concurrent
     * retries collapse onto one execution.
     *
     * @param v
     *            the secondary TTL
     * @return this builder
     */
    public SessionRouterBuilder idempotencySecondaryTtl(Duration v) {
        this.idempotencySecondaryTtl = v;
        return this;
    }

    /**
     * Sets how long a key stays reserved after its submit lost the session lock and forwarded the turn to the
     * inbox. This is a queue-wait budget, not a lease: nobody renews the entry while the message waits to be drained,
     * so it must be long enough to cover the busiest expected backlog on one session.
     *
     * <p>
     * Too short and a client retry arriving during the wait is treated as a first arrival and executes the input twice.
     * Too long and a message that is never drained (node loss with a non-durable inbox) keeps answering retries with
     * "queued" for a turn that will never run. The default sits far above a normal turn and well below a shift.
     *
     * @param v
     *            the forward-reservation TTL
     * @return this builder
     */
    public SessionRouterBuilder idempotencyForwardTtl(Duration v) {
        this.idempotencyForwardTtl = v;
        return this;
    }

    /**
     * Sets the timeout used when interrupting an in-flight session during release. The release path waits at most this
     * long for the worker to acknowledge before forcefully proceeding.
     *
     * @param v
     *            the interrupt timeout
     * @return this builder
     */
    public SessionRouterBuilder releaseInterruptTimeout(Duration v) {
        this.releaseInterruptTimeout = v;
        return this;
    }

    /**
     * Sets the metrics sink. Passing {@code null} resets to {@link SessionMetrics#NOOP}.
     *
     * @param v
     *            the metrics sink, or {@code null} for no-op
     * @return this builder
     */
    public SessionRouterBuilder metrics(SessionMetrics v) {
        this.metrics = v == null ? SessionMetrics.NOOP : v;
        return this;
    }

    /**
     * Enables holder-side {@code STATUS} snapshot broadcast for cluster-wide observability (default: disabled). When
     * enabled, the lock-holding node broadcasts a {@code LiveSessionStatus} snapshot at turn start, on a heartbeat
     * while a turn runs, and at turn end, so any subscribed node can answer {@link SessionRouter#status} for a
     * session running elsewhere. Keep it off until every node in the cluster runs a build that understands the
     * {@code STATUS} signal kind, then enable it cluster-wide — so a rolling deploy never emits a kind an older node
     * would reject.
     *
     * <p>
     * The cadence is {@link #statusHeartbeatInterval(Duration)}, which defaults to the same 10s the lease renewer uses
     * but is configured independently of it. Size it against the observability/traffic trade-off alone: one signal per
     * active session per interval, on the shared scheduler.
     *
     * @param enabled
     *            whether holder nodes broadcast status snapshots
     * @return this builder
     */
    public SessionRouterBuilder statusBroadcast(boolean enabled) {
        this.statusBroadcast = enabled;
        return this;
    }

    /**
     * Sets the session-scoped skill approval store the manager purges when a session is released or deleted,
     * and when a peer node broadcasts {@code EVICT} for one. Optional — leave unset when the deployment does not cache
     * approvals per session; the lifecycle hooks then simply do nothing.
     *
     * <p>
     * Pass the same instance handed to {@code OrcaAgentRuntimeFactory#withSessionApprovalStore}. Wiring a
     * different instance is not a correctness hazard but makes the purge a no-op on the entries that matter: approvals
     * granted in a deleted session would stay cached until the process exits, and a later session reusing
     * that {@code SessionId} would inherit them without asking the user.
     *
     * @param v
     *            the store, or {@code null} to disable the purge
     * @return this builder
     */
    public SessionRouterBuilder sessionApprovalStore(SessionApprovalStore v) {
        this.sessionApprovalStore = v;
        return this;
    }

    /**
     * Validates configuration and constructs a {@link SessionRouter}. In
     * {@link DeploymentMode#DISTRIBUTED} mode, fails fast if any SPI or {@code nodeId} is missing.
     *
     * @return the built manager
     */

    public SessionRouter build() {
        if (sessionFactory == null && sessionOpener == null) {
            throw new IllegalStateException("Either sessionFactory(...) or sessionOpener(...) must be set");
        }
        if (sessionFactory != null && sessionOpener != null) {
            throw new IllegalStateException("sessionFactory(...) and sessionOpener(...) are mutually exclusive");
        }
        Objects.requireNonNull(sessionRecordStore, "sessionRecordStore must be set");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(idleTtl, "idleTtl must not be null");
        Objects.requireNonNull(lockLease, "lockLease must not be null");
        Objects.requireNonNull(lockExtendInterval, "lockExtendInterval must not be null");
        Objects.requireNonNull(statusHeartbeatInterval, "statusHeartbeatInterval must not be null");
        Objects.requireNonNull(holderLossSweepInterval, "holderLossSweepInterval must not be null");
        Objects.requireNonNull(idempotencyPrimaryTtl, "idempotencyPrimaryTtl must not be null");
        Objects.requireNonNull(idempotencySecondaryTtl, "idempotencySecondaryTtl must not be null");
        Objects.requireNonNull(idempotencyForwardTtl, "idempotencyForwardTtl must not be null");
        Objects.requireNonNull(releaseInterruptTimeout, "releaseInterruptTimeout must not be null");
        // The lease timings are validated where the config is assembled below, not here: that is the one door every
        // construction path goes through, including the test harnesses that build a config directly.

        if (mode == DeploymentMode.DISTRIBUTED) {
            requireExplicit(sessionLeaseStore, "sessionLeaseStore");
            requireExplicit(signalBus, "signalBus");
            requireExplicit(sessionInbox, "sessionInbox");
            requireExplicit(idempotencyStore, "idempotencyStore");
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalStateException(
                        "nodeId must be set in DISTRIBUTED mode (used for lease holder identity)");
            }
        } else {
            if (sessionLeaseStore == null) {
                sessionLeaseStore = new InMemorySessionLeaseStore();
            }
            if (signalBus == null) {
                signalBus = new InMemorySignalBus();
            }
            if (sessionInbox == null) {
                sessionInbox = new InMemorySessionInbox();
            }
            if (idempotencyStore == null) {
                idempotencyStore = new InMemoryIdempotencyStore(Clock.systemUTC(), idempotencyPrimaryTtl);
            }
            if (nodeId == null || nodeId.isBlank()) {
                nodeId = "node-" + UUID.randomUUID();
            }
        }

        final SessionStore store = new DefaultSessionStore(sessionLeaseStore, sessionRecordStore);

        final SessionRouterConfig config = SessionRouterConfig.builder().store(store).signalBus(signalBus)
                .inbox(sessionInbox).idempotencyStore(idempotencyStore).nodeId(nodeId).idleTtl(idleTtl)
                .maxCachedSessions(maxCachedSessions).lockLease(lockLease).lockExtendInterval(lockExtendInterval)
                .statusHeartbeatInterval(statusHeartbeatInterval).holderLossSweepInterval(holderLossSweepInterval)
                .idempotencyPrimaryTtl(idempotencyPrimaryTtl).idempotencySecondaryTtl(idempotencySecondaryTtl)
                .idempotencyForwardTtl(idempotencyForwardTtl).releaseInterruptTimeout(releaseInterruptTimeout)
                .metrics(metrics).sessionApprovalStore(sessionApprovalStore).build();

        final DefaultSessionRouter manager;
        if (sessionOpener != null) {
            manager = new DefaultSessionRouter(sessionOpener, config);
        } else {
            manager = new DefaultSessionRouter(sessionFactory, config);
        }
        manager.setStatusBroadcastEnabled(statusBroadcast);
        return manager;
    }

    private static void requireExplicit(Object spi, String name) {
        if (spi == null) {
            throw new IllegalStateException(name
                    + " must be explicitly set in DISTRIBUTED mode (in-memory defaults are not safe across nodes)");
        }
    }
}
