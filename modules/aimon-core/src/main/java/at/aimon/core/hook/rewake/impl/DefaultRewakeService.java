package at.aimon.core.hook.rewake.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.rewake.RewakeEnvelope;
import at.aimon.core.hook.rewake.RewakeFireListener;
import at.aimon.core.hook.rewake.RewakeQuotaManager;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.hook.rewake.RewakeTrigger;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.hook.rewake.RewakeTriggerEvent;

/**
 * In-memory reference implementation of {@link RewakeService}.
 *
 * <p>
 * Capabilities:
 * <ul>
 * <li><b>Delay triggers</b> — backed by a {@link ScheduledExecutorService}. Fires once after the configured delay.
 * <li><b>Event triggers</b> — held in an in-memory registry; fired when a matching
 * {@link #resolve(String, String, Map)} call arrives.
 * <li><b>Cron triggers</b> — rejected with {@link UnsupportedOperationException}. Cron support requires the
 * Quartz-backed implementation in {@code aimon-scheduling-quartz}.
 * </ul>
 *
 * <p>
 * Persistence: <b>none</b>. Pending envelopes are lost on JVM restart. Production deployments must replace this with
 * the persistent (Quartz-backed) impl.
 *
 * <p>
 * Lifecycle: instances are <i>application-scoped</i> per the AIMON scope model. Construct once at bootstrap, inject
 * everywhere, and call {@link #close()} during JVM shutdown.
 *
 * <p>
 * Thread-safety: every public method is safe for concurrent invocation.
 */
public final class DefaultRewakeService implements RewakeService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultRewakeService.class);

    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Clock clock;
    private final RewakeFireListener listener;
    private volatile RewakeQuotaManager quotaManager = RewakeQuotaManager.NOOP;

    /** envelopeId → live registration (envelope + optional ScheduledFuture for delay triggers). */
    private final Map<String, Registration> pending = new ConcurrentHashMap<>();

    private volatile boolean closed;

    /**
     * Creates a service that owns its own single-threaded daemon scheduler.
     *
     * @param listener
     *            fire listener (must not be null; pass {@link RewakeFireListener#NOOP} for stand-alone use)
     */
    public DefaultRewakeService(RewakeFireListener listener) {
        this(listener, Clock.systemUTC(), defaultScheduler(), true);
    }

    /**
     * Creates a service against an externally-owned scheduler. The caller is responsible for shutting the scheduler
     * down; {@link #close()} only unschedules pending tasks.
     *
     * @param listener
     *            fire listener (must not be null)
     * @param clock
     *            clock used for fire-time calculations (must not be null)
     * @param scheduler
     *            scheduled executor (must not be null)
     */
    public DefaultRewakeService(RewakeFireListener listener, Clock clock, ScheduledExecutorService scheduler) {
        this(listener, clock, scheduler, false);
    }

    private DefaultRewakeService(RewakeFireListener listener, Clock clock, ScheduledExecutorService scheduler,
            boolean ownsScheduler) {
        this.listener = Objects.requireNonNull(listener, "listener cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.ownsScheduler = ownsScheduler;
    }

    /**
     * Installs a {@link RewakeQuotaManager} that vetoes {@link #schedule(RewakeEnvelope)} when a context exceeds its
     * cap. The default is {@link RewakeQuotaManager#NOOP NOOP} (unbounded). Use this setter at bootstrap; mid-flight
     * swaps are allowed but the prior manager is not reconciled (existing pending entries stay charged against the old
     * counters until they leave the pending set).
     *
     * @param quotaManager
     *            replacement manager (must not be null; pass {@link RewakeQuotaManager#NOOP} to disable)
     * @return this service, to allow fluent chaining at construction sites
     * @throws NullPointerException
     *             if {@code quotaManager} is null
     */
    public DefaultRewakeService withQuotaManager(RewakeQuotaManager quotaManager) {
        this.quotaManager = Objects.requireNonNull(quotaManager, "quotaManager cannot be null");
        return this;
    }

    private static ScheduledExecutorService defaultScheduler() {
        final AtomicLong seq = new AtomicLong();
        final ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "aimon-rewake-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    @Override
    public void schedule(RewakeEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope cannot be null");
        ensureOpen();
        final RewakeTrigger trigger = envelope.getTrigger();
        final String id = envelope.getEnvelopeId();

        // Idempotent: replace any existing registration for the same id (releases its quota slot).
        cancel(id);

        // Quota check happens after the idempotent cancel so a re-schedule of the same envelopeId is not
        // double-charged.
        if (!quotaManager.tryAcquire(envelope.getAgentRuntimeId())) {
            log.warn(
                    "Rewake schedule rejected by quota — context {} is at the cap "
                            + "(envelopeId={}, hookId={}, reason='{}'). Dropping.",
                    envelope.getAgentRuntimeId(), id, envelope.getOriginatingHookId(), envelope.getReason());
            return;
        }

        final Registration reg = new Registration(envelope);
        pending.put(id, reg);

        if (trigger instanceof RewakeTriggerDelay delay) {
            reg.future = scheduleDelay(envelope, delay.getDelay());
        } else if (trigger instanceof RewakeTriggerEvent) {
            // Event triggers wait for resolve(...). Nothing to schedule.
            log.debug("Registered event-trigger envelope {} (waiting for resolve)", id);
        } else if (trigger instanceof RewakeTriggerCron) {
            pending.remove(id);
            quotaManager.release(envelope.getAgentRuntimeId());
            throw new UnsupportedOperationException("Cron triggers require the Quartz-backed RewakeService impl "
                    + "(see aimon-scheduling-quartz). envelopeId=" + id);
        } else {
            pending.remove(id);
            quotaManager.release(envelope.getAgentRuntimeId());
            throw new UnsupportedOperationException("Unsupported trigger shape: " + trigger.getClass().getName());
        }
    }

    private ScheduledFuture<?> scheduleDelay(RewakeEnvelope envelope, Duration delay) {
        final long delayMillis = Math.max(0L, delay.toMillis());
        return scheduler.schedule(() -> fireFromTimer(envelope.getEnvelopeId()), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void fireFromTimer(String envelopeId) {
        final Registration reg = pending.remove(envelopeId);
        if (reg == null) {
            return;
        }
        quotaManager.release(reg.envelope.getAgentRuntimeId());
        deliver(reg.envelope);
    }

    private void deliver(RewakeEnvelope envelope) {
        try {
            listener.onFire(envelope);
        } catch (Exception e) {
            log.warn("RewakeFireListener threw for envelope {} (originatingHookId={}): {}", envelope.getEnvelopeId(),
                    envelope.getOriginatingHookId(), e.getMessage(), e);
        }
    }

    @Override
    public boolean cancel(String envelopeId) {
        Objects.requireNonNull(envelopeId, "envelopeId cannot be null");
        final Registration reg = pending.remove(envelopeId);
        if (reg == null) {
            return false;
        }
        if (reg.future != null) {
            reg.future.cancel(false);
        }
        quotaManager.release(reg.envelope.getAgentRuntimeId());
        return true;
    }

    @Override
    public int cancelByOriginatingHookId(String originatingHookId) {
        Objects.requireNonNull(originatingHookId, "originatingHookId cannot be null");
        if (originatingHookId.isBlank()) {
            throw new IllegalArgumentException("originatingHookId cannot be blank");
        }
        int cancelled = 0;
        for (Registration reg : List.copyOf(pending.values())) {
            if (originatingHookId.equals(reg.envelope.getOriginatingHookId()) && cancel(reg.envelope.getEnvelopeId())) {
                cancelled++;
            }
        }
        return cancelled;
    }

    @Override
    public int resolve(String eventType, String eventKey, Map<String, String> payload) {
        Objects.requireNonNull(eventType, "eventType cannot be null");
        Objects.requireNonNull(eventKey, "eventKey cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        if (eventType.isBlank() || eventKey.isBlank()) {
            throw new IllegalArgumentException("eventType / eventKey cannot be blank");
        }
        payload.forEach((k, v) -> {
            Objects.requireNonNull(k, "payload key cannot be null");
            Objects.requireNonNull(v, "payload value cannot be null");
        });

        final List<RewakeEnvelope> matched = new ArrayList<>();
        for (Registration reg : List.copyOf(pending.values())) {
            final RewakeTrigger envTrigger = reg.envelope.getTrigger();
            if (!(envTrigger instanceof RewakeTriggerEvent ev)) {
                continue;
            }
            if (ev.getEventType().equals(eventType) && ev.getEventKey().equals(eventKey)) {
                if (cancel(reg.envelope.getEnvelopeId())) {
                    matched.add(mergePayload(reg.envelope, payload));
                }
            }
        }
        for (RewakeEnvelope env : matched) {
            deliver(env);
        }
        return matched.size();
    }

    private static RewakeEnvelope mergePayload(RewakeEnvelope envelope, Map<String, String> incoming) {
        if (incoming.isEmpty()) {
            return envelope;
        }
        final Map<String, String> merged = new LinkedHashMap<>(envelope.getPayload());
        merged.putAll(incoming);
        return envelope.toBuilder().payload(merged).build();
    }

    @Override
    public List<RewakeEnvelope> listPending() {
        final List<RewakeEnvelope> snap = new ArrayList<>(pending.size());
        for (Registration reg : pending.values()) {
            snap.add(reg.envelope);
        }
        return List.copyOf(snap);
    }

    /**
     * Returns the wall-clock instant the service uses for fire-time calculations. Mainly useful for tests that inject
     * a fixed clock.
     *
     * @return current instant from the configured {@link Clock}
     */
    public Instant now() {
        return clock.instant();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Registration reg : pending.values()) {
            if (reg.future != null) {
                reg.future.cancel(false);
            }
            quotaManager.release(reg.envelope.getAgentRuntimeId());
        }
        pending.clear();
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RewakeService has been closed");
        }
    }

    private static final class Registration {
        final RewakeEnvelope envelope;
        volatile ScheduledFuture<?> future;

        Registration(RewakeEnvelope envelope) {
            this.envelope = envelope;
        }
    }
}
