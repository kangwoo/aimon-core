package at.aimon.core.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a {@link MemorySnapshotReader} knows about a subject right now, ready to be put in front of a model.
 *
 * <h2>{@link #getRenderedText() renderedText} is the canonical payload</h2>
 *
 * <p>
 * It is the backend's own prose about the subject — the summary for the store-backed default, and for a remote
 * backend the single rendered blob that is all such a service returns. Everything around it (which peer, which scope,
 * when, how many tokens, the headings) is framing the consumer adds from the fields on this object.
 *
 * <p>
 * {@link #getObservations() observations} is best-effort and structured: a backend that computes representations on
 * read has no individual observations to hand over, and one that has them still leaves the list empty when the query's
 * mode or budget excluded them — {@link #isObservationsAvailable()} and {@link #isTruncated()} tell those three cases
 * apart, so a consumer never has to guess which empty list it is looking at.
 *
 * <h2>Three signals for three losses inside the tier</h2>
 *
 * <p>
 * A backend either serves the SNAPSHOT tier or does not, and {@link MemoryCapabilities} settles that. It does not
 * settle what the snapshot contains, so each thing that can go missing says so on its own:
 *
 * <ul>
 * <li>{@link #isObservationsAvailable()} — without it, "this peer has no observations" and "this backend does not
 * expose observations" render identically, and the model is told the first when the second is true.
 * <li>{@link #isConfidenceAvailable()} — a backend that fabricates a confidence to fill the field would hand the model
 * a plausible false number, which is worse than an empty one.
 * <li>{@link #isTruncated()} and {@link #isTokenCountEstimated()} — a budget honoured approximately is still worth
 * reporting exactly.
 * </ul>
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class MemorySnapshot {

    private final String renderedText;
    private final MemorySnapshotScope resolvedScope;
    private final Instant generatedAt;
    private final int tokenCount;
    private final boolean tokenCountEstimated;
    private final boolean truncated;
    private final boolean observationsAvailable;
    private final boolean confidenceAvailable;
    private final List<Observation> observations;

    private MemorySnapshot(Builder builder) {
        this.renderedText = Objects.requireNonNull(builder.renderedText, "renderedText cannot be null");
        this.resolvedScope = Objects.requireNonNull(builder.resolvedScope, "resolvedScope cannot be null");
        if (this.resolvedScope == MemorySnapshotScope.LOCAL_THEN_GLOBAL) {
            throw new IllegalArgumentException("resolvedScope must say which scope answered, not which was preferred;"
                    + " LOCAL_THEN_GLOBAL is a request-only value");
        }
        this.generatedAt = Objects.requireNonNull(builder.generatedAt, "generatedAt cannot be null");
        if (builder.tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount must be >= 0, got " + builder.tokenCount);
        }
        this.tokenCount = builder.tokenCount;
        this.tokenCountEstimated = builder.tokenCountEstimated;
        this.truncated = builder.truncated;
        this.observationsAvailable = builder.observationsAvailable;
        this.confidenceAvailable = builder.confidenceAvailable;
        this.observations = List.copyOf(Objects.requireNonNull(builder.observations, "observations cannot be null"));

        if (!this.observationsAvailable && !this.observations.isEmpty()) {
            throw new IllegalArgumentException("observationsAvailable=false must come with an empty observations list;"
                    + " the flag says the backend does not expose them at all");
        }
    }

    /**
     * Starts a snapshot.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the backend's prose about the subject, unframed.
     *
     * @return the text, never null; may be empty when the backend has nothing to say in words
     */
    public String getRenderedText() {
        return renderedText;
    }

    /**
     * Returns which scope actually answered.
     *
     * @return {@link MemorySnapshotScope#LOCAL} or {@link MemorySnapshotScope#GLOBAL}, never null
     */
    public MemorySnapshotScope getResolvedScope() {
        return resolvedScope;
    }

    /**
     * Returns when the backend produced this snapshot.
     *
     * @return the instant, never null
     */
    public Instant getGeneratedAt() {
        return generatedAt;
    }

    /**
     * Returns the token count of the rendered content.
     *
     * @return the count, {@code >= 0}
     */
    public int getTokenCount() {
        return tokenCount;
    }

    /**
     * Returns whether {@link #getTokenCount()} was estimated rather than reported by the backend.
     *
     * @return {@code true} when the number is an estimate
     */
    public boolean isTokenCountEstimated() {
        return tokenCountEstimated;
    }

    /**
     * Returns whether content was dropped to fit the query's budget.
     *
     * <p>
     * This is about the <em>budget</em>, not about the mode: a caller who asked for
     * {@link MemoryInjectionMode#SUMMARY_ONLY} got what it asked for and is not truncated.
     *
     * @return {@code true} when the snapshot is not everything the backend had
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Returns whether this backend exposes the individual observations behind the snapshot at all.
     *
     * @return {@code false} when {@link #getObservations()} is empty because the backend has no such concept, rather
     *         than because this subject has no observations
     */
    public boolean isObservationsAvailable() {
        return observationsAvailable;
    }

    /**
     * Returns whether the confidence carried by each of {@link #getObservations()} is a stored value rather than a
     * placeholder.
     *
     * @return {@code true} when the numbers mean something
     */
    public boolean isConfidenceAvailable() {
        return confidenceAvailable;
    }

    /**
     * Returns the observations behind the snapshot, best-effort.
     *
     * @return the observations; always empty when {@link #isObservationsAvailable()} is {@code false}, and also empty
     *         when the query's mode or budget excluded them ({@link #isTruncated()})
     */
    public List<Observation> getObservations() {
        return observations;
    }

    @Override
    public String toString() {
        return "MemorySnapshot{scope=" + resolvedScope + ", tokens=" + tokenCount + (tokenCountEstimated ? "~" : "")
                + ", truncated=" + truncated + ", observations="
                + (observationsAvailable ? String.valueOf(observations.size()) : "n/a") + "}";
    }

    /** Builder for {@link MemorySnapshot}. */
    public static final class Builder {

        private String renderedText;
        private MemorySnapshotScope resolvedScope;
        private Instant generatedAt;
        private int tokenCount;
        private boolean tokenCountEstimated;
        private boolean truncated;
        private boolean observationsAvailable;
        private boolean confidenceAvailable;
        private List<Observation> observations = List.of();

        private Builder() {
        }

        /**
         * Sets the rendered content. Required.
         *
         * @param renderedText
         *            the text (must not be null)
         * @return this builder
         */
        public Builder renderedText(String renderedText) {
            this.renderedText = Objects.requireNonNull(renderedText, "renderedText cannot be null");
            return this;
        }

        /**
         * Sets which scope answered. Required.
         *
         * @param resolvedScope
         *            {@link MemorySnapshotScope#LOCAL} or {@link MemorySnapshotScope#GLOBAL} (must not be null)
         * @return this builder
         */
        public Builder resolvedScope(MemorySnapshotScope resolvedScope) {
            this.resolvedScope = Objects.requireNonNull(resolvedScope, "resolvedScope cannot be null");
            return this;
        }

        /**
         * Sets when the snapshot was produced. Required.
         *
         * @param generatedAt
         *            the instant (must not be null)
         * @return this builder
         */
        public Builder generatedAt(Instant generatedAt) {
            this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt cannot be null");
            return this;
        }

        /**
         * Sets the token count of the rendered content.
         *
         * @param tokenCount
         *            the count, {@code >= 0}
         * @return this builder
         */
        public Builder tokenCount(int tokenCount) {
            this.tokenCount = tokenCount;
            return this;
        }

        /**
         * Marks {@link #tokenCount(int)} as an estimate.
         *
         * @param tokenCountEstimated
         *            {@code true} when the backend did not report a count
         * @return this builder
         */
        public Builder tokenCountEstimated(boolean tokenCountEstimated) {
            this.tokenCountEstimated = tokenCountEstimated;
            return this;
        }

        /**
         * Marks the snapshot as cut down to fit the budget.
         *
         * @param truncated
         *            {@code true} when content was dropped
         * @return this builder
         */
        public Builder truncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        /**
         * Declares whether this backend exposes individual observations at all.
         *
         * @param observationsAvailable
         *            {@code true} when it does
         * @return this builder
         */
        public Builder observationsAvailable(boolean observationsAvailable) {
            this.observationsAvailable = observationsAvailable;
            return this;
        }

        /**
         * Declares whether the observations' confidence values are stored rather than filled in.
         *
         * @param confidenceAvailable
         *            {@code true} when they are real
         * @return this builder
         */
        public Builder confidenceAvailable(boolean confidenceAvailable) {
            this.confidenceAvailable = confidenceAvailable;
            return this;
        }

        /**
         * Sets the observations behind the snapshot.
         *
         * @param observations
         *            the observations (must not be null; empty unless {@link #observationsAvailable(boolean)})
         * @return this builder
         */
        public Builder observations(List<Observation> observations) {
            this.observations = Objects.requireNonNull(observations, "observations cannot be null");
            return this;
        }

        /**
         * Validates and builds the snapshot.
         *
         * @return the immutable snapshot
         * @throws IllegalArgumentException
         *             if the resolved scope is {@link MemorySnapshotScope#LOCAL_THEN_GLOBAL}, if {@code tokenCount} is
         *             negative, or if observations were supplied while {@code observationsAvailable} is {@code false}
         */
        public MemorySnapshot build() {
            return new MemorySnapshot(this);
        }
    }
}
