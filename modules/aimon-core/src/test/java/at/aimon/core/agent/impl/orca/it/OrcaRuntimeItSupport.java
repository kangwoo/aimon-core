package at.aimon.core.agent.impl.orca.it;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.cost.CostEstimator;
import at.aimon.core.mcp.McpClientFactory;
import at.aimon.core.mcp.McpServerConfigProvider;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * Wiring for the {@code OrcaAgentRuntime} integration suite.
 *
 * <p>
 * <b>What makes this an integration harness and not another unit fixture:</b> it builds the runtime through the
 * <b>real</b> {@link OrcaAgentRuntimeFactory} with {@link OrcaAgentRuntimeFactory#defaultToolProviders() the real
 * default providers}, so the tools under test are the production {@code Read}/{@code Write}/{@code Edit}/{@code Grep}/
 * {@code Bash}/{@code TodoWrite}/{@code Task}/{@code Skill} instances over a real {@link LocalFileSystem}. The sibling
 * {@code OrcaAgentExecutorTestSupport} deliberately does the opposite — it hand-assembles
 * {@code OrcaAgentRuntime.builder()} with purpose-built fake tools to isolate executor mechanics. Both are wanted; only
 * this one can catch an assembly regression (a provider that stops registering, a registry that stops being wired, a
 * collaborator that goes null).
 *
 * <p>
 * Only the {@link LlmClient} is a double. Everything else — registries, tool implementations, command registry,
 * compaction collaborators, transcript store — is the production type.
 *
 * <h2>Isolation</h2>
 *
 * <p>
 * Each {@code Node} owns its own {@link LocalFileSystem} rooted at a caller-supplied directory, its own executor, and
 * its own transcript store, so two nodes built from one {@code @TempDir} share nothing but the temp root. That is what
 * makes the multi-runtime isolation tests meaningful: any state they observe crossing between nodes crossed through
 * production code, not through the harness.
 *
 * <p>
 * One option deliberately breaks that separation — {@link Options#shareSubagentManagerWith(Node)} — because for the
 * background-task control plane the separated shape is the <b>unfaithful</b> one: production gives every runtime the
 * same manager, so total separation would make a cross-agent assertion pass for a reason that has nothing to do with
 * the guard under test. It is opt-in, and no other collaborator is shareable.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * {@link #close()} closes every runtime this support created and then the executors' subagent managers. Call it from
 * {@code @AfterEach} — it tears everything down and then rethrows any teardown failure, so a runtime that cannot close
 * fails the test that used it rather than the next one to run. Per the scope model, closing a runtime must not close
 * application-scoped collaborators — the harness owns the managers it built, so it is the one that closes them.
 */
final class OrcaRuntimeItSupport implements AutoCloseable {

    /** Iteration ceiling for harness-built agents — high enough for multi-step scripts, low enough to fail fast. */
    static final int MAX_ITERATIONS = 12;

    private final Path baseDir;
    private final List<Node> nodes = new ArrayList<>();

    OrcaRuntimeItSupport(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir must not be null");
    }

    /**
     * Builds a runtime + executor pair rooted at {@code <baseDir>/<name>}, using the real factory and the real default
     * providers. Distinct names yield fully disjoint agents (distinct {@link AgentRuntimeId}, distinct file system
     * root, distinct transcript store).
     */
    Node newNode(String name, ScriptedLlmClient llmClient) {
        return newNode(name, llmClient, options());
    }

    /** Options for a node that needs more than the default assembly — see {@link Options}. */
    static Options options() {
        return new Options();
    }

    /**
     * Builds a node with extra assembly options.
     *
     * <p>
     * The default providers are always included; {@link Options#extraToolProvider(OrcaToolProvider) extra providers}
     * are appended, never substituted. That keeps every node's production tool set intact — a test that needs to
     * observe something the production tools do not expose (the tool context, say) adds a probe alongside them rather
     * than swapping one out.
     */
    Node newNode(String name, ScriptedLlmClient llmClient, Options options) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(llmClient, "llmClient must not be null");
        Objects.requireNonNull(options, "options must not be null");

        final Path root = baseDir.resolve(name);
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(root.toString()));
        fileSystem.initialize();

        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(llmClient);
        // Borrowed when a test opted into a shared control plane, owned otherwise. Options#shareSubagentManagerWith
        // argues why sharing is sometimes the faithful thing to do.
        final boolean ownsSubagentManager = options.sharedSubagentManager == null;
        final DefaultSubagentExecutionManager subagentManager = ownsSubagentManager
                ? new DefaultSubagentExecutionManager(llmClient, toolManager, hookManager)
                : options.sharedSubagentManager;
        final InMemorySessionRecordStore recordStore = new InMemorySessionRecordStore();
        final TranscriptManager transcriptManager = new DefaultTranscriptManager(recordStore);

        // The builder rather than the six-arg constructor, and it is the same assembly: OrcaAgentExecutor.Builder
        // #llmClient wraps the client in LlmCallGateway.withDefaultRetry, which is exactly what that constructor
        // delegates to. The builder is used only because it is the one path that can supply a CostEstimator, and a
        // null one leaves the executor on its CostEstimator.NOOP default.
        final OrcaAgentExecutor executor = OrcaAgentExecutor.builder().llmClient(llmClient)
                .transcriptManager(transcriptManager).toolExecutionManager(toolManager)
                .hookExecutionManager(hookManager).commandExecutionManager(commandManager)
                .subagentExecutionManager(subagentManager).costEstimator(options.costEstimator).build();

        final Agent agent = DefaultAgent.builder().name(name).maxIterations(options.maxIterations)
                .systemPrompt("You are " + name + ", an integration-test agent.").build();

        final List<OrcaToolProvider> toolProviders = new ArrayList<>(OrcaAgentRuntimeFactory.defaultToolProviders());
        toolProviders.addAll(options.extraToolProviders);

        // The no-arg factory constructor delegates to this five-arg one with a null store; naming the defaults here is
        // what lets a test opt a knowledge store in without changing any other part of the assembly.
        final OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory("1.0.0", ".aimon/commands", ".aimon/agents",
                ".aimon/skills", options.knowledgeStore);
        if (options.codeSubagentRegistry != null) {
            factory.withCodeSubagentRegistry(options.codeSubagentRegistry);
        }
        if (options.skillInvocationPolicy != null) {
            factory.withSkillInvocationPolicy(options.skillInvocationPolicy);
        }
        factory.withWorkflowRunnerEnabled(options.workflowRunnerEnabled);

        // scheduledTaskManager and credentialStore are null on purpose: neither is required to assemble a runtime, and
        // leaving them out proves the factory's null-safe paths (the scheduling provider skips registration) stay
        // null-safe. A scheduling-specific IT can pass a real manager.
        final OrcaAgentRuntime runtime = options.mcpClientFactory == null
                ? factory.create(AgentRuntimeId.from(agent), executor, null, agent, fileSystem, null, toolProviders,
                        OrcaAgentRuntimeFactory.defaultCommandProviders())
                // The MCP-aware overload takes an AgentBundle rather than a bare Agent, and it is the only path that
                // builds an McpClientManager — which is precisely what makes it the positive control for the runtime's
                // "no MCP factory, no manager" default.
                : factory.create(AgentRuntimeId.from(agent), executor, null, AgentBundle.builder().agent(agent).build(),
                        fileSystem, null, toolProviders, OrcaAgentRuntimeFactory.defaultCommandProviders(),
                        options.mcpClientFactory, options.mcpServerConfigProvider);

        final Node node = new Node(runtime, executor, fileSystem, subagentManager, ownsSubagentManager, recordStore,
                hookManager, root);
        nodes.add(node);
        return node;
    }

    /**
     * Writes a markdown subagent definition to {@code <baseDir>/<nodeName>/.aimon/agents/<subagentName>.md}.
     *
     * <p>
     * IMPORTANT: call this <b>before</b> {@link #newNode}. {@code DefaultSubagentRegistry} loads every definition in
     * its constructor, so a file written afterwards is invisible until something calls {@code reloadAll()}. Skills do
     * not have this constraint ({@code DefaultSkillRegistry} loads on first lookup), but seeding both up front keeps
     * the tests uniform.
     */
    void seedSubagent(String nodeName, String subagentName, String markdown) {
        seedFile(nodeName, ".aimon/agents/" + subagentName + ".md", markdown);
    }

    /** Writes a skill to {@code <baseDir>/<nodeName>/.aimon/skills/<skillName>/SKILL.md}. */
    void seedSkill(String nodeName, String skillName, String markdown) {
        seedFile(nodeName, ".aimon/skills/" + skillName + "/SKILL.md", markdown);
    }

    /**
     * Seeds a file under a node's root before the node exists. Raw {@code java.nio} on purpose — the node's
     * {@code VirtualFileSystem} has not been built yet, and this is fixture setup rather than behaviour under test.
     */
    private void seedFile(String nodeName, String relativePath, String content) {
        final Path target = baseDir.resolve(nodeName).resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to seed " + target, e);
        }
    }

    /** A single node with the conventional name — for tests that only need one agent. */
    Node newNode(ScriptedLlmClient llmClient) {
        return newNode("agent-a", llmClient);
    }

    /**
     * Closes every node this support created, then reports.
     *
     * <p>
     * A failure in one node's teardown does not stop the others — stranding a file system or a subagent pool would leak
     * into the next test. But it is not swallowed either: the first failure is rethrown once everything is closed, with
     * any later ones attached as suppressed. Recording it on the node instead (as this used to) meant a runtime whose
     * {@code close()} threw produced a green test unless someone remembered to assert on the recorded field.
     */
    @Override
    public void close() {
        RuntimeException failure = null;
        // Reverse order so a node built later (and possibly borrowing from an earlier one) tears down first.
        for (int i = nodes.size() - 1; i >= 0; i--) {
            final Node node = nodes.get(i);
            // Handles before the runtime they are bound to: closing a live session fires OnSessionEnd, which reads the
            // runtime's hook registry and environment. Closing the runtime first would fire that hook against a torn
            // down context.
            for (final DefaultLiveSession session : node.liveSessions) {
                try {
                    session.close();
                } catch (RuntimeException e) {
                    failure = record(failure, e);
                }
            }
            try {
                node.runtime.close();
            } catch (RuntimeException e) {
                failure = record(failure, e);
            }
            // Borrowed managers belong to the node that built them, which closes them on its own pass. Closing one
            // twice would shut the pool down while the owner's teardown assertions still need it.
            if (node.ownsSubagentManager) {
                try {
                    node.subagentManager.close();
                } catch (RuntimeException e) {
                    failure = record(failure, e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException record(RuntimeException first, RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /** An assembled agent: the runtime under test plus the collaborators a test needs to drive and observe it. */
    static final class Node {

        private final OrcaAgentRuntime runtime;
        private final OrcaAgentExecutor executor;
        private final LocalFileSystem fileSystem;
        private final DefaultSubagentExecutionManager subagentManager;
        private final boolean ownsSubagentManager;
        private final InMemorySessionRecordStore recordStore;
        private final DefaultHookExecutionManager hookManager;
        private final List<DefaultLiveSession> liveSessions = new ArrayList<>();
        private final Path root;

        Node(OrcaAgentRuntime runtime, OrcaAgentExecutor executor, LocalFileSystem fileSystem,
                DefaultSubagentExecutionManager subagentManager, boolean ownsSubagentManager,
                InMemorySessionRecordStore recordStore, DefaultHookExecutionManager hookManager, Path root) {
            this.runtime = runtime;
            this.executor = executor;
            this.fileSystem = fileSystem;
            this.subagentManager = subagentManager;
            this.ownsSubagentManager = ownsSubagentManager;
            this.recordStore = recordStore;
            this.hookManager = hookManager;
            this.root = root;
        }

        OrcaAgentRuntime runtime() {
            return runtime;
        }

        /**
         * The store behind this node's {@code TranscriptManager} — the session-scoped state that outlives a turn.
         *
         * <p>
         * Exposed because a turn's own {@code OrcaAgentExecutionResult} cannot show whether anything was
         * <em>persisted</em>: it reports what that turn did, which is identical whether the record was written or
         * dropped. Only the store distinguishes them.
         */
        InMemorySessionRecordStore recordStore() {
            return recordStore;
        }

        /**
         * This node's background-task control plane — the same instance its {@code Task} / {@code AgentOutput} /
         * {@code TaskStop} tools were handed.
         *
         * <p>
         * Exists so a test can observe a task's <em>terminal state</em>, which no tool exposes directly: a stop is
         * asynchronous, and the tool that requested it returns as soon as the request is filed.
         */
        DefaultSubagentExecutionManager subagentManager() {
            return subagentManager;
        }

        /**
         * The on-disk root of this node's virtual file system — for asserting that a write landed here and only here.
         */
        Path root() {
            return root;
        }

        /** Runs one turn for {@code sessionId}. */
        OrcaAgentExecutionResult run(SessionId sessionId, String userInput) {
            return run(sessionId, userInput, null);
        }

        /**
         * Runs one turn for {@code sessionId} under {@code budget} (null means unbounded, as the executor's default).
         *
         * <p>
         * The compaction thresholds the runtime derives from {@code ModelContextLimits} are not overridable — the
         * factory keeps its token estimator and context-window registry private with no setter. A per-turn budget is
         * therefore the only way an integration test can reach the compaction path without generating a genuine
         * 95k-token transcript.
         */
        OrcaAgentExecutionResult run(SessionId sessionId, String userInput, ExecutionBudget budget) {
            return run(sessionId, userInput, budget, null);
        }

        /**
         * Runs one turn, handing {@code interruptObserver} the turn's {@link InterruptCoordinator} at loop entry.
         *
         * <p>
         * This is the only way an integration test can interrupt a turn. {@code LiveSession.interrupt(TurnId,
         * InterruptReason)} is the production entry point, but it is a <b>layer above</b> this harness — a live session
         * holds the coordinator of whatever turn is currently active and forwards to it. The harness drives the
         * executor
         * directly, so it takes the same coordinator from the request builder's observer hook, which is what the live
         * session itself ultimately trips.
         *
         * <p>
         * The observer runs on the <b>executing thread</b> before the loop starts, so it must not block. Capture the
         * coordinator and interrupt it from elsewhere.
         */
        OrcaAgentExecutionResult run(SessionId sessionId, String userInput, ExecutionBudget budget,
                Consumer<InterruptCoordinator> interruptObserver) {
            return executor.execute(runtime, OrcaAgentExecutionRequest.builder().userInput(userInput)
                    .sessionId(sessionId).budget(budget).interruptObserver(interruptObserver).build());
        }

        /** The hooks this runtime fires — register on it to observe or veto what a turn does. */
        HookRegistry hookRegistry() {
            return runtime.getHookRegistry();
        }

        /** Opens a live session on this node with unbounded default options. */
        DefaultLiveSession openLiveSession(SessionId sessionId) {
            return openLiveSession(sessionId, LiveSessionOptions.defaults());
        }

        /**
         * Opens a {@link DefaultLiveSession} over this node's runtime and executor — the production handle, assembled
         * the way {@code LiveSessionOpener} assembles it.
         *
         * <p>
         * This is the layer {@link #run} cannot reach. Driving the executor directly is right for anything scoped to a
         * single turn, but three things live <b>only</b> on the handle and are invisible from a turn's result:
         * {@link DefaultLiveSession#interrupt(at.aimon.core.agent.session.TurnId,
         * at.aimon.core.agent.interrupt.InterruptReason) turn-targeted interruption} (the executor has no notion of
         * which turn a coordinator belongs to),
         * {@code OnSessionStart} / {@code OnSessionEnd} hooks (fired by the handle's constructor and {@code close()},
         * never by the executor), and the write-back of {@code SessionTotals} / {@code budgetOverride} into the
         * {@link SessionRecordStore} at end of turn. Tests for those must go through here.
         *
         * <p>
         * The full seven-arg constructor is used with a null {@code MessageQueueManager}: with no queue wired, a
         * second {@code offerAsync} on a busy session falls through to a concurrent {@code submitAsync} instead of
         * queueing, which keeps a test that submits twice honest about what it is exercising. Everything else is real
         * — this node's runtime, its executor, its hook execution manager and its record store.
         *
         * <p>
         * Opened sessions are closed by {@link OrcaRuntimeItSupport#close()} before their runtime is. A test may close
         * one early to observe {@code OnSessionEnd}; {@code close()} is idempotent, so the teardown pass is harmless.
         */
        DefaultLiveSession openLiveSession(SessionId sessionId, LiveSessionOptions options) {
            Objects.requireNonNull(sessionId, "sessionId must not be null");
            Objects.requireNonNull(options, "options must not be null");
            final DefaultLiveSession session = new DefaultLiveSession(sessionId, runtime, executor, options, null,
                    hookManager, recordStore);
            liveSessions.add(session);
            return session;
        }

        /** Runs one turn for a fresh session — for tests that do not care about the id. */
        OrcaAgentExecutionResult run(String userInput) {
            return run(newSession(), userInput);
        }

        /** Seeds a file into this node's virtual file system (production {@code VirtualFileSystem} API, not raw IO). */
        void writeFile(String path, String content) {
            fileSystem.write(path, content);
        }

        /** Reads a file back through the virtual file system. */
        String readFile(String path) {
            try (InputStream in = fileSystem.read(path)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("failed to read " + path, e);
            }
        }

        boolean fileExists(String path) {
            return fileSystem.exists(path);
        }

        /** Names of every tool the assembled runtime registered. */
        List<String> toolNames() {
            return runtime.getAvailableTools().stream().map(t -> t.getDefinition().getName()).sorted().toList();
        }
    }

    /**
     * Assembly options for a node. Every option that touches tools is <b>additive</b> — none can remove or replace a
     * production provider, so a test that opts in still exercises the real tool set. The one option that is not
     * additive, {@link #shareSubagentManagerWith(Node)}, changes which instance a collaborator resolves to rather than
     * what is registered, and argues its case in its own javadoc.
     */
    static final class Options {

        private final List<OrcaToolProvider> extraToolProviders = new ArrayList<>();
        private SubagentRegistry codeSubagentRegistry;
        private KnowledgeStore knowledgeStore;
        private McpClientFactory mcpClientFactory;
        private McpServerConfigProvider mcpServerConfigProvider;
        private boolean workflowRunnerEnabled;
        private DefaultSubagentExecutionManager sharedSubagentManager;
        private SkillInvocationPolicy skillInvocationPolicy;
        private CostEstimator costEstimator;
        private int maxIterations = MAX_ITERATIONS;

        /** Appends a provider after the default ones. Use for probes that observe state production tools do not. */
        Options extraToolProvider(OrcaToolProvider provider) {
            extraToolProviders.add(Objects.requireNonNull(provider, "provider must not be null"));
            return this;
        }

        /**
         * Registers code-defined subagents as the authoritative layer — the same wiring a bootstrap uses. Prefer this
         * over markdown seeding when the test does not care about parsing.
         */
        Options codeSubagents(SubagentRegistry registry) {
            this.codeSubagentRegistry = Objects.requireNonNull(registry, "registry must not be null");
            return this;
        }

        /**
         * Supplies the knowledge store a bootstrap would inject. Exists so the assembly suite can prove the default
         * absence is caused by the bootstrap staying silent, not by a getter that can only ever return empty.
         */
        Options knowledgeStore(KnowledgeStore store) {
            this.knowledgeStore = Objects.requireNonNull(store, "store must not be null");
            return this;
        }

        /**
         * Routes assembly through the MCP-aware {@code create} overload, which is the only one that builds an
         * {@code McpClientManager}. Same purpose as {@link #knowledgeStore}: a control for the default absence.
         */
        Options mcp(McpClientFactory clientFactory, McpServerConfigProvider configProvider) {
            this.mcpClientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
            this.mcpServerConfigProvider = Objects.requireNonNull(configProvider, "configProvider must not be null");
            return this;
        }

        /** Opts the per-context {@code WorkflowRunner} in — off by default, exactly as in production. */
        Options workflowRunnerEnabled(boolean enabled) {
            this.workflowRunnerEnabled = enabled;
            return this;
        }

        /**
         * Replaces the factory's default {@code AlwaysAllowSkillInvocationPolicy}.
         *
         * <p>
         * The default is what makes skill approval untestable: it says yes to everything, so a suite running under it
         * cannot tell an approval mechanism that works from one that was deleted. This is the <b>only</b> option that
         * substitutes a production collaborator rather than adding to one, and it is narrow on purpose — the policy is
         * the single seam the factory already exposes for exactly this
         * ({@code OrcaAgentRuntimeFactory#withSkillInvocationPolicy}), and everything downstream of it stays real.
         */
        Options skillInvocationPolicy(SkillInvocationPolicy policy) {
            this.skillInvocationPolicy = Objects.requireNonNull(policy, "policy must not be null");
            return this;
        }

        /**
         * Prices the executor's LLM calls, turning the {@code maxCostUsd} budget dimension on.
         *
         * <p>
         * Without this the executor keeps {@code CostEstimator.NOOP}, every call is priced at zero, and
         * {@code COST_BUDGET_EXCEEDED} is <b>unreachable</b> — the accumulated cost never rises and
         * {@code ExecutionBudget.Builder#maxCostUsd} rejects a zero ceiling, so no budget can be written that a
         * zero total would cross. That is the production default and a test pins it; this option is what gives that
         * test a counterpart which actually reaches the stop.
         */
        Options costEstimator(CostEstimator costEstimator) {
            this.costEstimator = Objects.requireNonNull(costEstimator, "costEstimator must not be null");
            return this;
        }

        /**
         * Lowers (or raises) this agent's iteration ceiling from the harness default of {@link #MAX_ITERATIONS}.
         *
         * <p>
         * Exists for the tests that must actually <em>reach</em> the ceiling. Driving a turn into the default 12 would
         * mean scripting 12 tool calls to assert one thing; a ceiling of 2 reaches the same branch in the same way and
         * keeps the script readable.
         */
        Options maxIterations(int maxIterations) {
            if (maxIterations < 1) {
                throw new IllegalArgumentException("maxIterations must be >= 1, got " + maxIterations);
            }
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Builds this node on {@code other}'s subagent execution manager instead of its own, so both nodes' background
         * tasks live in <b>one</b> store and one control plane.
         *
         * <p>
         * This is the single deliberate exception to the harness's default separation, and it exists because the
         * default hides a production guarantee rather than testing it. Each node otherwise gets a fresh
         * {@code DefaultSubagentExecutionManager}, so a foreign task id is unknown to the queried node for the trivial
         * reason that its store is empty — an assertion that would still pass with every ownership check deleted from
         * {@code AgentOutputTool} and {@code TaskStopTool}. Production is the shared shape:
         * {@code OrcaAgentRuntimeManager} hands one {@code OrcaAgentExecutor} — and therefore one subagent manager and
         * one {@code BackgroundTaskStore} — to every runtime it creates, which is precisely the configuration where
         * {@code ScopedSubagentTaskController}'s {@link AgentRuntimeId}-keyed check is the only barrier between two
         * agents.
         *
         * <p>
         * The borrowing node does not close the manager; the node that built it does (see {@link #close()}).
         */
        Options shareSubagentManagerWith(Node other) {
            Objects.requireNonNull(other, "other must not be null");
            this.sharedSubagentManager = other.subagentManager();
            return this;
        }
    }

    /**
     * A session id that is unique per call and readable in failure output. {@code SessionId.generate()} would do, but a
     * labelled id makes a routing mistake in {@link ScriptedLlmClient} obvious in the assertion message.
     */
    static SessionId newSession() {
        return SessionId.of("it-" + UUID.randomUUID());
    }

    /** A labelled session id — use when a test scripts responses per session and must name them. */
    static SessionId session(String label) {
        return SessionId.of(label);
    }
}
