package at.aimon.core.agent.impl.orca;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.PromptSizeRecoveryStrategy;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.rewake.RewakeCapableRuntime;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.workflow.WorkflowRunner;

/**
 * Encapsulates the runtime configuration for agent execution.
 *
 * <p>
 * This class allows injecting execution handlers at execution time rather than at agent construction time, providing
 * greater flexibility for dynamic handler configuration.
 *
 * <p>
 * This context is <strong>agent-scoped</strong>: one instance per {@code (Agent, discriminator)} pair, shared across
 * all sessions of that agent. {@link #close()} must be invoked only at app shutdown / agent removal.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     ExecutionContext context = ExecutionContext.builder().config(config).toolHandler(toolHandler)
 *             .commandHandler(commandHandler).hookExecutor(hookExecutor).environment(Environment.createDefault())
 *             .build();
 *
 *     AgentExecutionResult result = agent.execute("user message", context);
 * }
 * </pre>
 */
public final class OrcaAgentRuntime implements AgentRuntime, RewakeCapableRuntime, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OrcaAgentRuntime.class);

    /** Builder를 생성한다. */
    public static Builder builder() {
        return new Builder();
    }

    private final AgentRuntimeId id;
    private final Agent agent;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;
    private final CommandRegistry commandRegistry;
    private final SubagentRegistry subagentRegistry;
    private final SkillRegistry skillRegistry;
    private final VirtualFileSystem fileSystem;
    private final Environment environment;
    private final McpClientManager mcpClientManager; // nullable - only present when MCP is configured
    private final KnowledgeStore knowledgeStore; // nullable - only present when knowledge directory is configured
    private final CompactionEngine compactionEngine; // nullable - opt-in conversation compaction
    private final CompactionGuard compactionGuard; // nullable - paired with compactionEngine; defaults to NoOp upstream
    private final PromptSizeRecoveryStrategy promptSizeRecoveryStrategy; // nullable - defaults to NoOp upstream
    private final List<ToolContextEnricher> toolContextEnrichers;
    private final WorkflowRunner workflowRunner; // nullable - only present when background runs are enabled
    // TCH-01: non-null ONLY when core built the default shell itself. A shell handed in via
    // OrcaAgentRuntimeFactory.withShell(...) belongs to the assembly and is not stored here — borrowed things are not
    // closed (docs/overview/scope-model.md §2).
    private final VirtualShell ownedShell;

    @SuppressWarnings("checkstyle:ParameterNumber")
    private OrcaAgentRuntime(AgentRuntimeId id, Agent agent, ToolRegistry toolRegistry, HookRegistry hookRegistry,
            CommandRegistry commandRegistry, SubagentRegistry subagentRegistry, SkillRegistry skillRegistry,
            VirtualFileSystem fileSystem, Environment environment, McpClientManager mcpClientManager,
            KnowledgeStore knowledgeStore, CompactionEngine compactionEngine, CompactionGuard compactionGuard,
            PromptSizeRecoveryStrategy promptSizeRecoveryStrategy, List<ToolContextEnricher> toolContextEnrichers,
            WorkflowRunner workflowRunner, VirtualShell ownedShell) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.agent = Objects.requireNonNull(agent, "Agent cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "Tool registry cannot be null");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "Hook registry cannot be null");
        this.commandRegistry = Objects.requireNonNull(commandRegistry, "Command registry cannot be null");
        this.subagentRegistry = Objects.requireNonNull(subagentRegistry, "Subagent registry cannot be null");
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "Skill registry cannot be null");
        this.fileSystem = Objects.requireNonNull(fileSystem, "File system cannot be null");
        this.environment = Objects.requireNonNull(environment, "Environment cannot be null");
        this.mcpClientManager = mcpClientManager; // nullable
        this.knowledgeStore = knowledgeStore; // nullable
        this.compactionEngine = compactionEngine; // nullable
        this.compactionGuard = compactionGuard; // nullable
        this.promptSizeRecoveryStrategy = promptSizeRecoveryStrategy; // nullable
        this.toolContextEnrichers = List
                .copyOf(Objects.requireNonNull(toolContextEnrichers, "toolContextEnrichers cannot be null"));
        this.workflowRunner = workflowRunner; // nullable
        this.ownedShell = ownedShell; // nullable
    }

    @Override
    public AgentRuntimeId getId() {
        return id;
    }

    /**
     * Gets the available tools.
     *
     * @return An immutable list of available tools (never null)
     */
    @Override
    public List<Tool> getAvailableTools() {
        return toolRegistry.findAll();
    }

    @Override
    public Optional<Tool> findToolByName(String toolName) {
        return toolRegistry.findByName(toolName);
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    @Override
    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    public SubagentRegistry getSubagentRegistry() {
        return subagentRegistry;
    }

    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    /**
     * Gets the virtual file system.
     *
     * @return The virtual file system (never null)
     */
    public VirtualFileSystem getFileSystem() {
        return fileSystem;
    }

    /**
     * Gets the agent configuration.
     *
     * @return The configuration (never null)
     */
    @Override
    public Agent getAgent() {
        return agent;
    }

    /**
     * Gets the runtime environment.
     *
     * @return The environment (never null)
     */
    @Override
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * Returns the MCP client manager, if MCP is configured.
     *
     * @return the MCP client manager (empty if MCP is not configured)
     */
    public Optional<McpClientManager> getMcpClientManager() {
        return Optional.ofNullable(mcpClientManager);
    }

    /**
     * Returns the knowledge store, if a knowledge directory is configured.
     *
     * @return the knowledge store (empty if not configured)
     */
    public Optional<KnowledgeStore> getKnowledgeStore() {
        return Optional.ofNullable(knowledgeStore);
    }

    /**
     * Returns the compaction engine, if conversation compaction is opted in.
     *
     * @return the compaction engine (empty if not configured)
     */
    public Optional<CompactionEngine> getCompactionEngine() {
        return Optional.ofNullable(compactionEngine);
    }

    /**
     * Returns the compaction guard, if conversation compaction is opted in. When absent, callers should default to
     * {@link at.aimon.core.agent.compact.NoOpCompactionGuard}.
     *
     * @return the compaction guard (empty if not configured)
     */
    public Optional<CompactionGuard> getCompactionGuard() {
        return Optional.ofNullable(compactionGuard);
    }

    /**
     * Returns the prompt-size recovery strategy, if the prompt-too-long fallback is opted in. When absent, callers
     * should default to {@link at.aimon.core.agent.compact.NoOpPromptSizeRecoveryStrategy}.
     *
     * @return the recovery strategy (empty if not configured)
     */
    public Optional<PromptSizeRecoveryStrategy> getPromptSizeRecoveryStrategy() {
        return Optional.ofNullable(promptSizeRecoveryStrategy);
    }

    /**
     * Returns the registered {@link ToolContextEnricher enrichers} that should be invoked before each tool call.
     *
     * @return an immutable list of enrichers (never null; possibly empty)
     */
    public List<ToolContextEnricher> getToolContextEnrichers() {
        return toolContextEnrichers;
    }

    /**
     * Returns the per-context (agent-scoped) {@link WorkflowRunner} owned by this context, if background
     * workflow runs are enabled. Used by the {@code Workflow} tool to submit background runs and (later) by the
     * CLI {@code /runs} command to inspect them.
     *
     * @return the workflow runner (empty if background runs are not enabled)
     */
    public Optional<WorkflowRunner> getWorkflowRunner() {
        return Optional.ofNullable(workflowRunner);
    }

    /**
     * Closes the agent-scoped resources owned by this context.
     *
     * <p>
     * Per the scope model (see {@code docs/overview/scope-model.md}), this context is <b>agent-scoped</b> —
     * shared across every session against the same {@code (Agent, discriminator)} pair. {@link #close()} must
     * therefore be invoked only at <b>application shutdown</b> or on an <b>explicit agent removal</b>, never as part
     * of a per-session or per-live-session teardown.
     *
     * <p>
     * Closes the {@link McpClientManager} (agent-scoped under the {@code AgentScoped} marker) so MCP server
     * connections / spawned stdio processes are released. {@link KnowledgeStore} is <b>application-scoped</b> and is
     * deliberately <i>not</i> closed here — closing it would violate the {@code ApplicationScoped} marker contract on
     * {@code KnowledgeStore} and break the store for any other agent that shares it.
     *
     * <p>
     * The list below is hardcoded on purpose: there is no fan-out over the {@code AgentScoped} marker, so a new
     * agent-scoped component holding native resources is closed only if it is added here explicitly.
     */
    @Override
    public void close() {
        if (mcpClientManager != null) {
            try {
                mcpClientManager.close();
            } catch (Exception e) {
                // Log but don't propagate — close must not prevent other cleanup
                log.warn("Failed to close McpClientManager: {}", e.getMessage(), e);
            }
        }
        if (workflowRunner != null) {
            try {
                workflowRunner.close();
            } catch (Exception e) {
                log.warn("Failed to close WorkflowRunner: {}", e.getMessage(), e);
            }
        }
        // TCH-01. This position in the list is NOT a solved teardown order — the risk is acknowledged and recorded,
        // not fixed. A background Bash task still using the shell when it closes would fail, but there is nothing to
        // order against: BackgroundBashManager has neither close() nor shutdown(), it is a local variable in
        // OrcaBashToolProvider that the runtime cannot reach, and it was never in this list. The way out is for the
        // assembly to own the shell via withShell(...) — then ownedShell is null here and teardown order is the
        // assembly's, which already runs the shell last. See docs/overview/scope-model.md §2.
        if (ownedShell != null) {
            try {
                ownedShell.close();
            } catch (Exception e) {
                log.warn("Failed to close VirtualShell: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final OrcaAgentRuntime that = (OrcaAgentRuntime) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ExecutionContext{" + "id=" + id + ", agent=" + agent + ", environment=" + environment + '}';
    }

    public static class Builder {
        private AgentRuntimeId id;
        private Agent agent;
        private ToolRegistry toolRegistry;
        private HookRegistry hookRegistry;
        private CommandRegistry commandRegistry;
        private SubagentRegistry subagentRegistry;
        private SkillRegistry skillRegistry;
        private VirtualFileSystem fileSystem;
        private Environment environment;
        private McpClientManager mcpClientManager;
        private KnowledgeStore knowledgeStore;
        private CompactionEngine compactionEngine;
        private CompactionGuard compactionGuard;
        private PromptSizeRecoveryStrategy promptSizeRecoveryStrategy;
        private List<ToolContextEnricher> toolContextEnrichers = List.of();
        private WorkflowRunner workflowRunner;
        private VirtualShell ownedShell;

        /**
         * ID를 설정한다. If you need a discriminator-scoped id, set it explicitly via {@code id(...)}; otherwise the
         * default uses the bare agent name (no discriminator) — see {@link #build()}.
         */
        public Builder id(AgentRuntimeId id) {
            this.id = id;
            return this;
        }

        /** Agent를 설정한다. */
        public Builder agent(Agent agent) {
            this.agent = agent;
            return this;
        }

        /** ToolRegistry를 설정한다. */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /** HookRegistry를 설정한다. */
        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        /** CommandRegistry를 설정한다. */
        public Builder commandRegistry(CommandRegistry commandRegistry) {
            this.commandRegistry = commandRegistry;
            return this;
        }

        /** SubagentRegistry를 설정한다. */
        public Builder subagentRegistry(SubagentRegistry subagentRegistry) {
            this.subagentRegistry = subagentRegistry;
            return this;
        }

        /** SkillRegistry를 설정한다. */
        public Builder skillRegistry(SkillRegistry skillRegistry) {
            this.skillRegistry = skillRegistry;
            return this;
        }

        /** VirtualFileSystem을 설정한다. */
        public Builder fileSystem(VirtualFileSystem fileSystem) {
            this.fileSystem = fileSystem;
            return this;
        }

        /** Environment를 설정한다. */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /** McpClientManager를 설정한다 (nullable). */
        public Builder mcpClientManager(McpClientManager mcpClientManager) {
            this.mcpClientManager = mcpClientManager;
            return this;
        }

        /** KnowledgeStore를 설정한다 (nullable). */
        public Builder knowledgeStore(KnowledgeStore knowledgeStore) {
            this.knowledgeStore = knowledgeStore;
            return this;
        }

        /** CompactionEngine을 설정한다 (nullable; opt-in). */
        public Builder compactionEngine(CompactionEngine compactionEngine) {
            this.compactionEngine = compactionEngine;
            return this;
        }

        /** CompactionGuard를 설정한다 (nullable; defaults to NoOpCompactionGuard at the call site). */
        public Builder compactionGuard(CompactionGuard compactionGuard) {
            this.compactionGuard = compactionGuard;
            return this;
        }

        /**
         * PromptSizeRecoveryStrategy를 설정한다 (nullable; defaults to NoOpPromptSizeRecoveryStrategy at the call site).
         */
        public Builder promptSizeRecoveryStrategy(PromptSizeRecoveryStrategy promptSizeRecoveryStrategy) {
            this.promptSizeRecoveryStrategy = promptSizeRecoveryStrategy;
            return this;
        }

        /**
         * ToolContextEnricher 목록을 설정한다. null이면 빈 목록으로 처리된다.
         */
        public Builder toolContextEnrichers(List<ToolContextEnricher> toolContextEnrichers) {
            this.toolContextEnrichers = toolContextEnrichers == null ? List.of() : List.copyOf(toolContextEnrichers);
            return this;
        }

        /** WorkflowRunner를 설정한다 (nullable; only present when background runs are enabled). */
        public Builder workflowRunner(WorkflowRunner workflowRunner) {
            this.workflowRunner = workflowRunner;
            return this;
        }

        /**
         * core 가 직접 만든 기본 셸을 설정한다 — 이 런타임이 소유하고 {@link OrcaAgentRuntime#close()} 에서 닫는다.
         * 조립이 {@code withShell(...)} 로 넘긴 셸은 조립 소유이므로 여기 넣지 않는다 (nullable).
         */
        public Builder ownedShell(VirtualShell ownedShell) {
            this.ownedShell = ownedShell;
            return this;
        }

        /** OrcaAgentRuntime를 생성한다. */
        public OrcaAgentRuntime build() {
            if (id == null) {
                Objects.requireNonNull(agent, "Agent must be set before build() can derive an id");
                id = AgentRuntimeId.from(agent);
            }
            return new OrcaAgentRuntime(id, agent, toolRegistry, hookRegistry, commandRegistry, subagentRegistry,
                    skillRegistry, fileSystem, environment, mcpClientManager, knowledgeStore, compactionEngine,
                    compactionGuard, promptSizeRecoveryStrategy, toolContextEnrichers, workflowRunner, ownedShell);
        }
    }

}
