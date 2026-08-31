package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Signals that a tool invocation has produced a result (success or error) for the current iteration.
 *
 * <p>
 * <b>Use when:</b> a tool dispatched via {@link ToolUseStarted} finishes. Subscribers typically use this to render the
 * tool outcome to the user or to terminate a "calling tool X" spinner. Correlate with the corresponding
 * {@link ToolUseStarted#getToolUseId()} via {@link #getToolUseId()}.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getToolName()} — canonical name of the tool
 * <li>{@link #getToolUseId()} — correlates with {@link ToolUseStarted#getToolUseId()}
 * <li>{@link #isSuccess()} — {@code true} when the tool returned a successful result
 * <li>{@link #getErrorMessage()} — present iff {@link #isSuccess()} is {@code false}; the builder enforces this
 * invariant
 * <li>{@link #getResultPreviewLength()} — optional preview length of the tool result content, useful for UIs that want
 * to render a truncation indicator
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class ToolResultReady extends AgentExecutionEvent {

    private final String toolName;
    private final String toolUseId;
    private final boolean success;
    private final Optional<String> errorMessage;
    private final Optional<Integer> resultPreviewLength;

    private ToolResultReady(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.toolName = Objects.requireNonNull(builder.toolName, "toolName cannot be null");
        if (this.toolName.isEmpty()) {
            throw new IllegalArgumentException("toolName cannot be empty");
        }
        this.toolUseId = Objects.requireNonNull(builder.toolUseId, "toolUseId cannot be null");
        this.success = builder.success;
        this.errorMessage = Objects.requireNonNull(builder.errorMessage, "errorMessage Optional cannot be null");
        this.resultPreviewLength = Objects.requireNonNull(builder.resultPreviewLength,
                "resultPreviewLength Optional cannot be null");
        validateSuccessErrorConsistency();
    }

    private void validateSuccessErrorConsistency() {
        if (success && errorMessage.isPresent()) {
            throw new IllegalArgumentException(
                    "errorMessage must be empty when success=true (was: " + errorMessage.get() + ")");
        }
        if (!success && errorMessage.isEmpty()) {
            throw new IllegalArgumentException("errorMessage is required when success=false");
        }
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
     * Returns the canonical name of the tool that produced the result.
     *
     * @return the tool name (never null, never empty)
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Returns the tool-use identifier correlating this event with its {@link ToolUseStarted}.
     *
     * @return the tool-use identifier (never null)
     */
    public String getToolUseId() {
        return toolUseId;
    }

    /**
     * Returns whether the tool invocation succeeded.
     *
     * @return {@code true} if the tool returned a successful result
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the error message produced by the tool.
     *
     * @return {@link Optional} error message; always present when {@link #isSuccess()} is {@code false}, always empty
     *         when {@link #isSuccess()} is {@code true} (never null)
     */
    public Optional<String> getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns the preview length of the tool result content.
     *
     * @return {@link Optional} preview length; empty when the producer did not provide it (never null)
     */
    public Optional<Integer> getResultPreviewLength() {
        return resultPreviewLength;
    }

    @Override
    protected String eventName() {
        return "ToolResultReady";
    }

    @Override
    protected String detailString() {
        return "toolName='" + toolName + "', toolUseId='" + toolUseId + "', success=" + success + ", errorMessage="
                + errorMessage + ", resultPreviewLength=" + resultPreviewLength;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ToolResultReady that = (ToolResultReady) o;
        return success == that.success && getIteration() == that.getIteration()
                && getTimestamp().equals(that.getTimestamp()) && getAgentRuntimeId().equals(that.getAgentRuntimeId())
                && toolName.equals(that.toolName) && toolUseId.equals(that.toolUseId)
                && errorMessage.equals(that.errorMessage) && resultPreviewLength.equals(that.resultPreviewLength);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), toolName, toolUseId, success,
                errorMessage, resultPreviewLength);
    }

    /** Builder for {@link ToolResultReady}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private String toolName;
        private String toolUseId;
        private boolean success;
        private Optional<String> errorMessage = Optional.empty();
        private Optional<Integer> resultPreviewLength = Optional.empty();

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
         * Sets the tool name.
         *
         * @param toolName
         *            the canonical tool name (must not be null or empty)
         * @return this builder
         */
        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        /**
         * Sets the tool-use identifier.
         *
         * @param toolUseId
         *            the tool-use identifier (must not be null)
         * @return this builder
         */
        public Builder toolUseId(String toolUseId) {
            this.toolUseId = toolUseId;
            return this;
        }

        /**
         * Sets whether the tool invocation succeeded.
         *
         * @param success
         *            {@code true} for a successful tool result
         * @return this builder
         */
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        /**
         * Sets the error message. Setting a non-null, non-empty error message implies {@link #isSuccess()} must be
         * {@code false}; otherwise {@link #build()} will fail. Passing {@code null} or an empty string clears the
         * error.
         *
         * @param errorMessage
         *            the error message, or {@code null}/empty to clear
         * @return this builder
         */
        public Builder errorMessage(String errorMessage) {
            if (errorMessage == null || errorMessage.isEmpty()) {
                this.errorMessage = Optional.empty();
            } else {
                this.errorMessage = Optional.of(errorMessage);
            }
            return this;
        }

        /**
         * Sets the result preview length.
         *
         * @param resultPreviewLength
         *            the preview length, or {@code null} to clear
         * @return this builder
         */
        public Builder resultPreviewLength(Integer resultPreviewLength) {
            this.resultPreviewLength = Optional.ofNullable(resultPreviewLength);
            return this;
        }

        /**
         * Builds the {@link ToolResultReady} event.
         *
         * @return a new {@link ToolResultReady}
         * @throws NullPointerException
         *             if {@code timestamp}, {@code agentRuntimeId}, {@code toolName}, or {@code toolUseId} is
         *             null
         * @throws IllegalArgumentException
         *             if {@code toolName} is empty, {@code iteration} is negative, or the
         *             {@code success}/{@code errorMessage} invariant is violated
         */
        public ToolResultReady build() {
            return new ToolResultReady(this);
        }
    }
}
