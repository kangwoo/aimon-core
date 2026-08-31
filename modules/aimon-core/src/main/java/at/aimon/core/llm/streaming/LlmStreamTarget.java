package at.aimon.core.llm.streaming;

import java.util.Objects;

/**
 * Immutable holder grouping the cohesive streaming-delivery trio consumed by
 * {@link at.aimon.core.llm.invoke.LlmCallGateway}'s streaming overloads: the streaming {@link LlmStreamingOptions
 * options}, the caller-supplied {@link LlmStreamSink sink} that receives chunks, and the optional
 * {@link StreamingRetryListener} notified before each discarded attempt.
 *
 * <p>
 * Collapsing these three arguments into a single value object keeps the gateway's streaming signature small and lets
 * callers assemble the delivery target once and pass it around as a unit.
 *
 * <p>
 * Immutable value object. Follows the builder pattern used across the codebase (see
 * {@link at.aimon.core.agent.AgentContent}).
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     LlmStreamTarget target = LlmStreamTarget.builder().options(LlmStreamingOptions.defaults()).sink(sink)
 *             .retryListener(sink::onRetry).build();
 * }
 * </pre>
 */
public final class LlmStreamTarget {

    private final LlmStreamingOptions options;
    private final LlmStreamSink sink;
    private final StreamingRetryListener retryListener;

    private LlmStreamTarget(Builder builder) {
        this.options = Objects.requireNonNull(builder.options, "options must not be null");
        this.sink = Objects.requireNonNull(builder.sink, "sink must not be null");
        this.retryListener = builder.retryListener != null ? builder.retryListener : StreamingRetryListener.NOOP;
    }

    /**
     * Creates a new builder.
     *
     * @return a new {@link Builder} (never {@code null})
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience factory pairing {@code options} and {@code sink} with the {@link StreamingRetryListener#NOOP}
     * listener.
     *
     * @param options
     *            the streaming options (must not be {@code null})
     * @param sink
     *            the caller-supplied chunk consumer (must not be {@code null})
     * @return a new {@link LlmStreamTarget} (never {@code null})
     */
    public static LlmStreamTarget of(LlmStreamingOptions options, LlmStreamSink sink) {
        return builder().options(options).sink(sink).build();
    }

    /**
     * Returns the streaming options.
     *
     * @return the streaming options (never {@code null})
     */
    public LlmStreamingOptions getOptions() {
        return options;
    }

    /**
     * Returns the caller-supplied chunk consumer.
     *
     * @return the sink (never {@code null})
     */
    public LlmStreamSink getSink() {
        return sink;
    }

    /**
     * Returns the retry listener; {@link StreamingRetryListener#NOOP} when none was configured.
     *
     * @return the retry listener (never {@code null})
     */
    public StreamingRetryListener getRetryListener() {
        return retryListener;
    }

    @Override
    public String toString() {
        return "LlmStreamTarget{options=" + options + ", sink=" + sink + ", retryListener=" + retryListener + '}';
    }

    /** Builder for {@link LlmStreamTarget}. Not thread-safe. */
    public static final class Builder {
        private LlmStreamingOptions options;
        private LlmStreamSink sink;
        private StreamingRetryListener retryListener;

        private Builder() {
        }

        /**
         * Sets the streaming options.
         *
         * @param options
         *            the streaming options (must not be {@code null})
         * @return this builder
         */
        public Builder options(LlmStreamingOptions options) {
            this.options = Objects.requireNonNull(options, "options must not be null");
            return this;
        }

        /**
         * Sets the caller-supplied chunk consumer.
         *
         * @param sink
         *            the sink (must not be {@code null})
         * @return this builder
         */
        public Builder sink(LlmStreamSink sink) {
            this.sink = Objects.requireNonNull(sink, "sink must not be null");
            return this;
        }

        /**
         * Sets the retry listener. When omitted (or {@code null}), {@link StreamingRetryListener#NOOP} is used.
         *
         * @param retryListener
         *            the retry listener (may be {@code null})
         * @return this builder
         */
        public Builder retryListener(StreamingRetryListener retryListener) {
            this.retryListener = retryListener;
            return this;
        }

        /**
         * Builds the {@link LlmStreamTarget}.
         *
         * @return an immutable target (never {@code null})
         * @throws NullPointerException
         *             if {@code options} or {@code sink} were not set
         */
        public LlmStreamTarget build() {
            return new LlmStreamTarget(this);
        }
    }
}
