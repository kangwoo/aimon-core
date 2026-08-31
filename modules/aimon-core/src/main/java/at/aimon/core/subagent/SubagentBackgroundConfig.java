package at.aimon.core.subagent;

/**
 * Immutable configuration for the background subagent execution pool.
 *
 * <p>
 * Background subagent tasks ({@code Task(run_in_background=true)}) previously ran on an <em>unbounded</em> cached
 * thread
 * pool, so a burst of spawns could create an unbounded number of concurrent ReAct loops (each with its own LLM traffic
 * and sandbox footprint). This config bounds that: {@link #getMaxConcurrency()} caps how many background subagents run
 * at once, and {@link #getQueueCapacity()} bounds how many may wait.
 *
 * <p>
 * The {@link #defaults() default} is deliberately conservative for concurrency ({@code min(4, availableProcessors)})
 * but
 * uses an effectively unbounded queue, so it caps thread/LLM fan-out without rejecting bursts — reproducing the old
 * "everything eventually runs" behaviour while removing the unbounded-thread hazard. Bound the queue explicitly to make
 * the pool shed load (reject) under saturation instead.
 *
 * <p>
 * Instances are immutable; obtain one via {@link #defaults()}, {@link #of(int)}, {@link #of(int, int)}, or
 * {@link #builder()}.
 */
public final class SubagentBackgroundConfig {

    /** Sentinel {@code queueCapacity} meaning an effectively unbounded backing queue (no rejection on saturation). */
    public static final int UNBOUNDED_QUEUE = Integer.MAX_VALUE;

    private static final int DEFAULT_MAX_CONCURRENCY = Math.min(4,
            Math.max(1, Runtime.getRuntime().availableProcessors()));

    /**
     * @return the default configuration: {@code maxConcurrency = min(4, availableProcessors)} with an unbounded queue.
     */
    public static SubagentBackgroundConfig defaults() {
        return builder().build();
    }

    /**
     * @param maxConcurrency
     *            the maximum number of background subagents to run concurrently (must be >= 1)
     * @return a configuration with the given concurrency bound and an unbounded queue
     */
    public static SubagentBackgroundConfig of(int maxConcurrency) {
        return builder().maxConcurrency(maxConcurrency).build();
    }

    /**
     * @param maxConcurrency
     *            the maximum number of background subagents to run concurrently (must be >= 1)
     * @param queueCapacity
     *            the maximum number of queued (waiting) background subagents (must be >= 1)
     * @return a configuration with the given bounds
     */
    public static SubagentBackgroundConfig of(int maxConcurrency, int queueCapacity) {
        return builder().maxConcurrency(maxConcurrency).queueCapacity(queueCapacity).build();
    }

    /**
     * @return a new builder; defaults to {@link #defaults()} values.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final int maxConcurrency;
    private final int queueCapacity;

    private SubagentBackgroundConfig(Builder builder) {
        this.maxConcurrency = builder.maxConcurrency;
        this.queueCapacity = builder.queueCapacity;
    }

    /**
     * @return the maximum number of background subagents that may run concurrently (the worker-pool size).
     */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    /**
     * @return the maximum number of background subagents that may wait for a worker. {@link #UNBOUNDED_QUEUE} means no
     *         bound (bursts queue rather than being rejected).
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

    @Override
    public String toString() {
        return "SubagentBackgroundConfig{maxConcurrency=" + maxConcurrency + ", queueCapacity="
                + (isQueueUnbounded() ? "unbounded" : queueCapacity) + '}';
    }

    /** Builder for {@link SubagentBackgroundConfig}. */
    public static final class Builder {
        private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;
        private int queueCapacity = UNBOUNDED_QUEUE;

        private Builder() {
        }

        /**
         * @param maxConcurrency
         *            the maximum number of background subagents to run concurrently (validated &gt;= 1 in
         *            {@link #build()})
         * @return this builder
         */
        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * @param queueCapacity
         *            the maximum number of queued background subagents (validated &gt;= 1 in {@link #build()}); use
         *            {@link #UNBOUNDED_QUEUE} for no bound
         * @return this builder
         */
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        /**
         * @return the immutable configuration
         * @throws IllegalArgumentException
         *             if {@code maxConcurrency < 1} or {@code queueCapacity < 1}
         */
        public SubagentBackgroundConfig build() {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be >= 1, got: " + maxConcurrency);
            }
            if (queueCapacity < 1) {
                throw new IllegalArgumentException("queueCapacity must be >= 1, got: " + queueCapacity);
            }
            return new SubagentBackgroundConfig(this);
        }
    }
}
