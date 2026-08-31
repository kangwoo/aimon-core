package at.aimon.core.memory.deriver;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import at.aimon.core.llm.Message;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Queue payload describing a unit of derivation work.
 *
 * <p>
 * Created by callers (typically an {@code ObserveTool} or a session listener)
 * and submitted to {@link DerivationQueueManager#enqueue(DerivationTask)}. The
 * queue manager applies redaction at enqueue time, so {@code messages} stored
 * here may already be the redacted form by the time a worker dequeues.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class DerivationTask {

    private final Workspace workspace;
    private final String sessionId;
    private final PeerView observer;
    private final List<Message> messages;
    private final Instant scheduledAt;

    private DerivationTask(Builder builder) {
        this.workspace = Objects.requireNonNull(builder.workspace, "workspace cannot be null");
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId cannot be null");
        if (this.sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be blank");
        }
        this.observer = Objects.requireNonNull(builder.observer, "observer cannot be null");
        this.messages = List.copyOf(Objects.requireNonNull(builder.messages, "messages cannot be null"));
        if (this.messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be empty");
        }
        this.scheduledAt = Objects.requireNonNull(builder.scheduledAt, "scheduledAt cannot be null");
        if (!observer.getWorkspace().equals(workspace)) {
            throw new IllegalArgumentException("observer workspace must match task workspace");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public String getSessionId() {
        return sessionId;
    }

    public PeerView getObserver() {
        return observer;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    /** Convenience: returns the {@link DerivationWorkUnit claim key} for this task. */
    public DerivationWorkUnit workUnit() {
        return DerivationWorkUnit.of(workspace, sessionId, observer);
    }

    /** Returns a copy with the messages list replaced (used by the redaction gate). */
    public DerivationTask withMessages(List<Message> redactedMessages) {
        return DerivationTask.builder().workspace(workspace).sessionId(sessionId).observer(observer)
                .messages(redactedMessages).scheduledAt(scheduledAt).build();
    }

    @Override
    public String toString() {
        return "DerivationTask{workspace=" + workspace.getId() + ", session=" + sessionId + ", observer="
                + observer.key() + ", messages=" + messages.size() + "}";
    }

    /** Builder for {@link DerivationTask}. */
    public static final class Builder {
        private Workspace workspace;
        private String sessionId;
        private PeerView observer;
        private List<Message> messages;
        private Instant scheduledAt = Instant.now();

        private Builder() {
        }

        public Builder workspace(Workspace workspace) {
            this.workspace = Objects.requireNonNull(workspace, "workspace cannot be null");
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null");
            return this;
        }

        public Builder observer(PeerView observer) {
            this.observer = Objects.requireNonNull(observer, "observer cannot be null");
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages = Objects.requireNonNull(messages, "messages cannot be null");
            return this;
        }

        public Builder scheduledAt(Instant scheduledAt) {
            this.scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt cannot be null");
            return this;
        }

        public DerivationTask build() {
            return new DerivationTask(this);
        }
    }
}
