package at.aimon.core.memory.dialectic;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Natural-language question routed to a {@link DialecticEngine}.
 *
 * <p>
 * The engine answers from observations stored about {@code subject}, optionally
 * scoped to a session. {@code observer} identifies the principal asking the
 * question (the agent or the user); {@code subject} identifies the peer the
 * question is about. For self-reflection both can point at the same peer.
 *
 * <p>
 * The {@code sessionId} is intentionally a plain {@link String} (not
 * {@code LiveSession}) so that this value object stays immutable and free of
 * runtime session references — same convention as {@link
 * at.aimon.core.memory.deriver.DerivationContext}.
 */
public final class DialecticQuery {

    private final Workspace workspace;
    private final PeerView subject;
    private final PeerView observer;
    private final String sessionId;
    private final String question;
    private final ReasoningLevel level;

    private DialecticQuery(Builder builder) {
        this.workspace = Objects.requireNonNull(builder.workspace, "workspace cannot be null");
        this.subject = Objects.requireNonNull(builder.subject, "subject cannot be null");
        this.observer = Objects.requireNonNull(builder.observer, "observer cannot be null");
        this.sessionId = builder.sessionId;
        this.question = Objects.requireNonNull(builder.question, "question cannot be null");
        if (this.question.isBlank()) {
            throw new IllegalArgumentException("question cannot be blank");
        }
        this.level = Objects.requireNonNull(builder.level, "level cannot be null");
        if (!subject.getWorkspace().equals(workspace)) {
            throw new IllegalArgumentException("subject workspace must match query workspace");
        }
        if (!observer.getWorkspace().equals(workspace)) {
            throw new IllegalArgumentException("observer workspace must match query workspace");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public PeerView getSubject() {
        return subject;
    }

    public PeerView getObserver() {
        return observer;
    }

    public Optional<String> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    public String getQuestion() {
        return question;
    }

    public ReasoningLevel getLevel() {
        return level;
    }

    @Override
    public String toString() {
        return "DialecticQuery{ws=" + workspace.getId() + ", subject=" + subject.key() + ", level=" + level
                + ", question='" + question + "'}";
    }

    /** Builder for {@link DialecticQuery}. */
    public static final class Builder {
        private Workspace workspace;
        private PeerView subject;
        private PeerView observer;
        private String sessionId;
        private String question;
        private ReasoningLevel level = ReasoningLevel.BALANCED;

        private Builder() {
        }

        public Builder workspace(Workspace workspace) {
            this.workspace = Objects.requireNonNull(workspace, "workspace cannot be null");
            return this;
        }

        public Builder subject(PeerView subject) {
            this.subject = Objects.requireNonNull(subject, "subject cannot be null");
            return this;
        }

        public Builder observer(PeerView observer) {
            this.observer = Objects.requireNonNull(observer, "observer cannot be null");
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder question(String question) {
            this.question = Objects.requireNonNull(question, "question cannot be null");
            return this;
        }

        public Builder level(ReasoningLevel level) {
            this.level = Objects.requireNonNull(level, "level cannot be null");
            return this;
        }

        public DialecticQuery build() {
            return new DialecticQuery(this);
        }
    }
}
