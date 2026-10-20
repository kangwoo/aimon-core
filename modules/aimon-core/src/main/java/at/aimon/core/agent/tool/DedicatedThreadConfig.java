package at.aimon.core.agent.tool;

/**
 * Immutable configuration for dedicated-thread dispatch: running a batch's consecutive
 * {@link ConcurrencyBehavior#DEDICATED_THREAD DEDICATED_THREAD} calls (foreground {@code Task} subagents) at the same
 * time, each on a thread created for that call and never reused.
 *
 * <p>
 * The default ({@link #disabled()}) keeps the feature <em>off</em>: the executor factory installs no
 * {@link DedicatedThreadDispatcher} and every such call runs in order on the calling thread, exactly as before.
 *
 * <p>
 * This is independent of {@link ToolConcurrencyConfig}: that one sizes the shared worker pool for
 * {@link ConcurrencyBehavior#CONCURRENT_SAFE CONCURRENT_SAFE} tools, this one bounds dedicated threads, which never
 * touch that pool. Either may be enabled without the other. It is also independent of the background subagent pool, so
 * a foreground call never queues behind a long background task.
 *
 * <p>
 * When enabled, two bounds apply:
 * <ul>
 * <li>{@link #getMaxThreads()} caps how many dedicated threads may be alive at once across everything one executor
 * factory assembled (the main executor and the fork executor, all sessions). The calling thread is not counted: when no
 * permit is free, a call runs inline on the calling thread instead of waiting, so the most foreground subagents one
 * batch can have running is {@code maxThreads + 1}.</li>
 * <li>{@link #getMaxForkDepth()} limits which executions fan out: only those whose fork depth is below it. The default
 * {@code 1} fans out an execution with no fork ancestor (a session's turn, a routine, a rewake replay); forks stay
 * sequential.</li>
 * </ul>
 *
 * <p>
 * Unlike {@code SubagentBackgroundConfig} the thread bound is not tied to {@code availableProcessors()}: a dedicated
 * thread spends almost all its life waiting on LLM responses, so the CPU count is not the right ceiling.
 *
 * <p>
 * Instances are immutable; obtain one via {@link #disabled()}, {@link #enabled(int)} or {@link #builder()}.
 */
public final class DedicatedThreadConfig {

    /** Default dedicated-thread bound used when the feature is enabled without an explicit {@code maxThreads}. */
    public static final int DEFAULT_MAX_THREADS = 4;

    /** Default fork-depth bound: only executions with no fork ancestor fan out. */
    public static final int DEFAULT_MAX_FORK_DEPTH = 1;

    private static final DedicatedThreadConfig DISABLED = builder().build();

    private final boolean enabled;
    private final int maxThreads;
    private final int maxForkDepth;

    private DedicatedThreadConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.maxThreads = builder.maxThreads;
        this.maxForkDepth = builder.maxForkDepth;
    }

    /**
     * @return the shared, immutable "feature off" configuration
     */
    public static DedicatedThreadConfig disabled() {
        return DISABLED;
    }

    /**
     * Convenience factory for an enabled configuration with the given thread bound and the default fork depth.
     *
     * @param maxThreads
     *            the maximum number of dedicated threads alive at once (must be >= 1)
     * @return an enabled configuration
     */
    public static DedicatedThreadConfig enabled(int maxThreads) {
        return builder().enabled(true).maxThreads(maxThreads).build();
    }

    /**
     * @return a new builder; defaults to disabled with {@link #DEFAULT_MAX_THREADS} and {@link #DEFAULT_MAX_FORK_DEPTH}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return {@code true} if consecutive {@code DEDICATED_THREAD} calls may fan out onto dedicated threads. Default
     *         {@code false}.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return the maximum number of dedicated threads alive at once, shared by every executor one factory assembled.
     *         The calling thread's inline lane is not counted. Default {@value #DEFAULT_MAX_THREADS}.
     */
    public int getMaxThreads() {
        return maxThreads;
    }

    /**
     * @return the fork depth from which executions stop fanning out: an execution fans out only when its fork depth is
     *         below this. Default {@value #DEFAULT_MAX_FORK_DEPTH} (only an execution with no fork ancestor: a
     *         session's turn, a
     *         routine, a rewake replay).
     */
    public int getMaxForkDepth() {
        return maxForkDepth;
    }

    @Override
    public String toString() {
        return "DedicatedThreadConfig{enabled=" + enabled + ", maxThreads=" + maxThreads + ", maxForkDepth="
                + maxForkDepth + '}';
    }

    /** Builder for {@link DedicatedThreadConfig}. */
    public static final class Builder {
        private boolean enabled = false;
        private int maxThreads = DEFAULT_MAX_THREADS;
        private int maxForkDepth = DEFAULT_MAX_FORK_DEPTH;

        private Builder() {
        }

        /**
         * @param enabled
         *            whether consecutive {@code DEDICATED_THREAD} calls may fan out
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * @param maxThreads
         *            the maximum number of dedicated threads alive at once (validated &gt;= 1 in {@link #build()})
         * @return this builder
         */
        public Builder maxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
            return this;
        }

        /**
         * @param maxForkDepth
         *            the fork depth from which executions stop fanning out (validated &gt;= 1 in {@link #build()})
         * @return this builder
         */
        public Builder maxForkDepth(int maxForkDepth) {
            this.maxForkDepth = maxForkDepth;
            return this;
        }

        /**
         * @return the immutable configuration
         * @throws IllegalArgumentException
         *             if {@code maxThreads < 1} or {@code maxForkDepth < 1}
         */
        public DedicatedThreadConfig build() {
            if (maxThreads < 1) {
                throw new IllegalArgumentException("maxThreads must be >= 1, got: " + maxThreads);
            }
            if (maxForkDepth < 1) {
                throw new IllegalArgumentException("maxForkDepth must be >= 1, got: " + maxForkDepth);
            }
            return new DedicatedThreadConfig(this);
        }
    }
}
