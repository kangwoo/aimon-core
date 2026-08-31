package at.aimon.core.subagent.execution;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.Subagent;

/**
 * Encapsulates the runtime configuration for subagent execution.
 *
 * <p>
 * This class separates execution context (how to execute) from execution request (what to execute), following the same
 * pattern as AgentRuntime.
 *
 * <p>
 * Contains:
 *
 * <ul>
 * <li>Subagent configuration and definition
 * <li>Tool handler for managing tool execution
 * <li>Hook handler for managing execution hooks
 * <li>Runtime environment information
 * <li>Default model configuration
 * </ul>
 *
 * <p>
 * The context delegates tool management to the ToolHandler, which provides access to the tool registry. This ensures
 * that tool definitions and their execution logic stay synchronized, particularly important for tools with dynamic
 * definitions.
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SubagentExecutionContext context = SubagentExecutionContext.builder().subagent(codeReviewer)
 *             .toolHandler(toolHandler).hookHandler(hookHandler).environment(Environment.createDefault()).build();
 *
 *     SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-001")
 *             .goal("Review authentication module").build();
 *
 *     SubagentExecutionResult result = executor.execute(context, request);
 * }
 * </pre>
 */
public final class SubagentExecutionContext {

    /** SubagentExecutionContext Builder를 반환한다. */
    public static Builder builder() {
        return new Builder();
    }

    private final AgentRuntimeId agentRuntimeId;
    private final Subagent subagent;
    private final LlmModel defaultModel;
    private final String modelOverride;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;
    private final Environment environment;
    private final CancellationSignal parentCancellationSignal;
    private final KnowledgeStore knowledgeStore;
    private final KnowledgeScope knowledgeScope;
    private final List<ToolContextEnricher> toolContextEnrichers;
    private final SubagentOutputSink outputSink;

    private SubagentExecutionContext(Builder builder) {
        this.agentRuntimeId = Objects.requireNonNull(builder.agentRuntimeId, "Agent runtime ID cannot be null");
        this.subagent = Objects.requireNonNull(builder.subagent, "Subagent cannot be null");
        this.defaultModel = Objects.requireNonNull(builder.defaultModel, "Default model config cannot be null");
        this.modelOverride = builder.modelOverride;
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "Tool registry cannot be null");
        this.hookRegistry = Objects.requireNonNull(builder.hookRegistry, "Hook registry cannot be null");
        this.environment = Objects.requireNonNull(builder.environment, "Environment cannot be null");
        this.parentCancellationSignal = builder.parentCancellationSignal != null
                ? builder.parentCancellationSignal
                : NoopCancellationSignal.INSTANCE;
        this.knowledgeStore = builder.knowledgeStore;
        this.knowledgeScope = builder.knowledgeScope;
        this.toolContextEnrichers = builder.toolContextEnrichers != null
                ? List.copyOf(builder.toolContextEnrichers)
                : List.of();
        this.outputSink = builder.outputSink != null ? builder.outputSink : SubagentOutputSink.NO_OP;
    }

    /**
     * Gets the agent runtime ID.
     *
     * @return The agent runtime ID (never null)
     */
    public AgentRuntimeId getAgentRuntimeId() {
        return agentRuntimeId;
    }

    /**
     * Gets the subagent.
     *
     * @return The subagent (never null)
     */
    public Subagent getSubagent() {
        return subagent;
    }

    /**
     * Gets the default model configuration.
     *
     * @return The default model config (never null)
     */
    public LlmModel getDefaultModel() {
        return defaultModel;
    }

    /**
     * Gets the per-invocation model override, if the caller requested one (e.g. the {@code Task} tool's {@code model}
     * argument).
     *
     * <p>
     * When present and non-blank it takes priority over the subagent's own {@code model} frontmatter and the
     * {@link #getDefaultModel() default model} — see
     * {@link SubagentLlmDefaults#resolveModel(at.aimon.core.subagent.Subagent, LlmModel, String)}.
     *
     * @return an {@link Optional} holding the override alias, or empty when none was supplied
     */
    public Optional<String> getModelOverride() {
        return Optional.ofNullable(modelOverride);
    }

    /**
     * Gets the tool registry.
     *
     * @return The tool registry (never null)
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Gets the hook registry.
     *
     * @return The hook registry (never null)
     */
    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    /**
     * Gets the runtime environment.
     *
     * @return The environment (never null)
     */
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * Gets the parent execution's cancellation signal.
     *
     * <p>
     * When a parent agent (or session) trips this signal, the subagent executor cascades the cancellation into its own
     * per-execution {@link at.aimon.core.agent.interrupt.InterruptCoordinator}, stopping the subagent's ReAct loop at
     * the next iteration boundary and letting cooperative subagent tools observe the cancellation. Defaults to
     * {@link NoopCancellationSignal#INSTANCE} (never cancelled) when no parent signal is wired.
     *
     * @return The parent cancellation signal (never null)
     */
    public CancellationSignal getParentCancellationSignal() {
        return parentCancellationSignal;
    }

    /**
     * Gets the knowledge store forwarded from the parent execution, if configured.
     *
     * <p>
     * Injected into the subagent tool context (alongside {@link #getKnowledgeScope()}) so subagent tools can search the
     * same knowledge base as the main-agent tools.
     *
     * @return an {@link Optional} holding the knowledge store, or empty if none was forwarded
     */
    public Optional<KnowledgeStore> getKnowledgeStore() {
        return Optional.ofNullable(knowledgeStore);
    }

    /**
     * Gets the knowledge scope forwarded from the parent execution, if configured.
     *
     * @return an {@link Optional} holding the knowledge scope, or empty if none was forwarded
     */
    public Optional<KnowledgeScope> getKnowledgeScope() {
        return Optional.ofNullable(knowledgeScope);
    }

    /**
     * Gets the tool-context enrichers forwarded from the parent agent runtime. The subagent executor invokes them
     * once per tool call, mirroring the main-agent executor, so module-supplied context keys are also present for
     * subagent tools.
     *
     * @return an immutable list of enrichers (never null, may be empty)
     */
    public List<ToolContextEnricher> getToolContextEnrichers() {
        return toolContextEnrichers;
    }

    /**
     * Gets the live output sink the executor streams progress to.
     *
     * <p>
     * For a background task this is bound to a {@code TaskOutputStore} so the {@code AgentOutput} tool can tail the
     * progress incrementally; for foreground execution it is {@link SubagentOutputSink#NO_OP}.
     *
     * @return the output sink (never null; {@link SubagentOutputSink#NO_OP} when none was supplied)
     */
    public SubagentOutputSink getOutputSink() {
        return outputSink;
    }

    /**
     * Gets the available tools from the tool handler's registry.
     *
     * @return An immutable list of available tools (never null)
     */
    public List<Tool> getAvailableTools() {
        return toolRegistry.findAll();
    }

    public static final class Builder {
        private AgentRuntimeId agentRuntimeId;
        private Subagent subagent;
        private LlmModel defaultModel;
        private String modelOverride;
        private ToolRegistry toolRegistry;
        private HookRegistry hookRegistry;
        private Environment environment;
        private CancellationSignal parentCancellationSignal;
        private KnowledgeStore knowledgeStore;
        private KnowledgeScope knowledgeScope;
        private List<ToolContextEnricher> toolContextEnrichers;
        private SubagentOutputSink outputSink;

        /** agentRuntimeId를 설정한다. */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /** subagent를 설정한다. */
        public Builder subagent(Subagent subagent) {
            this.subagent = subagent;
            return this;
        }

        /** defaultModel을 설정한다. */
        public Builder defaultModel(LlmModel defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        /**
         * Sets the per-invocation model override alias. When non-null and non-blank it takes priority over the
         * subagent's frontmatter model and the default model.
         *
         * @param modelOverride
         *            the override alias (nullable; ignored when null/blank)
         * @return this builder
         */
        public Builder modelOverride(String modelOverride) {
            this.modelOverride = modelOverride;
            return this;
        }

        /** toolRegistry를 설정한다. */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /** hookRegistry를 설정한다. */
        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        /** environment를 설정한다. */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the parent execution's cancellation signal so parent-initiated cancellation cascades into the subagent.
         *
         * @param parentCancellationSignal
         *            the parent signal (nullable, defaults to {@link NoopCancellationSignal#INSTANCE})
         * @return this builder
         */
        public Builder parentCancellationSignal(CancellationSignal parentCancellationSignal) {
            this.parentCancellationSignal = parentCancellationSignal;
            return this;
        }

        /**
         * Sets the knowledge store forwarded from the parent execution.
         *
         * @param knowledgeStore
         *            the knowledge store (nullable; subagent tools simply see no knowledge store when absent)
         * @return this builder
         */
        public Builder knowledgeStore(KnowledgeStore knowledgeStore) {
            this.knowledgeStore = knowledgeStore;
            return this;
        }

        /**
         * Sets the knowledge scope forwarded from the parent execution.
         *
         * @param knowledgeScope
         *            the knowledge scope (nullable)
         * @return this builder
         */
        public Builder knowledgeScope(KnowledgeScope knowledgeScope) {
            this.knowledgeScope = knowledgeScope;
            return this;
        }

        /**
         * Sets the tool-context enrichers forwarded from the parent agent runtime.
         *
         * @param toolContextEnrichers
         *            the enrichers (nullable; treated as an empty list when absent)
         * @return this builder
         */
        public Builder toolContextEnrichers(List<ToolContextEnricher> toolContextEnrichers) {
            this.toolContextEnrichers = toolContextEnrichers;
            return this;
        }

        /**
         * Sets the live output sink the executor streams progress to.
         *
         * @param outputSink
         *            the sink (nullable; defaults to {@link SubagentOutputSink#NO_OP} when absent)
         * @return this builder
         */
        public Builder outputSink(SubagentOutputSink outputSink) {
            this.outputSink = outputSink;
            return this;
        }

        /** SubagentExecutionContext를 생성한다. */
        public SubagentExecutionContext build() {
            return new SubagentExecutionContext(this);
        }
    }
}
