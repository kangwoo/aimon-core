package at.aimon.core.programmatic;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.llm.ToolDefinition;

/**
 * One program for an engine to run ({@code docs/design/tool/programmatic-tool-calling.md} §9).
 *
 * <p>
 * Immutable. Use {@link #builder()}.
 */
public final class CodeExecutionRequest {

    private final String toolUseId;
    private final String code;
    private final List<ToolDefinition> tools;
    private final CodeExecutionLimits limits;
    private final Duration wallClock;
    private final CancellationSignal cancellation;

    private CodeExecutionRequest(Builder builder) {
        this.toolUseId = Objects.requireNonNull(builder.toolUseId, "toolUseId cannot be null");
        this.code = Objects.requireNonNull(builder.code, "code cannot be null");
        this.tools = List.copyOf(Objects.requireNonNull(builder.tools, "tools cannot be null"));
        this.limits = Objects.requireNonNull(builder.limits, "limits cannot be null");
        this.wallClock = Objects.requireNonNull(builder.wallClock, "wallClock cannot be null");
        this.cancellation = Objects.requireNonNull(builder.cancellation, "cancellation cannot be null");
    }

    /**
     * Returns a new builder.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the id of the {@code ExecuteCode} call running this program.
     *
     * @return the tool-use id (never null)
     */
    public String getToolUseId() {
        return toolUseId;
    }

    /**
     * Returns the program's source.
     *
     * @return the code (never null)
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the definitions of the frozen callable set — the functions the engine exposes to the program.
     *
     * @return the definitions, sorted by name (never null)
     */
    public List<ToolDefinition> getTools() {
        return tools;
    }

    /**
     * Returns the limits.
     *
     * @return the limits (never null)
     */
    public CodeExecutionLimits getLimits() {
        return limits;
    }

    /**
     * Returns this program's wall clock — the limit, or the request's {@code timeout_seconds}, or the execution's
     * deadline, whichever is nearest.
     *
     * @return the wall clock (never null, positive)
     */
    public Duration getWallClock() {
        return wallClock;
    }

    /**
     * Returns the signal that stops the program: the bridge's child signal, tripped when the execution is cancelled or
     * the bridge stops the program for a limit ({@link ToolCallBridge#limitExceeded()}).
     *
     * @return the signal (never null)
     */
    public CancellationSignal getCancellation() {
        return cancellation;
    }

    @Override
    public String toString() {
        return "CodeExecutionRequest{toolUseId='" + toolUseId + "', codeLength=" + code.length() + ", tools="
                + tools.size() + ", wallClock=" + wallClock + '}';
    }

    /** Builder for {@link CodeExecutionRequest}. */
    public static final class Builder {

        private String toolUseId;
        private String code;
        private List<ToolDefinition> tools = List.of();
        private CodeExecutionLimits limits = CodeExecutionLimits.defaults();
        private Duration wallClock;
        private CancellationSignal cancellation;

        private Builder() {
        }

        /**
         * @param toolUseId
         *            the {@code ExecuteCode} call's id (must not be null)
         * @return this builder
         */
        public Builder toolUseId(String toolUseId) {
            this.toolUseId = toolUseId;
            return this;
        }

        /**
         * @param code
         *            the program (must not be null)
         * @return this builder
         */
        public Builder code(String code) {
            this.code = code;
            return this;
        }

        /**
         * @param tools
         *            the callable set's definitions (must not be null)
         * @return this builder
         */
        public Builder tools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        /**
         * @param limits
         *            the limits (must not be null); defaults to {@link CodeExecutionLimits#defaults()}
         * @return this builder
         */
        public Builder limits(CodeExecutionLimits limits) {
            this.limits = limits;
            return this;
        }

        /**
         * @param wallClock
         *            the program's wall clock (must not be null)
         * @return this builder
         */
        public Builder wallClock(Duration wallClock) {
            this.wallClock = wallClock;
            return this;
        }

        /**
         * @param cancellation
         *            the signal that stops the program (must not be null)
         * @return this builder
         */
        public Builder cancellation(CancellationSignal cancellation) {
            this.cancellation = cancellation;
            return this;
        }

        /**
         * @return the request
         * @throws NullPointerException
         *             if a required field is missing
         */
        public CodeExecutionRequest build() {
            return new CodeExecutionRequest(this);
        }
    }
}
