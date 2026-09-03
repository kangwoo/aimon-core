package at.aimon.core.memory;

import java.util.Objects;
import java.util.Optional;

/**
 * Asks a {@link MemorySearcher} for the observations most relevant to a phrase.
 *
 * <p>
 * As on {@link MemorySnapshotQuery}, the workspace is {@code subject.getWorkspace()} and is not a field of its own.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class MemorySearchQuery {

    /** Default number of hits when the caller does not say. */
    public static final int DEFAULT_TOP_K = 10;

    private final PeerView subject;
    private final PeerView observer;
    private final String query;
    private final int topK;
    private final double minScore;
    private final String sessionId;

    private MemorySearchQuery(Builder builder) {
        this.subject = Objects.requireNonNull(builder.subject, "subject cannot be null");
        this.observer = builder.observer;
        this.query = Objects.requireNonNull(builder.query, "query cannot be null");
        if (this.query.isBlank()) {
            throw new IllegalArgumentException("query cannot be blank");
        }
        if (builder.topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1, got " + builder.topK);
        }
        this.topK = builder.topK;
        if (Double.isNaN(builder.minScore) || builder.minScore < 0.0d || builder.minScore > 1.0d) {
            throw new IllegalArgumentException("minScore must be within [0, 1], got " + builder.minScore);
        }
        this.minScore = builder.minScore;
        this.sessionId = builder.sessionId;

        if (observer != null && !observer.getWorkspace().equals(subject.getWorkspace())) {
            throw new IllegalArgumentException("subject and observer must belong to the same workspace");
        }
    }

    /**
     * Starts a query.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the peer whose observations are searched. Its workspace is the query's workspace.
     *
     * @return the subject, never null
     */
    public PeerView getSubject() {
        return subject;
    }

    /**
     * Returns the peer running the search — who is asking, not a narrowing of what comes back.
     *
     * <p>
     * It is here because a backend that scopes conclusions by peer <em>pair</em> needs both halves to answer at all.
     * The store-backed default derives everything from {@link #getSubject()} and does not read this, which costs
     * nothing: an observation is filed as being about a subject, so a search about that subject is the same search
     * whoever runs it. Deliberately not one of the two axes {@link MemorySearcher} rejects when it cannot apply them
     * — those promise a smaller result, and this does not promise anything.
     *
     * @return the observer, or empty when the caller has none to name
     */
    public Optional<PeerView> getObserver() {
        return Optional.ofNullable(observer);
    }

    /**
     * Returns the search phrase.
     *
     * @return the phrase, never null or blank
     */
    public String getQuery() {
        return query;
    }

    /**
     * Returns the maximum number of hits wanted.
     *
     * @return the cap, {@code >= 1}
     */
    public int getTopK() {
        return topK;
    }

    /**
     * Returns the score floor hits must clear.
     *
     * <p>
     * {@code 0} means no floor. A positive value is only meaningful against a backend whose
     * {@link MemorySearcher#ranksByScore()} is {@code true}; one that cannot score must reject it rather than ignore
     * it, or the caller believes a filter ran that did not.
     *
     * @return the floor in {@code [0, 1]}
     */
    public double getMinScore() {
        return minScore;
    }

    /**
     * Returns the session the search is confined to.
     *
     * <p>
     * Only meaningful against a backend whose {@link MemorySearcher#narrowsBySession()} is {@code true}; one that
     * cannot narrow must reject it rather than search every session, for the same reason
     * {@link #getMinScore()} is rejected rather than dropped — a caller who asked for a narrower result and was not
     * told it could not be given one reads the wider result as the narrow one. The store-backed default cannot
     * narrow, because the store method underneath it has no session axis.
     *
     * @return the session id, or empty for a cross-session search
     */
    public Optional<String> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    @Override
    public String toString() {
        return "MemorySearchQuery{subject=" + subject.key() + ", topK=" + topK + ", minScore=" + minScore + ", query='"
                + query + "'}";
    }

    /** Builder for {@link MemorySearchQuery}. */
    public static final class Builder {

        private PeerView subject;
        private PeerView observer;
        private String query;
        private int topK = DEFAULT_TOP_K;
        private double minScore;
        private String sessionId;

        private Builder() {
        }

        /**
         * Sets the peer whose observations are searched. Required; carries the workspace.
         *
         * @param subject
         *            the subject (must not be null)
         * @return this builder
         */
        public Builder subject(PeerView subject) {
            this.subject = Objects.requireNonNull(subject, "subject cannot be null");
            return this;
        }

        /**
         * Sets the peer running the search.
         *
         * @param observer
         *            the observer, or {@code null}
         * @return this builder
         */
        public Builder observer(PeerView observer) {
            this.observer = observer;
            return this;
        }

        /**
         * Sets the search phrase. Required.
         *
         * @param query
         *            the phrase (must not be null or blank)
         * @return this builder
         */
        public Builder query(String query) {
            this.query = Objects.requireNonNull(query, "query cannot be null");
            return this;
        }

        /**
         * Sets the maximum number of hits. Defaults to {@value #DEFAULT_TOP_K}.
         *
         * @param topK
         *            the cap, {@code >= 1}
         * @return this builder
         */
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Sets the score floor. Defaults to {@code 0} (no floor).
         *
         * @param minScore
         *            the floor in {@code [0, 1]}
         * @return this builder
         */
        public Builder minScore(double minScore) {
            this.minScore = minScore;
            return this;
        }

        /**
         * Confines the search to one session.
         *
         * <p>
         * A blank string is folded to absent. "" is not a session, and the two spellings of "none" behaving
         * differently is how a caller ends up reading '' back out of an error message.
         *
         * @param sessionId
         *            the session id, or {@code null} for cross-session
         * @return this builder
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = (sessionId == null || sessionId.isBlank()) ? null : sessionId;
            return this;
        }

        /**
         * Validates and builds the query.
         *
         * @return the immutable query
         * @throws IllegalArgumentException
         *             if the phrase is blank, {@code topK < 1}, {@code minScore} is outside {@code [0, 1]}, or the
         *             subject and observer disagree about the workspace
         */
        public MemorySearchQuery build() {
            return new MemorySearchQuery(this);
        }
    }
}
