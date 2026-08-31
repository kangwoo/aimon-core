package at.aimon.core.agent.session;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.llm.TokenUsage;

/**
 * An immutable, point-in-time observability snapshot of a {@link LiveSession}.
 *
 * <p>
 * Returned by {@link LiveSession#status()} for <b>diagnostics, monitoring and UI display</b> — never as a control
 * gate. The snapshot is assembled by reading the session's live runtime state at call time; the individual backing
 * fields are read independently, so the snapshot is <em>best-effort</em> and is <b>not</b> a consistent atomic view
 * across all fields. Hosts that need to decide whether a turn may start must continue to use
 * {@link LiveSession#offerAsync(String, java.util.function.Consumer) offerAsync} and inspect the resulting
 * {@link SubmitOutcome}; reading {@code status()} and acting on it is inherently racy (the state may change between the
 * read and the action).
 *
 * <h2>Scope</h2>
 *
 * <p>
 * This carries only <b>session-/turn-scoped</b> state that the session itself owns:
 * <ul>
 * <li>{@link Phase} — IDLE / RUNNING / CLOSED lifecycle phase.
 * <li>{@link #isInterruptible() interruptible} — whether a turn with a live
 * {@link at.aimon.core.agent.interrupt.InterruptCoordinator} is currently in flight.
 * <li>{@link #getQueueDepth() queueDepth} — best-effort depth of the wired mid-turn injection queue (0 when no queue
 * is wired).
 * <li>{@link #getOptions() options} — the session options currently in effect.
 * <li>{@link #getTurnProgress() turnProgress} — live per-turn counters (iterations, tokens, elapsed) measured against
 * the turn's enforced {@link ExecutionBudget}, present only while a turn is running against an executor that
 * publishes its {@link at.aimon.core.agent.budget.BudgetTracker}.
 * <li>{@link #getSessionTotals() sessionTotals} — running totals (turn count, iterations, token usage)
 * accumulated
 * across all completed turns of the session; zero until the first turn finishes. Counts completed turns only — fold in
 * the live {@link #getTurnProgress() turnProgress} for an "including the current turn" figure.
 * </ul>
 *
 * <p>
 * Record-persisted state (message history, last-activity timestamp, compaction counters) is intentionally
 * <b>not</b> included — that is the {@code SessionRecordStore}'s responsibility and is queried separately, keeping
 * the durable record and the node-local handle from bleeding into one another.
 *
 * <h2>Multi-instance</h2>
 *
 * <p>
 * In scale-out deployments this reflects only the <b>local session instance's</b> view. A session whose turn is
 * executing on another node appears IDLE here; cluster-wide observability is provided by the routing layer
 * (e.g. {@code SessionMetrics} in {@code aimon-session-routing}), not by this snapshot.
 *
 * @see LiveSession#status()
 */
public final class LiveSessionStatus {

    /**
     * Coarse lifecycle phase of a session at snapshot time.
     */
    public enum Phase {
        /** No turn is currently in flight and the session is open. */
        IDLE,
        /** A turn is currently executing on this session. */
        RUNNING,
        /** {@link LiveSession#close()} has been invoked; no further turns may be submitted. */
        CLOSED
    }

    private final SessionId sessionId;
    private final Phase phase;
    private final boolean interruptible;
    private final int queueDepth;
    private final LiveSessionOptions options;
    private final TurnProgress turnProgress;
    private final SessionTotals sessionTotals;

    private LiveSessionStatus(Builder builder) {
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId must not be null");
        this.phase = Objects.requireNonNull(builder.phase, "phase must not be null");
        this.interruptible = builder.interruptible;
        this.queueDepth = builder.queueDepth;
        this.options = builder.options;
        this.turnProgress = builder.turnProgress;
        // The builder field defaults to SessionTotals.empty(); fail fast if a caller explicitly passed null.
        this.sessionTotals = Objects.requireNonNull(builder.sessionTotals, "sessionTotals must not be null");
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the session id bound to this handle (never null)
     */
    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * @return the lifecycle phase at snapshot time (never null)
     */
    public Phase getPhase() {
        return phase;
    }

    /**
     * Indicates whether a turn with a live interrupt coordinator is currently in flight, i.e. whether
     * {@link LiveSession#interrupt(at.aimon.core.agent.interrupt.InterruptReason)} would have a coordinator to trip.
     *
     * @return {@code true} iff an interruptible turn is currently active
     */
    public boolean isInterruptible() {
        return interruptible;
    }

    /**
     * Returns the best-effort depth of the session's mid-turn injection queue at snapshot time.
     *
     * <p>
     * {@code 0} when no {@link at.aimon.core.agent.queue.MessageQueueManager} is wired. As with the depth reported by
     * {@link SubmitOutcome}, this is an instantaneous indicator suitable for UX ("[queued: N]"), not a stable index.
     *
     * @return the queue depth (>= 0)
     */
    public int getQueueDepth() {
        return queueDepth;
    }

    /**
     * @return the session options in effect at snapshot time, or {@link Optional#empty()} if the implementation did
     *         not supply them
     */
    public Optional<LiveSessionOptions> getOptions() {
        return Optional.ofNullable(options);
    }

    /**
     * Returns the live per-turn progress counters, present only while a turn is running against an executor that
     * publishes its {@link at.aimon.core.agent.budget.BudgetTracker}.
     *
     * @return the turn progress, or {@link Optional#empty()} when no turn is active (or the executor does not publish
     *         live metrics)
     */
    public Optional<TurnProgress> getTurnProgress() {
        return Optional.ofNullable(turnProgress);
    }

    /**
     * Returns the session-cumulative totals accumulated across all <em>completed</em> turns (turn count,
     * iterations,
     * token usage). Never null — reports zeros before any turn finishes. Counts completed turns only; to include the
     * in-flight turn, fold in {@link #getTurnProgress()}.
     *
     * @return the session totals (never null)
     */
    public SessionTotals getSessionTotals() {
        return sessionTotals;
    }

    @Override
    public String toString() {
        return "LiveSessionStatus{sessionId=" + sessionId + ", phase=" + phase + ", interruptible=" + interruptible
                + ", queueDepth=" + queueDepth + ", turnProgress=" + turnProgress + ", sessionTotals=" + sessionTotals
                + '}';
    }

    /**
     * Builder for {@link LiveSessionStatus}. {@code sessionId} and {@code phase} are required; all other fields
     * default to an "idle / no live turn" view (not interruptible, zero queue depth, no options, no turn progress).
     */
    public static final class Builder {
        private SessionId sessionId;
        private Phase phase;
        private boolean interruptible;
        private int queueDepth;
        private LiveSessionOptions options;
        private TurnProgress turnProgress;
        private SessionTotals sessionTotals = SessionTotals.empty();

        private Builder() {
        }

        /**
         * @param sessionId
         *            the bound session id (required, must not be null)
         * @return this builder
         */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * @param phase
         *            the lifecycle phase (required, must not be null)
         * @return this builder
         */
        public Builder phase(Phase phase) {
            this.phase = phase;
            return this;
        }

        /**
         * @param interruptible
         *            whether an interruptible turn is in flight
         * @return this builder
         */
        public Builder interruptible(boolean interruptible) {
            this.interruptible = interruptible;
            return this;
        }

        /**
         * @param queueDepth
         *            the best-effort mid-turn queue depth (>= 0)
         * @return this builder
         */
        public Builder queueDepth(int queueDepth) {
            this.queueDepth = queueDepth;
            return this;
        }

        /**
         * @param options
         *            the session options in effect (can be null)
         * @return this builder
         */
        public Builder options(LiveSessionOptions options) {
            this.options = options;
            return this;
        }

        /**
         * @param turnProgress
         *            the live per-turn progress, or null when no turn is active
         * @return this builder
         */
        public Builder turnProgress(TurnProgress turnProgress) {
            this.turnProgress = turnProgress;
            return this;
        }

        /**
         * @param sessionTotals
         *            the session-cumulative totals (must not be null; use {@link SessionTotals#empty()} for
         *            zeros)
         * @return this builder
         */
        public Builder sessionTotals(SessionTotals sessionTotals) {
            this.sessionTotals = sessionTotals;
            return this;
        }

        /**
         * @return a new immutable {@link LiveSessionStatus}
         */
        public LiveSessionStatus build() {
            return new LiveSessionStatus(this);
        }
    }

    /**
     * Immutable snapshot of a running turn's live progress counters, copied out of the turn's
     * {@link at.aimon.core.agent.budget.BudgetTracker} at the moment {@link LiveSession#status()} is called.
     *
     * <p>
     * Pairs the live counters ({@link #getIterations() iterations}, {@link #getTokenUsage() tokenUsage},
     * {@link #getElapsed() elapsed}) with the {@link #getBudget() budget} they are measured against, so callers can
     * render "used / max" progress directly — e.g. {@code tokenUsage.getTotalTokens()} over
     * {@code budget.getMaxTokens()}.
     *
     * <p>
     * The counters are a copy, not a live view: the underlying tracker is not thread-safe and is confined to the
     * executor thread, so a monitoring thread that reads {@code status()} concurrently with a running turn may observe
     * slightly stale counters. The {@link ExecutionBudget} is itself an immutable value object and is shared directly.
     * This is acceptable for the diagnostic / UI use case this snapshot targets.
     */
    public static final class TurnProgress {
        private final int iterations;
        private final TokenUsage tokenUsage;
        private final Duration elapsed;
        private final ExecutionBudget budget;

        private TurnProgress(int iterations, TokenUsage tokenUsage, Duration elapsed, ExecutionBudget budget) {
            this.iterations = iterations;
            this.tokenUsage = Objects.requireNonNull(tokenUsage, "tokenUsage must not be null");
            this.elapsed = Objects.requireNonNull(elapsed, "elapsed must not be null");
            this.budget = Objects.requireNonNull(budget, "budget must not be null");
        }

        /**
         * Creates a turn-progress snapshot.
         *
         * @param iterations
         *            the number of ReAct iterations recorded so far (>= 0)
         * @param tokenUsage
         *            the accumulated token usage so far (must not be null)
         * @param elapsed
         *            the wall-clock duration since the turn started (must not be null)
         * @param budget
         *            the {@link ExecutionBudget} enforced for this turn — the limits the counters are measured against
         *            (must not be null; use {@link ExecutionBudget#unlimited()} when no limit applies)
         * @return a new immutable snapshot (never null)
         * @throws NullPointerException
         *             if {@code tokenUsage}, {@code elapsed} or {@code budget} is null
         */
        public static TurnProgress of(int iterations, TokenUsage tokenUsage, Duration elapsed, ExecutionBudget budget) {
            return new TurnProgress(iterations, tokenUsage, elapsed, budget);
        }

        /**
         * @return the number of ReAct iterations recorded so far
         */
        public int getIterations() {
            return iterations;
        }

        /**
         * @return the accumulated token usage so far (never null)
         */
        public TokenUsage getTokenUsage() {
            return tokenUsage;
        }

        /**
         * @return the wall-clock duration since the turn started (never null)
         */
        public Duration getElapsed() {
            return elapsed;
        }

        /**
         * Returns the {@link ExecutionBudget} enforced for this turn — the limits the live counters are measured
         * against. For example {@code getBudget().getMaxTokens()} paired with {@code getTokenUsage().getTotalTokens()}
         * yields the "used / max" token figures. An {@link ExecutionBudget#unlimited()} budget reports empty limits.
         *
         * @return the enforced budget (never null)
         */
        public ExecutionBudget getBudget() {
            return budget;
        }

        @Override
        public String toString() {
            return "TurnProgress{iterations=" + iterations + ", tokenUsage=" + tokenUsage + ", elapsed=" + elapsed
                    + ", budget=" + budget + '}';
        }
    }

}
