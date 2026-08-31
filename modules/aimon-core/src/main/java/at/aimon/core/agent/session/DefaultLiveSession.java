package at.aimon.core.agent.session;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.budget.BudgetTracker;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.MessageQueueListener;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.session.transcript.SessionRewindPoint;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.StreamingAgentExecutor;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.llm.Message;

/**
 * Default {@link LiveSession} implementation that wraps an {@link OrcaAgentRuntime} and an
 * {@link AgentExecutor}.
 *
 * <p>
 * Each call to {@link #submit(String)} builds a fresh {@link OrcaAgentExecutionRequest} internally using the
 * session's fixed {@link SessionId} and injects the session default {@link ExecutionBudget} from the options.
 * Because the same {@code SessionId} is reused across every turn, the executor's transcript manager
 * transparently
 * loads and appends to the same {@code TranscriptBuffer} — preserving history, artifacts, CTX-06 injection state,
 * and CQ-03 mid-turn queue semantics between turns.
 *
 * <h2>Resource ownership</h2>
 *
 * <p>
 * The session does <b>not</b> own the {@link OrcaAgentRuntime}: per the scope model (see
 * {@code .claude/rules/scheduling.md}), the context is <b>agent-scoped</b> and shared across every session
 * against the same {@code (Agent, discriminator)} pair. {@link #close()} therefore tears down only this session's own
 * session-scoped state (queue listener, active interrupt coordinator) and never invokes
 * {@link OrcaAgentRuntime#close()} — that call is reserved for application shutdown / explicit agent
 * removal and is performed by the bootstrap, not by sessions. The {@link AgentExecutor} and its dependencies (LLM
 * client, session record store, tool / hook / command / subagent managers) are likewise application-scoped and
 * are never closed here.
 *
 * <h2>Dependency inversion</h2>
 *
 * <p>
 * The session depends on the {@link AgentExecutor} abstraction rather than on a concrete implementation. Any executor
 * that produces {@link OrcaAgentExecutionResult} from {@link OrcaAgentRuntime} and
 * {@link OrcaAgentExecutionRequest} is acceptable — this keeps the session testable with a lightweight stub and lets
 * application code wrap the executor (e.g., via {@code InterceptingAgentExecutor}) without modifying session code.
 *
 * <h2>Thread-safety</h2>
 *
 * <p>
 * Submitting turns is <b>not</b> thread-safe: callers that need to multiplex submissions across threads must
 * synchronize externally. Two pieces of state are exempt, because their whole purpose is to be touched from another
 * thread. The closed-state flag is {@code volatile}, so a {@link #close()} on one thread is at least visible to a
 * subsequent {@link #submit(String)} on another. And the {@link #activeTurn active turn} is a single atomic reference
 * rather than a set of fields, so {@link #interrupt(TurnId, InterruptReason)}, {@link #status()} and {@link #close()}
 * — all of which legitimately run on a thread other than the one executing the turn — decide from one consistent read
 * of what is running. Everything else remains unsynchronized by design.
 *
 * @see LiveSession
 * @see LiveSessionFactory
 */
public final class DefaultLiveSession implements LiveSession {

    private static final Logger log = LoggerFactory.getLogger(DefaultLiveSession.class);

    private final SessionId sessionId;
    private final OrcaAgentRuntime agentRuntime;
    // @formatter:off
    private final AgentExecutor<
            OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> executor;
    // @formatter:on
    private final MessageQueueManager messageQueueManager;
    /**
     * Optional hook execution manager — when non-null, {@link #close()} fires {@code OnSessionEnd} hooks and the
     * constructor fires {@code OnSessionStart} hooks. Legacy callers that omit it via the older
     * 4-/5-arg constructors continue to work without firing the session-lifecycle chain.
     */
    private final HookExecutionManager hookExecutionManager;
    private volatile LiveSessionOptions options;
    /**
     * The opener-supplied default {@link ExecutionBudget} captured at construction, before any persisted runtime
     * override is hydrated over it. {@link #clearBudgetOverride()} reverts the effective budget to this value.
     */
    private final ExecutionBudget openerDefaultBudget;
    private volatile boolean closed;
    /**
     * Guards the session against concurrent or re-entrant turns so {@link #offerAsync} can decide whether to execute
     * directly or enqueue. The flag flips on successful acquisition and is reset when the in-flight stage completes
     * (synchronously via {@code whenComplete}), regardless of success / failure / cancellation.
     *
     * <p>
     * Intentionally not exposed as a getter: external hosts that need to observe busy-state should call
     * {@link #offerAsync} and inspect the {@link SubmitOutcome}.
     */
    private final AtomicBoolean busy = new AtomicBoolean(false);
    /**
     * The turn currently executing on this session, or {@code null} when the session is idle.
     *
     * <p>
     * The turn's three handles — {@link TurnId}, {@link InterruptCoordinator} and {@link BudgetTracker} — live together
     * inside one {@link ActiveTurn} behind one reference, rather than in three refs of their own. They describe a
     * single fact ("what is running, and what cancels it"), so splitting them across independent refs made every reader
     * that needed two of them race: {@link #interrupt(TurnId, InterruptReason)} matched the id from one ref and then
     * re-read the coordinator from another, and an interrupt that arrived while the addressed turn settled could land
     * on its successor — precisely the turn that overload exists to spare. One ref, one read, no window.
     *
     * <p>
     * The id is known before the executor is called and is installed up front, so a turn that has started but not yet
     * reached the ReAct loop is already addressable. The coordinator and tracker are published into the same object
     * later, at loop entry, via {@link OrcaAgentExecutionRequest#getInterruptObserver()} and
     * {@link OrcaAgentExecutionRequest#getBudgetObserver()} — hence they are {@code volatile} within
     * {@link ActiveTurn} while the reference itself is swapped atomically. Clearing uses
     * {@link AtomicReference#compareAndSet(Object, Object)} so overlapping turns (allowed only when no
     * {@link MessageQueueManager} is wired) never clobber a still-live peer.
     */
    private final AtomicReference<ActiveTurn> activeTurn = new AtomicReference<>();
    /**
     * Session-cumulative totals folded across every completed turn (turn count, iterations, token usage), so
     * {@link #status()} can report "this session used N tokens across M turns". Updated atomically via
     * {@link AtomicReference#updateAndGet} in {@link #clearActiveTurn} so overlapping turns (allowed only when no
     * {@link MessageQueueManager} is wired) accumulate without losing updates. Starts at
     * {@link SessionTotals#empty()} and only moves forward — survives {@link #close()}; a turn
     * still in flight when {@link #close()} is called is folded once it settles. Turns that never publish a tracker
     * (e.g. slash-command turns, which bypass the ReAct loop) are excluded.
     */
    private final AtomicReference<SessionTotals> sessionTotals = new AtomicReference<>(SessionTotals.empty());
    /**
     * Listener subscribed to {@link #messageQueueManager} that trips the active coordinator whenever a
     * {@link QueuedInputPriority#NOW} input targeting this session's {@link OrcaAgentRuntime#getId() context
     * id} is enqueued. {@code null} when no queue is wired. Unregistered in {@link #close()}.
     */
    private final MessageQueueListener nowPriorityListener;
    /**
     * The durable session record this session runs turns against, or {@code null} when the session is not wired
     * to one (in-memory-only behavior). The session reads it once at construction to hydrate
     * {@link #sessionTotals} and {@link #budgetOverride}, and writes those two fields back through
     * {@link #flushDurableState()}. Every write is best-effort — a failure logs a warning and is swallowed so it never
     * breaks a turn or the session. Not owned by this session: the record outlives it.
     */
    private final SessionRecordStore sessionRecords;
    /**
     * The runtime {@link ExecutionBudget} override currently in force, or {@code null} when none is — that is, when the
     * effective budget is still {@link #openerDefaultBudget}.
     *
     * <p>
     * This field exists because the durable write is a <em>pair</em> write: {@link #flushDurableState()} sends totals
     * and override together, so the end-of-turn flush must be able to state the override even on a turn that never
     * touched it. Without it the first turn after open would write {@code null} over an override this session
     * hydrated, silently erasing it. Kept in lockstep with {@link #options}: it mirrors whatever
     * {@link #setOptions(LiveSessionOptions)} last installed, and {@link #clearBudgetOverride()} nulls it.
     */
    private volatile ExecutionBudget budgetOverride;

    /**
     * Creates a new session without a message-queue integration. Equivalent to the
     * {@link #DefaultLiveSession(SessionId, OrcaAgentRuntime, AgentExecutor, LiveSessionOptions,
     * MessageQueueManager) 5-arg constructor} with a {@code null} queue — {@link #offerAsync} will always return
     * {@link SubmitOutcome.Kind#EXECUTED} (never enqueue) and falls back to direct execution even under concurrent
     * access.
     */
    public DefaultLiveSession(SessionId sessionId, OrcaAgentRuntime agentRuntime,
            AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> executor,
            LiveSessionOptions options) {
        this(sessionId, agentRuntime, executor, options, null, null, null);
    }

    /**
     * Creates a new session bound to {@code sessionId}, optionally wiring a {@link MessageQueueManager} so the
     * session can auto-enqueue turns that arrive while another turn is in flight.
     *
     * @param sessionId
     *            the durable session id shared by all turns this session runs (must not be null)
     * @param agentRuntime
     *            the underlying Orca agent runtime (must not be null). The session holds a reference but does
     *            <b>not</b> own this context. The context is agent-scoped (shared across sessions) and is closed only
     *            at agent removal or application shutdown.
     * @param executor
     *            the agent executor used to run each turn (must not be null). Not owned by this session.
     * @param options
     *            the session options (must not be null). {@link LiveSessionOptions#getBudget()} supplies the default
     *            {@link ExecutionBudget} injected into every built request.
     * @param messageQueueManager
     *            the queue manager used by {@link #offerAsync} when the session is already busy, or {@code null} to
     *            opt out of auto-queueing (offerAsync then falls back to direct execution).
     * @throws NullPointerException
     *             if any argument other than {@code messageQueueManager} is null
     */
    public DefaultLiveSession(SessionId sessionId, OrcaAgentRuntime agentRuntime,
            AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> executor,
            LiveSessionOptions options, MessageQueueManager messageQueueManager) {
        this(sessionId, agentRuntime, executor, options, messageQueueManager, null, null);
    }

    /**
     * Creates a new session bound to {@code sessionId}, optionally wiring a {@link MessageQueueManager} and a
     * {@link HookExecutionManager} so the session fires {@code OnSessionStart} / {@code OnSessionEnd} hooks
     * around its lifecycle.
     *
     * @param sessionId
     *            the durable session id shared by all turns this session runs (must not be null)
     * @param agentRuntime
     *            the underlying Orca agent runtime (must not be null). The session holds a reference but does
     *            <b>not</b> own this context. The context is agent-scoped (shared across sessions) and is closed only
     *            at agent removal or application shutdown.
     * @param executor
     *            the agent executor used to run each turn (must not be null). Not owned by this session.
     * @param options
     *            the session options (must not be null). {@link LiveSessionOptions#getBudget()} supplies the default
     *            {@link ExecutionBudget} injected into every built request.
     * @param messageQueueManager
     *            the queue manager used by {@link #offerAsync} when the session is already busy, or {@code null} to
     *            opt out of auto-queueing.
     * @param hookExecutionManager
     *            the hook execution manager used to fire session-lifecycle hooks, or {@code null} to opt out. Not
     *            owned by this session.
     * @throws NullPointerException
     *             if any argument other than {@code messageQueueManager} or {@code hookExecutionManager} is null
     */
    public DefaultLiveSession(SessionId sessionId, OrcaAgentRuntime agentRuntime,
            AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> executor,
            LiveSessionOptions options, MessageQueueManager messageQueueManager,
            HookExecutionManager hookExecutionManager) {
        this(sessionId, agentRuntime, executor, options, messageQueueManager, hookExecutionManager, null);
    }

    /**
     * Creates a new session bound to {@code sessionId}, additionally wiring the {@link SessionRecordStore}
     * that holds the session's durable record, so the session hydrates its restart-durable state
     * ({@code sessionTotals}, runtime budget override) from it at open and writes that state back at end-of-turn /
     * on {@link #setOptions(LiveSessionOptions)}.
     *
     * <p>
     * This is the fullest constructor; the shorter overloads delegate here passing {@code null} (which preserves the
     * in-memory-only behavior). Hydration runs before any {@code OnSessionStart} hook fires: the persisted totals seed
     * the in-memory accumulator and, when a persisted budget override is present, it takes precedence over the
     * opener-supplied default budget (the override wins) while preserving the options' locale / source agent id.
     *
     * @param sessionId
     *            the durable session id shared by all turns this session runs (must not be null)
     * @param agentRuntime
     *            the underlying Orca agent runtime (must not be null). The session holds a reference but does
     *            <b>not</b> own this context. The context is agent-scoped (shared across sessions) and is closed only
     *            at agent removal or application shutdown.
     * @param executor
     *            the agent executor used to run each turn (must not be null). Not owned by this session.
     * @param options
     *            the session options (must not be null). {@link LiveSessionOptions#getBudget()} supplies the default
     *            {@link ExecutionBudget} injected into every built request, unless overridden by a persisted budget.
     * @param messageQueueManager
     *            the queue manager used by {@link #offerAsync} when the session is already busy, or {@code null} to
     *            opt out of auto-queueing.
     * @param hookExecutionManager
     *            the hook execution manager used to fire session-lifecycle hooks, or {@code null} to opt out. Not
     *            owned by this session.
     * @param sessionRecords
     *            the store holding the session's durable record, or {@code null} to run without durable
     *            state. Not owned by this session.
     * @throws NullPointerException
     *             if any argument other than {@code messageQueueManager}, {@code hookExecutionManager} or
     *             {@code sessionRecords} is null
     */
    public DefaultLiveSession(SessionId sessionId, OrcaAgentRuntime agentRuntime,
            AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> executor,
            LiveSessionOptions options, MessageQueueManager messageQueueManager,
            HookExecutionManager hookExecutionManager, SessionRecordStore sessionRecords) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.agentRuntime = Objects.requireNonNull(agentRuntime, "agentRuntime must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        // Capture the opener default budget before hydration may overlay a persisted override (see hydrateFromRecord
        // / clearBudgetOverride). Reads getBudget() off the validated options, which never returns null.
        this.openerDefaultBudget = options.getBudget();
        this.messageQueueManager = messageQueueManager;
        this.hookExecutionManager = hookExecutionManager;
        this.sessionRecords = sessionRecords;
        if (messageQueueManager != null) {
            this.nowPriorityListener = this::onQueueEvent;
            messageQueueManager.addListener(this.nowPriorityListener);
        } else {
            this.nowPriorityListener = null;
        }
        hydrateFromRecord();
        fireOnSessionStart();
    }

    /**
     * Hydrates the session-owned durable state out of the session record at construction, before any session-start
     * hook fires. Seeds the in-memory {@link #sessionTotals} accumulator with the persisted totals and, when a
     * persisted runtime budget override is present, remembers it in {@link #budgetOverride} and applies it over the
     * opener-supplied default (the override wins), preserving the options' locale / source agent id.
     *
     * <p>
     * A fresh session — no store wired, or no record yet — leaves the totals empty, the override null, and
     * the options untouched.
     */
    private void hydrateFromRecord() {
        if (sessionRecords == null) {
            return;
        }
        sessionRecords.load(sessionId).ifPresent(record -> {
            this.sessionTotals.set(record.getSessionTotals());
            record.getBudgetOverride().ifPresent(budget -> {
                this.budgetOverride = budget;
                this.options = this.options.withBudget(budget);
            });
        });
    }

    /**
     * Writes this session's two durable fields — the absolute {@link #sessionTotals} and the current
     * {@link #budgetOverride} — back to the session record. Best-effort by design: a persistence failure logs a
     * warning and is swallowed, because losing a totals update must never fail a turn the user already got an answer
     * from.
     *
     * <p>
     * Called from three places, which is the whole set of moments this state changes: at end of turn
     * ({@link #clearActiveTurn}), and the two out-of-turn writes {@link #setOptions(LiveSessionOptions)} and
     * {@link #clearBudgetOverride()}. The write is absolute rather than a delta, so a duplicate flush cannot
     * double-count a turn.
     */
    private void flushDurableState() {
        if (sessionRecords == null) {
            return;
        }
        try {
            sessionRecords.setTotalsAndBudgetOverride(sessionId, sessionTotals.get(), budgetOverride);
        } catch (Exception e) {
            log.warn("Durable-state flush failed for session {}: {}", sessionId, e.toString());
        }
    }

    private void fireOnSessionStart() {
        if (hookExecutionManager == null) {
            return;
        }
        try {
            final OnSessionStartContext ctx = OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                    .invokerName(sessionId.toString()).hookRegistry(agentRuntime.getHookRegistry())
                    .environment(agentRuntime.getEnvironment()).sessionId(sessionId)
                    .agentRuntimeId(agentRuntime.getId() != null ? agentRuntime.getId().value() : "").build();
            hookExecutionManager.executeOnSessionStart(ctx);
        } catch (Exception e) {
            log.warn("OnSessionStart hook failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    private void fireOnSessionEnd() {
        if (hookExecutionManager == null) {
            return;
        }
        try {
            final OnSessionEndContext ctx = OnSessionEndContext.builder().invokerType(InvokerType.MAIN_AGENT)
                    .invokerName(sessionId.toString()).hookRegistry(agentRuntime.getHookRegistry())
                    .environment(agentRuntime.getEnvironment()).sessionId(sessionId)
                    .agentRuntimeId(agentRuntime.getId() != null ? agentRuntime.getId().value() : "").clean(true)
                    .build();
            hookExecutionManager.executeOnSessionEnd(ctx);
        } catch (Exception e) {
            log.warn("OnSessionEnd hook failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Fires {@link #interrupt(InterruptReason) interrupt(NOW_PRIORITY_INPUT)} whenever a
     * {@link QueuedInputPriority#NOW NOW}-priority input targeting this session.s agent runtime lands in the
     * shared message queue. Non-NOW priorities and inputs targeting a different context id are ignored so sibling
     * sessions / NEXT-priority enqueues never trip each other.
     */
    private void onQueueEvent(MessageQueueListener.Event event) {
        if (event.getChangeType() != MessageQueueListener.ChangeType.ENQUEUED) {
            return;
        }
        final QueuedInput input = event.getInput();
        if (input.getPriority() != QueuedInputPriority.NOW) {
            return;
        }
        if (!agentRuntime.getId().equals(input.getAgentRuntimeId())) {
            return;
        }
        interrupt(InterruptReason.NOW_PRIORITY_INPUT);
    }

    @Override
    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Reports the session's live runtime state. The {@link LiveSessionStatus.Phase phase} is derived from the
     * published turn handles rather than the {@link #busy} guard: {@code RUNNING} iff an interrupt coordinator or a
     * budget tracker is currently published for an in-flight turn (this covers <em>all</em> submit paths, not just
     * {@link #offerAsync}), {@code CLOSED} once {@link #close()} has run, otherwise {@code IDLE}. The handles are
     * published at ReAct loop entry, so the session still reports {@code IDLE} during the brief window between a
     * {@code submit*} call and loop entry, and for the whole duration of slash-command turns (which bypass the ReAct
     * loop and never publish handles — matching the existing {@link #interrupt(InterruptReason) interrupt} no-op
     * behaviour for command turns). This is acceptable for the diagnostic / UI use case.
     *
     * <p>
     * Live {@link LiveSessionStatus.TurnProgress turn progress} is included only when the executor published a
     * {@link BudgetTracker} for the active turn. The tracker is read best-effort (it is not thread-safe), so the
     * reported counters may be marginally stale relative to a concurrently advancing turn. Once a turn finishes the
     * live progress disappears; the turn's contribution is folded into the cumulative
     * {@link LiveSessionStatus#getSessionTotals() sessionTotals}, which stay visible after the session
     * returns to
     * {@code IDLE} / {@code CLOSED}.
     */
    @Override
    public LiveSessionStatus status() {
        final ActiveTurn turn = activeTurn.get();
        // Phase stays derived from the *published* handles, not from the mere existence of an ActiveTurn: the id is
        // installed before the executor is entered, so keying off it would flip the session to RUNNING during the
        // pre-loop window and for the whole of a slash-command turn, which this method documents as IDLE.
        final InterruptCoordinator coordinator = turn == null ? null : turn.coordinator;
        final BudgetTracker tracker = turn == null ? null : turn.tracker;
        final boolean turnActive = coordinator != null || tracker != null;
        final LiveSessionStatus.Phase phase;
        if (closed) {
            phase = LiveSessionStatus.Phase.CLOSED;
        } else if (turnActive) {
            phase = LiveSessionStatus.Phase.RUNNING;
        } else {
            phase = LiveSessionStatus.Phase.IDLE;
        }
        final LiveSessionStatus.Builder builder = LiveSessionStatus.builder().sessionId(sessionId).phase(phase)
                .interruptible(coordinator != null)
                .queueDepth(messageQueueManager != null ? messageQueueManager.snapshot().size() : 0).options(options)
                .sessionTotals(sessionTotals.get());
        if (tracker != null) {
            builder.turnProgress(snapshot(tracker));
        }
        return builder.build();
    }

    /**
     * Captures an immutable {@link LiveSessionStatus.TurnProgress} from the given tracker's current counters. Shared
     * by {@link #status()} (live progress) and {@link #clearActiveTurn} (final, retained snapshot).
     */
    private static LiveSessionStatus.TurnProgress snapshot(BudgetTracker tracker) {
        return LiveSessionStatus.TurnProgress.of(tracker.iterations(), tracker.tokens(), tracker.elapsed(),
                tracker.getBudget());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Wraps and runs rather than calling {@link #submit(UserInput, SubmitOptions)}: the interface default for that
     * overload unwraps a {@link TextInput} back into <em>this</em> method, so routing through it would be a cycle
     * the moment the {@code UserInput} override below were removed. Every overload in this class lands on a body
     * defined in this class for that reason.
     */
    @Override
    public AgentExecutionResult submit(String input, SubmitOptions submitOptions) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(submitOptions, "submitOptions must not be null");
        return submitUnderTurn(TurnId.generate(), TextInput.of(input), submitOptions);
    }

    @Override
    public AgentExecutionResult submit(UserInput input, SubmitOptions submitOptions) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(submitOptions, "submitOptions must not be null");
        return submitUnderTurn(TurnId.generate(), input, submitOptions);
    }

    @Override
    public Optional<RewoundTurn> rewindLastTurn() {
        if (closed) {
            throw new IllegalStateException("LiveSession has already been closed: " + sessionId);
        }
        // Rewinding while a turn runs is never a legitimate call, and the failure is silent: the running turn holds
        // its own buffer and writes it back when it ends, putting the trail we just removed straight back. Refusing
        // on an observed turn does not close the window entirely — one starting immediately after this check races
        // the same way — but it turns the common mistake into an error instead of a rewind that quietly did nothing.
        if (activeTurn.get() != null) {
            throw new IllegalStateException(
                    "Cannot rewind session " + sessionId + " while a turn is running — interrupt it first");
        }

        final SessionRecordView record = sessionRecords.load(sessionId).orElse(null);
        final SessionRewindPoint point = record == null ? null : record.getRewindPoint().orElse(null);
        if (point == null) {
            log.debug("Session {} has no interrupted turn to rewind", sessionId);
            return Optional.empty();
        }

        // Written through before returning, not after the caller has submitted. A retry that is itself interrupted
        // then starts from the same place as this one did, instead of stacking a second partial trail on the first.
        rewindPersistedTranscript(record, point);

        log.debug("Rewound the interrupted turn of session {} to message {} (inputType={})", sessionId,
                point.getMessageCount(), point.getUserInput().getType());
        return Optional.of(RewoundTurn.of(point.getUserInput(), point.getSubmitOptions()));
    }

    /**
     * Drops the interrupted turn's messages from the stored record.
     *
     * <p>
     * Goes through {@code mergeFromSnapshot} rather than a rewind primitive of its own, because that is already the
     * one write that replaces a transcript wholesale while leaving the side fields to their own writers — which is
     * exactly what a rewind is. The point is written out as absent in the same document, so the shortened history and
     * "there is nothing left to retry" land together or not at all.
     */
    private void rewindPersistedTranscript(SessionRecordView record, SessionRewindPoint point) {
        final List<Message> kept = List.copyOf(record.getMessages().subList(0, point.getMessageCount()));
        sessionRecords.mergeFromSnapshot(SessionSnapshot.of(sessionId, record.getSystemPrompt(), kept, null));
    }

    /**
     * Runs one synchronous turn under an already-issued {@link TurnId}.
     *
     * <p>
     * Shared by {@link #submit(UserInput, SubmitOptions)} (which issues the id itself) and by the non-streaming
     * fallback in
     * {@link #submitAsync(TurnId, UserInput, SubmitOptions, Consumer)} (which must honour the caller's id). Routing
     * both
     * through here is what keeps {@link #currentTurnId()} reporting the id the caller passed even when the wired
     * executor
     * cannot stream.
     */
    private AgentExecutionResult submitUnderTurn(TurnId turnId, UserInput input, SubmitOptions submitOptions) {
        final ActiveTurn turn = new ActiveTurn(turnId);
        final OrcaAgentExecutionRequest request = buildRequest(input, submitOptions, turn);
        installActiveTurn(turn);

        log.debug("Submitting turn {} for session {} (sourceAgentId={})", turnId, sessionId,
                options.getSourceAgentId().orElse("<unset>"));
        try {
            final OrcaAgentExecutionResult result = executor.execute(agentRuntime, request);
            log.debug("Completed turn {} for session {} (success={}, completionReason={})", turnId, sessionId,
                    result.isSuccess(), result.getCompletionReason());
            return result;
        } finally {
            clearActiveTurn(turn);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * When the underlying executor also implements
     * {@link at.aimon.core.agent.stream.StreamingAgentExecutor StreamingAgentExecutor}, this override delegates to its
     * {@code executeAsync} so that {@link AgentExecutionEvent}s reach {@code listener} as they are emitted. This is the
     * path taken by the Orca executor in the default CLI wiring and preserves the STREAM-04 streaming behavior across
     * the session facade.
     *
     * <p>
     * When the executor is non-streaming (custom test doubles, future backends), this method falls back to the default
     * interface implementation which completes the stage with the result of a synchronous {@link #submit(String)}. The
     * caller still gets a usable {@link CompletionStage}; only the per-event notifications are absent.
     */
    @Override
    public CompletionStage<AgentExecutionResult> submitAsync(String input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(input, "input must not be null");
        return submitAsync(TurnId.generate(), TextInput.of(input), submitOptions, listener);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Kept as an override rather than left to the interface default, which drops the caller's id on the way to the
     * {@code String} overload. Here the id is the point, so it is carried straight to the primitive.
     */
    @Override
    public CompletionStage<AgentExecutionResult> submitAsync(TurnId turnId, String input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(input, "input must not be null");
        return submitAsync(turnId, TextInput.of(input), submitOptions, listener);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Override that publishes {@code turnId} through {@link #currentTurnId()} for the duration of the turn, so
     * {@link #interrupt(TurnId, InterruptReason)} can tell this turn from its successor. The id is installed before the
     * executor is invoked and dropped when the returned stage settles.
     */
    @Override
    public CompletionStage<AgentExecutionResult> submitAsync(TurnId turnId, UserInput input,
            SubmitOptions submitOptions, Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(turnId, "turnId must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(submitOptions, "submitOptions must not be null");
        Objects.requireNonNull(listener, "listener must not be null");

        if (!(executor instanceof StreamingAgentExecutor<?, ?, ?>)) {
            // Non-streaming executor: complete synchronously with the result of the synchronous turn path, preserving
            // the legacy fallback behavior previously provided by the LiveSession default impl.
            final CompletableFuture<AgentExecutionResult> future = new CompletableFuture<>();
            try {
                future.complete(submitUnderTurn(turnId, input, submitOptions));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
            return future;
        }
        final ActiveTurn turn = new ActiveTurn(turnId);
        final OrcaAgentExecutionRequest request = buildRequest(input, submitOptions, turn);
        installActiveTurn(turn);

        @SuppressWarnings("unchecked")
        // @formatter:off
        final StreamingAgentExecutor<
                OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> streaming =
                (StreamingAgentExecutor<
                        OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult>) executor;
        // @formatter:on

        log.debug("Submitting streaming turn {} for session {} (sourceAgentId={})", turnId, sessionId,
                options.getSourceAgentId().orElse("<unset>"));
        return streaming.executeAsync(agentRuntime, request, listener)
                .whenComplete((result, err) -> clearActiveTurn(turn))
                .thenApply(result -> (AgentExecutionResult) result);
    }

    @Override
    public Optional<TurnId> currentTurnId() {
        final ActiveTurn turn = activeTurn.get();
        return turn == null ? Optional.empty() : Optional.of(turn.turnId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Returns {@link SubmitOutcome.Kind#EXECUTED} when the session is idle — the turn runs immediately via
     * {@link #submitAsync submitAsync} and the busy guard is released synchronously on stage completion (success,
     * failure, or cancellation) via {@code whenComplete}. Returns {@link SubmitOutcome.Kind#QUEUED} when the session
     * is already running a turn, a {@link MessageQueueManager} is wired <em>and</em> the input is text — the input is
     * appended with {@link QueuedInputPriority#NEXT} priority and tagged with this session's agent runtime id so the
     * CQ-03 mid-turn drain in the Orca ReAct loop picks it up.
     *
     * <p>
     * When the session is busy and no queue is wired, offerAsync falls back to a plain {@code submitAsync} so callers
     * without queue support still receive a usable stage. Concurrent turns in that mode are the caller's
     * responsibility (the session makes no promise of serialization).
     *
     * <p>
     * When the session is busy and the input is <b>not text</b>, it throws {@link IllegalStateException} instead. The
     * queue is a text channel — a deferred input is replayed as a {@code <system-reminder>} block built from
     * {@link QueuedInput#getInputText()} — so such an input can neither wait nor be flattened into something that
     * could without ceasing to be what the caller submitted; and running it beside the turn already in flight would
     * hand two turns the same transcript. Refusing is the only answer left that is not silently wrong. Nothing in
     * this codebase reaches it: the REPL submits one turn at a time, and a retry cannot start while a turn is running
     * because the rewind refuses first.
     */
    @Override
    public SubmitOutcome offerAsync(String input, SubmitOptions submitOptions, Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(input, "input must not be null");
        return offerAsync(TextInput.of(input), submitOptions, listener);
    }

    @Override
    public SubmitOutcome offerAsync(UserInput input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(submitOptions, "submitOptions must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (closed) {
            throw new IllegalStateException("LiveSession has already been closed: " + sessionId);
        }
        if (!busy.compareAndSet(false, true)) {
            // The queue carries text and nothing else: a deferred input is replayed as a <system-reminder> block, so
            // an image or a document has no form to wait in. Neither answer this method is allowed to give is
            // available for one — it cannot be deferred, and running it anyway would put a second turn on the
            // transcript the running one is still writing, which is precisely what the busy flag exists to prevent.
            // So it refuses, loudly. A caller that means to run turns concurrently has submitAsync for that, and one
            // that means to wait has the running turn's stage to wait on.
            if (!(input instanceof TextInput text)) {
                throw new IllegalStateException("Session " + sessionId + " is running a turn and a " + input.getType()
                        + " input cannot be queued behind it — the mid-turn queue carries text only."
                        + " Use submitAsync to run it concurrently, or wait for the turn to settle");
            }
            if (messageQueueManager == null) {
                log.debug("Session {} busy but no queue wired; falling back to concurrent submitAsync", sessionId);
                return SubmitOutcome.executed(submitAsync(input, submitOptions, listener));
            }
            final QueuedInput queuedInput = QueuedInput.builder().inputText(text.getText())
                    .priority(QueuedInputPriority.NEXT).agentRuntimeId(agentRuntime.getId())
                    .submitOptions(submitOptions).build();
            messageQueueManager.enqueue(queuedInput);
            // Queue depth observed immediately after enqueue. With a single session-owned producer (host input
            // loop) this equals the 1-based position of the just-enqueued item; under concurrent producers it is
            // still a useful upper bound for UX ("[queued: N]") because the queue only grows between enqueues of
            // the same producer. Callers must treat the number as a best-effort depth indicator, not a stable
            // index into the queue.
            final int queueDepth = messageQueueManager.snapshot().size();
            log.debug("Session {} busy; enqueued input at queue depth {} (agentRuntimeId={})", sessionId, queueDepth,
                    agentRuntime.getId());
            return SubmitOutcome.queued(queuedInput, queueDepth);
        }
        try {
            final CompletionStage<AgentExecutionResult> stage = submitAsync(input, submitOptions, listener)
                    .whenComplete((result, err) -> busy.set(false));
            return SubmitOutcome.executed(stage);
        } catch (Throwable e) {
            // Catch Throwable (not just RuntimeException) so that Errors (OOM, AssertionError, StackOverflowError,
            // …) thrown by submitAsync before the stage is returned also release the busy flag. Leaving the flag
            // set would dead-lock the session for the rest of its lifetime.
            busy.set(false);
            throw e;
        }
    }

    /**
     * Builds the {@link OrcaAgentExecutionRequest} shared by the sync and async submit paths.
     *
     * <p>
     * Centralizes the closed-state guard and the session defaults ({@code sessionId}, default
     * {@link ExecutionBudget}) so both entry points agree on what a "submitted turn" looks like. Also wires the
     * interrupt observer and the budget observer so the executor publishes the turn's {@link InterruptCoordinator} and
     * {@link BudgetTracker} straight into {@code turn} — the same object the session ref points at while this turn
     * runs, so a reader that has the turn has its cancel handle too.
     *
     * <p>
     * Per-turn metadata supplied via {@code submitOptions} is forwarded to the executor builder only when present —
     * unset fields preserve the executor's defaults so an empty {@link SubmitOptions} produces exactly the same
     * request as the legacy session behavior pre-SubmitOptions.
     *
     * @param input
     *            the user input forwarded to the builder
     * @param submitOptions
     *            the per-turn options (must not be null; use {@link SubmitOptions#empty()} for executor defaults)
     * @param turn
     *            the turn being submitted; receives the published coordinator and tracker, and is handed back to
     *            {@link #clearActiveTurn(ActiveTurn)} on completion
     */
    private OrcaAgentExecutionRequest buildRequest(UserInput input, SubmitOptions submitOptions, ActiveTurn turn) {
        if (closed) {
            throw new IllegalStateException("LiveSession has already been closed: " + sessionId);
        }
        final OrcaAgentExecutionRequest.Builder builder = OrcaAgentExecutionRequest.builder().userInput(input)
                .submitOptions(submitOptions).sessionId(sessionId).budget(options.getBudget())
                .interruptObserver(coordinator -> turn.coordinator = coordinator)
                .budgetObserver(tracker -> turn.tracker = tracker);
        submitOptions.getPrincipal().ifPresent(builder::principal);
        if (!submitOptions.getSystemPromptVariables().isEmpty()) {
            builder.systemPromptVariables(submitOptions.getSystemPromptVariables());
        }
        if (!submitOptions.getExecutionAttributes().isEmpty()) {
            builder.executionAttributes(submitOptions.getExecutionAttributes());
        }
        submitOptions.getLlmCallMetadata().ifPresent(builder::llmCallMetadata);
        submitOptions.getUserContextInjection().ifPresent(builder::userContextInjection);
        return builder.build();
    }

    /**
     * Publishes {@code turn} as this session's active turn.
     *
     * <p>
     * Called only after {@link #buildRequest} has passed the closed-state guard: installing first would leave a stale
     * turn behind whenever a submit on a closed session throws.
     *
     * @param turn
     *            the turn about to run (must not be null)
     */
    private void installActiveTurn(ActiveTurn turn) {
        activeTurn.set(turn);
    }

    /**
     * Retires {@code turn}: drops the session-level {@link #activeTurn} reference iff it still points at this turn, and
     * folds the turn's final counters into the session-cumulative totals. Uses
     * {@link AtomicReference#compareAndSet(Object, Object)} so overlapping turns (permitted only when no queue is
     * wired) don't clobber a peer's still-live state, and {@link ActiveTurn#settle()} so the fold happens exactly once
     * even if a future call path retires the same turn twice.
     *
     * <p>
     * The fold is driven off the turn handle rather than off the session ref, so a turn that {@link #close()} already
     * evicted still contributes its counters when it finally settles.
     *
     * @param turn
     *            the turn to retire (must not be null)
     */
    private void clearActiveTurn(ActiveTurn turn) {
        if (!turn.settle()) {
            return;
        }
        activeTurn.compareAndSet(turn, null);
        final BudgetTracker tracker = turn.tracker;
        if (tracker != null) {
            // Fold the turn's final counters into the session-cumulative totals (atomically, to tolerate overlapping
            // turns when no queue is wired), reading iterations/tokens straight off the tracker — no full TurnProgress
            // snapshot is needed for the fold.
            sessionTotals.updateAndGet(prev -> prev.plusTurn(tracker.iterations(), tracker.tokens()));
            // End-of-turn write-through. Best-effort: a persistence failure must never break the turn or the session
            // (matches saveSilently semantics).
            flushDurableState();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Override: if a turn is currently active on this session, trips its {@link InterruptCoordinator} with the
     * given {@link InterruptReason}. The first trip wins; subsequent calls on the same turn are idempotent no-ops (see
     * {@link InterruptCoordinator#requestInterrupt(InterruptReason)}). When the session is idle the call is a silent
     * debug-logged no-op — matching the contract described on {@link LiveSession#interrupt(InterruptReason)}.
     */
    @Override
    public void interrupt(InterruptReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        interrupt(activeTurn.get(), reason);
    }

    /**
     * Trips {@code turn}'s coordinator, or logs why it could not be tripped. Shared by both {@code interrupt}
     * overloads and by {@link #close()} so the "no turn / turn not yet interruptible" cases are decided in one place.
     *
     * @param turn
     *            the turn to interrupt, or {@code null} when the session is idle
     * @param reason
     *            the reason to hand the coordinator (must not be null)
     */
    private void interrupt(ActiveTurn turn, InterruptReason reason) {
        if (turn == null) {
            log.debug("Session {} interrupt({}) requested with no active turn — ignoring", sessionId, reason);
            return;
        }
        final InterruptCoordinator coordinator = turn.coordinator;
        if (coordinator == null) {
            // The turn is installed but has not reached ReAct loop entry, so nothing has published a coordinator yet
            // and there is no interruptible work in flight. Matches the historical no-op for slash-command turns,
            // which bypass the loop and never publish one at all.
            log.debug("Session {} interrupt({}) for turn {} requested before loop entry — ignoring", sessionId, reason,
                    turn.turnId);
            return;
        }
        log.debug("Session {} forwarding interrupt({}) to the coordinator of turn {}", sessionId, reason, turn.turnId);
        coordinator.requestInterrupt(reason);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Override that compares {@code turnId} against the active turn and only then trips that turn's coordinator. A
     * mismatch means the addressed turn already settled and a later one is running — the interrupt is dropped with a
     * debug log rather than cancelling a turn nobody asked to cancel.
     *
     * <p>
     * The active turn is read <b>once</b> and both the match and the coordinator come from that one read. Reading the
     * id and the coordinator separately would reopen the very gap this overload exists to close: the addressed turn
     * could settle between the two reads and the interrupt would land on its innocent successor.
     */
    @Override
    public void interrupt(TurnId turnId, InterruptReason reason) {
        Objects.requireNonNull(turnId, "turnId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        final ActiveTurn turn = activeTurn.get();
        if (turn == null) {
            log.debug("Session {} interrupt({}) for turn {} requested with no active turn — ignoring", sessionId,
                    reason, turnId);
            return;
        }
        if (!turn.turnId.equals(turnId)) {
            log.debug("Session {} interrupt({}) targets turn {} but turn {} is active — ignoring", sessionId, reason,
                    turnId, turn.turnId);
            return;
        }
        interrupt(turn, reason);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Forwards the queued input to the wired {@link MessageQueueManager#enqueue}. Throws
     * {@link UnsupportedOperationException} when no queue manager is wired, since mid-turn injection requires queue
     * support. Throws {@link IllegalStateException} when the session is already closed.
     *
     * <p>
     * <b>Context id contract:</b> the caller must build {@code input} with this session's
     * {@link at.aimon.core.agent.AgentRuntime#getId()} as the {@code agentRuntimeId} so the CQ-03
     * mid-turn drain in the Orca ReAct loop matches the input to the active turn. The web manager's
     * {@code SessionInbox} forwarding path rebuilds the {@link QueuedInput} with the local ctxId before
     * dispatching here (see routing design §3.4).
     */
    @Override
    public void enqueueMidTurnInput(QueuedInput input) {
        Objects.requireNonNull(input, "input must not be null");
        if (closed) {
            throw new IllegalStateException("LiveSession has already been closed: " + sessionId);
        }
        if (messageQueueManager == null) {
            throw new UnsupportedOperationException(
                    "Mid-turn injection requires a MessageQueueManager — none wired for session " + sessionId);
        }
        log.debug("Session {} accepting external mid-turn input (priority={}, agentRuntimeId={})", sessionId,
                input.getPriority(), input.getAgentRuntimeId());
        messageQueueManager.enqueue(input);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * A turn still in flight is asked to surrender: its coordinator is tripped with
     * {@link InterruptReason#SESSION_RELEASED} before the turn is evicted from {@link #activeTurn}. Evicting without
     * tripping would strand that turn — the coordinator would be unreachable from {@link #interrupt(InterruptReason)}
     * afterwards, leaving a running turn that nothing can cancel for the rest of its life.
     *
     * <p>
     * The interrupt is a <b>request</b>, not a join: {@code close()} does not block until the turn observes it. The
     * turn folds its counters into {@link #sessionTotals} whenever it settles, as
     * {@link #clearActiveTurn(ActiveTurn)} works off the turn handle rather than the evicted session ref.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (messageQueueManager != null && nowPriorityListener != null) {
            // Drop the queue subscription before any other teardown so late enqueues never re-trigger
            // interrupts on a session that has already closed.
            messageQueueManager.removeListener(nowPriorityListener);
        }
        final ActiveTurn inFlight = activeTurn.getAndSet(null);
        if (inFlight != null) {
            interrupt(inFlight, InterruptReason.SESSION_RELEASED);
        }
        fireOnSessionEnd();
        // Per the scope model the OrcaAgentRuntime is agent-scoped and shared across sessions —
        // this session must NOT close it. Context teardown happens at application shutdown / agent removal via
        // OrcaAgentRuntimeManager.destroyRuntime.
    }

    /**
     * Returns the session options currently in effect.
     *
     * <p>
     * Exposed primarily for diagnostics and tests — the runtime values (budget, locale, sourceAgentId) are also
     * accessible directly through the options object's getters. The value returned here reflects any intervening
     * {@link #setOptions(LiveSessionOptions)} mutation, not just the options passed to the constructor.
     *
     * @return the session options (never null)
     */
    public LiveSessionOptions getOptions() {
        return options;
    }

    /**
     * Replaces the session options currently in effect.
     *
     * <p>
     * Subsequent submits (both {@link #submit(String)} and {@link #submitAsync submitAsync}) read the updated options
     * when they build the underlying {@code OrcaAgentExecutionRequest}, so callers can swap in a new default
     * {@link ExecutionBudget} (for example, after the user issues a {@code /budget} command in the REPL) without
     * rebuilding the session or tearing down its agent runtime. The options object itself remains immutable; only
     * the session's reference to it is swapped.
     *
     * <p>
     * This mutability is a concession to interactive callers that need a runtime-adjustable default. SESSION-04 will
     * revisit it as part of session-scoped budget/queue integration; until then, REPL-style callers are expected to use
     * this hook directly.
     *
     * <p>
     * <b>Restart durability (IMPORTANT):</b> of the supplied options, <b>only the {@link ExecutionBudget budget} is
     * persisted</b> (written through as a runtime override; see {@link #clearBudgetOverride()} to remove it). The
     * {@code locale} and {@code sourceAgentId} are applied in memory for the current session lifetime but are
     * <b>not</b> persisted — they are open-time attribution supplied by the opener, so after a restart they revert to
     * the opener-provided values rather than to whatever was last set here. Callers that need a durable locale change
     * must change it at the opener.
     *
     * @param newOptions
     *            the replacement options (must not be null)
     * @throws NullPointerException
     *             if {@code newOptions} is null
     */
    public void setOptions(LiveSessionOptions newOptions) {
        this.options = Objects.requireNonNull(newOptions, "newOptions must not be null");
        // Out-of-turn write #1. Update the remembered override first so the flush — and every end-of-turn flush after
        // it — states the new budget rather than the one this session hydrated.
        this.budgetOverride = newOptions.getBudget();
        flushDurableState();
    }

    /**
     * Clears any runtime budget override, reverting the effective {@link ExecutionBudget} to the opener-supplied
     * default captured at construction and erasing the persisted override so a later restart does not re-apply it.
     *
     * <p>
     * This is the explicit counterpart to {@link #setOptions(LiveSessionOptions)}: where {@code setOptions} installs a
     * runtime override (and persists it), {@code clearBudgetOverride} removes it — the intended path for a REPL
     * "revert to default budget" action. The locale / source agent id on the current options are preserved; only the
     * budget is reset. The persist call is best-effort: a failure logs a warning and never breaks the caller.
     */
    public void clearBudgetOverride() {
        this.options = this.options.withBudget(openerDefaultBudget);
        // Out-of-turn write #2. A null override is how "no override" is spelled on the record, so this both reverts the
        // effective budget in memory and erases the persisted one.
        this.budgetOverride = null;
        flushDurableState();
    }

    /**
     * Indicates whether {@link #close()} has already been invoked.
     *
     * <p>
     * Primarily useful for tests that want to assert idempotency of {@code close()}.
     *
     * @return {@code true} iff the session has been closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * The three handles of one in-flight turn, held together so a reader never has to combine two independent reads.
     *
     * <p>
     * Not a value object and deliberately not immutable: the {@link TurnId} is known at submit time but the
     * {@link InterruptCoordinator} and {@link BudgetTracker} only exist once the executor reaches ReAct loop entry, and
     * are published into this object from the executing thread while other threads ({@code interrupt}, {@code status})
     * read them. Hence the two {@code volatile} fields — the identity of the object is what is swapped atomically on
     * {@link #activeTurn}, and its contents fill in afterwards.
     *
     * <p>
     * This is why the three are not three {@code AtomicReference}s: they answer one question ("what is running, and
     * what cancels it") and any reader that answers it from two separate refs can be overtaken between them.
     */
    private static final class ActiveTurn {

        private final TurnId turnId;
        /** Published at ReAct loop entry; stays {@code null} for turns that never enter the loop (slash commands). */
        private volatile InterruptCoordinator coordinator;
        /** Published at ReAct loop entry, right after {@link #coordinator}. Read best-effort — not thread-safe. */
        private volatile BudgetTracker tracker;
        /** Guards the end-of-turn fold so a turn is counted into the session totals exactly once. */
        private final AtomicBoolean settled = new AtomicBoolean(false);

        ActiveTurn(TurnId turnId) {
            this.turnId = Objects.requireNonNull(turnId, "turnId must not be null");
        }

        /**
         * Marks this turn retired.
         *
         * @return {@code true} for the first caller only; {@code false} once the turn has already been retired
         */
        boolean settle() {
            return settled.compareAndSet(false, true);
        }
    }
}
