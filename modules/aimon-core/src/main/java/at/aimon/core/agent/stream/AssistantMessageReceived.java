package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.llm.TokenUsage;

/**
 * Signals that an assistant message was received from the LLM for the current iteration.
 *
 * <p>
 * <b>Use when:</b> the executor has just received an assistant response that <b>includes tool-use requests</b>
 * (i.e., a non-terminal iteration). Subscribers typically use this to render the agent's intermediate reasoning
 * between tool calls, update UI progress, or accumulate token metrics. The terminal assistant response — the one
 * that carries the final answer with no tool uses — is <i>not</i> emitted as this event; it is delivered through
 * the {@link at.aimon.core.agent.AgentExecutionResult} returned by the executor (and the
 * {@link ExecutionCompleted} boundary event), so renderers do not double-print the final answer.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getMessageSummary()} — a short textual preview of the assistant message (maximum 200 characters; longer
 * strings are truncated by the builder with a trailing ellipsis "…")
 * <li>{@link #getTokenUsage()} — token-usage breakdown for the LLM call, when the provider reported it; never-null
 * {@link Optional}
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class AssistantMessageReceived extends AgentExecutionEvent {

    /** Maximum length of {@link #getMessageSummary()} before the builder truncates with an ellipsis. */
    public static final int MAX_SUMMARY_LENGTH = 200;

    private static final String ELLIPSIS = "…";

    private final String messageSummary;
    private final Optional<TokenUsage> tokenUsage;

    private AssistantMessageReceived(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.messageSummary = Objects.requireNonNull(builder.messageSummary, "messageSummary cannot be null");
        this.tokenUsage = Objects.requireNonNull(builder.tokenUsage, "tokenUsage Optional cannot be null");
    }

    /**
     * Creates a new builder.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the short textual preview of the assistant message.
     *
     * @return the message summary (never null; length {@code <= 200})
     */
    public String getMessageSummary() {
        return messageSummary;
    }

    /**
     * Returns the token-usage breakdown for the LLM call.
     *
     * @return {@link Optional} of {@link TokenUsage}; empty when the provider did not report usage (never null)
     */
    public Optional<TokenUsage> getTokenUsage() {
        return tokenUsage;
    }

    @Override
    protected String eventName() {
        return "AssistantMessageReceived";
    }

    @Override
    protected String detailString() {
        return "messageSummary='" + messageSummary + "', tokenUsage=" + tokenUsage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AssistantMessageReceived that = (AssistantMessageReceived) o;
        return getIteration() == that.getIteration() && getTimestamp().equals(that.getTimestamp())
                && getAgentRuntimeId().equals(that.getAgentRuntimeId()) && messageSummary.equals(that.messageSummary)
                && tokenUsage.equals(that.tokenUsage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), messageSummary, tokenUsage);
    }

    /** Builder for {@link AssistantMessageReceived}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private String messageSummary;
        private Optional<TokenUsage> tokenUsage = Optional.empty();

        private Builder() {
        }

        /**
         * Sets the event timestamp.
         *
         * @param timestamp
         *            the wall-clock instant (must not be null)
         * @return this builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the agent runtime identifier.
         *
         * @param agentRuntimeId
         *            the agent runtime identifier (must not be null)
         * @return this builder
         */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * Sets the 1-based iteration number.
         *
         * @param iteration
         *            the iteration number (must be {@code >= 0})
         * @return this builder
         */
        public Builder iteration(int iteration) {
            this.iteration = iteration;
            return this;
        }

        /**
         * Sets the message summary. Strings longer than {@value #MAX_SUMMARY_LENGTH} characters are truncated to
         * exactly {@value #MAX_SUMMARY_LENGTH} characters with a trailing ellipsis ("…").
         *
         * @param messageSummary
         *            the message summary (must not be null)
         * @return this builder
         */
        public Builder messageSummary(String messageSummary) {
            Objects.requireNonNull(messageSummary, "messageSummary cannot be null");
            if (messageSummary.length() > MAX_SUMMARY_LENGTH) {
                // Reserve one character slot for the ellipsis marker so the total length stays within the cap.
                this.messageSummary = messageSummary.substring(0, MAX_SUMMARY_LENGTH - 1) + ELLIPSIS;
            } else {
                this.messageSummary = messageSummary;
            }
            return this;
        }

        /**
         * Sets the token usage (nullable — null becomes {@link Optional#empty()}).
         *
         * @param tokenUsage
         *            the token usage, or {@code null} to clear
         * @return this builder
         */
        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = Optional.ofNullable(tokenUsage);
            return this;
        }

        /**
         * Builds the {@link AssistantMessageReceived} event.
         *
         * @return a new {@link AssistantMessageReceived}
         * @throws NullPointerException
         *             if {@code timestamp}, {@code agentRuntimeId}, or {@code messageSummary} is null
         * @throws IllegalArgumentException
         *             if {@code iteration} is negative
         */
        public AssistantMessageReceived build() {
            return new AssistantMessageReceived(this);
        }
    }
}
