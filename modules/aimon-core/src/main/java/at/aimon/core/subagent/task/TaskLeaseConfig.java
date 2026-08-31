package at.aimon.core.subagent.task;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for background-task lease recovery (design §4).
 *
 * <p>
 * In a scale-out deployment a background subagent task's execution lives only on the node that spawned it, while its
 * {@link BackgroundTask} metadata is shared through a {@link BackgroundTaskStore}. If that node crashes mid-run, its
 * task
 * would otherwise stay {@code RUNNING} in the shared store forever — a zombie. Lease recovery closes that gap:
 *
 * <ul>
 * <li>the owning node stamps a {@link BackgroundTask#getLastHeartbeat() heartbeat} on every non-terminal task it owns,
 * refreshing it every {@link #getHeartbeatInterval() heartbeatInterval} via a {@link TaskHeartbeatPublisher};
 * <li>a {@link ZombieTaskReaper} on any surviving node sweeps every {@link #getSweepInterval() sweepInterval} and
 * transitions to {@code FAILED} any non-terminal task whose heartbeat has aged past {@link #getLeaseTtl() leaseTtl} —
 * its owner is presumed lost.
 * </ul>
 *
 * <p>
 * The TTL must be safely larger than the heartbeat interval so a live-but-briefly-stalled node (GC pause, scheduling
 * hiccup) is not mistaken for a dead one: {@code build()} enforces {@code leaseTtl > heartbeatInterval}, and the
 * {@link #defaults() default} leaves a 3× margin ({@code 10s} heartbeat, {@code 30s} TTL). Pick a TTL comfortably above
 * your worst-case stop-the-world pause.
 *
 * <p>
 * Lease recovery is <b>opt-in</b>: with no {@code TaskLeaseConfig} installed, no heartbeat or reaper thread runs and
 * single-node behaviour is unchanged. Instances are immutable; obtain one via {@link #defaults()},
 * {@link #of(Duration, Duration)}, or {@link #builder()}.
 */
public final class TaskLeaseConfig {

    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofSeconds(10);

    /**
     * @return the default configuration: {@code 10s} heartbeat interval, {@code 30s} lease TTL (a 3× margin), and a
     *         {@code 10s} reaper sweep interval.
     */
    public static TaskLeaseConfig defaults() {
        return builder().build();
    }

    /**
     * @param heartbeatInterval
     *            how often the owning node renews each task's heartbeat (must be positive)
     * @param leaseTtl
     *            how long a heartbeat stays valid before the task is reapable (must be greater than
     *            {@code heartbeatInterval})
     * @return a configuration with the given heartbeat/TTL and a sweep interval equal to {@code heartbeatInterval}
     */
    public static TaskLeaseConfig of(Duration heartbeatInterval, Duration leaseTtl) {
        return builder().heartbeatInterval(heartbeatInterval).leaseTtl(leaseTtl).sweepInterval(heartbeatInterval)
                .build();
    }

    /**
     * @return a new builder seeded with the {@link #defaults() default} values.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final Duration heartbeatInterval;
    private final Duration leaseTtl;
    private final Duration sweepInterval;

    private TaskLeaseConfig(Builder builder) {
        this.heartbeatInterval = Objects.requireNonNull(builder.heartbeatInterval, "heartbeatInterval cannot be null");
        this.leaseTtl = Objects.requireNonNull(builder.leaseTtl, "leaseTtl cannot be null");
        this.sweepInterval = Objects.requireNonNull(builder.sweepInterval, "sweepInterval cannot be null");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(leaseTtl, "leaseTtl");
        requirePositive(sweepInterval, "sweepInterval");
        if (leaseTtl.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException(
                    "leaseTtl (" + leaseTtl + ") must be greater than heartbeatInterval (" + heartbeatInterval + ")");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, got: " + value);
        }
    }

    /**
     * @return how often the owning node refreshes the heartbeat of every non-terminal task it owns.
     */
    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * @return how long a heartbeat stays valid; a non-terminal task whose heartbeat is older than this is reaped.
     */
    public Duration getLeaseTtl() {
        return leaseTtl;
    }

    /**
     * @return how often the reaper sweeps the store for expired-lease zombies.
     */
    public Duration getSweepInterval() {
        return sweepInterval;
    }

    @Override
    public String toString() {
        return "TaskLeaseConfig{heartbeatInterval=" + heartbeatInterval + ", leaseTtl=" + leaseTtl + ", sweepInterval="
                + sweepInterval + '}';
    }

    /** Builder for {@link TaskLeaseConfig}. */
    public static final class Builder {
        private Duration heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
        private Duration leaseTtl = DEFAULT_LEASE_TTL;
        private Duration sweepInterval = DEFAULT_SWEEP_INTERVAL;

        private Builder() {
        }

        /**
         * @param heartbeatInterval
         *            how often the owning node renews each task's heartbeat (validated positive in {@link #build()})
         * @return this builder
         */
        public Builder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
            return this;
        }

        /**
         * @param leaseTtl
         *            how long a heartbeat stays valid before the task is reapable (validated
         *            {@code > heartbeatInterval}
         *            in {@link #build()})
         * @return this builder
         */
        public Builder leaseTtl(Duration leaseTtl) {
            this.leaseTtl = leaseTtl;
            return this;
        }

        /**
         * @param sweepInterval
         *            how often the reaper sweeps for expired leases (validated positive in {@link #build()})
         * @return this builder
         */
        public Builder sweepInterval(Duration sweepInterval) {
            this.sweepInterval = sweepInterval;
            return this;
        }

        /**
         * @return the immutable configuration
         * @throws IllegalArgumentException
         *             if any interval is non-positive or {@code leaseTtl <= heartbeatInterval}
         */
        public TaskLeaseConfig build() {
            return new TaskLeaseConfig(this);
        }
    }
}
