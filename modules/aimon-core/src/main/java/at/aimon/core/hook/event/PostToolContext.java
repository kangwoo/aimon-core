package at.aimon.core.hook.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Context for PostToolHook.
 *
 * <p>
 * Provides information about the tools execution result.
 *
 * <p>
 * Immutable value object. Use builder to create instances.
 *
 * <p>
 * Hooks may inspect both the {@link #originalOutput() original output} (the output the dispatcher produced) and the
 * {@link #currentOutput() current output} (the output as mutated by previously executed PostTool hooks). When the
 * executor accumulates an updated output from one hook, it threads a new context whose {@code currentOutput()} reflects
 * the update to subsequent hooks via {@link #withCurrentOutput(ToolResult)}; {@link #originalOutput()} is preserved.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     PostToolContext context = PostToolContext.builder().invokerType(InvokerType.MAIN_AGENT)
 *             .invokerName("default-agent").environment(environment).toolUse(toolUse).toolResult(toolResult)
 *             .iterationCount(3).build();
 * }
 * </pre>
 */
public final class PostToolContext implements HookContext {
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
    private final ToolUse toolUse;
    private final ToolUseResult originalToolUseResult;
    private final ToolUseResult currentToolUseResult;
    private final int iterationCount;
    private final Instant timestamp;
    private final Map<String, Object> executionAttributes;

    private PostToolContext(Builder builder) {
        invokerType = Objects.requireNonNull(builder.invokerType, "Executor type cannot be null");
        invokerName = Objects.requireNonNull(builder.invokerName, "Executor name cannot be null");
        hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        toolUse = Objects.requireNonNull(builder.toolUse, "Tool use cannot be null");
        originalToolUseResult = Objects.requireNonNull(builder.toolUseResult, "Tool use result cannot be null");
        currentToolUseResult = builder.currentToolUseResult != null
                ? builder.currentToolUseResult
                : originalToolUseResult;
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
     * Gets the tools use that was executed.
     *
     * @return The tools use (never null)
     */
    public ToolUse getToolUse() {
        return toolUse;
    }

    /**
     * Gets the original tool use result produced by the dispatcher, before any PostTool hook mutated it.
     *
     * @return The original tool use result (never null)
     */
    public ToolUseResult getOriginalToolUseResult() {
        return originalToolUseResult;
    }

    /**
     * Gets the tool use result as it currently stands after accumulating any prior PostTool hooks' updated outputs.
     *
     * <p>
     * For the first hook in the chain this equals {@link #getOriginalToolUseResult()}.
     *
     * @return The current tool use result (never null)
     */
    public ToolUseResult getCurrentToolUseResult() {
        return currentToolUseResult;
    }

    /**
     * Gets the original tool output as a {@link ToolResult} view of {@link #getOriginalToolUseResult()}.
     *
     * @return The original output (never null)
     */
    public ToolResult originalOutput() {
        return toToolResult(originalToolUseResult);
    }

    /**
     * Gets the current tool output as a {@link ToolResult} view of {@link #getCurrentToolUseResult()}.
     *
     * @return The current output (never null)
     */
    public ToolResult currentOutput() {
        return toToolResult(currentToolUseResult);
    }

    private static ToolResult toToolResult(ToolUseResult tur) {
        return tur.isError() ? ToolResult.error(tur.getContent()) : ToolResult.success(tur.getContent());
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
     * Returns a copy of this context with the given current output applied.
     *
     * <p>
     * The original tool use result, registry, environment and timestamp are preserved. Used by the hook executor to
     * thread an updated output to subsequent PostTool hooks.
     *
     * @param newOutput
     *            The replacement current output (must not be null)
     * @return A new context (never null)
     * @throws NullPointerException
     *             if newOutput is null
     */
    public PostToolContext withCurrentOutput(ToolResult newOutput) {
        Objects.requireNonNull(newOutput, "newOutput cannot be null");
        final ToolUseResult rebuilt = newOutput.isError()
                ? ToolUseResult.error(toolUse.getId(), newOutput.getContent())
                : ToolUseResult.success(toolUse.getId(), newOutput.getContent());
        final Builder b = new Builder().executorType(invokerType).invokerName(invokerName).hookRegistry(hookRegistry)
                .environment(environment).toolUse(toolUse).toolUseResult(originalToolUseResult)
                .iterationCount(iterationCount).timestamp(timestamp).executionAttributes(executionAttributes);
        b.currentToolUseResult = rebuilt;
        return new PostToolContext(b);
    }

    @Override
    public String toString() {
        return "PostToolContext{" + "invokerType=" + invokerType + ", invokerName='" + invokerName + '\''
                + ", toolName='" + toolUse.getName() + '\'' + ", success=" + !currentToolUseResult.isError()
                + ", iterationCount=" + iterationCount + ", timestamp=" + timestamp + ", executionAttributes="
                + executionAttributes + '}';
    }

    /** Builder for PostToolContext. */
    public static final class Builder {
        private InvokerType invokerType;
        private String invokerName;
        private HookRegistry hookRegistry;
        private Environment environment;
        private ToolUse toolUse;
        private ToolUseResult toolUseResult;
        private ToolUseResult currentToolUseResult;
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
         * Sets the tools result.
         *
         * @param toolUseResult
         *            The tool use result (must not be null)
         * @return This builder
         */
        public Builder toolUseResult(ToolUseResult toolUseResult) {
            this.toolUseResult = toolUseResult;
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
         * @return A new PostToolContext (never null)
         * @throws NullPointerException
         *             if any required field is null
         */
        public PostToolContext build() {
            return new PostToolContext(this);
        }
    }
}
