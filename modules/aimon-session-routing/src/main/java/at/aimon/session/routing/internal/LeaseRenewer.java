package at.aimon.session.routing.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.session.store.SessionStore;
import at.aimon.session.routing.metrics.SessionMetrics;

/**
 * Periodically renews a {@link SessionLease} (design §7.4) on a manager-owned
 * {@link ScheduledExecutorService}.
 *
 * <p>
 * The renewer is intentionally separate from the turn-execution thread: when a turn blocks on an LLM call or a tool,
 * the lease must still be renewed by an unrelated scheduler tick or it will silently expire and another node may
 * race ahead. The recommended interval is {@code lease/3} — two missed ticks of headroom, which the manager protects by
 * handing this class a scheduler of its own rather than the pool that carries sweeps, heartbeats and polls. A tick that
 * is merely queued is indistinguishable from a lease that was stolen, so renewal must not queue behind unrelated work.
 *
 * <p>
 * Renewal goes through {@link SessionStore#renew} rather than the underlying lease backend, which matters for more
 * than tidiness: a failed renewal makes the store drop its local record of the lease, so the very next fenced record
 * write is refused instead of landing on top of the new holder's history. Renewing at the backend directly would leave
 * the store believing it still holds a lease it has lost.
 *
 * <p>
 * When {@link SessionStore#renew} returns {@code false} — the fencing token no longer matches because the lease
 * already expired — the {@code onExtendFailed} hook is invoked exactly once. The hook is the manager's signal to
 * interrupt the turn with {@code InterruptReason.LEASE_LOST}. Subsequent ticks become no-ops because the
 * {@code lostLease} flag latches.
 *
 * <p>
 * That hook runs on the scheduler thread, which makes it the one way unbounded work gets onto a pool whose whole
 * purpose is never to be busy — so <b>{@code onExtendFailed} must not block</b>. Sessions being torn down, hooks being
 * fired and leases being released all belong to some other executor; the hook's job here is to hand them over and
 * return. One lease lost is a local event, but a hook that waits for a deployment's {@code OnSessionEnd} code to
 * finish makes every session sharing this thread lose its lease too.
 *
 * <p>
 * A successful extend also piggybacks the secondary-TTL refresh of whatever idempotency reservation is bound to the
 * {@link IdempotencyTouchSlot} at that moment, so the holder-loss sweeper does not pick a live turn's entry as stale
 * (design §9.2 stale cleanup). The slot is read per tick rather than captured at schedule time because a lease now
 * outlives the turn that won it — see {@link IdempotencyTouchSlot} for what a captured key would break.
 *
 * <p>
 * That touch names the <em>reservation's</em> holder, not {@link SessionLease#getHolderId()}. The two used to be
 * one string; since Stage 3b the lease is held under the bare node id, and touching with that would silently do
 * nothing — {@link IdempotencyStore#touch(String, String)} ignores a holder that does not match the entry's, so the
 * entry would go quiet and the sweeper would reset a key whose turn is running fine.
 */
public final class LeaseRenewer {

    private static final Logger log = LoggerFactory.getLogger(LeaseRenewer.class);

    private final SessionStore store;
    private final ScheduledExecutorService scheduler;
    private final Duration interval;
    private final Duration lease;
    private final SessionMetrics metrics;

    public LeaseRenewer(SessionStore store, ScheduledExecutorService scheduler, Duration interval, Duration lease) {
        this(store, scheduler, interval, lease, SessionMetrics.NOOP);
    }

    public LeaseRenewer(SessionStore store, ScheduledExecutorService scheduler, Duration interval, Duration lease,
            SessionMetrics metrics) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
        this.lease = Objects.requireNonNull(lease, "lease must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive: " + lease);
        }
    }

    /**
     * Schedule periodic renewal of {@code held} for as long as its session lives. Cancel the returned future when the
     * lease is returned.
     *
     * @param held
     *            the lease to keep alive (must not be null)
     * @param touchSlot
     *            the reservation to refresh alongside each successful extend, or {@code null} for none
     * @param onExtendFailed
     *            invoked at most once when renewal returns {@code false}; tells the manager the lease is lost, which
     *            interrupts any running turn and drops the session. Runs on the scheduler thread, so it must return
     *            promptly and hand any blocking teardown to another executor (must not be null)
     * @return the {@link ScheduledFuture} the manager cancels on lease return (never null)
     */
    public ScheduledFuture<?> start(SessionLease held, IdempotencyTouchSlot touchSlot, Runnable onExtendFailed) {
        Objects.requireNonNull(held, "held must not be null");
        Objects.requireNonNull(onExtendFailed, "onExtendFailed must not be null");
        final long intervalMs = interval.toMillis();
        final LeaseRenewalTask task = new LeaseRenewalTask(store, held, lease, touchSlot, metrics, onExtendFailed);
        return scheduler.scheduleAtFixedRate(task, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * The periodic lease-extend tick. Extracted from an anonymous {@link Runnable} so its body stays within the
     * anon-inner-length limit; holds the per-schedule {@code lostLease} latch (design §7.4).
     */
    private static final class LeaseRenewalTask implements Runnable {

        private final SessionStore store;
        private final SessionLease held;
        private final Duration lease;
        private final IdempotencyTouchSlot touchSlot;
        private final SessionMetrics metrics;
        private final Runnable onExtendFailed;
        private boolean lostLease;

        LeaseRenewalTask(SessionStore store, SessionLease held, Duration lease, IdempotencyTouchSlot touchSlot,
                SessionMetrics metrics, Runnable onExtendFailed) {
            this.store = store;
            this.held = held;
            this.lease = lease;
            this.touchSlot = touchSlot;
            this.metrics = metrics;
            this.onExtendFailed = onExtendFailed;
        }

        @Override
        public void run() {
            if (lostLease) {
                return;
            }
            try {
                final boolean ok = store.renew(held, lease);
                if (!ok) {
                    lostLease = true;
                    log.warn("Lease renewal rejected (token mismatch) for session {} holder {}", held.getSessionId(),
                            held.getHolderId());
                    safeMetric(metrics::onLeaseExtendFailed, "onLeaseExtendFailed");
                    try {
                        onExtendFailed.run();
                    } catch (Exception cbEx) {
                        log.warn("onExtendFailed callback threw: {}", cbEx.toString());
                    }
                    return;
                }
                safeMetric(metrics::onLeaseExtendSucceeded, "onLeaseExtendSucceeded");
                if (touchSlot != null) {
                    touchSlot.touch();
                }
            } catch (Exception e) {
                log.warn("Lease renewal threw for session {}: {}", held.getSessionId(), e.toString());
            }
        }
    }

    private static void safeMetric(Runnable hook, String name) {
        try {
            hook.run();
        } catch (Exception e) {
            log.warn("SessionMetrics.{} threw: {}", name, e.toString());
        }
    }
}
