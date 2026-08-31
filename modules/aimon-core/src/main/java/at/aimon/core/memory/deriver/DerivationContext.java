package at.aimon.core.memory.deriver;

import java.util.List;
import java.util.Objects;

import at.aimon.core.llm.Message;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Input passed to {@link Deriver#derive(DerivationContext)}.
 *
 * <p>
 * Carries everything a deriver implementation needs to process one batch of
 * messages without having to reach back to the queue manager. The
 * {@code sessionId} is a plain string — derivers process messages and persist
 * observations; they do not manipulate the live {@code LiveSession}.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class DerivationContext {

    private final Workspace workspace;
    private final String sessionId;
    private final PeerView observer;
    private final List<Message> messages;
    private final int tokenBudget;

    private DerivationContext(Builder builder) {
        this.workspace = Objects.requireNonNull(builder.workspace, "workspace cannot be null");
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId cannot be null");
        this.observer = Objects.requireNonNull(builder.observer, "observer cannot be null");
        this.messages = List.copyOf(Objects.requireNonNull(builder.messages, "messages cannot be null"));
        if (builder.tokenBudget < 1) {
            throw new IllegalArgumentException("tokenBudget must be >= 1, got " + builder.tokenBudget);
        }
        this.tokenBudget = builder.tokenBudget;
        if (!observer.getWorkspace().equals(workspace)) {
            throw new IllegalArgumentException("observer workspace must match context workspace");
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

    public int getTokenBudget() {
        return tokenBudget;
    }

    /** Builder for {@link DerivationContext}. */
    public static final class Builder {
        private Workspace workspace;
        private String sessionId;
        private PeerView observer;
        private List<Message> messages;
        private int tokenBudget = 8000;

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

        public Builder tokenBudget(int tokenBudget) {
            this.tokenBudget = tokenBudget;
            return this;
        }

        public DerivationContext build() {
            return new DerivationContext(this);
        }
    }
}
