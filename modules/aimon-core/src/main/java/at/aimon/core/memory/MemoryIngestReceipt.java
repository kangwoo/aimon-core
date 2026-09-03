package at.aimon.core.memory;

/**
 * What a {@link MemoryIngestor} did with a {@link MemoryIngestRequest}.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class MemoryIngestReceipt {

    private final int accepted;
    private final boolean derived;

    private MemoryIngestReceipt(Builder builder) {
        if (builder.accepted < 0) {
            throw new IllegalArgumentException("accepted must be >= 0, got " + builder.accepted);
        }
        this.accepted = builder.accepted;
        this.derived = builder.derived;
    }

    /**
     * Starts a receipt.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns how many messages the backend took.
     *
     * @return the count, {@code >= 0}
     */
    public int getAccepted() {
        return accepted;
    }

    /**
     * Returns whether derivation actually finished before the call returned.
     *
     * <p>
     * {@code false} is the ordinary answer for a queue-backed backend, and it is also the answer when
     * {@link MemoryIngestRequest#isWaitForDerivation()} was asked for and could not be honoured — the caller reads the
     * outcome here rather than assuming the request was granted.
     *
     * @return {@code true} when the messages have already been turned into observations
     */
    public boolean isDerived() {
        return derived;
    }

    @Override
    public String toString() {
        return "MemoryIngestReceipt{accepted=" + accepted + ", derived=" + derived + "}";
    }

    /** Builder for {@link MemoryIngestReceipt}. */
    public static final class Builder {

        private int accepted;
        private boolean derived;

        private Builder() {
        }

        /**
         * Sets how many messages were taken.
         *
         * @param accepted
         *            the count, {@code >= 0}
         * @return this builder
         */
        public Builder accepted(int accepted) {
            this.accepted = accepted;
            return this;
        }

        /**
         * Records whether derivation finished before returning.
         *
         * @param derived
         *            {@code true} when it did
         * @return this builder
         */
        public Builder derived(boolean derived) {
            this.derived = derived;
            return this;
        }

        /**
         * Validates and builds the receipt.
         *
         * @return the immutable receipt
         * @throws IllegalArgumentException
         *             if {@code accepted} is negative
         */
        public MemoryIngestReceipt build() {
            return new MemoryIngestReceipt(this);
        }
    }
}
