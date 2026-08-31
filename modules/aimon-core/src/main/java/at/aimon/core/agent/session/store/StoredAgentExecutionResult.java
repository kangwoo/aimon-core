package at.aimon.core.agent.session.store;

import java.util.Objects;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.signal.SessionSignal;

/**
 * Minimal, wire-safe {@link AgentExecutionResult} projection: the fields that survive a JSON round-trip and nothing
 * else.
 *
 * <p>
 * Two paths need a result to cross a process boundary and both reconstruct it here:
 *
 * <ul>
 * <li><b>Idempotency replay</b> — every {@link IdempotencyStore} backend caches {@link IdempotencyEntry.Status#DONE}
 * results and rebuilds them on replay. The three backends each carried a byte-identical private copy of this class;
 * they
 * now share this one.
 * <li><b>Forwarded turns</b> — the holder publishes the terminal result of a forwarded turn on the
 * {@link SessionSignal.SignalKind#TURN_RESULT} rail, and the origin node rebuilds it to complete the future it
 * handed its caller (design §7.1 F6/F7).
 * </ul>
 *
 * <p>
 * <b>Artifacts are dropped.</b> {@link AgentExecutionResult#getArtifacts()} holds arbitrary typed values with no wire
 * encoding anywhere in this tree, so a reconstructed result always reports no artifacts. This was already true of
 * idempotency replay; design §9 question 4 asked whether the forwarded-turn path should be different, and the answer is
 * no — the contract is uniform: <b>a result that crossed a node boundary carries no artifacts</b>. Callers that need
 * them must observe the turn on the node that ran it.
 */
public final class StoredAgentExecutionResult implements AgentExecutionResult {

    private final boolean success;
    private final String finalAnswer;
    private final String errorMessage;
    private final CompletionReason completionReason;
    private final boolean wasStreamed;

    private StoredAgentExecutionResult(Builder b) {
        this.success = b.success;
        this.finalAnswer = b.finalAnswer;
        this.errorMessage = b.errorMessage;
        this.completionReason = Objects.requireNonNull(b.completionReason, "completionReason must not be null");
        this.wasStreamed = b.wasStreamed;
        if (success && finalAnswer == null) {
            throw new IllegalStateException("Successful result must have non-null finalAnswer");
        }
        if (!success && errorMessage == null) {
            throw new IllegalStateException("Failed result must have non-null errorMessage");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Projects {@code source} onto the wire-safe fields, dropping artifacts.
     *
     * @param source
     *            the result to project (must not be null)
     * @return the projection (never null)
     */
    public static StoredAgentExecutionResult from(AgentExecutionResult source) {
        Objects.requireNonNull(source, "source must not be null");
        return builder().success(source.isSuccess()).finalAnswer(source.getFinalAnswer())
                .errorMessage(source.getErrorMessage()).completionReason(source.getCompletionReason())
                .wasStreamed(source.wasStreamed()).build();
    }

    @Override
    public boolean isSuccess() {
        return success;
    }

    @Override
    public String getFinalAnswer() {
        return finalAnswer;
    }

    @Override
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public CompletionReason getCompletionReason() {
        return completionReason;
    }

    @Override
    public boolean wasStreamed() {
        return wasStreamed;
    }

    /** Builder for {@link StoredAgentExecutionResult}. */
    public static final class Builder {
        private boolean success;
        private String finalAnswer;
        private String errorMessage;
        private CompletionReason completionReason = CompletionReason.COMPLETED;
        private boolean wasStreamed;

        private Builder() {
        }

        public Builder success(boolean v) {
            this.success = v;
            return this;
        }

        public Builder finalAnswer(String v) {
            this.finalAnswer = v;
            return this;
        }

        public Builder errorMessage(String v) {
            this.errorMessage = v;
            return this;
        }

        public Builder completionReason(CompletionReason v) {
            this.completionReason = v;
            return this;
        }

        public Builder wasStreamed(boolean v) {
            this.wasStreamed = v;
            return this;
        }

        public StoredAgentExecutionResult build() {
            return new StoredAgentExecutionResult(this);
        }
    }
}
