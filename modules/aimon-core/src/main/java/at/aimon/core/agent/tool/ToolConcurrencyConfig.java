package at.aimon.core.agent.tool;

/**
 * Immutable configuration for batch-parallel tool execution.
 *
 * <p>
 * Controls whether the {@link ParallelToolDispatcher} is allowed to run independent
 * {@link ConcurrencyBehavior#CONCURRENT_SAFE
 * CONCURRENT_SAFE} tools from the same LLM batch in parallel, and the upper bound on how many run at once.
 *
 * <p>
 * The default ({@link #disabled()}) keeps parallel execution <em>off</em>: dispatchers configured with it always take
 * the sequential path, so introducing the feature causes no behavioural change until an operator opts in.
 *
 * <p>
 * Two independent bounds apply when enabled (a two-tier model):
 * <ul>
 * <li>{@link #getMaxConcurrency()} sizes the shared, executor-scoped worker pool. It is the global host-protection
 * ceiling: across all concurrent turns flowing through one executor, no more than this many tools run at once (default
 * {@value #DEFAULT_MAX_CONCURRENCY}).</li>
 * <li>{@link #getPerBatchMax()} caps how many tools of a <em>single</em> LLM batch may occupy the shared pool
 * simultaneously, so one fat batch cannot monopolise every worker and starve concurrent turns. It must be in
 * {@code [1, maxConcurrency]} and defaults to {@code maxConcurrency} — reproducing the single-tier behaviour
 * bit-for-bit until an operator lowers it.</li>
 * </ul>
 *
 * <p>
 * {@link #isStreamingOverlap() Streaming-tool overlap} (design §7) is an additional opt-in on top of
 * {@code enabled}: when both are set <em>and</em> the executor streams, a completed side-effect-free tool_use block is
 * dispatched to the shared pool as soon as it finishes streaming, so its execution overlaps the still-arriving token
 * stream. It defaults to {@code false} and only takes effect while {@link #isEnabled()} is also {@code true}, so it is
 * a strict no-op until an operator opts into both.
 *
 * <p>
 * Instances are immutable; obtain one via {@link #disabled()} or {@link #builder()}.
 */
public final class ToolConcurrencyConfig {

    /** Default worker-pool bound used when parallel execution is enabled without an explicit {@code maxConcurrency}. */
    public static final int DEFAULT_MAX_CONCURRENCY = 4;

    /**
     * Builder sentinel meaning "no explicit per-batch cap" → resolved to {@code maxConcurrency} at build time. Kept
     * out of the valid {@code [1, maxConcurrency]} range (negative) so an explicit {@code perBatchMax(0)} is rejected
     * by validation rather than silently coerced to the default.
     */
    private static final int UNSET_PER_BATCH_MAX = Integer.MIN_VALUE;

    private static final ToolConcurrencyConfig DISABLED = builder().build();

    private final boolean enabled;
    private final int maxConcurrency;
    private final int perBatchMax;
    private final boolean streamingOverlap;

    private ToolConcurrencyConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.maxConcurrency = builder.maxConcurrency;
        this.perBatchMax = builder.perBatchMax == UNSET_PER_BATCH_MAX ? builder.maxConcurrency : builder.perBatchMax;
        this.streamingOverlap = builder.streamingOverlap;
    }

    /**
     * @return the shared, immutable "parallel execution off" configuration. A dispatcher built with this always runs
     *         tools sequentially and never creates a worker pool.
     */
    public static ToolConcurrencyConfig disabled() {
        return DISABLED;
    }

    /**
     * Convenience factory for an enabled configuration with the given concurrency bound.
     *
     * @param maxConcurrency
     *            the maximum number of tools to run concurrently (must be >= 1)
     * @return an enabled configuration
     */
    public static ToolConcurrencyConfig enabled(int maxConcurrency) {
        return builder().enabled(true).maxConcurrency(maxConcurrency).build();
    }

    /**
     * Convenience factory for an enabled configuration with both an explicit global pool bound and a per-batch cap.
     *
     * @param maxConcurrency
     *            the shared worker-pool bound (must be >= 1)
     * @param perBatchMax
     *            the maximum tools from one batch that may run concurrently (must be in {@code [1, maxConcurrency]})
     * @return an enabled configuration
     */
    public static ToolConcurrencyConfig enabled(int maxConcurrency, int perBatchMax) {
        return builder().enabled(true).maxConcurrency(maxConcurrency).perBatchMax(perBatchMax).build();
    }

    /**
     * @return a new builder; defaults to disabled with {@link #DEFAULT_MAX_CONCURRENCY}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return {@code true} if the dispatcher may parallelise eligible batches. Default {@code false}.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return the size of the shared, executor-scoped worker pool — the maximum number of tools that may run
     *         concurrently across all batches/turns sharing one dispatcher (the global host-protection ceiling). For
     *         the per-batch cap see {@link #getPerBatchMax()}. Only consulted when {@link #isEnabled()} is
     *         {@code true}. Default {@value #DEFAULT_MAX_CONCURRENCY}.
     */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    /**
     * @return the maximum number of tools from a single batch allowed to run concurrently on the shared pool. Always in
     *         {@code [1, maxConcurrency]}; defaults to {@link #getMaxConcurrency()}. Only consulted when
     *         {@link #isEnabled()} is {@code true}.
     */
    public int getPerBatchMax() {
        return perBatchMax;
    }

    /**
     * @return {@code true} if streaming-tool overlap (design §7) is opted in. When {@code true} <em>and</em>
     *         {@link #isEnabled()} is {@code true} and the executor streams, completed side-effect-free tool_use blocks
     *         are dispatched as soon as they finish streaming, overlapping tool execution with the token stream.
     *         Default {@code false}.
     */
    public boolean isStreamingOverlap() {
        return streamingOverlap;
    }

    @Override
    public String toString() {
        return "ToolConcurrencyConfig{enabled=" + enabled + ", maxConcurrency=" + maxConcurrency + ", perBatchMax="
                + perBatchMax + ", streamingOverlap=" + streamingOverlap + '}';
    }

    public static final class Builder {
        private boolean enabled = false;
        private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;
        private int perBatchMax = UNSET_PER_BATCH_MAX;
        private boolean streamingOverlap = false;

        private Builder() {
        }

        /**
         * @param enabled
         *            whether the dispatcher may parallelise eligible batches
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * @param maxConcurrency
         *            the maximum number of tools to run concurrently (validated &gt;= 1 in {@link #build()})
         * @return this builder
         */
        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Caps how many tools of a single batch may run concurrently on the shared pool. When left unset, it defaults
         * to {@code maxConcurrency} (single-tier behaviour).
         *
         * @param perBatchMax
         *            the per-batch concurrency cap (validated to be in {@code [1, maxConcurrency]} in {@link #build()})
         * @return this builder
         */
        public Builder perBatchMax(int perBatchMax) {
            this.perBatchMax = perBatchMax;
            return this;
        }

        /**
         * Opts into streaming-tool overlap (design §7). Only effective when {@link #enabled(boolean)} is also
         * {@code true} and the executor streams; otherwise it is ignored. Defaults to {@code false}.
         *
         * @param streamingOverlap
         *            whether completed side-effect-free tool_use blocks may be dispatched mid-stream
         * @return this builder
         */
        public Builder streamingOverlap(boolean streamingOverlap) {
            this.streamingOverlap = streamingOverlap;
            return this;
        }

        /**
         * @return the immutable configuration
         * @throws IllegalArgumentException
         *             if {@code maxConcurrency < 1}, or an explicit {@code perBatchMax} is not in
         *             {@code [1, maxConcurrency]}
         */
        public ToolConcurrencyConfig build() {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be >= 1, got: " + maxConcurrency);
            }
            final int effectivePerBatchMax = perBatchMax == UNSET_PER_BATCH_MAX ? maxConcurrency : perBatchMax;
            if (effectivePerBatchMax < 1 || effectivePerBatchMax > maxConcurrency) {
                throw new IllegalArgumentException(
                        "perBatchMax must be in [1, maxConcurrency(" + maxConcurrency + ")], got: " + perBatchMax);
            }
            return new ToolConcurrencyConfig(this);
        }
    }
}
