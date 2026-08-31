package at.aimon.core.agent.orca.tool;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.task.SessionSnapshotStore;
import at.aimon.core.subagent.task.TaskOutputStore;
import at.aimon.core.subagent.task.TaskResultStore;
import at.aimon.core.workflow.WorkflowRunner;

/**
 * Context providing dependencies for Orca tool providers.
 *
 * <p>
 * This class aggregates all dependencies that tool providers might need when creating and registering tools. It uses
 * the Builder pattern to provide a fluent API for construction.
 *
 * <p>
 * Common registry dependencies are held via {@link OrcaProviderDependencies} (composition), while tool-specific fields
 * ({@code fileSystem}, {@code environment}, {@code agent}) are held directly.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OrcaToolProviderContext context = OrcaToolProviderContext.builder().fileSystem(fileSystem)
 *             .environment(environment).agent(agent).dependencies(deps).build();
 * }
 * </pre>
 *
 * <h2>On the size of this type</h2>
 *
 * <p>
 * Seventeen accessors reaching into ten subsystems is a lot for a public SPI, and it is deliberate rather than
 * accumulated: {@code PackageDependencyArchitectureTest} carves this package out of the layering rules precisely
 * because it "aggregates dependencies from cross-cutting registries by design — this is the SPI surface external
 * modules implement, not the broader {@code agent.*} contract". A provider asks for what it needs and ignores the
 * rest; nothing here has to be non-null for a provider that never calls it.
 *
 * <p>
 * There were eighteen. {@code getDependencies()}, which handed back the whole {@link OrcaProviderDependencies}
 * aggregate rather than one collaborator from it, had no caller in main or test sources and no documented reason to
 * exist — a bypass around the seventeen typed accessors that nothing had ever taken. It was removed rather than
 * grandfathered, per the {@code 0.x} policy in {@code docs/project/api-stability.md} §5. The composition it exposed
 * is unchanged; only the escape hatch is gone.
 *
 * <p>
 * What that decision does not say is how lopsided the asking is, so it is measured here rather than left to the next
 * reader to re-derive. Across the thirteen in-tree providers (2026-08-31):
 *
 * <ul>
 * <li><b>four use none of it</b> — {@code OrcaKnowledgeToolProvider}, {@code OrcaMemoryToolProvider},
 * {@code OrcaTodoToolProvider}, {@code OrcaMcpToolProvider} take their collaborators through the constructor instead;
 * <li><b>the two that live outside {@code aimon-core}</b> — the very modules this SPI exists for — use one and two:
 * {@code OrcaSandboxToolProvider} reads {@code getFileSystem()}, {@code OrcaBrowserToolProvider} that plus
 * {@code getCredentialStore()};
 * <li>three reach double digits or close to it ({@code OrcaSubagentToolProvider} 11, {@code OrcaSkillToolProvider} and
 * {@code GraalJsWorkflowToolProvider} 8 each), and they are what the width is actually for — a subagent tool needs the
 * registry, the execution manager, three task stores and the enrichers at once.
 * </ul>
 *
 * <p>
 * So the shape is honest for its heaviest consumer and generous for its lightest. What would change the answer is an
 * out-of-tree provider that has to reason about accessors it does not use — and the two that exist read one and two
 * respectively, so that has not happened. Until it does, splitting this into role interfaces would add types without
 * removing a cost anyone is paying; see {@code docs/backlog/module-dependency-scope.md} §0 for the neighbouring case
 * where that same reasoning was tested and the "obvious cleanup" turned out to be a decision.
 *
 * <p>
 * The other trigger — an accessor nobody calls — is not listed here because it is not something to wait for. It was
 * checked, it had fired, and the accessor is gone (above). Re-run that census before adding one.
 *
 * @see OrcaToolProvider
 */
public final class OrcaToolProviderContext {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final VirtualFileSystem fileSystem;
    private final VirtualShell shell;
    private final Environment environment;
    private final Agent agent;
    private final OrcaProviderDependencies dependencies;
    private final List<ToolContextEnricher> toolContextEnrichers;
    private final WorkflowRunner workflowRunner;

    private OrcaToolProviderContext(Builder builder) {
        fileSystem = builder.fileSystem;
        shell = builder.shell;
        environment = builder.environment;
        agent = builder.agent;
        dependencies = Objects.requireNonNull(builder.dependencies, "dependencies must not be null");
        toolContextEnrichers = builder.toolContextEnrichers != null
                ? List.copyOf(builder.toolContextEnrichers)
                : List.of();
        workflowRunner = builder.workflowRunner;
    }

    /**
     * Returns the virtual file system.
     *
     * @return the virtual file system, may be null
     */
    public VirtualFileSystem getFileSystem() {
        return fileSystem;
    }

    /**
     * Returns the shell that command-executing tools run through.
     *
     * <p>
     * Deliberately <b>not</b> required: providers that need no shell must still be able to assemble a context. A
     * provider that does need one checks for null and skips registering its tool rather than risking a runtime NPE.
     *
     * @return the shell, may be null when neither the assembly nor the runtime factory supplied one
     */
    public VirtualShell getShell() {
        return shell;
    }

    /**
     * Returns the environment.
     *
     * @return the environment, may be null
     */
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * Returns the agent.
     *
     * @return the agent, may be null
     */
    public Agent getAgent() {
        return agent;
    }

    /**
     * Returns the subagent registry.
     *
     * @return the subagent registry, may be null
     */
    public SubagentRegistry getSubagentRegistry() {
        return dependencies.getSubagentRegistry();
    }

    /**
     * Returns the subagent execution manager.
     *
     * @return the subagent execution manager, may be null
     */
    public SubagentExecutionManager getSubagentExecutionManager() {
        return dependencies.getSubagentExecutionManager();
    }

    /**
     * Returns the task output store used to record and tail background subagents' live progress logs.
     *
     * @return the task output store, may be null when live-output streaming is not configured
     */
    public TaskOutputStore getTaskOutputStore() {
        return dependencies.getTaskOutputStore();
    }

    /**
     * Returns the task result store holding what each background task finally produced.
     *
     * @return the task result store, may be null when result retention is not configured
     */
    public TaskResultStore getTaskResultStore() {
        return dependencies.getTaskResultStore();
    }

    /**
     * Returns the session snapshot store used to persist a finished subagent's transcript for later resume.
     *
     * @return the session snapshot store, may be null when resume is not configured
     */
    public SessionSnapshotStore getSessionSnapshotStore() {
        return dependencies.getSessionSnapshotStore();
    }

    /**
     * Returns the skill registry.
     *
     * @return the skill registry, may be null
     */
    public SkillRegistry getSkillRegistry() {
        return dependencies.getSkillRegistry();
    }

    /**
     * Returns the tool registry.
     *
     * @return the tool registry, may be null
     */
    public ToolRegistry getToolRegistry() {
        return dependencies.getToolRegistry();
    }

    /**
     * Returns the hook registry.
     *
     * @return the hook registry, may be null
     */
    public HookRegistry getHookRegistry() {
        return dependencies.getHookRegistry();
    }

    /**
     * Returns the scheduled task manager.
     *
     * @return the scheduled task manager, may be null
     */
    public ScheduledTaskManager getScheduledTaskManager() {
        return dependencies.getScheduledTaskManager();
    }

    /**
     * Returns the credential store.
     *
     * @return the credential store, may be null
     */
    public CredentialStore getCredentialStore() {
        return dependencies.getCredentialStore();
    }

    /**
     * Returns the {@link SkillInvocationPolicy} that gates {@code Skill} tool invocations.
     *
     * <p>
     * When non-null, {@code OrcaSkillToolProvider} wires the policy into {@code SkillTool} so per-tool denials agree
     * with the {@code OrcaAgentExecutor}'s pre-flight scanner. When null, {@code SkillTool} falls back to
     * {@code AlwaysAllowSkillInvocationPolicy}.
     *
     * @return the skill invocation policy, may be null when SK-11 wiring is absent
     */
    public SkillInvocationPolicy getSkillInvocationPolicy() {
        return dependencies.getSkillInvocationPolicy();
    }

    /**
     * Returns the tool-context enrichers registered on the agent runtime. Forwarded to subagent execution so
     * subagent tools receive the same module-supplied context keys as the main-agent tools.
     *
     * @return an immutable list of enrichers (never null, may be empty)
     */
    public List<ToolContextEnricher> getToolContextEnrichers() {
        return toolContextEnrichers;
    }

    /**
     * Returns the per-context (agent-scoped) {@link WorkflowRunner} for this context, used by the
     * {@code Workflow} tool to submit background runs (and, later, by the CLI {@code /runs} command to inspect
     * them).
     *
     * @return the workflow runner, or {@code null} when workflow background runs are disabled
     */
    public WorkflowRunner getWorkflowRunner() {
        return workflowRunner;
    }

    /**
     * Builder for {@link OrcaToolProviderContext}.
     */
    public static final class Builder {
        private VirtualFileSystem fileSystem;
        private VirtualShell shell;
        private Environment environment;
        private Agent agent;
        private OrcaProviderDependencies dependencies;
        private List<ToolContextEnricher> toolContextEnrichers;
        private WorkflowRunner workflowRunner;

        private Builder() {
        }

        /**
         * Sets the virtual file system.
         *
         * @param fileSystem
         *            the virtual file system
         * @return this builder
         */
        public Builder fileSystem(VirtualFileSystem fileSystem) {
            this.fileSystem = fileSystem;
            return this;
        }

        /**
         * Sets the shell that command-executing tools run through.
         *
         * @param shell
         *            the shell (nullable; a provider that needs one skips registering its tool when it is absent)
         * @return this builder
         */
        public Builder shell(VirtualShell shell) {
            this.shell = shell;
            return this;
        }

        /**
         * Sets the environment.
         *
         * @param environment
         *            the environment
         * @return this builder
         */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the agent.
         *
         * @param agent
         *            the agent
         * @return this builder
         */
        public Builder agent(Agent agent) {
            this.agent = agent;
            return this;
        }

        /**
         * Sets the shared provider dependencies.
         *
         * @param dependencies
         *            the provider dependencies
         * @return this builder
         */
        public Builder dependencies(OrcaProviderDependencies dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        /**
         * Sets the tool-context enrichers registered on the agent runtime.
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
         * Sets the application-scoped workflow runner shared by this context for background runs.
         *
         * @param workflowRunner
         *            the workflow runner (nullable; null disables the {@code Workflow} tool's background mode)
         * @return this builder
         */
        public Builder workflowRunner(WorkflowRunner workflowRunner) {
            this.workflowRunner = workflowRunner;
            return this;
        }

        /**
         * Builds the context.
         *
         * @return a new {@link OrcaToolProviderContext} instance
         */
        public OrcaToolProviderContext build() {
            return new OrcaToolProviderContext(this);
        }
    }
}
