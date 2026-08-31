package at.aimon.core.llm.streaming;

/**
 * Caller-side options for a streaming LLM call.
 *
 * <p>
 * Options tune the behaviour of {@link at.aimon.core.llm.invoke.LlmCallGateway}'s streaming overload and, for some
 * flags, the provider client as well.
 *
 * <p>
 * Instances are immutable. Obtain the defaults via {@link #defaults()} or build a custom instance via
 * {@link #builder()}.
 */
public final class LlmStreamingOptions {

    private static final LlmStreamingOptions DEFAULTS = builder().build();

    /**
     * @return an instance with all flags set to their defaults ({@code bufferUntilFirstSuccess=false},
     *         {@code includeUsage=true}).
     */
    public static LlmStreamingOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    private final boolean bufferUntilFirstSuccess;
    private final boolean includeUsage;

    private LlmStreamingOptions(Builder builder) {
        this.bufferUntilFirstSuccess = builder.bufferUntilFirstSuccess;
        this.includeUsage = builder.includeUsage;
    }

    /**
     * When {@code true}, the gateway isolates per-attempt chunks in a {@link BufferingStreamSink} and flushes them to
     * the outer sink only after the attempt succeeds. Retries remain invisible to the UI at the cost of losing the
     * Time-To-First-Token benefit. Default {@code false}.
     */
    public boolean isBufferUntilFirstSuccess() {
        return bufferUntilFirstSuccess;
    }

    /**
     * When {@code true}, providers that support it are asked to include cumulative usage with the final chunk (e.g.,
     * OpenAI {@code stream_options.include_usage=true}). Default {@code true}.
     */
    public boolean isIncludeUsage() {
        return includeUsage;
    }

    @Override
    public String toString() {
        return "LlmStreamingOptions{bufferUntilFirstSuccess=" + bufferUntilFirstSuccess + ", includeUsage="
                + includeUsage + '}';
    }

    public static final class Builder {
        private boolean bufferUntilFirstSuccess = false;
        private boolean includeUsage = true;

        private Builder() {
        }

        public Builder bufferUntilFirstSuccess(boolean bufferUntilFirstSuccess) {
            this.bufferUntilFirstSuccess = bufferUntilFirstSuccess;
            return this;
        }

        public Builder includeUsage(boolean includeUsage) {
            this.includeUsage = includeUsage;
            return this;
        }

        public LlmStreamingOptions build() {
            return new LlmStreamingOptions(this);
        }
    }
}
