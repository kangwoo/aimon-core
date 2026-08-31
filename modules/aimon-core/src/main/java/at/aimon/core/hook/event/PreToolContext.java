package at.aimon.core.hook.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.llm.ToolUse;

/**
 * Context for PreToolHook.
 *
 * <p>
 * Provides information about the tools that is about to be executed.
 *
 * <p>
 * Immutable value object. Use builder to create instances.
 *
 * <p>
 * Hooks may inspect both the {@link #originalInput() original input} (the input the LLM produced) and the
 * {@link #currentInput() current input} (the input as mutated by previously executed PreTool hooks). When the executor
 * accumulates an updated input from one hook, it threads a new context whose {@code currentInput()} reflects the update
 * to subsequent hooks via {@link #withCurrentInput(ToolInput)}; {@link #originalInput()} is preserved.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     PreToolContext context = PreToolContext.builder().invokerType(InvokerType.MAIN_AGENT)
 *             .invokerName("default-agent").environment(environment).toolUse(toolUse).iterationCount(3).build();
 * }
 * </pre>
 */
public final class PreToolContext implements HookContext {
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
    private final ToolUse originalToolUse;
    private final ToolUse currentToolUse;
    private final int iterationCount;
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private PreToolContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Executor type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Executor name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        originalToolUse = Objects.requireNonNull(builder.toolUse, "Tool use cannot be null");
        currentToolUse = builder.currentToolUse != null ? builder.currentToolUse : originalToolUse;
        iterationCount = builder.iterationCount;
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
     * Gets the tool use produced by the LLM, before any PreTool hook mutated it.
     *
     * @return The original tool use (never null)
     */
    public ToolUse getOriginalToolUse() {
        return originalToolUse;
    }

    /**
     * Gets the tool use as it currently stands after accumulating any prior PreTool hooks' updated inputs.
     *
     * <p>
     * For the first hook in the chain this equals {@link #getOriginalToolUse()}.
     *
     * @return The current tool use (never null)
     */
    public ToolUse getCurrentToolUse() {
        return currentToolUse;
    }

    /**
     * Gets the original tool input (a {@link ToolInput} view of {@link #getOriginalToolUse()}'s input map).
     *
     * @return The original input (never null)
     */
    public ToolInput originalInput() {
        return ToolInput.of(originalToolUse.getInput());
    }

    /**
     * Gets the current tool input (a {@link ToolInput} view of {@link #getCurrentToolUse()}'s input map).
     *
     * @return The current input (never null)
     */
    public ToolInput currentInput() {
        return ToolInput.of(currentToolUse.getInput());
    }

    /**
     * Gets the current iteration count in the ReAct loop.
     *
     * @return The iteration count (1-based)
     */
    public int getIterationCount() {
        return iterationCount;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public Map<String, Object> getExecutionAttributes() {
        return executionAttributes;
    }

    /**
     * Returns a copy of this context with the given current input applied.
     *
     * <p>
     * The original tool use, registry, environment and timestamp are preserved. Used by the hook executor to thread an
     * updated input to subsequent PreTool hooks.
     *
     * @param newInput
     *            The replacement current input (must not be null)
     * @return A new context (never null)
     * @throws NullPointerException
     *             if newInput is null
     */
    public PreToolContext withCurrentInput(ToolInput newInput) {
        Objects.requireNonNull(newInput, "newInput cannot be null");
        final ToolUse rebuilt = ToolUse.of(originalToolUse.getId(), originalToolUse.getName(), newInput.toMap());
        final Builder b = new Builder().executorType(invokerType).invokerName(invokerName).hookRegistry(hookRegistry)
                .environment(environment).toolUse(originalToolUse).iterationCount(iterationCount).timestamp(timestamp)
                .executionAttributes(executionAttributes);
        b.currentToolUse = rebuilt;
        return new PreToolContext(b);
    }

    @Override
    public String toString() {
        return "PreToolContext{" + "invokerType=" + invokerType + ", invokerName='" + invokerName + '\''
                + ", toolName='" + currentToolUse.getName() + '\'' + ", iterationCount=" + iterationCount
                + ", timestamp=" + timestamp + ", executionAttributes=" + executionAttributes + '}';
    }

    /** Builder for PreToolContext. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private ToolUse toolUse;
        private ToolUse currentToolUse;
        private int iterationCount;
        private Instant timestamp = Instant.now();
        private Map<String, Object> executionAttributes;

        private Builder() {
        }

        /**
         * Sets the executor type.
         *
         * @param invokerType
         *            The executor type (must not be null)
         * @return This builder
         */
        public Builder executorType(InvokerType invokerType) {
            this.invokerType = invokerType;
            return this;
        }

        /**
         * Sets the invoker name.
         *
         * @param invokerName
         *            The invoker name (must not be null)
         * @return This builder
         */
        public Builder invokerName(String invokerName) {
            this.invokerName = invokerName;
            return this;
        }

        /**
         * Sets the hook registry.
         *
         * @param hookRegistry
         *            The hook registry (must not be null)
         * @return This builder
         */
        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        /**
         * Sets the environment.
         *
         * @param environment
         *            The environment (must not be null)
         * @return This builder
         */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the tools use.
         *
         * @param toolUse
         *            The tools use (must not be null)
         * @return This builder
         */
        public Builder toolUse(ToolUse toolUse) {
            this.toolUse = toolUse;
            return this;
        }

        /**
         * Sets the iteration count.
         *
         * @param iterationCount
         *            The iteration count (must be positive)
         * @return This builder
         */
        public Builder iterationCount(int iterationCount) {
            this.iterationCount = iterationCount;
            return this;
        }

        /**
         * Sets the timestamp.
         *
         * <p>
         * Defaults to {@code Instant.now()} if not set.
         *
         * @param timestamp
         *            The timestamp (must not be null)
         * @return This builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the execution attributes.
         *
         * <p>
         * <b>Note:</b> The map is stored using {@code Map.copyOf()}, which creates a shallow copy. Map values should be
         * effectively immutable types (e.g., {@code String}, {@code Integer}).
         *
         * @param executionAttributes
         *            The execution attributes (can be null)
         * @return This builder
         */
        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        /**
         * Builds the context.
         *
         * @return A new PreToolContext (never null)
         * @throws NullPointerException
         *             if any required field is null
         */
        public PreToolContext build() {
            return new PreToolContext(this);
        }
    }
}
