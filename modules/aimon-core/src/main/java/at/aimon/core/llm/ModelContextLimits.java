package at.aimon.core.llm;

/**
 * Token budget thresholds for a given LLM model used to drive
 * {@link at.aimon.core.agent.compact.CompactionGuard} decisions.
 *
 * <p>
 * Field semantics:
 *
 * <ul>
 * <li>{@code contextWindow} — total context window of the model in tokens.
 * <li>{@code reservedOutputTokens} — slack reserved for the assistant response.
 * <li>{@code autoCompactBuffer} — additional buffer above which auto compaction kicks in.
 * <li>{@code warningBuffer} — earlier warning band (above {@code autoCompactBuffer}).
 * <li>{@code blockingBuffer} — final hard blocking band; if exceeded, the conversation cannot continue.
 * </ul>
 *
 * <p>
 * Derived thresholds (computed by callers):
 *
 * <pre>
 * effectiveWindow      = contextWindow - reservedOutputTokens
 * autoCompactThreshold = effectiveWindow - autoCompactBuffer
 * warningThreshold     = autoCompactThreshold - warningBuffer
 * blockingLimit        = effectiveWindow - blockingBuffer
 * </pre>
 *
 * <p>
 * Immutable value object built via {@link Builder}.
 */
public final class ModelContextLimits {

    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    public static final int DEFAULT_RESERVED_OUTPUT_TOKENS = 20_000;
    public static final int DEFAULT_AUTO_COMPACT_BUFFER = 13_000;
    public static final int DEFAULT_WARNING_BUFFER = 20_000;
    public static final int DEFAULT_BLOCKING_BUFFER = 3_000;

    private final int contextWindow;
    private final int reservedOutputTokens;
    private final int autoCompactBuffer;
    private final int warningBuffer;
    private final int blockingBuffer;

    private ModelContextLimits(Builder builder) {
        this.contextWindow = builder.contextWindow;
        this.reservedOutputTokens = builder.reservedOutputTokens;
        this.autoCompactBuffer = builder.autoCompactBuffer;
        this.warningBuffer = builder.warningBuffer;
        this.blockingBuffer = builder.blockingBuffer;
        validate();
    }

    private void validate() {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be > 0, got: " + contextWindow);
        }
        if (reservedOutputTokens <= 0) {
            throw new IllegalArgumentException("reservedOutputTokens must be > 0, got: " + reservedOutputTokens);
        }
        if (autoCompactBuffer <= 0) {
            throw new IllegalArgumentException("autoCompactBuffer must be > 0, got: " + autoCompactBuffer);
        }
        if (warningBuffer <= 0) {
            throw new IllegalArgumentException("warningBuffer must be > 0, got: " + warningBuffer);
        }
        if (blockingBuffer <= 0) {
            throw new IllegalArgumentException("blockingBuffer must be > 0, got: " + blockingBuffer);
        }
        // Ensure thresholds remain positive
        if ((long) reservedOutputTokens + autoCompactBuffer + warningBuffer >= contextWindow) {
            throw new IllegalArgumentException("reservedOutputTokens + autoCompactBuffer + warningBuffer ("
                    + ((long) reservedOutputTokens + autoCompactBuffer + warningBuffer) + ") must be < contextWindow ("
                    + contextWindow + ")");
        }
        if (blockingBuffer > reservedOutputTokens) {
            throw new IllegalArgumentException("blockingBuffer (" + blockingBuffer
                    + ") must be <= reservedOutputTokens (" + reservedOutputTokens + ")");
        }
    }

    /** Returns a {@link ModelContextLimits} populated with the framework default values for the given window. */
    public static ModelContextLimits ofContextWindow(int contextWindow) {
        return builder().contextWindow(contextWindow).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public int getReservedOutputTokens() {
        return reservedOutputTokens;
    }

    public int getAutoCompactBuffer() {
        return autoCompactBuffer;
    }

    public int getWarningBuffer() {
        return warningBuffer;
    }

    public int getBlockingBuffer() {
        return blockingBuffer;
    }

    public int getEffectiveContextWindow() {
        return contextWindow - reservedOutputTokens;
    }

    public int getAutoCompactThreshold() {
        return getEffectiveContextWindow() - autoCompactBuffer;
    }

    public int getWarningThreshold() {
        return getAutoCompactThreshold() - warningBuffer;
    }

    public int getBlockingLimit() {
        return getEffectiveContextWindow() - blockingBuffer;
    }

    @Override
    public String toString() {
        return "ModelContextLimits{contextWindow=" + contextWindow + ", reservedOutputTokens=" + reservedOutputTokens
                + ", autoCompactBuffer=" + autoCompactBuffer + ", warningBuffer=" + warningBuffer + ", blockingBuffer="
                + blockingBuffer + '}';
    }

    /** Builder for {@link ModelContextLimits}. */
    public static final class Builder {
        private int contextWindow = DEFAULT_CONTEXT_WINDOW;
        private int reservedOutputTokens = DEFAULT_RESERVED_OUTPUT_TOKENS;
        private int autoCompactBuffer = DEFAULT_AUTO_COMPACT_BUFFER;
        private int warningBuffer = DEFAULT_WARNING_BUFFER;
        private int blockingBuffer = DEFAULT_BLOCKING_BUFFER;

        private Builder() {
        }

        public Builder contextWindow(int contextWindow) {
            this.contextWindow = contextWindow;
            return this;
        }

        public Builder reservedOutputTokens(int reservedOutputTokens) {
            this.reservedOutputTokens = reservedOutputTokens;
            return this;
        }

        public Builder autoCompactBuffer(int autoCompactBuffer) {
            this.autoCompactBuffer = autoCompactBuffer;
            return this;
        }

        public Builder warningBuffer(int warningBuffer) {
            this.warningBuffer = warningBuffer;
            return this;
        }

        public Builder blockingBuffer(int blockingBuffer) {
            this.blockingBuffer = blockingBuffer;
            return this;
        }

        public ModelContextLimits build() {
            return new ModelContextLimits(this);
        }
    }
}
