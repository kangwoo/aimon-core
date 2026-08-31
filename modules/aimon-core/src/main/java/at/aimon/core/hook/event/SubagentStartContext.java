package at.aimon.core.hook.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;

/**
 * Context for {@link SubagentStartHook}.
 *
 * <p>
 * Fired immediately before a subagent dispatched via the Task tool begins execution. Hooks may use it for
 * audit/logging, metrics, or seeding correlation ids — they must not block.
 *
 * <p>
 * Immutable value object. Use {@link #builder()} to create instances.
 */
public final class SubagentStartContext implements HookContext {

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
    private final String goal;
    private final String description;
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private SubagentStartContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Invoker type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Invoker name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        subagentName = Objects.requireNonNull(builder.subagentName, "Subagent name cannot be null");
        taskId = Objects.requireNonNull(builder.taskId, "Task id cannot be null");
        goal = Objects.requireNonNull(builder.goal, "Goal cannot be null");
        description = builder.description != null ? builder.description : "";
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
     * Gets the name of the subagent being launched.
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
     * Gets the goal/prompt the subagent will pursue.
     *
     * @return the goal (never null)
     */
    public String getGoal() {
        return goal;
    }

    /**
     * Gets the optional human-readable description for the dispatch.
     *
     * @return the description (never null; empty string when not provided)
     */
    public String getDescription() {
        return description;
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
        return "SubagentStartContext{" + "invokerType=" + invokerType + ", invokerName='" + invokerName + '\''
                + ", subagentName='" + subagentName + '\'' + ", taskId='" + taskId + '\'' + ", timestamp=" + timestamp
                + '}';
    }

    /** Builder for {@link SubagentStartContext}. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private String subagentName;
        private String taskId;
        private String goal;
        private String description;
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
         * Sets the goal.
         *
         * @param goal
         *            the goal (must not be null)
         * @return this builder
         */
        public Builder goal(String goal) {
            this.goal = goal;
            return this;
        }

        /**
         * Sets the description (optional; defaults to empty string).
         *
         * @param description
         *            the description (may be null)
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
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
         * @return a new {@link SubagentStartContext} (never null)
         */
        public SubagentStartContext build() {
            return new SubagentStartContext(this);
        }
    }
}
