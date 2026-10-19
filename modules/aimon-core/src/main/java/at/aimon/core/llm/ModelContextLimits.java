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
 * <li>{@code autoCompactRatio} — fraction of the effective window past which auto compaction starts, in (0, 1]
 * (default {@value #DEFAULT_AUTO_COMPACT_RATIO}). Both context engines start compacting here.
 * <li>{@code warningBuffer} — earlier warning band below the auto-compact threshold.
 * <li>{@code blockingBuffer} — final hard blocking band; if exceeded, the conversation cannot continue.
 * </ul>
 *
 * <p>
 * Derived thresholds (computed by callers):
 *
 * <pre>
 * effectiveWindow      = contextWindow - reservedOutputTokens
 * autoCompactThreshold = (int) (effectiveWindow * autoCompactRatio)
 * warningThreshold     = max(0, autoCompactThreshold - warningBuffer)
 * blockingLimit        = effectiveWindow - blockingBuffer
 * </pre>
 *
 * <p>
 * With a ratio close to 1 the auto-compact threshold can sit above the blocking limit; the blocking limit is checked
 * first, so compaction then starts there.
 *
 * <p>
 * Immutable value object built via {@link Builder}.
 */
public final class ModelContextLimits {

    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    public static final int DEFAULT_RESERVED_OUTPUT_TOKENS = 20_000;
    /** The default fraction of the effective window past which auto compaction starts. */
    public static final double DEFAULT_AUTO_COMPACT_RATIO = 0.8;
    public static final int DEFAULT_WARNING_BUFFER = 20_000;
    public static final int DEFAULT_BLOCKING_BUFFER = 3_000;

    private final int contextWindow;
    private final int reservedOutputTokens;
    private final double autoCompactRatio;
    private final int warningBuffer;
    private final int blockingBuffer;

    private ModelContextLimits(Builder builder) {
        this.contextWindow = builder.contextWindow;
        this.reservedOutputTokens = builder.reservedOutputTokens;
        this.autoCompactRatio = builder.autoCompactRatio;
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
        if (!(autoCompactRatio > 0.0 && autoCompactRatio <= 1.0)) {
            throw new IllegalArgumentException("autoCompactRatio must be in (0, 1], got: " + autoCompactRatio);
        }
        if (warningBuffer <= 0) {
            throw new IllegalArgumentException("warningBuffer must be > 0, got: " + warningBuffer);
        }
        if (blockingBuffer <= 0) {
            throw new IllegalArgumentException("blockingBuffer must be > 0, got: " + blockingBuffer);
        }
        // The effective window must hold the warning band, so a full-ratio threshold leaves a warning above zero.
        if ((long) reservedOutputTokens + warningBuffer >= contextWindow) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens + warningBuffer (" + ((long) reservedOutputTokens + warningBuffer)
                            + ") must be < contextWindow (" + contextWindow + ")");
        }
        // The blocking limit must stay above zero.
        if ((long) reservedOutputTokens + blockingBuffer >= contextWindow) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens + blockingBuffer (" + ((long) reservedOutputTokens + blockingBuffer)
                            + ") must be < contextWindow (" + contextWindow + ")");
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

    /**
     * Returns a builder pre-populated with this instance's values.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return builder().contextWindow(contextWindow).reservedOutputTokens(reservedOutputTokens)
                .autoCompactRatio(autoCompactRatio).warningBuffer(warningBuffer).blockingBuffer(blockingBuffer);
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public int getReservedOutputTokens() {
        return reservedOutputTokens;
    }

    /**
     * Returns the fraction of the effective window past which auto compaction starts.
     *
     * @return the ratio, in (0, 1]
     */
    public double getAutoCompactRatio() {
        return autoCompactRatio;
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
        return (int) (autoCompactRatio * getEffectiveContextWindow());
    }

    public int getWarningThreshold() {
        return Math.max(0, getAutoCompactThreshold() - warningBuffer);
    }

    public int getBlockingLimit() {
        return getEffectiveContextWindow() - blockingBuffer;
    }

    @Override
    public String toString() {
        return "ModelContextLimits{contextWindow=" + contextWindow + ", reservedOutputTokens=" + reservedOutputTokens
                + ", autoCompactRatio=" + autoCompactRatio + ", warningBuffer=" + warningBuffer + ", blockingBuffer="
                + blockingBuffer + '}';
    }

    /** Builder for {@link ModelContextLimits}. */
    public static final class Builder {
        private int contextWindow = DEFAULT_CONTEXT_WINDOW;
        private int reservedOutputTokens = DEFAULT_RESERVED_OUTPUT_TOKENS;
        private double autoCompactRatio = DEFAULT_AUTO_COMPACT_RATIO;
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

        /**
         * Sets the fraction of the effective window past which auto compaction starts.
         *
         * @param autoCompactRatio
         *            the ratio, in (0, 1] (default {@value ModelContextLimits#DEFAULT_AUTO_COMPACT_RATIO})
         * @return this builder
         */
        public Builder autoCompactRatio(double autoCompactRatio) {
            this.autoCompactRatio = autoCompactRatio;
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
