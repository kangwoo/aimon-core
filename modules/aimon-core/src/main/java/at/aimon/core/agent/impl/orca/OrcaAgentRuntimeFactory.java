package at.aimon.core.agent.impl.orca;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionFailureStore;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.compact.DefaultPromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.InMemoryCompactionFailureStore;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.command.OrcaCommandProvider;
import at.aimon.core.agent.impl.orca.command.OrcaCommandProviderContext;
import at.aimon.core.agent.impl.orca.command.OrcaSystemCommandProvider;
import at.aimon.core.agent.impl.orca.environment.LocalShells;
import at.aimon.core.agent.impl.orca.environment.WorktreeToolEnvironmentFactory;
import at.aimon.core.agent.impl.orca.tool.OrcaBashToolProvider;
import at.aimon.core.agent.impl.orca.tool.OrcaFileToolProvider;
import at.aimon.core.agent.impl.orca.tool.OrcaSchedulingToolProvider;
import at.aimon.core.agent.impl.orca.tool.OrcaSkillToolProvider;
import at.aimon.core.agent.impl.orca.tool.OrcaSubagentToolProvider;
import at.aimon.core.agent.impl.orca.tool.OrcaTodoToolProvider;
import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.ModelContextWindowRegistry;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llm.token.TokenEstimator;
import at.aimon.core.mcp.McpClientFactory;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.mcp.McpServerConfigProvider;
import at.aimon.core.mcp.orca.OrcaMcpToolProvider;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.CompositeSkillRegistry;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.parser.SkillParser;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.core.skill.repository.BundledSkillMaterializer;
import at.aimon.core.subagent.CompositeSubagentRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.task.InMemorySessionSnapshotStore;
import at.aimon.core.subagent.task.InMemoryTaskResultStore;
import at.aimon.core.subagent.task.SessionSnapshotStore;
import at.aimon.core.subagent.task.TaskOutputStore;
import at.aimon.core.subagent.task.TaskResultStore;
import at.aimon.core.subagent.task.VfsSessionSnapshotStore;
import at.aimon.core.subagent.task.VfsTaskOutputStore;
import at.aimon.core.subagent.task.VfsTaskResultStore;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowRunnerOptions;
import at.aimon.core.workflow.WorkflowRunners;
import at.aimon.core.workflow.WorktreeEnvironmentFactory;

/**
 * Factory for creating {@link OrcaAgentRuntime} instances.
 *
 * <p>
 * This factory supports dependency injection of tool and command providers, enabling flexible configuration. Providers
 * are passed to the create method, allowing different provider configurations per context creation.
 *
 * <p>
 * Example usage with AgentBundle:
 *
 * <pre>
 * {
 *     &#64;code
 *     OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory();
 *     AgentBundle bundle = agentBundleLoader.load("default");
 *     OrcaAgentRuntime context = factory.create(agentRuntimeId, executor, scheduledTaskManager, bundle,
 *             fileSystem, credentialStore, OrcaAgentRuntimeFactory.defaultToolProviders(),
 *             OrcaAgentRuntimeFactory.defaultCommandProviders());
 * }
 * </pre>
 *
 * <p>
 * Example usage with custom providers:
 *
 * <pre>
 * {
 *     &#64;code
 *     OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory();
 *     List<OrcaToolProvider> customToolProviders = List.of(new OrcaFileToolProvider(), new CustomToolProvider());
 *     List<OrcaCommandProvider> customCommandProviders = List.of(new OrcaSystemCommandProvider(),
 *             new CustomCommandProvider());
 *     OrcaAgentRuntime context = factory.create(agentRuntimeId, executor, scheduledTaskManager, bundle,
 *             fileSystem, credentialStore, customToolProviders, customCommandProviders);
 * }
 * </pre>
 */
public class OrcaAgentRuntimeFactory {

    /**
     * Returns the default tool providers.
     *
     * <p>
     * The default tool providers include:
     *
     * <ul>
     * <li>{@link OrcaTodoToolProvider} - Todo management
     * <li>{@link OrcaFileToolProvider} - File operations (Read, Write, Edit, Grep)
     * <li>{@link OrcaBashToolProvider} - Bash execution
     * <li>{@link OrcaSubagentToolProvider} - Subagent management
     * <li>{@link OrcaSkillToolProvider} - Skill activation
     * </ul>
     *
     * @return a list of default tool providers
     */
    public static List<OrcaToolProvider> defaultToolProviders() {
        return defaultToolProviders(false);
    }

    /**
     * Returns the default tool providers, optionally enabling the experimental {@code Workflow} tool.
     *
     * @param workflowToolEnabled
     *            whether the subagent provider also registers the opt-in {@code Workflow} tool
     * @return a list of default tool providers
     */
    public static List<OrcaToolProvider> defaultToolProviders(boolean workflowToolEnabled) {
        return List.of(new OrcaTodoToolProvider(), new OrcaFileToolProvider(), new OrcaBashToolProvider(),
                new OrcaSubagentToolProvider(workflowToolEnabled), new OrcaSkillToolProvider(),
                new OrcaSchedulingToolProvider());
    }

    /**
     * Returns the default command providers.
     *
     * <p>
     * The default command providers include:
     *
     * <ul>
     * <li>{@link OrcaSystemCommandProvider} - System commands (help, version, clear)
     * </ul>
     *
     * @return a list of default command providers
     */
    public static List<OrcaCommandProvider> defaultCommandProviders() {
        return List.of(new OrcaSystemCommandProvider());
    }

    private final String commandsDirectory;
    private final String agentsDirectory;
    private final String skillsDirectory;
    private final String version;
    private final KnowledgeStore knowledgeStore; // nullable
    // Transcript-compaction collaborators are stateless (estimator) or registry-only (model windows) so we can
    // share a single instance across every context produced by this factory. The CompactionGuard is created per
    // context because it holds node-local per-session locks; where the failure count behind its circuit breaker
    // lives is a separate question, answered by withCompactionFailureStore(...).
    private final TokenEstimator tokenEstimator = new HeuristicTokenEstimator();
    private final ModelContextWindowRegistry modelContextWindowRegistry = InMemoryModelContextWindowRegistry
            .withDefaults();
    // SK-11.5: optional app-scoped collaborators threaded into OrcaProviderDependencies. When non-null these are
    // surfaced to the system command provider so /pending, /deny, /approve register, and to OrcaSkillToolProvider so
    // SkillTool's per-call check agrees with the OrcaAgentExecutor's pre-flight scanner.
    private PendingTurnRegistry pendingTurnRegistry;
    private AgentApprovalStore agentApprovalStore;
    private SessionApprovalStore sessionApprovalStore;
    private SkillInvocationPolicy skillInvocationPolicy;
    private SkillRegistry preBuiltSkillRegistry;
    private List<ToolContextEnricher> toolContextEnrichers = List.of();
    // TCH-01: the shell that command-executing tools run through. Null => each context builds its own local shell and
    // owns it (OrcaAgentRuntime.ownedShell). Supplied => the assembly owns it and closes it; that is how a sandboxed
    // (Docker/Kubernetes) shell gets wired in, and how one shell comes to be shared with the skill hooks.
    private VirtualShell shell;
    private RewakeService rewakeService;
    // Code-defined subagents: composed as the highest-priority (authoritative) subagent layer. When non-null this
    // registry is layered after the bundled and user (agents/*.md) registries so code definitions cannot be shadowed
    // by a same-named user markdown file. See withCodeSubagentRegistry / buildCompositeSubagentRegistry.
    private SubagentRegistry preBuiltCodeSubagentRegistry;
    // Subagent §5/§7: bootstrap overrides for the per-context background-task stores, expressed as factories over the
    // context's VirtualFileSystem (each context gets its own store rooted in its own file system). Null => the built-in
    // default (VfsTaskOutputStore for output; in-memory for the resume-snapshot store). A scale-out bootstrap points
    // the snapshot factory at VfsSessionSnapshotStore so any node can reload a finished subagent's transcript.
    private Function<VirtualFileSystem, TaskOutputStore> taskOutputStoreFactory;
    private Function<VirtualFileSystem, SessionSnapshotStore> sessionSnapshotStoreFactory;
    private Function<VirtualFileSystem, TaskResultStore> taskResultStoreFactory;
    // Bootstrap override for the compaction circuit breaker's counter storage. Null => a fresh
    // InMemoryCompactionFailureStore per context, which breaks the circuit per process. A scale-out bootstrap passes
    // one SessionRecordCompactionFailureStore over its (fenced) record store so every node breaks the same circuit;
    // that instance is shared by every context this factory builds, which is correct because the state it reads and
    // writes lives in the session record, not in the store object.
    private CompactionFailureStore compactionFailureStore;
    // When true, each created context builds ONE per-context (agent-scoped) WorkflowRunner (with in-memory
    // resume step cache + background hosting pool) that the Workflow tool uses for background runs; the context owns
    // and closes it. Off by default — no hosting pool is created and the tool's background mode reports unavailable.
    // Note: enabling this for many agents/discriminators multiplies the per-context hosting + fan-out pools.
    private boolean workflowRunnerEnabled;
    // Design §6.3 — bootstrap override for the worktree isolation factory wired into each per-context
    // workflow runner, expressed (like the store factories above) as a factory over the context's
    // VirtualFileSystem. Null => the built-in WorktreeToolEnvironmentFactory, which rebinds the file tools to a
    // branch-scoped VFS. A bootstrap supplies an alternative implementation for e.g. Bash-inclusive isolation or
    // overlay read-through without touching buildWorkflowRunner.
    private Function<VirtualFileSystem, WorktreeEnvironmentFactory> worktreeEnvironmentFactoryFactory;

    /**
     * Creates a factory with default configuration.
     */
    public OrcaAgentRuntimeFactory() {
        this("1.0.0", ".aimon/commands", ".aimon/agents", ".aimon/skills", null);
    }

    /**
     * Creates a factory with custom configuration.
     *
     * @param version
     *            the version string for VersionCommand
     * @param commandsDirectory
     *            the directory containing custom commands
     * @param agentsDirectory
     *            the directory containing agent definitions
     * @param skillsDirectory
     *            the directory containing skill definitions
     * @throws NullPointerException
     *             if version, commandsDirectory, agentsDirectory, or skillsDirectory is null
     */
    public OrcaAgentRuntimeFactory(String version, String commandsDirectory, String agentsDirectory,
            String skillsDirectory) {
        this(version, commandsDirectory, agentsDirectory, skillsDirectory, null);
    }

    /**
     * Creates a factory with custom configuration and a knowledge store.
     *
     * @param version
     *            the version string for VersionCommand
     * @param commandsDirectory
     *            the directory containing custom commands
     * @param agentsDirectory
     *            the directory containing agent definitions
     * @param skillsDirectory
     *            the directory containing skill definitions
     * @param knowledgeStore
     *            the knowledge store to inject into created contexts (may be null)
     * @throws NullPointerException
     *             if version, commandsDirectory, agentsDirectory, or skillsDirectory is null
     */
    public OrcaAgentRuntimeFactory(String version, String commandsDirectory, String agentsDirectory,
            String skillsDirectory, KnowledgeStore knowledgeStore) {
        this.version = Objects.requireNonNull(version, "version must not be null");
        this.commandsDirectory = Objects.requireNonNull(commandsDirectory, "commandsDirectory must not be null");
        this.agentsDirectory = Objects.requireNonNull(agentsDirectory, "agentsDirectory must not be null");
        this.skillsDirectory = Objects.requireNonNull(skillsDirectory, "skillsDirectory must not be null");
        this.knowledgeStore = knowledgeStore; // nullable
    }

    /**
     * SK-11.5: configures the {@link PendingTurnRegistry} surfaced to {@link OrcaProviderDependencies} so the system
     * command provider can register {@code /pending}, {@code /deny}, and (paired with
     * {@link #withAgentApprovalStore}) {@code /approve}. When {@code null} (the default) those commands are
     * skipped at registration time.
     *
     * @param pendingTurnRegistry
     *            the registry (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withPendingTurnRegistry(PendingTurnRegistry pendingTurnRegistry) {
        this.pendingTurnRegistry = pendingTurnRegistry;
        return this;
    }

    /**
     * SK-11.5: configures the {@link AgentApprovalStore} surfaced to {@link OrcaProviderDependencies} so the system
     * command provider can register {@code /approve}. When {@code null} (the default) the {@code /approve} command
     * is skipped at registration time even when a {@link PendingTurnRegistry} is configured.
     *
     * @param agentApprovalStore
     *            the store (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withAgentApprovalStore(AgentApprovalStore agentApprovalStore) {
        this.agentApprovalStore = agentApprovalStore;
        return this;
    }

    /**
     * Configures the {@link SessionApprovalStore} surfaced to {@link OrcaProviderDependencies} so the system
     * command provider can scope {@code /revoke}, {@code /approve} and {@code /deny} to the current session, and
     * so {@code /clear} drops that session's approvals. When {@code null} (the default) those commands keep
     * acting on the agent-scoped store alone.
     *
     * @param sessionApprovalStore
     *            the store (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withSessionApprovalStore(SessionApprovalStore sessionApprovalStore) {
        this.sessionApprovalStore = sessionApprovalStore;
        return this;
    }

    /**
     * SK-11.5: configures the {@link SkillInvocationPolicy} surfaced to {@link OrcaProviderDependencies} so
     * {@link OrcaSkillToolProvider} wires {@code SkillTool} with a policy that matches the
     * {@code OrcaAgentExecutor}'s pre-flight scanner. When {@code null} (the default) {@code SkillTool} falls back
     * to {@code AlwaysAllowSkillInvocationPolicy}.
     *
     * @param skillInvocationPolicy
     *            the policy (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withSkillInvocationPolicy(SkillInvocationPolicy skillInvocationPolicy) {
        this.skillInvocationPolicy = skillInvocationPolicy;
        return this;
    }

    /**
     * Async-rewake §7.4: configures the application-scoped {@link RewakeService} surfaced to
     * {@link OrcaProviderDependencies} so the system command provider registers the {@code /rewakes} command. When
     * {@code null} (the default) or {@link RewakeService#NOOP}, command registration is skipped.
     *
     * @param rewakeService
     *            the rewake service (may be {@code null})
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withRewakeService(RewakeService rewakeService) {
        this.rewakeService = rewakeService;
        return this;
    }

    /**
     * SK-11.5: configures a pre-built composite {@link SkillRegistry}. When set the factory uses this registry
     * instead of constructing its own from the agent bundle and virtual filesystem, allowing the caller to share the
     * same registry instance with the {@code OrcaAgentExecutor}'s pre-flight scanner.
     *
     * <p>
     * When {@code null} (the default) the factory builds the registry internally exactly as before.
     *
     * @param skillRegistry
     *            the pre-built registry (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withSkillRegistry(SkillRegistry skillRegistry) {
        this.preBuiltSkillRegistry = skillRegistry;
        return this;
    }

    /**
     * Configures a registry of code-defined subagents (typically an
     * {@link at.aimon.core.subagent.InMemorySubagentRegistry} populated via
     * {@link at.aimon.core.subagent.Subagent#builder()}). When set, {@link #doCreate} composes it as the
     * highest-priority (authoritative) subagent layer — after the bundled and user {@code agents/*.md} registries — so
     * code-defined subagents cannot be shadowed by a same-named user markdown file. See
     * {@link #buildCompositeSubagentRegistry} for the intentional precedence asymmetry vs. skills/commands.
     *
     * <p>
     * When {@code null} (the default) no code layer is added and the composite registry behaves exactly as before.
     *
     * @param codeSubagentRegistry
     *            the code-defined subagent registry (may be {@code null} to clear the override)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withCodeSubagentRegistry(SubagentRegistry codeSubagentRegistry) {
        this.preBuiltCodeSubagentRegistry = codeSubagentRegistry;
        return this;
    }

    /**
     * Registers {@link ToolContextEnricher enrichers} that the executor invokes once per tool call to push
     * additional entries into the {@link at.aimon.core.agent.tool.ToolContext}. Enrichers must be thread-safe and
     * idempotent. Pass {@code null} to clear.
     *
     * @param enrichers
     *            the enrichers (may be {@code null} to clear)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withToolContextEnrichers(List<ToolContextEnricher> enrichers) {
        this.toolContextEnrichers = enrichers == null ? List.of() : List.copyOf(enrichers);
        return this;
    }

    /**
     * TCH-01: supplies the {@link VirtualShell} that command-executing tools run through, so an assembly can swap in a
     * sandboxed (Docker/Kubernetes) shell — or hand over the shell it already builds for the skill hooks, letting both
     * share one instance ({@code VirtualShell} implementations are required to be thread-safe).
     *
     * <p>
     * <b>Ownership follows creation.</b> A shell passed here belongs to the caller, which closes it; the created
     * runtime only borrows it. When this is left unset each context builds its own local shell instead and closes that
     * one on {@link OrcaAgentRuntime#close()}.
     *
     * @param shell
     *            the shell to share (may be {@code null} to fall back to a per-context local shell)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withShell(VirtualShell shell) {
        this.shell = shell;
        return this;
    }

    /**
     * Enables one per-context (agent-scoped) {@link WorkflowRunner} so the {@code Workflow} tool can
     * submit background runs (and a later CLI {@code /runs} command can inspect them). Off by default: when disabled no
     * runner (and no hosting pool) is built and the tool's background mode reports it is unavailable. The created
     * context owns the runner and closes it on {@link OrcaAgentRuntime#close()}.
     *
     * @param enabled
     *            whether to build a per-context workflow runner
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withWorkflowRunnerEnabled(boolean enabled) {
        this.workflowRunnerEnabled = enabled;
        return this;
    }

    /**
     * Subagent §5: overrides how each context's background-task {@link TaskOutputStore} is built from its
     * {@link VirtualFileSystem}. When {@code null} (the default) a {@link VfsTaskOutputStore} rooted in the context's
     * file system is used. Supply a factory to change the base directory or backend without touching {@link #doCreate}.
     *
     * @param factory
     *            builds the output store from the context's file system (may be {@code null} to restore the default)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withTaskOutputStoreFactory(Function<VirtualFileSystem, TaskOutputStore> factory) {
        this.taskOutputStoreFactory = factory;
        return this;
    }

    /**
     * Subagent §7: overrides how each context's resume {@link SessionSnapshotStore} is built from its
     * {@link VirtualFileSystem}. When {@code null} (the default) an in-memory, single-JVM store is used. Supply a
     * factory to plug in a shared-backend store; see {@link #withDistributedSnapshotStore()} for the common VFS choice.
     *
     * @param factory
     *            builds the snapshot store from the context's file system (may be {@code null} to restore the default)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withSessionSnapshotStoreFactory(
            Function<VirtualFileSystem, SessionSnapshotStore> factory) {
        this.sessionSnapshotStoreFactory = factory;
        return this;
    }

    /**
     * Subagent §7 convenience: switches the resume-snapshot store default from in-memory to
     * {@link VfsSessionSnapshotStore}, so a finished subagent's transcript is persisted to the context's file
     * system and any node backed by the same (GridFS/S3) file system can serve {@code Task(resume=<taskId>)}. This is
     * the scale-out bootstrap toggle for cross-node resume; equivalent to
     * {@code withSessionSnapshotStoreFactory(VfsSessionSnapshotStore::new)}.
     *
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withDistributedSnapshotStore() {
        this.sessionSnapshotStoreFactory = VfsSessionSnapshotStore::new;
        return this;
    }

    /**
     * Overrides how each context's {@link TaskResultStore} is built from its {@link VirtualFileSystem}. When
     * {@code null} (the default) an in-memory, single-JVM store is used. Supply a factory to plug in a shared-backend
     * store; see {@link #withDistributedTaskResultStore()} for the common VFS choice.
     *
     * @param factory
     *            builds the result store from the context's file system (may be {@code null} to restore the default)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withTaskResultStoreFactory(Function<VirtualFileSystem, TaskResultStore> factory) {
        this.taskResultStoreFactory = factory;
        return this;
    }

    /**
     * Switches the background-task result store default from in-memory to {@link VfsTaskResultStore}, so a finished
     * background subagent's result is persisted to the context's file system and any node backed by the same
     * (GridFS/S3) file system can serve {@code AgentOutput(taskId=...)}. This is the scale-out bootstrap toggle for
     * cross-node result collection; equivalent to {@code withTaskResultStoreFactory(VfsTaskResultStore::new)}.
     *
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withDistributedTaskResultStore() {
        this.taskResultStoreFactory = VfsTaskResultStore::new;
        return this;
    }

    /**
     * Overrides where each context's compaction circuit breaker counts consecutive AUTO-compaction failures. When
     * {@code null} (the default) every context gets its own {@link InMemoryCompactionFailureStore}, so the circuit
     * breaks per process — right for a single instance, and wrong for a session whose turns land on different nodes,
     * where three consecutive failures spread over three nodes look like one failure each and the breaker never trips.
     *
     * <p>
     * A scale-out bootstrap passes
     * {@code new SessionRecordCompactionFailureStore(sessionStore.records())} — the fenced view, so the counter is
     * written only while this node holds the session's lease. The instance is shared by every context this factory
     * builds; that is deliberate, since the state it reads and writes lives in the session record rather than in the
     * store object.
     *
     * @param failureStore
     *            the shared failure-counter storage (may be {@code null} to restore the per-context in-memory default)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withCompactionFailureStore(CompactionFailureStore failureStore) {
        this.compactionFailureStore = failureStore;
        return this;
    }

    /**
     * Design §6.3 — overrides how each per-context workflow runner's
     * {@link WorktreeEnvironmentFactory} is built from the context's {@link VirtualFileSystem}. When {@code null}
     * (the default) the built-in {@link WorktreeToolEnvironmentFactory} is used, which rebinds the file tools to a
     * branch-scoped filesystem view for {@code isolate=true} steps. Supply a factory to install an alternative
     * isolation strategy (e.g. Bash-inclusive isolation via a scoped working directory, or overlay read-through)
     * without touching {@link #buildWorkflowRunner}. Only consulted when
     * {@link #withWorkflowRunnerEnabled(boolean)} is on.
     *
     * @param factory
     *            builds the worktree environment factory from the context's file system (may be {@code null} to
     *            restore the default)
     * @return this factory (for chaining)
     */
    public OrcaAgentRuntimeFactory withWorktreeEnvironmentFactory(
            Function<VirtualFileSystem, WorktreeEnvironmentFactory> factory) {
        this.worktreeEnvironmentFactoryFactory = factory;
        return this;
    }

    /**
     * Builds the composite {@link SkillRegistry} layered as bundled (low priority) + user (high priority), matching
     * what {@link #doCreate} constructs internally. Exposed so AgentSetupFactory (or analogous bootstrap code) can
     * pre-build the registry and share the same instance with both the executor's pre-flight scanner and this
     * factory via {@link #withSkillRegistry(SkillRegistry)}.
     *
     * @param agentBundle
     *            the agent bundle (must not be null)
     * @param fileSystem
     *            the virtual filesystem (must not be null)
     * @param skillsDirectory
     *            the directory under {@code fileSystem} that holds user skill definitions (must not be null)
     * @return the composite skill registry (never null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public static SkillRegistry buildSkillRegistry(AgentBundle agentBundle, VirtualFileSystem fileSystem,
            String skillsDirectory) {
        Objects.requireNonNull(agentBundle, "agentBundle must not be null");
        Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        Objects.requireNonNull(skillsDirectory, "skillsDirectory must not be null");
        final SkillRegistry userSkillRegistry = new DefaultSkillRegistry(fileSystem, skillsDirectory);
        return buildCompositeSkillRegistry(agentBundle.getSkillRegistry(), userSkillRegistry);
    }

    /**
     * Same as {@link #buildSkillRegistry(AgentBundle, VirtualFileSystem, String)} but uses the supplied
     * {@link SkillParser} for the user-skill registry. Wire a parser built with a
     * {@link at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor DefaultShellActionExecutor} here when
     * user skills should be allowed to declare {@code shell} hook actions.
     *
     * <p>
     * The bundled-skill registry inside {@code agentBundle} is used as-is; if the bundle was loaded with a no-shell
     * parser (the default for {@link at.aimon.core.agent.impl.AdaptiveAgentBundleLoader}), bundle-side {@code shell}
     * hooks
     * will still fail at parse time. Inject the same shell-aware parser into the loader to lift that restriction.
     *
     * @param agentBundle
     *            the agent bundle (must not be null)
     * @param fileSystem
     *            the virtual filesystem (must not be null)
     * @param skillsDirectory
     *            the directory under {@code fileSystem} that holds user skill definitions (must not be null)
     * @param skillParser
     *            the parser used by the user-skill registry (must not be null)
     * @return the composite skill registry (never null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public static SkillRegistry buildSkillRegistry(AgentBundle agentBundle, VirtualFileSystem fileSystem,
            String skillsDirectory, SkillParser skillParser) {
        Objects.requireNonNull(agentBundle, "agentBundle must not be null");
        Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        Objects.requireNonNull(skillsDirectory, "skillsDirectory must not be null");
        Objects.requireNonNull(skillParser, "skillParser must not be null");
        final SkillRegistry userSkillRegistry = new DefaultSkillRegistry(fileSystem, skillsDirectory, skillParser);
        return buildCompositeSkillRegistry(agentBundle.getSkillRegistry(), userSkillRegistry);
    }

    /**
     * Builds a skill registry that <b>materializes</b> bundled (class path) skills onto {@code fileSystem} before
     * composing the layered registry, so that bundled skills' supplementary files (scripts, references, assets, and
     * arbitrary directories such as {@code templates/}) become real, agent-readable workspace files.
     *
     * <p>
     * The resulting composite is layered, lowest to highest priority:
     *
     * <ol>
     * <li>the bundle's own class path skill registry (if any) — a graceful fallback that still serves skill
     * <em>instructions</em> when materialization cannot enumerate the class path (for example an unsupported URL
     * protocol);
     * <li>a {@link at.aimon.core.skill.repository.VfsSkillRepository}-backed registry over
     * {@code bundledSkillsDirectory} — the materialized copy,
     * carrying a resolvable base directory and full file map. It shadows the class path layer for every skill that was
     * successfully materialized;
     * <li>a {@link at.aimon.core.skill.repository.VfsSkillRepository}-backed registry over {@code userSkillsDirectory}
     * — user-authored skills, which
     * override bundled skills of the same name.
     * </ol>
     *
     * <p>
     * Materialization is overwrite-on-bootstrap (see {@link BundledSkillMaterializer}); it is a no-op when no bundled
     * skills exist under {@code classpathSkillsBase}.
     *
     * @param agentBundle
     *            the agent bundle whose class path skill registry is retained as a fallback layer (must not be null)
     * @param fileSystem
     *            the virtual filesystem skills are materialized onto and read from (must not be null)
     * @param userSkillsDirectory
     *            the directory under {@code fileSystem} holding user skill definitions (must not be null)
     * @param bundledSkillsDirectory
     *            the directory under {@code fileSystem} that bundled skills are materialized into (must not be null)
     * @param classpathSkillsBase
     *            the class path base holding bundled skills, e.g. {@code "agents/default/skills"} (must not be null)
     * @param classLoader
     *            the class loader used to read bundled skill resources (must not be null)
     * @param skillParser
     *            the parser used by the VFS-backed registries (must not be null)
     * @return the composite skill registry (never null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public static SkillRegistry buildMaterializedSkillRegistry(AgentBundle agentBundle, VirtualFileSystem fileSystem,
            String userSkillsDirectory, String bundledSkillsDirectory, String classpathSkillsBase,
            ClassLoader classLoader, SkillParser skillParser) {
        Objects.requireNonNull(agentBundle, "agentBundle must not be null");
        Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        Objects.requireNonNull(userSkillsDirectory, "userSkillsDirectory must not be null");
        Objects.requireNonNull(bundledSkillsDirectory, "bundledSkillsDirectory must not be null");
        Objects.requireNonNull(classpathSkillsBase, "classpathSkillsBase must not be null");
        Objects.requireNonNull(classLoader, "classLoader must not be null");
        Objects.requireNonNull(skillParser, "skillParser must not be null");

        new BundledSkillMaterializer(classLoader).materialize(classpathSkillsBase, fileSystem, bundledSkillsDirectory);

        final List<SkillRegistry> layers = new ArrayList<>();
        agentBundle.getSkillRegistry().ifPresent(layers::add);
        layers.add(new DefaultSkillRegistry(fileSystem, bundledSkillsDirectory, skillParser));
        layers.add(new DefaultSkillRegistry(fileSystem, userSkillsDirectory, skillParser));
        return new CompositeSkillRegistry(layers);
    }

    /**
     * Creates an Orca agent runtime from an agent bundle.
     *
     * <p>
     * The bundle's subagent and skill registries (if present) are used as the lowest-priority layer, with user-defined
     * registries from the virtual file system as the highest-priority layer.
     *
     * @param agentRuntimeId
     *            the agent runtime ID
     * @param agentExecutor
     *            the agent executor
     * @param scheduledTaskManager
     *            the scheduled task manager (may be null)
     * @param agentBundle
     *            the agent bundle containing agent and optional registries
     * @param fileSystem
     *            the virtual file system
     * @param credentialStore
     *            the credential store for reference-based credential resolution (may be null)
     * @param toolProviders
     *            the tool providers to use for registering tools
     * @param commandProviders
     *            the command providers to use for registering commands
     * @return a new {@link OrcaAgentRuntime} instance
     * @throws NullPointerException
     *             if any required parameter is null
     */
    // Context creation requires the full irreducible collaborator set (parity with sibling create()/doCreate()).
    @SuppressWarnings("checkstyle:ParameterNumber")
    public OrcaAgentRuntime create(AgentRuntimeId agentRuntimeId, OrcaAgentExecutor agentExecutor,
            ScheduledTaskManager scheduledTaskManager, AgentBundle agentBundle, VirtualFileSystem fileSystem,
            CredentialStore credentialStore, List<OrcaToolProvider> toolProviders,
            List<OrcaCommandProvider> commandProviders) {
        return doCreate(agentRuntimeId, agentExecutor, scheduledTaskManager, agentBundle, fileSystem, credentialStore,
                toolProviders, commandProviders, null);
    }

    /**
     * Creates an Orca agent runtime with user-only registries (no bundled subagents/skills).
     *
     * <p>
     * This method is provided for backward compatibility when no agent bundle is available.
     *
     * @param agentRuntimeId
     *            the agent runtime ID
     * @param agentExecutor
     *            the agent executor
     * @param scheduledTaskManager
     *            the scheduled task manager (may be null)
     * @param agent
     *            the agent
     * @param fileSystem
     *            the virtual file system
     * @param credentialStore
     *            the credential store for reference-based credential resolution (may be null)
     * @param toolProviders
     *            the tool providers to use for registering tools
     * @param commandProviders
     *            the command providers to use for registering commands
     * @return a new {@link OrcaAgentRuntime} instance
     * @throws NullPointerException
     *             if any required parameter is null
     */
    // Context creation requires the full irreducible collaborator set (parity with sibling create()/doCreate()).
    @SuppressWarnings("checkstyle:ParameterNumber")
    public OrcaAgentRuntime create(AgentRuntimeId agentRuntimeId, OrcaAgentExecutor agentExecutor,
            ScheduledTaskManager scheduledTaskManager, Agent agent, VirtualFileSystem fileSystem,
            CredentialStore credentialStore, List<OrcaToolProvider> toolProviders,
            List<OrcaCommandProvider> commandProviders) {
        Objects.requireNonNull(agent, "agent must not be null");

        final AgentBundle bundle = AgentBundle.builder().agent(agent).build();
        return create(agentRuntimeId, agentExecutor, scheduledTaskManager, bundle, fileSystem, credentialStore,
                toolProviders, commandProviders);
    }

    /**
     * Creates an Orca agent runtime with MCP support from an agent bundle.
     *
     * <p>
     * Creates a AgentScoped {@link McpClientManager} internally and adds {@link OrcaMcpToolProvider} to the tool
     * providers. The McpClientManager is injected into the resulting context for lifecycle management (closed when the
     * context is closed).
     *
     * @param agentRuntimeId
     *            the agent runtime ID
     * @param agentExecutor
     *            the agent executor
     * @param scheduledTaskManager
     *            the scheduled task manager (may be null)
     * @param agentBundle
     *            the agent bundle containing agent and optional registries
     * @param fileSystem
     *            the virtual file system
     * @param credentialStore
     *            the credential store for reference-based credential resolution (may be null)
     * @param toolProviders
     *            the tool providers to use for registering tools
     * @param commandProviders
     *            the command providers to use for registering commands
     * @param mcpClientFactory
     *            the factory for creating MCP clients (ApplicationScoped)
     * @param mcpServerConfigProvider
     *            the provider of MCP server configurations (ApplicationScoped)
     * @return a new {@link OrcaAgentRuntime} instance with MCP support
     * @throws NullPointerException
     *             if any required parameter is null
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public OrcaAgentRuntime create(AgentRuntimeId agentRuntimeId, OrcaAgentExecutor agentExecutor,
            ScheduledTaskManager scheduledTaskManager, AgentBundle agentBundle, VirtualFileSystem fileSystem,
            CredentialStore credentialStore, List<OrcaToolProvider> toolProviders,
            List<OrcaCommandProvider> commandProviders, McpClientFactory mcpClientFactory,
            McpServerConfigProvider mcpServerConfigProvider) {
        Objects.requireNonNull(mcpClientFactory, "mcpClientFactory must not be null");
        Objects.requireNonNull(mcpServerConfigProvider, "mcpServerConfigProvider must not be null");

        // 1. Create AgentScoped McpClientManager
        final McpClientManager mcpClientManager = new McpClientManager(mcpClientFactory);

        try {
            // 2. Add MCP provider to tool providers
            List<OrcaToolProvider> allProviders = new ArrayList<>(toolProviders);
            allProviders.add(new OrcaMcpToolProvider(mcpServerConfigProvider, mcpClientManager));

            // 3. Delegate to internal create with mcpClientManager for lifecycle management
            return doCreate(agentRuntimeId, agentExecutor, scheduledTaskManager, agentBundle, fileSystem,
                    credentialStore, allProviders, commandProviders, mcpClientManager);
        } catch (Exception e) {
            // Clean up McpClientManager if context creation fails to prevent resource leak
            mcpClientManager.close();
            throw e;
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private OrcaAgentRuntime doCreate(AgentRuntimeId agentRuntimeId, OrcaAgentExecutor agentExecutor,
            ScheduledTaskManager scheduledTaskManager, AgentBundle agentBundle, VirtualFileSystem fileSystem,
            CredentialStore credentialStore, List<OrcaToolProvider> toolProviders,
            List<OrcaCommandProvider> commandProviders, McpClientManager mcpClientManager) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
        Objects.requireNonNull(agentExecutor, "agentExecutor must not be null");
        Objects.requireNonNull(agentBundle, "agentBundle must not be null");
        Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        Objects.requireNonNull(toolProviders, "toolProviders must not be null");
        Objects.requireNonNull(commandProviders, "commandProviders must not be null");

        final Agent agent = agentBundle.getAgent();
        final SubagentExecutionManager subagentExecutionManager = agentExecutor.getSubagentExecutionManager();
        final Environment environment = Environment.createWithWorkingDirectory(fileSystem.getWorkingDirectory());

        // Initialize registries
        final ToolRegistry toolRegistry = new DefaultToolRegistry();
        final HookRegistry hookRegistry = new DefaultHookRegistry();

        // Initialize subagent registry: bundled (low) < user (mid) < code (high, authoritative). The optional code
        // layer carries programmatically-defined subagents (withCodeSubagentRegistry) and is placed last so it wins.
        final SubagentRegistry userSubagentRegistry = new DefaultSubagentRegistry(fileSystem, agentsDirectory);
        final SubagentRegistry subagentRegistry = buildCompositeSubagentRegistry(agentBundle.getSubagentRegistry(),
                userSubagentRegistry, preBuiltCodeSubagentRegistry);

        // Initialize skill registry: bundled (low priority) + user (high priority). Built before the command registry
        // so user-invocable skills can be exposed as /<name> commands (SK-08-D). When a pre-built registry was
        // supplied via withSkillRegistry, reuse it so the executor's pre-flight scanner and this context observe
        // the same instance.
        final SkillRegistry skillRegistry = preBuiltSkillRegistry != null
                ? preBuiltSkillRegistry
                : buildSkillRegistry(agentBundle, fileSystem, skillsDirectory);

        // Initialize command registry: system > skill-backed. The legacy .aimon/commands directory is scanned for
        // migration enforcement (SK-08-F) — initialize() throws if any *.md files remain. Cross-source name conflicts
        // are rejected here too.
        final DefaultCommandRegistry commandRegistry = new DefaultCommandRegistry(List.<SystemCommand>of(),
                skillRegistry, fileSystem, commandsDirectory);
        commandRegistry.initialize();

        // Build conversation-compaction collaborators. The engine pulls the LlmClient and HookExecutionManager from
        // the agent executor so it shares the same plumbing as in-loop ReAct calls. Each context gets its own guard
        // since its per-session locks are node-local; where the failure counter that guard consults lives is the
        // bootstrap's choice — per process by default, on the session record when withCompactionFailureStore(...)
        // hands us a shared one.
        final CompactionEngine compactionEngine = DefaultCompactionEngine.withDefaults(agentExecutor.getLlmClient(),
                tokenEstimator, agentExecutor.getHookExecutionManager());
        final CompactionGuard compactionGuard = new DefaultCompactionGuard(compactionEngine, modelContextWindowRegistry,
                tokenEstimator, DefaultCompactionGuard.DEFAULT_MAX_CONSECUTIVE_FAILURES,
                DefaultCompactionGuard.DEFAULT_MAX_TRACKED_SESSIONS,
                compactionFailureStore != null ? compactionFailureStore : new InMemoryCompactionFailureStore());

        // Back background-subagent live output with a VFS-persisted segment log rooted in this context's file
        // system, so the AgentOutput tool can tail progress incrementally and (in a scale-out deployment) any node can
        // reconstruct the log from the shared file system. Best-effort: append/read failures degrade to a no-op sink.
        // Subagent §5: a bootstrap may override the store factory (e.g. a different base dir / backend); default VFS.
        final TaskOutputStore taskOutputStore = taskOutputStoreFactory != null
                ? taskOutputStoreFactory.apply(fileSystem)
                : new VfsTaskOutputStore(fileSystem);

        // Persist finished subagents' session snapshots so a later Task(resume=<taskId>) continues the prior
        // transcript. The default store is in-memory and agent-scoped (lives as long as this agent runtime), so
        // resume works within a single JVM. For cross-node resume (subagent §7), a scale-out bootstrap installs a
        // VFS store factory via withDistributedSnapshotStore()/withSessionSnapshotStoreFactory(...) — it persists
        // each snapshot as a single JSON object via the JsonSessionSnapshotCodec, so a GridFS/S3 backing file
        // system lets any node reload it. The default stays in-memory to avoid a serialization cost when scale-out off.
        final SessionSnapshotStore sessionSnapshotStore = sessionSnapshotStoreFactory != null
                ? sessionSnapshotStoreFactory.apply(fileSystem)
                : new InMemorySessionSnapshotStore();

        // Persist what each background task finally produced, so AgentOutput reads a store rather than a node-local
        // future — the task's result then outlives the execution that produced it and, with a shared file system,
        // is collectable from another node. Written before the terminal state transition, so a caller that sees a
        // task settle can always read why. Default in-memory and agent-scoped, matching the snapshot store above;
        // withDistributedTaskResultStore()/withTaskResultStoreFactory(...) swaps in the VFS-backed store.
        final TaskResultStore taskResultStore = taskResultStoreFactory != null
                ? taskResultStoreFactory.apply(fileSystem)
                : new InMemoryTaskResultStore();

        // Build shared provider dependencies once
        final OrcaProviderDependencies providerDependencies = OrcaProviderDependencies.builder()
                .subagentRegistry(subagentRegistry).subagentExecutionManager(subagentExecutionManager)
                .taskOutputStore(taskOutputStore).taskResultStore(taskResultStore)
                .sessionSnapshotStore(sessionSnapshotStore).skillRegistry(skillRegistry).toolRegistry(toolRegistry)
                .hookRegistry(hookRegistry).hookExecutionManager(agentExecutor.getHookExecutionManager())
                .scheduledTaskManager(scheduledTaskManager).credentialStore(credentialStore).environment(environment)
                .compactionEngine(compactionEngine).compactionGuard(compactionGuard)
                .pendingTurnRegistry(pendingTurnRegistry).agentApprovalStore(agentApprovalStore)
                .sessionApprovalStore(sessionApprovalStore).skillInvocationPolicy(skillInvocationPolicy)
                .rewakeService(rewakeService).build();

        // When enabled, build ONE per-context (agent-scoped) workflow runner so the Workflow tool can
        // submit background runs and the CLI /runs command can inspect them. It borrows the context's
        // registries/manager
        // via a base environment; the context owns it and closes it in close(). Disabled => null (no hosting pool
        // created). Background runs are fire-and-forget: they run under this base environment and do not carry the
        // invoking execution's principal or trace attribution.
        final WorkflowRunner workflowRunner = workflowRunnerEnabled
                ? buildWorkflowRunner(agentRuntimeId, agent, subagentRegistry, toolRegistry, hookRegistry, environment,
                        subagentExecutionManager, toolContextEnrichers, fileSystem)
                : null;

        // TCH-01: tools consume the shell through the context, never by constructing one (ArchUnit confines
        // at.aimon.core.shell.impl to the shell tree and the in-core assembler package that LocalShells lives in).
        // When the assembly supplied no shell we build the default here and hand it to the runtime as ownedShell, so
        // whoever created it is the one who closes it.
        final VirtualShell ownedShell = shell == null ? LocalShells.create() : null;
        final VirtualShell effectiveShell = shell != null ? shell : ownedShell;

        // Create tool provider context (null-safe: scheduledTaskManager may be null if scheduling is not configured)
        final OrcaToolProviderContext context = OrcaToolProviderContext.builder().fileSystem(fileSystem)
                .shell(effectiveShell).environment(environment).agent(agent).dependencies(providerDependencies)
                .toolContextEnrichers(toolContextEnrichers).workflowRunner(workflowRunner).build();

        // Register tools via providers
        for (OrcaToolProvider provider : toolProviders) {
            provider.registerTools(toolRegistry, context);
        }

        // Create command provider context
        final OrcaCommandProviderContext commandContext = OrcaCommandProviderContext.builder()
                .commandRegistry(commandRegistry).version(version).dependencies(providerDependencies).build();

        // Register commands via providers
        for (OrcaCommandProvider provider : commandProviders) {
            provider.registerCommands(commandRegistry, commandContext);
        }

        return OrcaAgentRuntime.builder().id(agentRuntimeId).agent(agent).toolRegistry(toolRegistry)
                .hookRegistry(hookRegistry).commandRegistry(commandRegistry).subagentRegistry(subagentRegistry)
                .skillRegistry(skillRegistry).fileSystem(fileSystem).environment(environment)
                .mcpClientManager(mcpClientManager).knowledgeStore(knowledgeStore).compactionEngine(compactionEngine)
                .compactionGuard(compactionGuard)
                // Wire the default prompt-too-long recovery strategy so a PromptTooLong error is recovered by
                // dropping the oldest droppable user message and retrying, instead of aborting the turn. The strategy
                // is stateless; the executor still falls back to NoOp when a context carries none.
                .promptSizeRecoveryStrategy(new DefaultPromptSizeRecoveryStrategy())
                .toolContextEnrichers(toolContextEnrichers).workflowRunner(workflowRunner).ownedShell(ownedShell)
                .build();
    }

    /**
     * Builds the per-context (agent-scoped) {@link WorkflowRunner}. It borrows this context's registries /
     * manager through a base {@link SubagentExecutionEnvironment} (never owning or closing them) and is configured with
     * an in-memory resume step cache; the run store and background hosting pool take their in-memory defaults. The
     * worktree isolation factory defaults to {@link WorktreeToolEnvironmentFactory} unless overridden via
     * {@link #withWorktreeEnvironmentFactory(Function)}.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private WorkflowRunner buildWorkflowRunner(AgentRuntimeId agentRuntimeId, Agent agent,
            SubagentRegistry subagentRegistry, ToolRegistry toolRegistry, HookRegistry hookRegistry,
            Environment environment, SubagentExecutionManager subagentExecutionManager,
            List<ToolContextEnricher> toolContextEnrichers, VirtualFileSystem fileSystem) {
        // No invokingSessionId here, deliberately: this runner is agent-scoped and outlives every session
        // that uses it, so there is no one session whose skill approvals it could inherit. The per-call runners
        // built inside WorkflowTool / GraalJsWorkflowTool do carry it, because those are built per invocation from the
        // calling execution's ToolContext.
        final SubagentExecutionEnvironment baseEnv = SubagentExecutionEnvironment.builder()
                .agentRuntimeId(agentRuntimeId).subagentRegistry(subagentRegistry).toolRegistry(toolRegistry)
                .hookRegistry(hookRegistry).environment(environment).defaultModel(agent.getMetadata().getModel())
                .toolContextEnrichers(toolContextEnrichers).build();
        // Wire the worktree environment factory so an `isolate=true` workflow step gets a per-branch scoped
        // filesystem view (it fails loud if a script requests isolation and none is wired). Isolation stays
        // opt-in per AgentTask; wiring the factory only makes it available. A bootstrap may swap the built-in
        // file-tool rebinding implementation via withWorktreeEnvironmentFactory(...) (design §4.3d/§4.3f).
        final WorktreeEnvironmentFactory worktreeFactory = worktreeEnvironmentFactoryFactory != null
                ? worktreeEnvironmentFactoryFactory.apply(fileSystem)
                : new WorktreeToolEnvironmentFactory(fileSystem);
        return WorkflowRunners.create(subagentExecutionManager, baseEnv, WorkflowRunnerOptions.builder()
                .stepResultCache(WorkflowRunners.inMemoryStepResultCache()).worktreeFactory(worktreeFactory).build());
    }

    /**
     * Builds the composite subagent registry layered as bundled (lowest) &lt; user &lt; code (highest).
     *
     * <p>
     * <b>Intentional asymmetry:</b> unlike skills and commands — where user definitions win — code-defined subagents
     * are authoritative and CANNOT be shadowed by a same-named user {@code agents/*.md} file. This protects the curated
     * {@code AllowedTool} allow-lists of built-in code subagents from being widened by user markdown. Code is therefore
     * placed LAST in the layer list; {@link CompositeSubagentRegistry} resolves later layers with higher priority.
     *
     * <p>
     * {@code protected} and non-static so subclasses can override the composition (e.g. to inject additional layers)
     * and so the instance-scoped code layer can participate.
     *
     * @param bundledRegistry
     *            the bundle-provided registry (lowest priority, optional)
     * @param userRegistry
     *            the user {@code agents/*.md} registry (never null; guarantees the composite is non-empty)
     * @param codeRegistry
     *            the code-defined registry (highest priority; may be {@code null} when none is configured)
     * @return the composite registry (never null)
     */
    protected SubagentRegistry buildCompositeSubagentRegistry(Optional<SubagentRegistry> bundledRegistry,
            SubagentRegistry userRegistry, SubagentRegistry codeRegistry) {
        final List<SubagentRegistry> layers = new ArrayList<>();
        bundledRegistry.ifPresent(layers::add);
        layers.add(userRegistry);
        if (codeRegistry != null) {
            layers.add(codeRegistry);
        }
        return new CompositeSubagentRegistry(layers);
    }

    private static SkillRegistry buildCompositeSkillRegistry(Optional<SkillRegistry> bundledRegistry,
            SkillRegistry userRegistry) {
        final List<SkillRegistry> layers = new ArrayList<>();
        bundledRegistry.ifPresent(layers::add);
        layers.add(userRegistry);
        return new CompositeSkillRegistry(layers);
    }

}
