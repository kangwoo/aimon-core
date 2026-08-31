package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Signals that the executor is about to invoke a tool as part of the current iteration.
 *
 * <p>
 * <b>Use when:</b> a tool-use request extracted from the latest assistant message is about to be dispatched to the
 * corresponding {@link at.aimon.core.agent.tool.Tool}. Subscribers typically use this to render a "calling tool X"
 * indicator or to record the intent for later correlation with a {@link ToolResultReady} event via
 * {@link #getToolUseId()}.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getToolName()} — canonical name of the tool (non-null, non-empty)
 * <li>{@link #getToolUseId()} — provider-supplied identifier correlating this event with its matching
 * {@link ToolResultReady} (non-null)
 * <li>{@link #getInputSummary()} — unmodifiable, defensively copied map containing a short summary of the tool input;
 * may be empty but never null
 * </ul>
 *
 * <p>
 * Immutable value object. The input summary map is defensively copied at build time, so mutations to the source map
 * made after {@link Builder#build()} do not affect the event.
 */
public final class ToolUseStarted extends AgentExecutionEvent {

    private final String toolName;
    private final String toolUseId;
    private final Map<String, Object> inputSummary;

    private ToolUseStarted(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.toolName = Objects.requireNonNull(builder.toolName, "toolName cannot be null");
        if (this.toolName.isEmpty()) {
            throw new IllegalArgumentException("toolName cannot be empty");
        }
        this.toolUseId = Objects.requireNonNull(builder.toolUseId, "toolUseId cannot be null");
        final Map<String, Object> source = builder.inputSummary != null ? builder.inputSummary : Collections.emptyMap();
        this.inputSummary = Collections.unmodifiableMap(new HashMap<>(source));
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
     * Returns the canonical name of the tool being invoked.
     *
     * @return the tool name (never null, never empty)
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Returns the provider-supplied identifier correlating this event with its matching {@link ToolResultReady}.
     *
     * @return the tool-use identifier (never null)
     */
    public String getToolUseId() {
        return toolUseId;
    }

    /**
     * Returns an unmodifiable snapshot of the tool input summary.
     *
     * @return the input summary map (never null; may be empty; always unmodifiable)
     */
    public Map<String, Object> getInputSummary() {
        return inputSummary;
    }

    @Override
    protected String eventName() {
        return "ToolUseStarted";
    }

    @Override
    protected String detailString() {
        return "toolName='" + toolName + "', toolUseId='" + toolUseId + "', inputSummary=" + inputSummary;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ToolUseStarted that = (ToolUseStarted) o;
        return getIteration() == that.getIteration() && getTimestamp().equals(that.getTimestamp())
                && getAgentRuntimeId().equals(that.getAgentRuntimeId()) && toolName.equals(that.toolName)
                && toolUseId.equals(that.toolUseId) && inputSummary.equals(that.inputSummary);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), toolName, toolUseId, inputSummary);
    }

    /** Builder for {@link ToolUseStarted}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private String toolName;
        private String toolUseId;
        private Map<String, Object> inputSummary;

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
         * Sets the input summary. The source map is defensively copied by {@link #build()}, so later mutations to the
         * caller's map do not affect the built event.
         *
         * @param inputSummary
         *            the input summary (may be {@code null}, which is treated as empty)
         * @return this builder
         */
        public Builder inputSummary(Map<String, Object> inputSummary) {
            this.inputSummary = inputSummary;
            return this;
        }

        /**
         * Builds the {@link ToolUseStarted} event.
         *
         * @return a new {@link ToolUseStarted}
         * @throws NullPointerException
         *             if {@code timestamp}, {@code agentRuntimeId}, {@code toolName}, or {@code toolUseId} is
         *             null
         * @throws IllegalArgumentException
         *             if {@code toolName} is empty or {@code iteration} is negative
         */
        public ToolUseStarted build() {
            return new ToolUseStarted(this);
        }
    }
}
