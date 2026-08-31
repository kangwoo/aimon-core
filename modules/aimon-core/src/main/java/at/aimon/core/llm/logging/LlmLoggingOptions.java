package at.aimon.core.llm.logging;

import java.util.Objects;

/**
 * Configuration for {@link LoggingLlmClient}.
 *
 * <p>
 * Default policy is metadata-only: provider, model, message/tool counts, latency, token usage. Prompt and response text
 * are <b>never</b> logged at INFO. Set {@link Builder#logBodies(boolean)} to {@code true} to additionally emit
 * truncated previews at DEBUG level.
 *
 * <p>
 * Immutable; build with {@link #builder()} or use {@link #defaults()}.
 */
public final class LlmLoggingOptions {

    private static final int DEFAULT_PREVIEW_CHARS = 200;

    private final boolean logBodies;
    private final int maxPreviewChars;

    private LlmLoggingOptions(Builder builder) {
        this.logBodies = builder.logBodies;
        this.maxPreviewChars = builder.maxPreviewChars;
    }

    /**
     * @return the safe default options (no body logging, 200-char previews)
     */
    public static LlmLoggingOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return true when prompt/response previews are emitted at DEBUG level
     */
    public boolean isLogBodies() {
        return logBodies;
    }

    /**
     * @return maximum preview length in characters when {@link #isLogBodies()} is true
     */
    public int getMaxPreviewChars() {
        return maxPreviewChars;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LlmLoggingOptions that = (LlmLoggingOptions) o;
        return logBodies == that.logBodies && maxPreviewChars == that.maxPreviewChars;
    }

    @Override
    public int hashCode() {
        return Objects.hash(logBodies, maxPreviewChars);
    }

    @Override
    public String toString() {
        return "LlmLoggingOptions{logBodies=" + logBodies + ", maxPreviewChars=" + maxPreviewChars + '}';
    }

    /**
     * Builder for {@link LlmLoggingOptions}.
     */
    public static final class Builder {

        private boolean logBodies = false;
        private int maxPreviewChars = DEFAULT_PREVIEW_CHARS;

        private Builder() {
        }

        /**
         * Enables truncated body previews at DEBUG level. Disabled by default to avoid leaking user prompts or
         * provider responses into logs.
         */
        public Builder logBodies(boolean logBodies) {
            this.logBodies = logBodies;
            return this;
        }

        /**
         * Sets the maximum preview length in characters. Must be non-negative.
         */
        public Builder maxPreviewChars(int maxPreviewChars) {
            if (maxPreviewChars < 0) {
                throw new IllegalArgumentException("maxPreviewChars must be >= 0, got " + maxPreviewChars);
            }
            this.maxPreviewChars = maxPreviewChars;
            return this;
        }

        public LlmLoggingOptions build() {
            return new LlmLoggingOptions(this);
        }
    }
}
