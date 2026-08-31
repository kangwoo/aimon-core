package at.aimon.core.hook.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;

/**
 * Context for {@link SubagentStopHook}.
 *
 * <p>
 * Fired after a subagent dispatched via the Task tool finishes execution (success or failure). Hooks may use it for
 * audit/logging and metrics — they must not block.
 *
 * <p>
 * Immutable value object. Use {@link #builder()} to create instances.
 */
public final class SubagentStopContext implements HookContext {

    /**
     * Creates a new builder.
     *
     * @return A new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    private final InvokerType invokerType;
    private final String invokerName;
    private final HookRegistry hookRegistry;
    private final Environment environment;
    private final String subagentName;
    private final String taskId;
    private final boolean success;
    private final String errorMessage;
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private SubagentStopContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Invoker type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Invoker name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        subagentName = Objects.requireNonNull(builder.subagentName, "Subagent name cannot be null");
        taskId = Objects.requireNonNull(builder.taskId, "Task id cannot be null");
        success = builder.success;
        errorMessage = builder.errorMessage;
        timestamp = Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null");
        executionAttributes = builder.executionAttributes != null ? Map.copyOf(builder.executionAttributes) : Map.of();
    }

    @Override
    public InvokerType getInvokerType() {
        return invokerType;
    }

    @Override
    public String getInvokerName() {
        return invokerName;
    }

    @Override
    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    @Override
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * Gets the name of the subagent that finished.
     *
     * @return the subagent name (never null)
     */
    public String getSubagentName() {
        return subagentName;
    }

    /**
     * Gets the task id assigned to this subagent dispatch.
     *
     * @return the task id (never null)
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Whether the subagent execution finished successfully.
     *
     * @return true on success, false on failure
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Gets the error message when the subagent failed.
     *
     * @return optional error message; empty when {@link #isSuccess()} is true or no message was provided
     */
    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public Map<String, Object> getExecutionAttributes() {
        return executionAttributes;
    }

    @Override
    public String toString() {
        return "SubagentStopContext{" + "invokerType=" + invokerType + ", invokerName='" + invokerName + '\''
                + ", subagentName='" + subagentName + '\'' + ", taskId='" + taskId + '\'' + ", success=" + success
                + ", timestamp=" + timestamp + '}';
    }

    /** Builder for {@link SubagentStopContext}. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private String subagentName;
        private String taskId;
        private boolean success;
        private String errorMessage;
        private Instant timestamp = Instant.now();
        private Map<String, Object> executionAttributes;

        private Builder() {
        }

        /**
         * Sets the invoker type.
         *
         * @param invokerType
         *            the invoker type (must not be null)
         * @return this builder
         */
        public Builder invokerType(InvokerType invokerType) {
            this.invokerType = invokerType;
            return this;
        }

        /**
         * Sets the invoker name.
         *
         * @param invokerName
         *            the invoker name (must not be null)
         * @return this builder
         */
        public Builder invokerName(String invokerName) {
            this.invokerName = invokerName;
            return this;
        }

        /**
         * Sets the hook registry.
         *
         * @param hookRegistry
         *            the hook registry (must not be null)
         * @return this builder
         */
        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        /**
         * Sets the environment.
         *
         * @param environment
         *            the environment (must not be null)
         * @return this builder
         */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the subagent name.
         *
         * @param subagentName
         *            the subagent name (must not be null)
         * @return this builder
         */
        public Builder subagentName(String subagentName) {
            this.subagentName = subagentName;
            return this;
        }

        /**
         * Sets the task id.
         *
         * @param taskId
         *            the task id (must not be null)
         * @return this builder
         */
        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        /**
         * Sets the success flag.
         *
         * @param success
         *            true when the subagent finished successfully
         * @return this builder
         */
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        /**
         * Sets the error message (only meaningful when {@code success} is false).
         *
         * @param errorMessage
         *            the error message (may be null)
         * @return this builder
         */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /**
         * Sets the timestamp; defaults to {@link Instant#now()} when not set.
         *
         * @param timestamp
         *            the timestamp (must not be null)
         * @return this builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the execution attributes (stored via {@link Map#copyOf(Map)}).
         *
         * @param executionAttributes
         *            the attributes (may be null)
         * @return this builder
         */
        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        /**
         * Builds the context.
         *
         * @return a new {@link SubagentStopContext} (never null)
         */
        public SubagentStopContext build() {
            return new SubagentStopContext(this);
        }
    }
}
