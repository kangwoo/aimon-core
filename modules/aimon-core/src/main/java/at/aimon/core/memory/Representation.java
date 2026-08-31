package at.aimon.core.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Snapshot of insights about a {@link PeerView subject} at a given point in time,
 * ready to be injected into an LLM context window.
 *
 * <p>
 * A representation is either <em>global</em> (no observer — the system's overall
 * understanding of the subject) or <em>local</em> (scoped to a specific observer
 * and optionally a session — the subject as seen by that observer).
 *
 * <p>
 * The {@code sessionId} is intentionally a plain string, not a live
 * {@code LiveSession} reference: representations are immutable value objects and
 * must not hold long-lived session resources. Consumers that need the session
 * itself look it up by id at use time.
 */
public final class Representation {

    private final PeerView subject;
    private final PeerView observer;
    private final String sessionId;
    private final List<Observation> observations;
    private final String summary;
    private final Instant generatedAt;
    private final int tokenCount;

    private Representation(Builder builder) {
        this.subject = Objects.requireNonNull(builder.subject, "subject cannot be null");
        this.observer = builder.observer;
        this.sessionId = builder.sessionId;
        this.observations = List.copyOf(Objects.requireNonNull(builder.observations, "observations cannot be null"));
        this.summary = Objects.requireNonNull(builder.summary, "summary cannot be null");
        this.generatedAt = Objects.requireNonNull(builder.generatedAt, "generatedAt cannot be null");
        if (builder.tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount must be >= 0, got " + builder.tokenCount);
        }
        this.tokenCount = builder.tokenCount;

        if (observer == null && sessionId != null) {
            throw new IllegalArgumentException("Global representation (observer == null) cannot have a sessionId");
        }
        if (observer != null && !observer.getWorkspace().equals(subject.getWorkspace())) {
            throw new IllegalArgumentException("subject and observer must belong to the same workspace");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public PeerView getSubject() {
        return subject;
    }

    public Optional<PeerView> getObserver() {
        return Optional.ofNullable(observer);
    }

    public Optional<String> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    public List<Observation> getObservations() {
        return observations;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public boolean isGlobal() {
        return observer == null;
    }

    public boolean isLocal() {
        return observer != null;
    }

    @Override
    public String toString() {
        return "Representation{subject=" + subject.key() + ", scope="
                + (isGlobal() ? "global" : "local(" + observer.key() + ")") + ", obs=" + observations.size()
                + ", tokens=" + tokenCount + "}";
    }

    /** Builder for {@link Representation}. */
    public static final class Builder {
        private PeerView subject;
        private PeerView observer;
        private String sessionId;
        private List<Observation> observations = List.of();
        private String summary = "";
        private Instant generatedAt = Instant.now();
        private int tokenCount;

        private Builder() {
        }

        public Builder subject(PeerView subject) {
            this.subject = Objects.requireNonNull(subject, "subject cannot be null");
            return this;
        }

        /** Sets the observer. Pass {@code null} to mark this representation as global. */
        public Builder observer(PeerView observer) {
            this.observer = observer;
            return this;
        }

        /** Sets the session id. May be {@code null} for cross-session representations. */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder observations(List<Observation> observations) {
            this.observations = Objects.requireNonNull(observations, "observations cannot be null");
            return this;
        }

        public Builder summary(String summary) {
            this.summary = Objects.requireNonNull(summary, "summary cannot be null");
            return this;
        }

        public Builder generatedAt(Instant generatedAt) {
            this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt cannot be null");
            return this;
        }

        public Builder tokenCount(int tokenCount) {
            this.tokenCount = tokenCount;
            return this;
        }

        public Representation build() {
            return new Representation(this);
        }
    }
}
