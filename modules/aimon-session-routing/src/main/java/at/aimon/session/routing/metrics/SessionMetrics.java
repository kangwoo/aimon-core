package at.aimon.session.routing.metrics;

import java.time.Duration;

import at.aimon.session.routing.SubmitDisposition;

/**
 * Operational metric hooks for {@link at.aimon.session.routing.SessionRouter} (design §12).
 *
 * <p>
 * Implementations forward to a metrics backend (Micrometer, OpenTelemetry, Dropwizard, …). The interface itself is
 * deliberately framework-agnostic — every method has a no-op default so callers can implement only the metrics they
 * actually export, and so the manager can wire {@link #NOOP} as the safe default when the application has not asked
 * for instrumentation.
 *
 * <p>
 * Callback contract:
 *
 * <ul>
 * <li>Hooks are invoked on whichever thread emits the corresponding event (turn executor, scheduler, signal-bus
 * dispatcher, …) — implementations <strong>must not block</strong> and must be thread-safe.
 * <li>Hooks must never throw — the manager wraps each invocation defensively so a metrics outage cannot break the
 * session lifecycle, but well-behaved adapters should still swallow their own exceptions.
 * <li>Latency-bearing hooks receive a non-null {@link Duration}. Reason-bearing hooks receive a non-null enum.
 * </ul>
 *
 * <p>
 * The set of hooks is the §12 hook table: lock acquire latency, cache hit-rate, evict frequency, lease-extend
 * failures, submit outcome, and holder loss. The last two answer the same operational questions ("are nodes piling
 * up in the inbox?", "did we trip a recovery in production?") with the same backend cost.
 *
 * <p>
 * {@link #onForwardDoorbellRerung()} joined them afterwards, for the case the holder-loss hook cannot see: a message
 * whose holder died while it was still queued was never anybody's in-flight turn, so there is no stale reservation to
 * recover from — the node waiting on it simply has to ask again until somebody can collect it.
 */
public interface SessionMetrics {

    /**
     * No-op metrics. Wire this in the manager when no metrics backend is configured — keeping the call sites uniform
     * avoids null-checks in hot paths.
     */
    SessionMetrics NOOP = new SessionMetrics() {
    };

    /**
     * Why the local session cache dropped an entry. Maps from Caffeine's {@code RemovalCause}.
     *
     * <p>
     * Note: Caffeine reports {@code EXPLICIT} uniformly for {@code invalidate()} regardless of intent — the manager
     * cannot distinguish a {@code releaseSession()} from a cross-node {@code EVICT}-triggered eviction at the
     * cache layer. Operators wanting that breakdown should correlate {@link #onHolderLossRecovered()} with eviction
     * counts.
     */
    enum CacheEvictionReason {
        /** Idle TTL elapsed (Caffeine {@code EXPIRED}). */
        IDLE,
        /** LRU bound reached (Caffeine {@code SIZE}). */
        LRU,
        /** Manager-driven explicit eviction — {@code releaseSession()} or cross-node {@code EVICT}. */
        EXPLICIT_RELEASE,
        /** Catch-all for Caffeine's {@code REPLACED} / {@code COLLECTED}. */
        OTHER
    }

    /**
     * Called when {@code SessionStore.claim()} won the lease.
     *
     * @param latency
     *            wall-clock duration of the {@code tryAcquire} call (never null)
     */
    default void onLockAcquireSucceeded(Duration latency) {
    }

    /**
     * Called when {@code SessionStore.claim()} answered {@code HeldElsewhere}. The submit is then
     * forwarded to the inbox.
     *
     * @param latency
     *            wall-clock duration of the {@code tryAcquire} call (never null)
     */
    default void onLockAcquireRejected(Duration latency) {
    }

    /** Called when {@code LiveSessionCache.ensureOpen} found an existing entry. */
    default void onCacheHit() {
    }

    /** Called when {@code LiveSessionCache.ensureOpen} opened a fresh session. */
    default void onCacheMiss() {
    }

    /**
     * Called when the local session cache drops an entry (regardless of cause).
     *
     * @param reason
     *            the eviction classification (never null)
     */
    default void onCacheEviction(CacheEvictionReason reason) {
    }

    /** Called on every successful {@code SessionStore.renew} from the {@code LeaseRenewer}. */
    default void onLeaseExtendSucceeded() {
    }

    /** Called once per turn when {@code SessionStore.renew} returns false (lease lost). */
    default void onLeaseExtendFailed() {
    }

    /**
     * Called once per submit, after the manager has classified the request.
     *
     * @param kind
     *            the submit outcome kind (never null)
     */
    default void onSubmitOutcome(SubmitDisposition.Kind kind) {
    }

    /**
     * Called by the holder-loss sweeper after winning the {@code compareAndReset} CAS for a stale IN_FLIGHT entry —
     * i.e. exactly once per recovered session, on the recovering node.
     */
    default void onHolderLossRecovered() {
    }

    /**
     * Called each time a node waiting on a forwarded turn rings that session's doorbell again — once per forward poll
     * interval, for as long as the forward is unresolved <em>and</em> its message is still uncollected. The ring is
     * node-local: it schedules a drain pass here, rather than announcing anything to peers.
     *
     * <p>
     * Counts <em>retries, not recoveries</em>. A message a healthy holder has not reached yet increments this too, so
     * a low rate is ordinary queueing. What it does say is that somebody is waiting on a message no node has taken out
     * of the inbox — and the counter is its own success signal, because a retry that gets the session collects the
     * message and the ringing stops. A rate that does not fall is the shape to alert on. Nothing else reports the
     * takeover: the drain pass acquires its lease outside {@link #onLockAcquireSucceeded(Duration)}, which only the
     * submit path fires.
     */
    default void onForwardDoorbellRerung() {
    }
}
