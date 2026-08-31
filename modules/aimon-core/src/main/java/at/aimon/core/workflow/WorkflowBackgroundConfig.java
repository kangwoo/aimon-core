package at.aimon.core.workflow;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for the runner-owned <b>run-hosting</b> pool that executes background workflow runs
 * (design §5.2). Run-scoped analog of {@code SubagentBackgroundConfig}.
 *
 * <p>
 * A background run's script body occupies one hosting worker for the run's whole lifetime, so this pool must stay
 * <b>physically separate</b> from the per-run fan-out pool: hosting a run on the fan-out pool would pin a fan-out
 * worker and reintroduce the worker-starvation the fan-out reentry guard prevents. {@link #getMaxConcurrentRuns()} caps
 * how many runs execute at once; {@link #getQueueCapacity()} bounds how many may wait (bound it to make the pool shed —
 * reject — under saturation, which {@code runInBackground} settles as a FAILED run instead of spawning an unbounded
 * thread).
 */
public final class WorkflowBackgroundConfig {

    /** Sentinel {@code queueCapacity} meaning an effectively unbounded backing queue (no rejection on saturation). */
    public static final int UNBOUNDED_QUEUE = Integer.MAX_VALUE;

    /** Default {@link #getShutdownDrain() shutdown drain}: how long {@code close()} waits before force-stopping. */
    public static final Duration DEFAULT_SHUTDOWN_DRAIN = Duration.ofSeconds(5);

    private static final int DEFAULT_MAX_CONCURRENT_RUNS = Math.min(4,
            Math.max(1, Runtime.getRuntime().availableProcessors()));

    /**
     * @return the default configuration: {@code maxConcurrentRuns = min(4, availableProcessors)} with an unbounded
     *         queue.
     */
    public static WorkflowBackgroundConfig defaults() {
        return builder().build();
    }

    /**
     * @param maxConcurrentRuns
     *            the maximum number of background runs to host concurrently (must be >= 1)
     * @return a configuration with the given concurrency bound and an unbounded queue
     */
    public static WorkflowBackgroundConfig of(int maxConcurrentRuns) {
        return builder().maxConcurrentRuns(maxConcurrentRuns).build();
    }

    /**
     * @param maxConcurrentRuns
     *            the maximum number of background runs to host concurrently (must be >= 1)
     * @param queueCapacity
     *            the maximum number of queued (waiting) runs (must be >= 1)
     * @return a configuration with the given bounds
     */
    public static WorkflowBackgroundConfig of(int maxConcurrentRuns, int queueCapacity) {
        return builder().maxConcurrentRuns(maxConcurrentRuns).queueCapacity(queueCapacity).build();
    }

    /**
     * @return a new builder; defaults to {@link #defaults()} values.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final int maxConcurrentRuns;
    private final int queueCapacity;
    private final Duration shutdownDrain;

    private WorkflowBackgroundConfig(Builder builder) {
        this.maxConcurrentRuns = builder.maxConcurrentRuns;
        this.queueCapacity = builder.queueCapacity;
        this.shutdownDrain = builder.shutdownDrain;
    }

    /**
     * @return the maximum number of background runs that may be hosted concurrently (the hosting-pool size).
     */
    public int getMaxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    /**
     * @return the maximum number of runs that may wait for a hosting worker. {@link #UNBOUNDED_QUEUE} means no bound.
     */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /**
     * @return {@code true} if the backing queue is effectively unbounded.
     */
    public boolean isQueueUnbounded() {
        return queueCapacity == UNBOUNDED_QUEUE;
    }

    /**
     * @return how long the runner's {@code close()} waits for hosted runs to drain before force-stopping the pool
     *         (interrupting running bodies, dropping queued ones — which the runner then settles as FAILED).
     */
    public Duration getShutdownDrain() {
        return shutdownDrain;
    }

    @Override
    public String toString() {
        return "WorkflowBackgroundConfig{maxConcurrentRuns=" + maxConcurrentRuns + ", queueCapacity="
                + (isQueueUnbounded() ? "unbounded" : queueCapacity) + ", shutdownDrain=" + shutdownDrain + '}';
    }

    /** Builder for {@link WorkflowBackgroundConfig}. */
    public static final class Builder {
        private int maxConcurrentRuns = DEFAULT_MAX_CONCURRENT_RUNS;
        private int queueCapacity = UNBOUNDED_QUEUE;
        private Duration shutdownDrain = DEFAULT_SHUTDOWN_DRAIN;

        private Builder() {
        }

        /**
         * @param maxConcurrentRuns
         *            the maximum number of background runs to host concurrently (validated &gt;= 1 in {@link #build()})
         * @return this builder
         */
        public Builder maxConcurrentRuns(int maxConcurrentRuns) {
            this.maxConcurrentRuns = maxConcurrentRuns;
            return this;
        }

        /**
         * @param queueCapacity
         *            the maximum number of queued runs (validated &gt;= 1 in {@link #build()}); use
         *            {@link #UNBOUNDED_QUEUE} for no bound
         * @return this builder
         */
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        /**
         * @param shutdownDrain
         *            how long {@code close()} waits for hosted runs to drain before force-stopping (must not be null
         *            or negative; {@link Duration#ZERO} force-stops immediately)
         * @return this builder
         */
        public Builder shutdownDrain(Duration shutdownDrain) {
            this.shutdownDrain = shutdownDrain;
            return this;
        }

        /**
         * @return the immutable configuration
         * @throws IllegalArgumentException
         *             if {@code maxConcurrentRuns < 1}, {@code queueCapacity < 1}, or {@code shutdownDrain} is negative
         */
        public WorkflowBackgroundConfig build() {
            if (maxConcurrentRuns < 1) {
                throw new IllegalArgumentException("maxConcurrentRuns must be >= 1, got: " + maxConcurrentRuns);
            }
            if (queueCapacity < 1) {
                throw new IllegalArgumentException("queueCapacity must be >= 1, got: " + queueCapacity);
            }
            Objects.requireNonNull(shutdownDrain, "shutdownDrain cannot be null");
            if (shutdownDrain.isNegative()) {
                throw new IllegalArgumentException("shutdownDrain must not be negative, got: " + shutdownDrain);
            }
            return new WorkflowBackgroundConfig(this);
        }
    }
}
