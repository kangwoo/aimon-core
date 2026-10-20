package at.aimon.core.agent.tool;

/**
 * Per-call facts about the execution that is dispatching a batch, passed to
 * {@link ParallelToolDispatcher#dispatch(java.util.List, ToolRegistry, DispatchOptions, java.util.function.Function,
 * java.util.function.Consumer, java.util.function.BiConsumer)}.
 *
 * <p>
 * Exists because one fork executor runs forks of every depth, so a dispatcher cannot learn the depth when it is built.
 * Today it carries only the {@linkplain #getForkDepth() fork depth}, which {@link DedicatedThreadDispatcher} compares
 * with {@link DedicatedThreadConfig#getMaxForkDepth()}. A dispatcher that has no use for it ignores it.
 */
public final class DispatchOptions {

    private static final DispatchOptions ROOT = builder().build();

    private final int forkDepth;

    private DispatchOptions(Builder builder) {
        this.forkDepth = builder.forkDepth;
    }

    /**
     * @return the options of an execution with no fork ancestor — a session's turn, a routine run, a rewake replay
     *         (fork depth 0)
     */
    public static DispatchOptions root() {
        return ROOT;
    }

    /**
     * @return a new builder; defaults to fork depth 0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return how many forks deep the dispatching execution is: 0 for an execution with no fork ancestor, 1 for a fork
     *         it spawned, and so on
     */
    public int getForkDepth() {
        return forkDepth;
    }

    @Override
    public String toString() {
        return "DispatchOptions{forkDepth=" + forkDepth + '}';
    }

    /** Builder for {@link DispatchOptions}. */
    public static final class Builder {
        private int forkDepth;

        private Builder() {
        }

        /**
         * @param forkDepth
         *            how many forks deep the dispatching execution is (validated &gt;= 0 in {@link #build()})
         * @return this builder
         */
        public Builder forkDepth(int forkDepth) {
            this.forkDepth = forkDepth;
            return this;
        }

        /**
         * @return the immutable options
         * @throws IllegalArgumentException
         *             if {@code forkDepth < 0}
         */
        public DispatchOptions build() {
            if (forkDepth < 0) {
                throw new IllegalArgumentException("forkDepth must be >= 0, got: " + forkDepth);
            }
            return new DispatchOptions(this);
        }
    }
}
