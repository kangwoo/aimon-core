package at.aimon.core.memory;

import java.util.Objects;
import java.util.Optional;

/**
 * Asks a {@link MemorySnapshotReader} for the snapshot of one subject.
 *
 * <p>
 * <b>The workspace is not a field here.</b> It is {@code subject.getWorkspace()}, and that is the only place it comes
 * from. A separate field would put up to three copies of the same workspace in one query (the field, the subject's and
 * the observer's) and would make the multi-tenant failure representable: a subject in workspace A alongside a field
 * saying B is a query that reads B's store for A's peer. {@link ObservationStore} already draws the line this way —
 * its methods derive the workspace from a {@link PeerView}, and the two that take a {@link Workspace} directly are the
 * two with no peer at all.
 *
 * <p>
 * Widening happens by adding fields, never by adding parameters to
 * {@link MemorySnapshotReader#read(MemorySnapshotQuery)}: a new axis must not break an implementation that does not
 * know about it.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class MemorySnapshotQuery {

    private final PeerView subject;
    private final PeerView observer;
    private final String sessionId;
    private final MemorySnapshotScope scope;
    private final MemoryInjectionMode mode;
    private final int maxTokens;

    private MemorySnapshotQuery(Builder builder) {
        this.subject = Objects.requireNonNull(builder.subject, "subject cannot be null");
        this.observer = builder.observer;
        this.sessionId = builder.sessionId;
        this.scope = Objects.requireNonNull(builder.scope, "scope cannot be null");
        this.mode = Objects.requireNonNull(builder.mode, "mode cannot be null");
        if (builder.maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must be >= 0 (0 means no cap), got " + builder.maxTokens);
        }
        this.maxTokens = builder.maxTokens;

        if (observer != null && !observer.getWorkspace().equals(subject.getWorkspace())) {
            throw new IllegalArgumentException("subject and observer must belong to the same workspace");
        }
        if (scope == MemorySnapshotScope.LOCAL && observer == null) {
            throw new IllegalArgumentException("LOCAL scope needs an observer — a local snapshot is by definition the"
                    + " subject as seen by someone. Use GLOBAL or LOCAL_THEN_GLOBAL instead.");
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
     * Returns the peer the snapshot is about. Its workspace is the query's workspace.
     *
     * @return the subject, never null
     */
    public PeerView getSubject() {
        return subject;
    }

    /**
     * Returns the peer whose view is asked for.
     *
     * @return the observer, or empty for a global snapshot
     */
    public Optional<PeerView> getObserver() {
        return Optional.ofNullable(observer);
    }

    /**
     * Returns the session the local snapshot is scoped to.
     *
     * @return the session id, or empty for a session-less execution or a cross-session snapshot
     */
    public Optional<String> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * Returns which snapshot is asked for.
     *
     * @return the scope, never null
     */
    public MemorySnapshotScope getScope() {
        return scope;
    }

    /**
     * Returns how much of the snapshot the caller wants rendered.
     *
     * <p>
     * The default backend honours this exactly. A remote backend translates it into whatever budget parameter its
     * server takes, so there it is a hint — {@link MemorySnapshot#isTruncated()} and
     * {@link MemorySnapshot#isTokenCountEstimated()} keep that asymmetry observable rather than hidden.
     *
     * @return the mode, never null
     */
    public MemoryInjectionMode getMode() {
        return mode;
    }

    /**
     * Returns the token budget for the rendered snapshot.
     *
     * @return the cap, or {@code 0} for no cap
     */
    public int getMaxTokens() {
        return maxTokens;
    }

    @Override
    public String toString() {
        return "MemorySnapshotQuery{subject=" + subject.key() + ", observer="
                + (observer == null ? "-" : observer.key()) + ", session=" + sessionId + ", scope=" + scope + ", mode="
                + mode + ", maxTokens=" + maxTokens + "}";
    }

    /** Builder for {@link MemorySnapshotQuery}. */
    public static final class Builder {

        private PeerView subject;
        private PeerView observer;
        private String sessionId;
        private MemorySnapshotScope scope = MemorySnapshotScope.LOCAL_THEN_GLOBAL;
        private MemoryInjectionMode mode = MemoryInjectionMode.SUMMARY_ONLY;
        private int maxTokens;

        private Builder() {
        }

        /**
         * Sets the peer the snapshot is about. Required; carries the workspace.
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
         * Sets the peer whose view is asked for.
         *
         * @param observer
         *            the observer, or {@code null} for a global snapshot
         * @return this builder
         */
        public Builder observer(PeerView observer) {
            this.observer = observer;
            return this;
        }

        /**
         * Sets the session the local snapshot is scoped to.
         *
         * @param sessionId
         *            the session id, or {@code null} for a session-less execution
         * @return this builder
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * Sets which snapshot is asked for. Defaults to {@link MemorySnapshotScope#LOCAL_THEN_GLOBAL}.
         *
         * @param scope
         *            the scope (must not be null)
         * @return this builder
         */
        public Builder scope(MemorySnapshotScope scope) {
            this.scope = Objects.requireNonNull(scope, "scope cannot be null");
            return this;
        }

        /**
         * Sets how much of the snapshot to render. Defaults to {@link MemoryInjectionMode#SUMMARY_ONLY}.
         *
         * @param mode
         *            the mode (must not be null)
         * @return this builder
         */
        public Builder mode(MemoryInjectionMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode cannot be null");
            return this;
        }

        /**
         * Sets the token budget for the rendered snapshot.
         *
         * @param maxTokens
         *            the cap, or {@code 0} for no cap
         * @return this builder
         */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Validates and builds the query.
         *
         * @return the immutable query
         * @throws IllegalArgumentException
         *             if the subject and observer disagree about the workspace, if {@code maxTokens} is negative, or
         *             if {@link MemorySnapshotScope#LOCAL} was asked for without an observer
         */
        public MemorySnapshotQuery build() {
            return new MemorySnapshotQuery(this);
        }
    }
}
