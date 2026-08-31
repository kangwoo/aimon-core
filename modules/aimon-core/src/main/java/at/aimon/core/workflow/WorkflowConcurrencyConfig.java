package at.aimon.core.workflow;

/**
 * Immutable configuration for workflow fan-out concurrency.
 *
 * <p>
 * Controls whether {@code parallel} / {@code pipeline} may run their tasks on a bounded worker pool and the upper bound
 * on how many run at once. It mirrors the two-tier bound of {@code ToolConcurrencyConfig}, but has no streaming-overlap
 * knob (workflow has no token stream to overlap) and — unlike the tool config — is <em>on</em> by default via
 * {@link #defaults()}, since parallelism is the point of an workflow run. {@link #disabled()} yields a purely
 * sequential runner (useful for deterministic tests and for a resume/replay mode).
 *
 * <p>
 * Two independent bounds apply when enabled:
 * <ul>
 * <li>{@link #getMaxConcurrency()} sizes the shared worker pool — the global host-protection ceiling.</li>
 * <li>{@link #getPerBatchMax()} caps how many tasks of a <em>single</em> {@code parallel}/{@code pipeline} call may
 * occupy the shared pool at once, so one fat fan-out cannot monopolise every worker and starve a concurrent run. It
 * must
 * be in {@code [1, maxConcurrency]} and defaults to {@code maxConcurrency}.</li>
 * </ul>
 *
 * <p>
 * Instances are immutable; obtain one via {@link #disabled()}, {@link #defaults()} or {@link #builder()}.
 */
public final class WorkflowConcurrencyConfig {

    /**
     * Static fallback worker-pool bound used when a caller enables concurrency without an explicit
     * {@code maxConcurrency}. {@link #defaults()} derives a machine-aware bound instead.
     */
    public static final int DEFAULT_MAX_CONCURRENCY = 4;

    /** Upper clamp for the machine-derived default (mirrors Claude Code's {@code min(16, cores - 2)} cap). */
    private static final int DEFAULT_MAX_CONCURRENCY_CEILING = 16;

    /**
     * Default maximum fan-out nesting depth (design §6.2). {@code 1} reproduces the original single-level behaviour:
     * the top-level {@code parallel}/{@code pipeline} uses the pool, every nested one runs sequentially. Raise it to
     * enable true nested parallelism to that depth.
     */
    public static final int DEFAULT_MAX_NESTING_DEPTH = 1;

    /**
     * Floor for the machine-independent default absolute live-thread guard (design §6.2). The effective default
     * is {@code max(maxConcurrency, this)} so the guard cap is always at least the leaf ceiling.
     */
    private static final int DEFAULT_MIN_LIVE_FANOUT_THREADS = 256;

    /**
     * Builder sentinel meaning "derive maxLiveFanoutThreads from maxConcurrency" (see
     * {@link #DEFAULT_MIN_LIVE_FANOUT_THREADS}).
     */
    private static final int UNSET_MAX_LIVE_FANOUT_THREADS = Integer.MIN_VALUE;

    /**
     * Builder sentinel meaning "no explicit per-batch cap" → resolved to {@code maxConcurrency} at build time. Kept out
     * of the valid {@code [1, maxConcurrency]} range (negative) so an explicit {@code perBatchMax(0)} is rejected by
     * validation rather than silently coerced to the default.
     */
    private static final int UNSET_PER_BATCH_MAX = Integer.MIN_VALUE;

    private static final WorkflowConcurrencyConfig DISABLED = builder().build();

    private final boolean enabled;
    private final int maxConcurrency;
    private final int perBatchMax;
    private final int maxNestingDepth;
    private final int maxLiveFanoutThreads;

    private WorkflowConcurrencyConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.maxConcurrency = builder.maxConcurrency;
        this.perBatchMax = builder.perBatchMax == UNSET_PER_BATCH_MAX ? builder.maxConcurrency : builder.perBatchMax;
        this.maxNestingDepth = builder.maxNestingDepth;
        this.maxLiveFanoutThreads = builder.maxLiveFanoutThreads == UNSET_MAX_LIVE_FANOUT_THREADS
                ? Math.max(builder.maxConcurrency, DEFAULT_MIN_LIVE_FANOUT_THREADS)
                : builder.maxLiveFanoutThreads;
    }

    /**
     * @return the shared, immutable "fan-out off" configuration. A dispatcher built with this always runs tasks
     *         sequentially and never creates a worker pool.
     */
    public static WorkflowConcurrencyConfig disabled() {
        return DISABLED;
    }

    /**
     * Returns the default enabled configuration with a machine-aware bound: {@code max(1, min(16, cores - 2))}, leaving
     * headroom for the caller/main threads while capping fan-out on large machines.
     *
     * @return an enabled configuration sized to the current machine
     */
    public static WorkflowConcurrencyConfig defaults() {
        final int cores = Runtime.getRuntime().availableProcessors();
        final int bound = Math.max(1, Math.min(DEFAULT_MAX_CONCURRENCY_CEILING, cores - 2));
        return enabled(bound);
    }

    /**
     * Convenience factory for an enabled configuration with the given concurrency bound.
     *
     * @param maxConcurrency
     *            the maximum number of tasks to run concurrently (must be >= 1)
     * @return an enabled configuration
     */
    public static WorkflowConcurrencyConfig enabled(int maxConcurrency) {
        return builder().enabled(true).maxConcurrency(maxConcurrency).build();
    }

    /**
     * Convenience factory for an enabled configuration with both an explicit global pool bound and a per-batch cap.
     *
     * @param maxConcurrency
     *            the shared worker-pool bound (must be >= 1)
     * @param perBatchMax
     *            the maximum tasks from one fan-out call that may run concurrently (must be in
     *            {@code [1, maxConcurrency]})
     * @return an enabled configuration
     */
    public static WorkflowConcurrencyConfig enabled(int maxConcurrency, int perBatchMax) {
        return builder().enabled(true).maxConcurrency(maxConcurrency).perBatchMax(perBatchMax).build();
    }

    /**
     * @return a new builder; defaults to disabled with {@link #DEFAULT_MAX_CONCURRENCY}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return {@code true} if the dispatcher may fan tasks out to the worker pool. Default {@code false} (but
     *         {@link #defaults()} is enabled).
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return the size of the shared worker pool — the global host-protection ceiling. Only consulted when
     *         {@link #isEnabled()} is {@code true}. Default {@value #DEFAULT_MAX_CONCURRENCY}.
     */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    /**
     * @return the maximum number of tasks from a single fan-out call allowed to run concurrently on the shared pool.
     *         Always in {@code [1, maxConcurrency]}; defaults to {@link #getMaxConcurrency()}. Only consulted when
     *         {@link #isEnabled()} is {@code true}.
     */
    public int getPerBatchMax() {
        return perBatchMax;
    }

    /**
     * @return the maximum fan-out nesting depth (design §6.2). A {@code parallel}/{@code pipeline} at a deeper level
     *         degrades to sequential execution. {@code 1} (the default) reproduces the pre-Phase-4 sequential-fallback
     *         behaviour exactly.
     */
    public int getMaxNestingDepth() {
        return maxNestingDepth;
    }

    /**
     * @return the absolute live fan-out thread guard (design §6.2). When the runner-owned live-thread reservation
     *         would exceed this, a dispatch degrades to sequential execution rather than spawning threads, bounding the
     *         {@code perBatchMax^maxNestingDepth} growth of the unbounded cached pool. Always
     *         {@code >= maxConcurrency};
     *         defaults to {@code max(maxConcurrency, 256)}.
     */
    public int getMaxLiveFanoutThreads() {
        return maxLiveFanoutThreads;
    }

    /**
     * Derives a shared-pool <b>fair</b> variant of this config (design §5.2). When one runner-owned pool is
     * shared across concurrently-hosted runs, a single {@code parallel}/{@code pipeline} whose {@code perBatchMax}
     * equals {@code maxConcurrency} — the "use the whole pool" default — can seize every worker and starve the other
     * runs. This tames only that whole-pool case:
     *
     * <ul>
     * <li><b>Sequential</b> ({@link #isEnabled()} false) — returned unchanged; there is no shared pool to contend for.
     * <li><b>Single-worker</b> ({@code maxConcurrency < 2}) — returned unchanged; one worker cannot be split, and it is
     * inherently shared FIFO across runs.
     * <li><b>A tighter {@code perBatchMax}</b> ({@code perBatchMax < maxConcurrency}) — returned unchanged. A value
     * strictly below the pool size is an operator-chosen cap and is honoured as-is; it is the operator's responsibility
     * and is <em>not</em> re-sized against {@code maxConcurrentRuns}.
     * <li><b>Whole-pool {@code perBatchMax}</b> ({@code perBatchMax == maxConcurrency} — the default, and equivalently
     * an explicit {@code enabled(n, n)}) — auto-derived to an equal share
     * {@code max(1, min(maxConcurrency - 1, floor(maxConcurrency / maxConcurrentRuns)))}, so it is always strictly
     * below
     * {@code maxConcurrency} (leaving headroom for a concurrent run) and satisfies the sizing rule
     * {@code maxConcurrency >= maxConcurrentRuns x perBatchMax} (except when there are more runs than workers, where it
     * floors to 1 and runs share the pool FIFO).
     * </ul>
     *
     * <p>
     * The derivation sizes fairness against the runner's background hosting ceiling; it is best-effort under concurrent
     * foreground {@code run()} callers, which also share the pool.
     *
     * @param maxConcurrentRuns
     *            the maximum number of runs the runner may host concurrently (must be >= 1)
     * @return a fairness-adjusted config (possibly {@code this})
     * @throws IllegalArgumentException
     *             if {@code maxConcurrentRuns < 1}
     */
    public WorkflowConcurrencyConfig forSharedPool(int maxConcurrentRuns) {
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("maxConcurrentRuns must be >= 1, got: " + maxConcurrentRuns);
        }
        if (!enabled || maxConcurrency < 2 || perBatchMax < maxConcurrency) {
            return this;
        }
        final int fair = Math.max(1, Math.min(maxConcurrency - 1, maxConcurrency / maxConcurrentRuns));
        // Preserve every non-perBatchMax field (maxNestingDepth, maxLiveFanoutThreads) across the whole-pool
        // rebuild — only perBatchMax is re-derived. A dropped guard field would silently revert to its default.
        return builder().enabled(true).maxConcurrency(maxConcurrency).perBatchMax(fair).maxNestingDepth(maxNestingDepth)
                .maxLiveFanoutThreads(maxLiveFanoutThreads).build();
    }

    @Override
    public String toString() {
        return "WorkflowConcurrencyConfig{enabled=" + enabled + ", maxConcurrency=" + maxConcurrency + ", perBatchMax="
                + perBatchMax + ", maxNestingDepth=" + maxNestingDepth + ", maxLiveFanoutThreads="
                + maxLiveFanoutThreads + '}';
    }

    /** Builder for {@link WorkflowConcurrencyConfig}. */
    public static final class Builder {
        private boolean enabled = false;
        private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;
        private int perBatchMax = UNSET_PER_BATCH_MAX;
        private int maxNestingDepth = DEFAULT_MAX_NESTING_DEPTH;
        private int maxLiveFanoutThreads = UNSET_MAX_LIVE_FANOUT_THREADS;

        private Builder() {
        }

        /**
         * @param enabled
         *            whether the dispatcher may fan tasks out to the worker pool
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * @param maxConcurrency
         *            the maximum number of tasks to run concurrently (validated &gt;= 1 in {@link #build()})
         * @return this builder
         */
        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Caps how many tasks of a single fan-out call may run concurrently on the shared pool. When left unset, it
         * defaults to {@code maxConcurrency} (single-tier behaviour).
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
         * Caps fan-out nesting depth (design §6.2). A {@code parallel}/{@code pipeline} deeper than this degrades to
         * sequential execution. Defaults to {@link #DEFAULT_MAX_NESTING_DEPTH} (= pre-Phase-4 behaviour).
         *
         * @param maxNestingDepth
         *            the maximum nesting depth (validated &gt;= 1 in {@link #build()})
         * @return this builder
         */
        public Builder maxNestingDepth(int maxNestingDepth) {
            this.maxNestingDepth = maxNestingDepth;
            return this;
        }

        /**
         * Sets the absolute live fan-out thread guard (design §6.2). When left unset it defaults to
         * {@code max(maxConcurrency, 256)}. Must be {@code >= maxConcurrency} so the leaf ceiling stays reachable.
         *
         * @param maxLiveFanoutThreads
         *            the live-thread cap (validated &gt;= {@code maxConcurrency} in {@link #build()})
         * @return this builder
         */
        public Builder maxLiveFanoutThreads(int maxLiveFanoutThreads) {
            this.maxLiveFanoutThreads = maxLiveFanoutThreads;
            return this;
        }

        /**
         * @return the immutable configuration
         * @throws IllegalArgumentException
         *             if {@code maxConcurrency < 1}; an explicit {@code perBatchMax} is not in
         *             {@code [1, maxConcurrency]}; {@code maxNestingDepth < 1}; an explicit
         *             {@code maxLiveFanoutThreads < maxConcurrency}; or the worst-case nested fan-out footprint
         *             {@code perBatchMax^maxNestingDepth} exceeds {@code maxLiveFanoutThreads}
         */
        public WorkflowConcurrencyConfig build() {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be >= 1, got: " + maxConcurrency);
            }
            final int effectivePerBatchMax = perBatchMax == UNSET_PER_BATCH_MAX ? maxConcurrency : perBatchMax;
            if (effectivePerBatchMax < 1 || effectivePerBatchMax > maxConcurrency) {
                throw new IllegalArgumentException(
                        "perBatchMax must be in [1, maxConcurrency(" + maxConcurrency + ")], got: " + perBatchMax);
            }
            if (maxNestingDepth < 1) {
                throw new IllegalArgumentException("maxNestingDepth must be >= 1, got: " + maxNestingDepth);
            }
            final int effectiveMaxLive = maxLiveFanoutThreads == UNSET_MAX_LIVE_FANOUT_THREADS
                    ? Math.max(maxConcurrency, DEFAULT_MIN_LIVE_FANOUT_THREADS)
                    : maxLiveFanoutThreads;
            if (effectiveMaxLive < maxConcurrency) {
                throw new IllegalArgumentException("maxLiveFanoutThreads must be >= maxConcurrency(" + maxConcurrency
                        + "), got: " + maxLiveFanoutThreads);
            }
            // Reject a config whose worst-case nested footprint (perBatchMax multiplied maxNestingDepth times) would
            // exceed the absolute thread budget — depth and width multiply (design §6.2). Computed in long with an
            // early exit so a deep/wide config cannot overflow before the comparison.
            long worstCase = 1L;
            for (int d = 0; d < maxNestingDepth; d++) {
                worstCase *= effectivePerBatchMax;
                if (worstCase > effectiveMaxLive) {
                    throw new IllegalArgumentException("perBatchMax^maxNestingDepth (" + effectivePerBatchMax + "^"
                            + maxNestingDepth + ") exceeds maxLiveFanoutThreads(" + effectiveMaxLive
                            + "); lower maxNestingDepth/perBatchMax or raise maxLiveFanoutThreads");
                }
            }
            return new WorkflowConcurrencyConfig(this);
        }
    }
}
