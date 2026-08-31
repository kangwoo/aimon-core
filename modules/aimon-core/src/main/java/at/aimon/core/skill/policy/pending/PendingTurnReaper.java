package at.aimon.core.skill.policy.pending;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically evicts expired {@link PendingTurn}s from a {@link PendingTurnRegistry}.
 *
 * <p>
 * Application-scoped: build once at startup with {@link #builder()}, call {@link #start()} to schedule the periodic
 * sweep, and call {@link #close()} on shutdown. Without the reaper, abandoned suspended turns accumulate in the
 * registry until the process restarts because no SK-11 path other than user input ({@code /approve}, {@code /deny})
 * removes them.
 *
 * <p>
 * Each sweep delegates to {@link PendingTurnRegistry#removeExpired(java.time.Instant) registry.removeExpired(now)}; the
 * registry is responsible for thread-safe eviction. The reaper itself only owns the periodic trigger and a
 * single-thread
 * daemon executor.
 *
 * <p>
 * Multi-instance deployments must take care that the configured registry is shared (or partitioned), since otherwise
 * reapers on different instances will only see locally registered turns. The reaper itself is safe to run on every
 * instance — {@link PendingTurnRegistry#removeExpired(java.time.Instant)} is idempotent.
 */
public final class PendingTurnReaper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PendingTurnReaper.class);

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final PendingTurnRegistry registry;
    private final Duration interval;
    private final Clock clock;
    private final Consumer<List<PendingTurn>> expirationListener;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

    private PendingTurnReaper(Builder builder) {
        this.registry = Objects.requireNonNull(builder.registry, "registry cannot be null");
        this.interval = Objects.requireNonNull(builder.interval, "interval cannot be null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive, got: " + interval);
        }
        this.clock = builder.clock != null ? builder.clock : Clock.systemUTC();
        this.expirationListener = builder.expirationListener != null ? builder.expirationListener : turns -> {
        };
        if (builder.scheduler != null) {
            this.scheduler = builder.scheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = defaultScheduler();
            this.ownsScheduler = true;
        }
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "pending-turn-reaper");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Schedules the periodic sweep. The first sweep fires after {@code interval}; subsequent sweeps run
     * {@code interval} after the previous one completes. No-op if already started.
     */
    public synchronized void start() {
        if (task.get() != null) {
            return;
        }
        final long delayMs = interval.toMillis();
        final ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(this::reapOnce, delayMs, delayMs,
                TimeUnit.MILLISECONDS);
        task.set(future);
        log.debug("PendingTurnReaper started with interval={}", interval);
    }

    /**
     * Performs a single reap pass and returns the removed snapshots. Safe to call directly (e.g., from tests or on
     * SIGINT). Exceptions thrown by the listener are caught and logged to keep the scheduled sweep alive.
     *
     * @return the snapshots that were evicted (immutable, never null, may be empty)
     */
    public List<PendingTurn> reapOnce() {
        try {
            final List<PendingTurn> removed = registry.removeExpired(clock.instant());
            if (!removed.isEmpty()) {
                log.info("Removed {} expired pending turn(s) from registry", removed.size());
                try {
                    expirationListener.accept(removed);
                } catch (RuntimeException e) {
                    log.warn("Expiration listener threw: {}", e.getMessage(), e);
                }
            }
            return removed;
        } catch (RuntimeException e) {
            log.error("PendingTurnReaper sweep failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Cancels the scheduled task and shuts down the executor when the reaper owns it (i.e., a custom scheduler was not
     * supplied). Safe to call multiple times.
     */
    @Override
    public synchronized void close() {
        final ScheduledFuture<?> future = task.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
        log.debug("PendingTurnReaper closed");
    }

    /** Builder for {@link PendingTurnReaper}. */
    public static final class Builder {

        private PendingTurnRegistry registry;
        private Duration interval;
        private Clock clock;
        private Consumer<List<PendingTurn>> expirationListener;
        private ScheduledExecutorService scheduler;

        private Builder() {
        }

        /** Sets the registry whose expired turns should be evicted. Required. */
        public Builder registry(PendingTurnRegistry registry) {
            this.registry = registry;
            return this;
        }

        /** Sets the sweep interval. Must be positive. Required. */
        public Builder interval(Duration interval) {
            this.interval = interval;
            return this;
        }

        /** Overrides the clock used to compute "now" for expiry checks. Defaults to {@link Clock#systemUTC()}. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Sets a callback invoked after every non-empty eviction. Useful for surfacing expirations to the CLI or to a
         * notification channel. Exceptions thrown by the listener are caught and logged so the sweep keeps running.
         */
        public Builder expirationListener(Consumer<List<PendingTurn>> expirationListener) {
            this.expirationListener = expirationListener;
            return this;
        }

        /**
         * Supplies an external scheduler. When provided, the reaper does NOT shut it down on {@link #close()} — the
         * caller retains lifecycle ownership. When omitted, the reaper creates its own single-thread daemon scheduler
         * and shuts it down on close.
         */
        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /** Builds the reaper. */
        public PendingTurnReaper build() {
            return new PendingTurnReaper(this);
        }
    }
}
