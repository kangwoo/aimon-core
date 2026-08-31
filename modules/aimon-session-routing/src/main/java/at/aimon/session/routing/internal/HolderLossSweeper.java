package at.aimon.session.routing.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.stream.InterruptedAt;
import at.aimon.session.routing.metrics.SessionMetrics;

/**
 * Periodic sweeper that detects holder loss via the {@link IdempotencyStore} and emits the recovery sequence
 * required by design §6.3 D / §9.2 stale cleanup.
 *
 * <p>
 * For every IN_FLIGHT entry whose {@code lastTouchedAt} is older than {@code now - secondaryTtl}, the sweeper
 * attempts {@link IdempotencyStore#compareAndReset(String, String) compareAndReset}. The CAS winner — exactly one
 * across all nodes — performs:
 *
 * <ol>
 * <li>Emit {@link InterruptedAt}({@link InterruptReason#HOLDER_LOST}) to the injected {@link EventSink} so
 * {@code events()} subscribers see why the turn stopped producing. Where that reaches is the sink's business, not the
 * sweeper's: the manager wires one that delivers on this node and relays to peers, since a subscriber attached
 * elsewhere would otherwise watch a stream that merely goes quiet.
 * <li>Hand the reservation to the {@link LostTurnAnnouncer}, which fails the callers waiting on that turn — here and
 * on peers — instead of leaving them to their forward deadlines.
 * </ol>
 *
 * <p>
 * <b>Recovery is turn-scoped.</b> It was session-scoped until Stage 3b: the sweeper also completed the local event
 * stream and broadcast {@link SessionSignal.SignalKind#EVICT} so every node tore the session down. But a lost
 * holder does not mean a lost session — the lease expires and a successor claims it, possibly before the sweep
 * even
 * runs — so that broadcast raced the successor's {@code claim()} and evicted a live session, dropping its cached
 * session, purging its approvals and completing its subscribers' streams over a turn that had died elsewhere. What the
 * sweeper detects is one dead attempt, and that is now all it reports.
 *
 * <p>
 * The loop never throws — exceptions are swallowed and logged so a transient backend failure does not kill the
 * scheduled task.
 */
public final class HolderLossSweeper {

    private static final Logger log = LoggerFactory.getLogger(HolderLossSweeper.class);

    /** Synthetic context id used in {@link InterruptedAt} since the holder is gone — purely diagnostic. */
    private static final AgentRuntimeId LOST_CTX = AgentRuntimeId.of("agent:lost-holder");

    private final IdempotencyStore store;
    private final EventSink eventSink;
    private final LostTurnAnnouncer announcer;
    private final ScheduledExecutorService scheduler;
    private final Duration sweepInterval;
    private final Duration secondaryTtl;
    private final SessionMetrics metrics;

    private HolderLossSweeper(Builder builder) {
        this.store = Objects.requireNonNull(builder.store, "store must not be null");
        this.eventSink = Objects.requireNonNull(builder.eventSink, "eventSink must not be null");
        this.announcer = Objects.requireNonNull(builder.announcer, "announcer must not be null");
        this.scheduler = Objects.requireNonNull(builder.scheduler, "scheduler must not be null");
        this.sweepInterval = Objects.requireNonNull(builder.sweepInterval, "sweepInterval must not be null");
        this.secondaryTtl = Objects.requireNonNull(builder.secondaryTtl, "secondaryTtl must not be null");
        this.metrics = Objects.requireNonNull(builder.metrics, "metrics must not be null");
        if (sweepInterval.isZero() || sweepInterval.isNegative()) {
            throw new IllegalArgumentException("sweepInterval must be positive: " + sweepInterval);
        }
        if (secondaryTtl.isZero() || secondaryTtl.isNegative()) {
            throw new IllegalArgumentException("secondaryTtl must be positive: " + secondaryTtl);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link HolderLossSweeper}. {@code metrics} defaults to {@link SessionMetrics#NOOP}; all other
     * collaborators are required. Positive-duration validation for {@code sweepInterval} / {@code secondaryTtl} runs at
     * {@link #build()}.
     */
    public static final class Builder {

        private IdempotencyStore store;
        private EventSink eventSink;
        private LostTurnAnnouncer announcer;
        private ScheduledExecutorService scheduler;
        private Duration sweepInterval;
        private Duration secondaryTtl;
        private SessionMetrics metrics = SessionMetrics.NOOP;

        private Builder() {
        }

        public Builder store(IdempotencyStore store) {
            this.store = store;
            return this;
        }

        public Builder eventSink(EventSink eventSink) {
            this.eventSink = eventSink;
            return this;
        }

        public Builder announcer(LostTurnAnnouncer announcer) {
            this.announcer = announcer;
            return this;
        }

        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder sweepInterval(Duration sweepInterval) {
            this.sweepInterval = sweepInterval;
            return this;
        }

        public Builder secondaryTtl(Duration secondaryTtl) {
            this.secondaryTtl = secondaryTtl;
            return this;
        }

        public Builder metrics(SessionMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        public HolderLossSweeper build() {
            return new HolderLossSweeper(this);
        }
    }

    /**
     * Schedule periodic sweeps. The returned future is cancellable; the manager cancels it on close.
     *
     * @return the scheduled task handle (never null)
     */
    public ScheduledFuture<?> start() {
        final long ms = sweepInterval.toMillis();
        return scheduler.scheduleAtFixedRate(this::sweepOnce, ms, ms, TimeUnit.MILLISECONDS);
    }

    /** Visible for testing — performs one sweep pass synchronously. */
    public void sweepOnce() {
        try {
            final Instant cutoff = Instant.now().minus(secondaryTtl);
            final List<IdempotencyEntry> stale = store.findStaleInFlight(cutoff);
            for (IdempotencyEntry entry : stale) {
                final String holderId = entry.getHolderId().orElse(null);
                if (holderId == null) {
                    // A holderless IN_FLIGHT entry is a reservation for a turn that was forwarded to the inbox and is
                    // waiting to be drained (IdempotencyStore#releaseHolder). No node is executing it, so nobody
                    // touches it and it is stale by construction — it is not evidence of a lost holder. Stores are
                    // expected to filter these out of findStaleInFlight; this is the belt to that suspenders.
                    continue;
                }
                handleStale(entry, holderId);
            }
        } catch (Exception e) {
            log.warn("HolderLossSweeper pass threw: {}", e.toString());
        }
    }

    private void handleStale(IdempotencyEntry entry, String holderId) {
        final boolean won;
        try {
            won = store.compareAndReset(entry.getKey(), holderId);
        } catch (Exception e) {
            log.warn("compareAndReset threw for key {}: {}", entry.getKey(), e.toString());
            return;
        }
        if (!won) {
            return;
        }
        final SessionId convId = entry.getSessionId();
        log.info("Holder loss detected for session {} (holder {}) — emitting HOLDER_LOST recovery", convId, holderId);
        try {
            metrics.onHolderLossRecovered();
        } catch (Exception e) {
            log.warn("SessionMetrics.onHolderLossRecovered threw: {}", e.toString());
        }
        try {
            eventSink.emit(convId, InterruptedAt.builder().timestamp(Instant.now()).agentRuntimeId(LOST_CTX)
                    .iteration(0).reason(InterruptReason.HOLDER_LOST).iterationIndex(0).partialOutput("").build());
        } catch (Exception e) {
            log.warn("InterruptedAt emit threw for session {}: {}", convId, e.toString());
        }
        // Deliberately not followed by ending that stream, and deliberately not an EVICT broadcast. Both end the
        // session's event stream, and the session has not ended — only one attempt at it has. A successor can
        // already hold the lease and be running the next turn, in which case completing the stream cuts its subscribers
        // off from a session that is working fine, and the EVICT went further still: it dropped the successor's cached
        // session and purged approvals on every node that heard it. EventSink offers no completion at all now, so this
        // is a rule the type system keeps rather than a comment.
        try {
            announcer.announceHolderLost(convId, entry.getKey(), holderId);
        } catch (Exception e) {
            log.warn("Holder-loss announcement threw for session {}: {}", convId, e.toString());
        }
    }
}
