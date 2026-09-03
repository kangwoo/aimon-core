package at.aimon.cli.factory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackBuilder;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.bootstrap.TeardownPhase;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.ExecutorSpec;
import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.bootstrap.spec.SchedulingSpec;
import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.bootstrap.spec.SkillApprovalSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.cli.config.AgentConfig;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.McpConfig;
import at.aimon.cli.config.MemoryConfig;
import at.aimon.cli.config.MemoryDreamerConfig;
import at.aimon.cli.hook.SubagentLaunchDisplayHook;
import at.aimon.cli.hook.SubagentResultDisplayHook;
import at.aimon.cli.hook.ToolCallDisplayHook;
import at.aimon.cli.repl.OutputFormatter;
import at.aimon.cli.scheduling.ScheduledTaskEventDisplayListener;
import at.aimon.cli.skill.InteractiveSkillApprovalChannel;
import at.aimon.cli.tool.GraalJsWorkflowToolProvider;
import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser;
import at.aimon.core.agent.impl.AdaptiveAgentBundleLoader;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.AgentBundleLoader;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.impl.orca.environment.WorktreeToolEnvironmentFactory;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.core.config.hook.HookHotReloadBootstrap;
import at.aimon.core.config.hook.ReloadInvoker;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.knowledge.embedding.EmbeddingClient;
import at.aimon.core.knowledge.wiki.ContextResolvingWikiStorageLocator;
import at.aimon.core.knowledge.wiki.DefaultWikiKnowledgeBase;
import at.aimon.core.knowledge.wiki.LlmWikiPageGenerator;
import at.aimon.core.knowledge.wiki.WikiKnowledgeStore;
import at.aimon.core.knowledge.wiki.WikiPageGenerator;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llms.openai.OpenAIEmbeddingClient;
import at.aimon.core.llms.openai.OpenAIEmbeddingConfig;
import at.aimon.core.mcp.DefaultMcpClientFactory;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.InMemoryRepresentationStore;
import at.aimon.core.memory.InMemoryWorkspaceStore;
import at.aimon.core.memory.MemoryIngestMode;
import at.aimon.core.memory.MemoryInjectionMode;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerMemory;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.StoreBackedPeerMemory;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.WorkspaceStore;
import at.aimon.core.memory.deriver.DerivationQueueManager;
import at.aimon.core.memory.deriver.DerivationTask;
import at.aimon.core.memory.deriver.Deriver;
import at.aimon.core.memory.deriver.DeriverProperties;
import at.aimon.core.memory.deriver.InMemoryDerivationQueueManager;
import at.aimon.core.memory.deriver.LlmDeriver;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.LlmDialecticEngine;
import at.aimon.core.memory.dreamer.DefaultDreamerEngine;
import at.aimon.core.memory.dreamer.DreamerEngine;
import at.aimon.core.memory.dreamer.EmbeddingSurprisalScorer;
import at.aimon.core.memory.dreamer.LlmJudgeSurprisalScorer;
import at.aimon.core.memory.dreamer.RandomWalkDreamer;
import at.aimon.core.memory.dreamer.SurprisalScorer;
import at.aimon.core.memory.reconciler.DefaultReconciler;
import at.aimon.core.memory.reconciler.Reconciler;
import at.aimon.core.memory.redaction.DefaultRedactionPolicy;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.scheduling.SchedulingEngine;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillHookSetParser;
import at.aimon.core.skill.parser.SkillParser;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.pending.PendingTurn;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.render.ShellArgumentTokenizer;
import at.aimon.core.tools.console.ConsoleOutputTool;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanRedactor;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.TraceSpanStore;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import at.aimon.core.tracing.impl.TracingLlmClient;
import at.aimon.memory.file.Compactable;
import at.aimon.memory.file.FileMemoryMaintenanceScheduler;
import at.aimon.memory.file.FileObservationStore;
import at.aimon.memory.file.FileRepresentationStore;
import at.aimon.scheduling.quartz.dreamer.DreamerJobRegistrar;
import at.aimon.workflow.graaljs.GraalJsEngineHolder;

public class AgentSetupFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentSetupFactory.class);

    private static final String DEFAULT_AGENT_NAME = "default";

    /**
     * The single session the REPL runs in. A CLI process is one conversation, so the id is fixed rather than
     * generated — the same value on every run is what lets an in-memory session's peer-memory records line up with
     * the enrichers that stamped them.
     */
    private static final SessionId DEFAULT_SESSION_ID = SessionId.of("default");

    /**
     * Disambiguates dreamer scheduler instance names within one JVM. Quartz keys its
     * {@code SchedulerRepository} by instance name, so two schedulers sharing a name in one process collide; a counter
     * says that plainly, where the wall-clock nonce it replaced read like a cluster-node id and was not one.
     */
    private static final AtomicInteger DREAMER_SCHEDULER_SEQ = new AtomicInteger();

    /**
     * Wrapper class that contains AgentExecutor along with its default tools, config, and transcript buffer.
     *
     * <p>
     * Exposes concrete Orca types directly since this is an application-level module (not a library). This avoids
     * unsafe casts in downstream code like ReplSession.
     *
     * <h2>Ownership</h2>
     *
     * <p>
     * Every closeable this setup is responsible for lives on the {@link AimonStack}'s teardown plan — the ones the
     * stack assembled itself, and the CLI-owned ones {@link AgentSetupFactory#create(CliConfig)} enrolled through
     * {@link AimonStack#own(TeardownPhase, String, AutoCloseable)}. {@link #close()} is therefore a delegation
     * rather than a hand-maintained shutdown sequence, and the order it runs in is
     * {@link TeardownPhase}'s, not this file's. Adding a collaborator that holds a thread or a native handle means
     * enrolling it there; adding a field here does nothing.
     */
    public static final class AgentSetup implements AutoCloseable {
        // Null only for test-built setups, which wire a few fields directly and never call close().
        private final AimonStack stack;
        private final OrcaAgentExecutor agentExecutor;
        private final Agent agent;
        private final OrcaAgentRuntime agentRuntime;
        private final OutputFormatter outputFormatter;
        private final LocalFileSystem fileSystem;
        private final SchedulingEngine schedulingEngine;
        private final MessageQueueManager messageQueueManager;
        private final LiveSession liveSession;
        private final PendingTurnRegistry pendingTurnRegistry;
        private final InteractiveSkillApprovalChannel skillApprovalChannel;
        // TRACE-01: the in-memory span store when tracing is enabled (cli.tracing), else null. The `/trace` REPL
        // command reads it to render the most recent turn's span tree.
        private final TraceSpanStore traceSpanStore;

        /** AgentSetup을 생성한다. */
        private AgentSetup(Builder builder) {
            this.stack = builder.stack;
            this.agentExecutor = builder.agentExecutor;
            this.agent = builder.agent;
            this.agentRuntime = builder.agentRuntime;
            this.outputFormatter = builder.outputFormatter;
            this.fileSystem = builder.fileSystem;
            this.schedulingEngine = builder.schedulingEngine;
            this.messageQueueManager = builder.messageQueueManager;
            this.liveSession = builder.liveSession;
            this.pendingTurnRegistry = builder.pendingTurnRegistry;
            this.skillApprovalChannel = builder.skillApprovalChannel;
            this.traceSpanStore = builder.traceSpanStore;
        }

        /**
         * Returns a builder. Production callers (factory + tests) construct {@link AgentSetup} exclusively this way.
         */
        public static Builder builder() {
            return new Builder();
        }

        public OrcaAgentExecutor getAgentExecutor() {
            return agentExecutor;
        }

        public Agent getAgent() {
            return agent;
        }

        public OrcaAgentRuntime getAgentRuntime() {
            return agentRuntime;
        }

        public OutputFormatter getOutputFormatter() {
            return outputFormatter;
        }

        public LocalFileSystem getFileSystem() {
            return fileSystem;
        }

        public SchedulingEngine getSchedulingEngine() {
            return schedulingEngine;
        }

        /**
         * Returns the session-scoped {@link MessageQueueManager} shared by the Orca executor and REPL input path.
         *
         * <p>
         * Producers (ReplSession, future SDK callers) and consumers (the Orca ReAct loop's mid-turn drain) must all
         * interact with this instance to keep the mid-turn injection queue consistent.
         *
         * @return the shared manager (never null)
         */
        public MessageQueueManager getMessageQueueManager() {
            return messageQueueManager;
        }

        /**
         * Returns the {@link PendingTurnRegistry} shared with the executor's pre-flight scanner and the
         * {@code /pending}, {@code /approve}, {@code /deny} system commands.
         *
         * <p>
         * The REPL uses this to drop a just-suspended pending turn when the user issues SIGINT mid-turn, so abandoned
         * turns don't accumulate until TTL.
         *
         * @return the shared registry (never null)
         */
        public PendingTurnRegistry getPendingTurnRegistry() {
            return pendingTurnRegistry;
        }

        /**
         * Returns the inline skill-approval channel used by the SK-11.6 pre-flight scanner, if one is wired.
         *
         * <p>
         * The CLI binds its JLine terminal to this channel for the duration of the REPL ({@code ReplSession.start()})
         * so the channel can prompt the user inline; outside of an interactive REPL (tests, programmatic invocation),
         * this returns {@code null} and the scanner uses the legacy SK-11.4 suspend/resume flow.
         *
         * @return the channel or {@code null} when the setup has no inline approval support
         */
        public InteractiveSkillApprovalChannel getSkillApprovalChannel() {
            return skillApprovalChannel;
        }

        /**
         * Returns the {@link LiveSession} wired around the wired-up Orca executor and session-scoped context.
         *
         * <p>
         * The REPL (and future SDK callers) interacts with the agent exclusively through this session so STREAM-04
         * event streaming and SESSION-02's session defaults (fixed {@link SessionId}, default
         * {@link at.aimon.core.agent.budget.ExecutionBudget}) are honored consistently across entry points.
         *
         * @return the shared session (never null)
         */
        public LiveSession getLiveSession() {
            return liveSession;
        }

        /**
         * Returns the trace span store when tracing is enabled (cli.tracing), otherwise empty.
         *
         * @return the optional {@link TraceSpanStore}
         */
        public Optional<TraceSpanStore> getTraceSpanStore() {
            return Optional.ofNullable(traceSpanStore);
        }

        /**
         * Releases everything this CLI process started, in {@link TeardownPhase} order.
         *
         * <p>
         * Unlike the hand-written sequence this replaced, the plan runs <b>every</b> entry even when one throws:
         * a failure no longer abandons the later steps, several of which stop daemon threads. The aggregate is
         * reported through the formatter rather than rethrown, because shutdown is the last thing that happens and
         * a throw here would only replace a clean exit with a stack trace.
         */
        @Override
        public void close() {
            if (stack == null) {
                // Test-built setup — nothing was assembled, so there is nothing to release.
                return;
            }
            try {
                stack.close();
            } catch (RuntimeException e) {
                if (outputFormatter != null) {
                    outputFormatter.displayInfo("Shutdown reported: " + e.getMessage());
                }
            }
        }

        /**
         * Builder for {@link AgentSetup}. Replaces the previous 12-arg public constructor so callers (factory + tests)
         * can wire only the fields they need without remembering positional order. Optional fields default to null;
         * production wiring populates them all, tests typically supply just the executor/context/formatter triple.
         */
        public static final class Builder {
            private AimonStack stack;
            private OrcaAgentExecutor agentExecutor;
            private Agent agent;
            private OrcaAgentRuntime agentRuntime;
            private OutputFormatter outputFormatter;
            private LocalFileSystem fileSystem;
            private SchedulingEngine schedulingEngine;
            private MessageQueueManager messageQueueManager;
            private LiveSession liveSession;
            private PendingTurnRegistry pendingTurnRegistry;
            private InteractiveSkillApprovalChannel skillApprovalChannel;
            private TraceSpanStore traceSpanStore;

            private Builder() {
            }

            /**
             * The assembled stack that owns every closeable, including the CLI-owned ones enrolled through
             * {@link AimonStack#own(TeardownPhase, String, AutoCloseable)}. Left unset by tests, which build a
             * setup for its accessors and never close it.
             */
            public Builder stack(AimonStack stack) {
                this.stack = stack;
                return this;
            }

            public Builder agentExecutor(OrcaAgentExecutor agentExecutor) {
                this.agentExecutor = agentExecutor;
                return this;
            }

            public Builder agent(Agent agent) {
                this.agent = agent;
                return this;
            }

            public Builder agentRuntime(OrcaAgentRuntime agentRuntime) {
                this.agentRuntime = agentRuntime;
                return this;
            }

            public Builder outputFormatter(OutputFormatter outputFormatter) {
                this.outputFormatter = outputFormatter;
                return this;
            }

            public Builder fileSystem(LocalFileSystem fileSystem) {
                this.fileSystem = fileSystem;
                return this;
            }

            public Builder schedulingEngine(SchedulingEngine schedulingEngine) {
                this.schedulingEngine = schedulingEngine;
                return this;
            }

            public Builder messageQueueManager(MessageQueueManager messageQueueManager) {
                this.messageQueueManager = messageQueueManager;
                return this;
            }

            public Builder liveSession(LiveSession liveSession) {
                this.liveSession = liveSession;
                return this;
            }

            public Builder pendingTurnRegistry(PendingTurnRegistry pendingTurnRegistry) {
                this.pendingTurnRegistry = pendingTurnRegistry;
                return this;
            }

            public Builder skillApprovalChannel(InteractiveSkillApprovalChannel skillApprovalChannel) {
                this.skillApprovalChannel = skillApprovalChannel;
                return this;
            }

            /** TRACE-01: optional span store (set when cli.tracing is enabled). */
            public Builder traceSpanStore(TraceSpanStore traceSpanStore) {
                this.traceSpanStore = traceSpanStore;
                return this;
            }

            public AgentSetup build() {
                return new AgentSetup(this);
            }
        }

    }

    private static final String DEFAULT_AGENT_BUNDLE_BASE_PATH = "agents";

    private final LlmClientFactory llmClientFactory;
    private final AgentBundleLoader agentBundleLoader;
    private final DefaultMcpClientFactory mcpClientFactory;

    /**
     * Creates an AgentSetupFactory with default implementations.
     *
     * <p>
     * The bundle loader is built lazily inside {@link #create(CliConfig)} so it can be wired with a SK-13 shell-aware
     * skill parser whose lifecycle (a {@link LocalShell}) is owned by the resulting {@link AgentSetup}.
     */
    public AgentSetupFactory() {
        this(new LlmClientFactory(), null);
    }

    /**
     * Creates an AgentSetupFactory with custom implementations.
     * <p>
     * This constructor is primarily intended for testing and custom configurations.
     *
     * @param llmClientFactory
     *            the factory for creating LLM clients (must not be null)
     * @param agentBundleLoader
     *            the loader for agent bundles (may be {@code null} — when null, {@link #create(CliConfig)} builds an
     *            {@link AdaptiveAgentBundleLoader} wired with a SK-13 shell-aware {@link MarkdownSkillParser})
     */
    AgentSetupFactory(LlmClientFactory llmClientFactory, AgentBundleLoader agentBundleLoader) {
        this.llmClientFactory = Objects.requireNonNull(llmClientFactory, "llmClientFactory cannot be null");
        this.agentBundleLoader = agentBundleLoader;

        this.mcpClientFactory = new DefaultMcpClientFactory();
    }

    /**
     * Creates a complete agent setup with all necessary components.
     *
     * <p>
     * The assembly itself belongs to {@link AimonStackBuilder}: session storage, the executor, the skill policy
     * chain, the agent runtime, scheduling and rewake are described declaratively as an {@link AimonStackSpec} and
     * built there, in the order and with the teardown plan that module owns. What is left here is what is genuinely
     * the CLI's: the {@link OutputFormatter} and everything bound to a terminal (the inline approval channel, the
     * display hooks, the scheduled-task listener, the reaper's expiry printer), plus the peer-memory subsystem,
     * which is CLI configuration rather than framework wiring.
     *
     * <p>
     * CLI-owned resources that outlive the call are enrolled on the stack's plan through
     * {@link AimonStack#own(TeardownPhase, String, AutoCloseable)} instead of being closed by {@link AgentSetup}
     * itself — see that class's ownership note for why the whole shutdown has to run in one order.
     *
     * @param config
     *            the CLI configuration
     * @return the configured agent setup
     * @throws NullPointerException
     *             if config is null
     */
    public AgentSetup create(CliConfig config) {
        Objects.requireNonNull(config, "config cannot be null");

        final LlmClient llmClient = createLlmClient(config);
        // TRACE-01: when cli.tracing is enabled, record a per-turn span tree. One tracer is shared by the LLM client
        // decorator (LLM spans) and the executor (turn/iteration/tool spans); the store backs the /trace command.
        // Only the agent turn (ReAct loop + subagents) is traced; background subsystems (wiki indexing, peer memory,
        // dreamer) keep the raw llmClient by design — their calls carry no turn span context, so wrapping them would
        // produce no spans anyway. TRACE-02: the default redactor masks secret-bearing keys (token/secret/password
        // /...) before storage, so it is always wired when tracing is on; content capture is opt-in via
        // cli.tracingCaptureContent and truncated to cli.tracingMaxPayloadChars.
        final TraceSpanStore traceSpanStore = config.getCliSettings().isTracing() ? new InMemoryTraceSpanStore() : null;
        final Tracer tracer = (traceSpanStore == null)
                ? null
                : new DefaultTracer(traceSpanStore, SpanExporter.noop(), SpanRedactor.defaultRedactor());
        final TracePayloadPolicy tracePayloadPolicy = resolveTracePayloadPolicy(config.getCliSettings(), tracer);
        final LlmClient effectiveLlmClient = (tracer == null)
                ? llmClient
                : new TracingLlmClient(llmClient, tracer, tracePayloadPolicy);

        final OutputFormatter outputFormatter = createOutputFormatter(config);
        // SK-13: build the LocalShell + shell-aware skill parser here rather than letting the stack default them, so
        // the bundle loader below shares the same parser. Supplying a parser is also what tells AimonStackBuilder not
        // to open a second shell of its own.
        final LocalShell skillHookShell = new LocalShell();
        final SkillParser skillParser = createShellAwareSkillParser(skillHookShell);
        final AgentBundleLoader effectiveBundleLoader = (this.agentBundleLoader != null)
                ? this.agentBundleLoader
                : new AdaptiveAgentBundleLoader(DEFAULT_AGENT_BUNDLE_BASE_PATH, new MarkdownAgentDefinitionParser(),
                        Thread.currentThread().getContextClassLoader(), skillParser);
        final AgentBundle agentBundle = effectiveBundleLoader.load(extractAgentName(config));
        final LocalFileSystem fileSystem = createFileSystem();

        // SK-MEM Stage 9: optional Honcho-analogue peer memory. When config.memory is populated we build a file-backed
        // RepresentationStore and a MemoryToolContextEnricher that injects workspace + observer + sessionId before
        // every tool call so MemoryRecallTool can resolve them. Built before the stack because the enrichers and the
        // auto-injection MemoryContextProvider are spec inputs. The ObservationStore is hoisted so the session-close
        // deriver and the long-running dreamer share one instance (otherwise the dreamer walks an empty store).
        final RepresentationStore representationStore = createRepresentationStore(config.getMemoryConfig(),
                outputFormatter);
        final MemoryWiring memoryWiring = buildMemoryWiring(config.getMemoryConfig());
        final ObservationStore observationStore = createObservationStore(config.getMemoryConfig(), memoryWiring,
                outputFormatter);
        // Built from the same observation store the deriver writes to, so MemoryChatTool answers from it. The
        // redaction policy is shared with the deriver queue to keep detection categories consistent (design §6.5).
        final DialecticEngine dialecticEngine = (observationStore == null)
                ? null
                : new LlmDialecticEngine(llmClient, observationStore, config.getLlmConfig().getModel());
        // The deriver and its queue used to be built after the stack, with the rest of the memory subsystem. They
        // move ahead of it because the queue is now a *material* of the memory backend — it is what makes the INGEST
        // tier exist — and MemorySpec is a stack input. Only the final-derivation runnable still has to wait, since
        // it needs the executor the stack publishes.
        final Deriver memoryDeriver = buildMemoryDeriver(memoryWiring, representationStore, observationStore, llmClient,
                config.getLlmConfig().getModel(), config.getMemoryConfig(), outputFormatter);
        final DerivationQueueManager memoryQueue = buildDerivationQueue(memoryDeriver);
        final MemorySpec memorySpec = buildMemorySpec(config.getMemoryConfig(), memoryWiring, representationStore,
                observationStore, dialecticEngine, memoryQueue);
        // The app-scoped shared GraalVM engine + watchdog schedulers, built once when cli.enableWorkflowJs
        // is on and reused by every session's WorkflowJs tool.
        final GraalJsEngineHolder graalJsEngines = config.getCliSettings().isEnableWorkflowJs()
                ? GraalJsEngineHolder.create()
                : null;

        // SK-11.6: the inline approval channel is built by the stack because it needs the stack's own approval
        // stores — but ReplSession.start() has to bind the JLine terminal to that exact instance, so capture what
        // the factory returned. Until binding occurs (headless callers, tests) the channel throws
        // IllegalStateException from requestApproval and the preflight scanner falls back to the SK-11.4 suspend path.
        final AtomicReference<InteractiveSkillApprovalChannel> approvalChannel = new AtomicReference<>();
        final SkillApprovalSpec approvalSpec = SkillApprovalSpec.channelFactory((sessions, agents) -> {
            final InteractiveSkillApprovalChannel channel = new InteractiveSkillApprovalChannel(sessions, agents,
                    outputFormatter);
            approvalChannel.set(channel);
            return channel;
        }).withDefaultDecision(SkillInvocationDecision.ASK)
                .withPendingTurnExpirationListener(turns -> reportExpiredPendingTurns(turns, outputFormatter));

        final AimonStackSpec spec = AimonStackSpec.builder().llm(LlmSpec.of(effectiveLlmClient))
                // Supplied, not stack-owned: the REPL keeps a LocalFileSystem-typed handle and today's CLI never
                // closes it, so enrolling it in AGENT_RESOURCES would change shutdown behaviour.
                .fileSystem(FileSystemSpec.supplied(fileSystem)).skillParser(skillParser)
                .agent(AgentSpec.builder().bundle(agentBundle)
                        .addCustomizer(runtime -> configureHooks(runtime, outputFormatter))
                        .addCustomizer(runtime -> registerCliTools(runtime, outputFormatter)).build())
                .session(SessionSpec.builder().recordStore(new InMemorySessionRecordStore()).build())
                .skillApproval(approvalSpec).memory(memorySpec)
                // No memoryContextProvider here any more: MemoryAssembly builds the injection provider from the spec
                // above, and AimonStackSpec rejects having both. The CLI supplies neither instead of both.
                .executor(ExecutorSpec.builder().streaming(config.getCliSettings().isStreaming()).tracer(tracer)
                        .tracePayloadPolicy(tracePayloadPolicy).build())
                .tools(buildToolSpec(config, fileSystem, graalJsEngines)).scheduling(SchedulingSpec.enabled())
                // A factory rather than an instance: the wiki locator resolves each scope's VFS through the runtime
                // registry, which only exists once the builder has created it. The raw llmClient is deliberate —
                // wiki generation is background work and carries no turn span context.
                .knowledgeStoreFactory(registry -> createWikiKnowledgeStore(registry, llmClient)).build();

        final AimonStack stack;
        try {
            stack = AimonStackBuilder.build(spec);
        } catch (RuntimeException e) {
            // These three predate the stack, so they are on nobody's teardown plan yet; everything the builder itself
            // created is released by its own failure path. The queue joined the list when it became a material of the
            // memory backend and had to be built ahead of MemorySpec: buildDerivationQueue starts its worker pool, so
            // a boot that fails here would otherwise leave one running for the life of the process.
            closeSuppressing(graalJsEngines, e);
            closeSuppressing(skillHookShell, e);
            if (memoryQueue != null) {
                // Guarded rather than left to closeSuppressing's null check, for the reason enrollMemorySubsystem
                // gives: a method reference on a null receiver throws before the call is ever made.
                closeSuppressing(memoryQueue::stop, e);
            }
            throw e;
        }
        return decorate(config, stack, agentBundle, fileSystem, skillHookShell, graalJsEngines,
                new CliDecorations(outputFormatter, approvalChannel.get(), traceSpanStore, llmClient,
                        new CliMemoryDecorations(memoryWiring, representationStore, observationStore, memoryQueue)));
    }

    /**
     * Adds the terminal-bound and CLI-configured pieces on top of an assembled stack, enrolling each one on the
     * stack's teardown plan so shutdown stays a single ordered sequence.
     */
    private AgentSetup decorate(CliConfig config, AimonStack stack, AgentBundle agentBundle, LocalFileSystem fileSystem,
            LocalShell skillHookShell, GraalJsEngineHolder graalJsEngines, CliDecorations cli) {
        // Closed after the agent runtime, so no WorkflowJs script can still be resolving against a
        // half-closed engine. TeardownPhase, not this call order, is what puts it there.
        stack.own(TeardownPhase.SCRIPT_ENGINES, "graalJsEngines", graalJsEngines);
        stack.own(TeardownPhase.SKILL_HOOK_SHELL, "skillHookShell", skillHookShell);

        final OrcaAgentExecutor agentExecutor = stack.agentExecutor();
        final OrcaAgentRuntime agentRuntime = stack.runtime(stack.primaryRuntimeId()).orElseThrow(
                () -> new IllegalStateException("Stack published no runtime for " + stack.primaryRuntimeId()));
        // Closed before skillHookShell: the reload callback fires shell-backed declarative hooks, so a debounced
        // reload landing between the two closes would otherwise hit a closed shell.
        stack.own(TeardownPhase.HOOK_HOT_RELOAD, "hookHotReload", setupHookHotReload(agentRuntime, agentExecutor,
                skillHookShell, fileSystem, agentBundle.getAgent().getName()));
        stack.schedulingEngine().ifPresent(
                engine -> engine.addEventListener(new ScheduledTaskEventDisplayListener(config.getCliSettings())));

        // Registered into SESSIONS after the stack's own router entry, so reverse-within-phase closes this handle
        // first and the router drains afterwards.
        final LiveSession liveSession = stack.own(TeardownPhase.SESSIONS, "liveSession",
                new DefaultLiveSession(DEFAULT_SESSION_ID, agentRuntime, agentExecutor, LiveSessionOptions.defaults(),
                        stack.messageQueueManager(), agentExecutor.getHookExecutionManager(),
                        stack.sessionRecordStore()));
        enrollMemorySubsystem(stack, config, cli);

        return AgentSetup.builder().stack(stack).agentExecutor(agentExecutor).agent(agentBundle.getAgent())
                .agentRuntime(agentRuntime).outputFormatter(cli.outputFormatter).fileSystem(fileSystem)
                .schedulingEngine(stack.schedulingEngine().orElse(null))
                .messageQueueManager(stack.messageQueueManager()).liveSession(liveSession)
                .pendingTurnRegistry(stack.pendingTurnRegistry()).skillApprovalChannel(cli.skillApprovalChannel)
                .traceSpanStore(cli.traceSpanStore).build();
    }

    /**
     * Builds the SK-MEM Stage 9 peer-memory pieces and enrolls each on the stack's teardown plan.
     *
     * <p>
     * They are built in dependency order but close in {@link TeardownPhase} order, which is the order this subsystem
     * needs: the final derivation runs first, while the transcript manager and the stores are all still alive; then
     * the queue drains; then the dreamer and the maintenance scheduler stop. {@link AimonStack#own} ignores a null
     * resource, but the two whose teardown is a method reference still need a guard — a method reference on a null
     * receiver throws before {@code own} is ever reached.
     */
    private void enrollMemorySubsystem(AimonStack stack, CliConfig config, CliDecorations cli) {
        final DerivationQueueManager memoryQueue = cli.memoryQueue;
        final MemoryIngestMode ingestMode = config.getMemoryConfig() == null
                ? MemoryIngestMode.SESSION_END
                : config.getMemoryConfig().resolvedIngest();
        final Runnable memoryFinalDerivation = buildMemoryFinalDerivation(ingestMode, cli.memoryWiring, memoryQueue,
                stack.agentExecutor(), DEFAULT_SESSION_ID, cli.outputFormatter);
        if (memoryFinalDerivation != null) {
            stack.own(TeardownPhase.MEMORY_FINAL_DERIVATION, "memoryFinalDerivation", memoryFinalDerivation::run);
        }
        if (memoryQueue != null) {
            // stop() blocks until the queue is empty or the per-implementation drain timeout expires (30s in-memory).
            stack.own(TeardownPhase.MEMORY_QUEUE, "memoryQueue.stop", memoryQueue::stop);
        }
        final DreamerSubsystem dreamerSubsystem = buildDreamerSubsystem(cli.memoryWiring, cli.observationStore,
                cli.representationStore, cli.llmClient, config.getLlmConfig().getModel(), config.getMemoryConfig(),
                cli.outputFormatter);
        if (dreamerSubsystem != null) {
            stack.own(TeardownPhase.DREAMER, "dreamerScheduler", dreamerSubsystem::close);
        }
        stack.own(TeardownPhase.MEMORY_MAINTENANCE, "memoryMaintenance", buildMemoryMaintenance(cli.memoryWiring,
                cli.observationStore, cli.representationStore, cli.outputFormatter));
    }

    /**
     * Translates CLI tool settings into a {@link ToolSpec}.
     *
     * <p>
     * Bash stays in the default set — the CLI runs on the user's own workstation, where the shell is the point.
     * The two workflow switches diverge here: {@code cli.enableWorkflowJs} contributes its own provider rather than
     * the built-in Workflow tool, so the runner has to be enabled even when {@code cli.enableWorkflow} is off, or
     * every WorkflowJs background dispatch would fail at the point of use.
     *
     * @param config
     *            the CLI configuration
     * @param fileSystem
     *            the workspace file system the WorkflowJs worktree factory branches from
     * @param graalJsEngines
     *            the shared GraalJS engine holder, or {@code null} when {@code cli.enableWorkflowJs} is off
     * @return the spec
     */
    // Package-private (not private) so AgentSetupFactoryGraalJsTest can exercise the cli.enableWorkflowJs wiring
    // branch directly, mirroring the sibling buildDerivationQueue/buildMemoryMaintenance test seams.
    ToolSpec buildToolSpec(CliConfig config, LocalFileSystem fileSystem, GraalJsEngineHolder graalJsEngines) {
        final CliSettings settings = config.getCliSettings();
        // No memory enricher here any more: MemoryAssembly contributes it, so the memory-shaped half of the tool
        // context and the memory tools that read it are decided in one place instead of two.
        final ToolSpec.Builder builder = ToolSpec.builder().workflowToolEnabled(settings.isEnableWorkflow())
                .workflowRunnerEnabled(settings.isEnableWorkflow() || settings.isEnableWorkflowJs());
        if (graalJsEngines != null) {
            // Core cannot register this tool itself (it must not depend on the aimon-workflow-graaljs impl module),
            // so the assembly layer adds it. The worktree factory is built here — the sanctioned assembler may touch
            // agent.impl.orca.environment — and handed over as the neutral WorktreeEnvironmentFactory, so the SPI
            // provider stays impl-free.
            builder.addProvider(
                    new GraalJsWorkflowToolProvider(graalJsEngines, new WorktreeToolEnvironmentFactory(fileSystem)));
        }
        final McpConfig mcpConfig = config.getMcpConfig();
        if (mcpConfig != null && mcpConfig.hasServers()) {
            builder.mcp(mcpClientFactory, mcpConfig.toConfigProvider());
        }
        return builder.build();
    }

    /**
     * TRACE-02: resolves the payload policy that goes with {@code tracer}, or {@code null} when tracing is off —
     * a policy without a tracer records nothing and the stack reports it as a degradation.
     */
    private static TracePayloadPolicy resolveTracePayloadPolicy(CliSettings settings, Tracer tracer) {
        if (tracer == null) {
            return null;
        }
        return settings.isTracingCaptureContent()
                ? TracePayloadPolicy.full(resolveTracingMaxPayloadChars(settings))
                : TracePayloadPolicy.summaryOnly();
    }

    /**
     * Surfaces reaper expirations through the {@link OutputFormatter} so users see why a pending turn they had
     * listed has vanished.
     */
    private static void reportExpiredPendingTurns(List<PendingTurn> turns, OutputFormatter outputFormatter) {
        for (PendingTurn turn : turns) {
            outputFormatter.displayInfo("Pending turn " + turn.getId().value()
                    + " expired and was removed (no /approve or /deny within TTL).");
        }
    }

    /**
     * Closes {@code resource} while a bootstrap failure is already in flight, attaching any close failure to it.
     */
    private static void closeSuppressing(AutoCloseable resource, RuntimeException inFlight) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeError) {
            inFlight.addSuppressed(closeError);
        }
    }

    /**
     * The CLI-side collaborators {@link #decorate} and {@link #enrollMemorySubsystem} both need. Grouped rather than
     * passed individually because the two methods would otherwise exceed the parameter limit — this is a carrier for
     * one call chain, not a domain type, hence the package-private fields.
     */
    private static final class CliDecorations {

        private final OutputFormatter outputFormatter;
        private final InteractiveSkillApprovalChannel skillApprovalChannel;
        private final TraceSpanStore traceSpanStore;
        private final LlmClient llmClient;
        private final MemoryWiring memoryWiring;
        private final RepresentationStore representationStore;
        private final ObservationStore observationStore;
        private final DerivationQueueManager memoryQueue;

        private CliDecorations(OutputFormatter outputFormatter, InteractiveSkillApprovalChannel skillApprovalChannel,
                TraceSpanStore traceSpanStore, LlmClient llmClient, CliMemoryDecorations memory) {
            this.outputFormatter = outputFormatter;
            this.skillApprovalChannel = skillApprovalChannel;
            this.traceSpanStore = traceSpanStore;
            this.llmClient = llmClient;
            this.memoryWiring = memory.memoryWiring;
            this.representationStore = memory.representationStore;
            this.observationStore = memory.observationStore;
            this.memoryQueue = memory.memoryQueue;
        }
    }

    /**
     * The memory pieces the CLI keeps for itself after the stack is built — the dreamer's stores, the queue whose
     * drain is on the teardown plan, and the workspace/observer pair the final-derivation runnable attributes to.
     *
     * <p>
     * They travel as one value because they are one subsystem, and because the alternative is a constructor with
     * eight parameters in which two adjacent stores can be swapped without the compiler noticing.
     */
    private static final class CliMemoryDecorations {

        private final MemoryWiring memoryWiring;
        private final RepresentationStore representationStore;
        private final ObservationStore observationStore;
        private final DerivationQueueManager memoryQueue;

        private CliMemoryDecorations(MemoryWiring memoryWiring, RepresentationStore representationStore,
                ObservationStore observationStore, DerivationQueueManager memoryQueue) {
            this.memoryWiring = memoryWiring;
            this.representationStore = representationStore;
            this.observationStore = observationStore;
            this.memoryQueue = memoryQueue;
        }
    }

    /**
     * Builds the file-backend maintenance scheduler when peer memory is enabled on the {@code file} backend,
     * otherwise returns {@code null}. The scheduler periodically purges soft-deleted observations and old
     * representations past their retention windows and compacts the append logs, so disk usage and
     * restart-replay cost stay bounded independently of the (optional) Dreamer. Only the file stores are
     * {@link Compactable}; the in-memory backend and any future backends return {@code null} here.
     *
     * <p>
     * Application-scoped per the multi-instance design rules — owned by {@link AgentSetup} and stopped on
     * {@link AgentSetup#close()}.
     */
    FileMemoryMaintenanceScheduler buildMemoryMaintenance(MemoryWiring memoryWiring, ObservationStore observationStore,
            RepresentationStore representationStore, OutputFormatter outputFormatter) {
        if (!memoryWiring.isEnabled() || observationStore == null || representationStore == null) {
            return null;
        }
        if (!(observationStore instanceof Compactable) || !(representationStore instanceof Compactable)) {
            // Not the file backend — nothing to compact / no file-level retention to enforce here.
            return null;
        }
        // A lightweight workspace store so the scheduler can enumerate the configured workspace; the CLI's
        // workspace is config-derived (not file-persisted), so an in-memory holder is sufficient.
        final InMemoryWorkspaceStore workspaceStore = new InMemoryWorkspaceStore();
        workspaceStore.create(memoryWiring.workspace);
        final FileMemoryMaintenanceScheduler maintenance = new FileMemoryMaintenanceScheduler(workspaceStore,
                observationStore, representationStore,
                List.of((Compactable) observationStore, (Compactable) representationStore));
        maintenance.start();
        outputFormatter.displayInfo("Peer memory maintenance enabled (file backend): retention purge + compaction"
                + " every " + FileMemoryMaintenanceScheduler.DEFAULT_INTERVAL);
        return maintenance;
    }

    /**
     * TRACE-02: resolves the trace payload truncation cap from CLI settings, tolerating a misconfigured
     * non-positive {@code cli.tracingMaxPayloadChars} by falling back to {@link TracePayloadPolicy#DEFAULT_MAX_CHARS}
     * with a WARN. Observability config must not crash CLI bootstrap (fail-safe, mirroring the tracing subsystem).
     */
    private static int resolveTracingMaxPayloadChars(CliSettings settings) {
        final int configured = settings.getTracingMaxPayloadChars();
        if (configured >= 1) {
            return configured;
        }
        log.warn("Ignoring invalid cli.tracingMaxPayloadChars={} (must be >= 1); using default {}", configured,
                TracePayloadPolicy.DEFAULT_MAX_CHARS);
        return TracePayloadPolicy.DEFAULT_MAX_CHARS;
    }

    /**
     * Builds the {@link MarkdownSkillParser} used for both bundled and user skills. Wiring a
     * {@link DefaultShellActionExecutor} here is what activates the SK-13 frontmatter {@code shell} action type — the
     * default zero-arg parser falls back to a no-op executor that fails such declarations at parse time.
     */
    private static SkillParser createShellAwareSkillParser(VirtualShell shell) {
        return new MarkdownSkillParser(new ShellArgumentTokenizer(),
                new SkillHookSetParser(new DefaultShellActionExecutor(shell)));
    }

    /**
     * Creates an LLM client based on the configuration.
     */
    private LlmClient createLlmClient(CliConfig config) {
        return llmClientFactory.create(config.getLlmConfig());
    }

    /**
     * Creates an output formatter based on CLI settings.
     */
    private OutputFormatter createOutputFormatter(CliConfig config) {
        return new OutputFormatter(config.getCliSettings());
    }

    /**
     * Extracts the agent name from configuration, or returns the default name.
     */
    private String extractAgentName(CliConfig config) {
        return Optional.ofNullable(config.getAgentConfig()).map(AgentConfig::getName).orElse(DEFAULT_AGENT_NAME);
    }

    /**
     * Creates and initializes a local file system.
     */
    private LocalFileSystem createFileSystem() {
        final String workingDirectory = getJarDirectory();
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(workingDirectory));
        fileSystem.initialize();
        return fileSystem;
    }

    /**
     * Configures display hooks for the agent runtime.
     */
    private void configureHooks(OrcaAgentRuntime agentRuntime, OutputFormatter outputFormatter) {
        final HookRegistry hookRegistry = agentRuntime.getHookRegistry();
        hookRegistry.register(HookEventType.PRE_TOOL, new ToolCallDisplayHook(outputFormatter));
        hookRegistry.register(HookEventType.POST_TOOL, new SubagentResultDisplayHook(outputFormatter));
        // Subagent launches (Task tool, Workflow tool, skill forks) all fire SUBAGENT_START on the agent's hook
        // registry — display them uniformly. The Task tool's generic tool-call line is suppressed in OutputFormatter to
        // avoid a duplicate, so this is the single source of the "[Subagent] <name>" launch line.
        hookRegistry.register(HookEventType.SUBAGENT_START, new SubagentLaunchDisplayHook(outputFormatter));
    }

    /**
     * Wires the hot-reload pipeline so {@code hooks.json} edits propagate to the live
     * registry without restarting the CLI. Delegates to {@link HookHotReloadBootstrap} so web bootstraps that adopt the
     * helper get the same wiring shape.
     *
     * @return the {@link HookHotReloadBootstrap.Started} handle owned by {@code AgentSetup}; closed during
     *         {@link AgentSetup#close()}
     */
    private HookHotReloadBootstrap.Started setupHookHotReload(OrcaAgentRuntime agentRuntime,
            OrcaAgentExecutor agentExecutor, VirtualShell skillHookShell, LocalFileSystem fileSystem,
            String agentName) {
        return HookHotReloadBootstrap.builder().userHome(Paths.get(System.getProperty("user.home")))
                .projectRoot(Paths.get(fileSystem.getWorkingDirectory()))
                .shellExecutor(new DefaultShellActionExecutor(skillHookShell)).processEnv(System.getenv())
                .registry(agentRuntime.getHookRegistry()).executionManager(agentExecutor.getHookExecutionManager())
                .invoker(new ReloadInvoker(InvokerType.MAIN_AGENT, agentName, Environment.createDefault())).start();
    }

    /**
     * Registers the one tool that is genuinely CLI-specific: console output.
     *
     * <p>
     * The four memory tools used to be registered here, each guarded by the presence of a store. They are now
     * registered by {@code MemoryAssembly} from {@link #buildMemorySpec}, driven by the backend's capabilities rather
     * than by a list of stores — the same four tools appear for the CLI's backend, and a backend that cannot serve one
     * of them no longer gets it registered and failing.
     */
    private void registerCliTools(OrcaAgentRuntime agentRuntime, OutputFormatter outputFormatter) {
        agentRuntime.getToolRegistry()
                .register(new ConsoleOutputTool(outputFormatter::displayInfo, outputFormatter::displayError));
    }

    /**
     * Builds the {@link MemorySpec} the stack assembles memory from, or {@code null} when memory is off.
     *
     * <p>
     * Everything the CLI used to wire by hand — the injection provider, the tool-context enricher and the four memory
     * tools — is produced by {@code MemoryAssembly} from this one value, and produced from the backend's
     * <em>capabilities</em> rather than from a list of stores. The CLI's job shrinks to naming the materials.
     *
     * <p>
     * The four tiers come from what the CLI has always built: the representation store answers SNAPSHOT, the
     * observation store answers SEARCH and OBSERVE, the dialectic engine answers CHAT, and the derivation queue
     * answers INGEST. All five capabilities are present, so no memory degradation is recorded — which is a truthful
     * change rather than a quiet one: the CLI has had a write path all along, and it simply never passed through the
     * place that reports on one.
     *
     * <p>
     * The dreamer, the maintenance scheduler and the final-derivation runnable stay with the CLI. They are not tiers;
     * they are the default backend's background work, and a deployment that swapped the backend would want them gone
     * rather than re-pointed.
     */
    MemorySpec buildMemorySpec(MemoryConfig memoryConfig, MemoryWiring memoryWiring,
            RepresentationStore representationStore, ObservationStore observationStore, DialecticEngine dialecticEngine,
            DerivationQueueManager derivationQueue) {
        if (!memoryWiring.isEnabled()) {
            return null;
        }
        final PeerMemory backend = StoreBackedPeerMemory.builder().representationStore(representationStore)
                .observationStore(observationStore).dialecticEngine(dialecticEngine).derivationQueue(derivationQueue)
                .build();
        return MemorySpec.forPeer(memoryWiring.getWorkspace(), memoryWiring.getObserver().getPrincipal())
                .peerMemory(backend).injectionMode(MemoryInjectionMode.SUMMARY_ONLY).maxTokens(0)
                .ingestMode(memoryConfig == null ? MemoryIngestMode.SESSION_END : memoryConfig.resolvedIngest())
                .redactionPolicy(new DefaultRedactionPolicy()).build();
    }

    /**
     * Builds the {@link RepresentationStore} when {@link MemoryConfig} is enabled, otherwise returns null. The
     * concrete implementation is chosen by {@link MemoryConfig#resolvedBackend()}: {@code file} (default) persists
     * snapshots to the JSONL log at {@code storagePath}; {@code in-memory} keeps them in memory (non-durable). The
     * store is application-scoped per multi-instance design rules: storage is behind an interface so swapping in a
     * Postgres-backed implementation is a wiring change, not a refactor.
     */
    RepresentationStore createRepresentationStore(MemoryConfig memoryConfig, OutputFormatter outputFormatter) {
        if (memoryConfig == null || !memoryConfig.isEnabled()) {
            log.debug("Peer memory disabled (no cli.memory block or required fields missing)");
            return null;
        }
        final String backend = memoryConfig.resolvedBackend();
        if (MemoryConfig.BACKEND_IN_MEMORY.equals(backend)) {
            // Design §5.4/§12: in-memory backend is dev/test only — emit a WARN log in addition to the
            // startup banner so the non-durable, single-JVM, OOM-prone choice is visible in operations.
            log.warn("Peer memory using IN-MEMORY backend: non-durable (lost on restart), single-JVM only, "
                    + "and prone to OOM beyond ~10k observations. Use 'file' or a persistent backend in production.");
            outputFormatter.displayInfo("Peer memory enabled (in-memory backend, non-durable): workspace="
                    + memoryConfig.getWorkspaceId() + " peer=" + memoryConfig.getPeerId());
            return new InMemoryRepresentationStore();
        }
        final Path logFile = Paths.get(memoryConfig.getStoragePath());
        outputFormatter.displayInfo("Peer memory enabled (file backend): workspace=" + memoryConfig.getWorkspaceId()
                + " peer=" + memoryConfig.getPeerId() + " storage=" + logFile.toAbsolutePath().getParent());
        return new FileRepresentationStore(logFile);
    }

    /**
     * Builds the {@link ObservationStore} when memory is enabled, otherwise returns null. The concrete implementation
     * tracks {@link MemoryConfig#resolvedBackend()}: {@code file} (default) persists observations to an
     * {@code observations.jsonl} sibling of {@code storagePath} (its built-in {@code InMemoryObservationIndex} backs
     * substring {@code semanticSearch}); {@code in-memory} keeps them in memory. Unknown backends fall back to file
     * with a warning — PostgreSQL/OpenSearch backends are not yet wired into the CLI (use
     * {@link at.aimon.core.memory.IndexedObservationStore} + the matching persistence module when adding them).
     *
     * <p>
     * The instance is hoisted to factory scope so the session-close deriver and the long-running dreamer share it
     * (see {@link #create(CliConfig)}).
     */
    ObservationStore createObservationStore(MemoryConfig memoryConfig, MemoryWiring memoryWiring,
            OutputFormatter outputFormatter) {
        if (!memoryWiring.isEnabled() || memoryConfig == null) {
            return null;
        }
        final String backend = memoryConfig.resolvedBackend();
        if (MemoryConfig.BACKEND_IN_MEMORY.equals(backend)) {
            log.warn("Peer memory observation store using IN-MEMORY backend: non-durable and dev/test only.");
            return new InMemoryObservationStore();
        }
        if (!MemoryConfig.BACKEND_FILE.equals(backend)) {
            outputFormatter.displayInfo(
                    "Peer memory: unknown backend '" + backend + "', falling back to file for observations");
        }
        final Path observationFile = Paths.get(memoryConfig.getStoragePath()).resolveSibling("observations.jsonl");
        return new FileObservationStore(observationFile);
    }

    /**
     * Builds the per-context memory wiring: tool-context enrichers plus the workspace/observer pair the SK-MEM
     * Stage 9 B3 final-derivation hook needs. Returns a disabled wiring (empty enrichers, null workspace/observer)
     * when memory is not enabled — callers must guard on {@link MemoryWiring#isEnabled()} before using the pair.
     */
    MemoryWiring buildMemoryWiring(MemoryConfig memoryConfig) {
        if (memoryConfig == null || !memoryConfig.isEnabled()) {
            return MemoryWiring.disabled();
        }
        final Workspace workspace = Workspace.builder().id(memoryConfig.getWorkspaceId()).build();
        final String peerName = memoryConfig.getPeerName() != null && !memoryConfig.getPeerName().isBlank()
                ? memoryConfig.getPeerName()
                : memoryConfig.getPeerId();
        final PeerView observer = PeerView.of(workspace, Principal.user(memoryConfig.getPeerId(), peerName));
        return new MemoryWiring(workspace, observer);
    }

    /**
     * Builds the {@link LlmDeriver} consumed by the application-scope derivation queue. Returns {@code null} when
     * memory
     * is disabled or the representation/observation stores are not wired.
     *
     * <p>
     * The {@code observationStore} is hoisted to factory scope by {@link #create(CliConfig)} so the deriver and the
     * background dreamer share the same instance — without sharing, the dreamer would walk an empty store on every
     * cycle.
     */
    Deriver buildMemoryDeriver(MemoryWiring memoryWiring, RepresentationStore representationStore,
            ObservationStore observationStore, LlmClient llmClient, String llmModelName, MemoryConfig memoryConfig,
            OutputFormatter outputFormatter) {
        if (!memoryWiring.isEnabled() || representationStore == null || observationStore == null) {
            return null;
        }
        final Reconciler reconciler = (memoryConfig != null && memoryConfig.isReconcilerEnabled())
                ? new DefaultReconciler(llmClient, llmModelName)
                : null;
        if (reconciler != null) {
            outputFormatter.displayInfo("Peer memory: reconciler enabled (LLM-as-judge)");
        }
        return new LlmDeriver(llmClient, observationStore, llmModelName, representationStore, reconciler);
    }

    /**
     * Builds and starts the application-scope {@link DerivationQueueManager} that funnels every {@link DerivationTask}
     * through a redaction gate before handing it to the {@code deriver}. Returns {@code null} when no deriver was wired
     * (memory disabled).
     *
     * <p>
     * Multi-instance design rule: the queue manager is constructed behind the {@link DerivationQueueManager} interface
     * so swapping in a Postgres-backed implementation (roadmap stage 5) is a wiring change, not a refactor. The default
     * worker count, batch budget, and poll interval come from {@link DeriverProperties#defaults()} — which is all
     * that type carries: the deriver arrives already holding the agent's model.
     */
    DerivationQueueManager buildDerivationQueue(Deriver deriver) {
        if (deriver == null) {
            return null;
        }
        final RedactionPolicy redactionPolicy = new DefaultRedactionPolicy();
        final DerivationQueueManager queue = new InMemoryDerivationQueueManager(deriver, redactionPolicy,
                DeriverProperties.defaults());
        queue.start();
        return queue;
    }

    /**
     * Builds the SK-MEM Stage 9 B3 final-derivation runnable. Returns {@code null} when memory or the queue is
     * disabled; otherwise returns a closure that, at REPL exit, loads the conversation history, builds a
     * {@link DerivationTask}, and enqueues it into the application-scope {@link DerivationQueueManager}.
     *
     * <p>
     * The runnable does not block on the derive call itself — actual derivation runs on a queue worker. The drain is
     * performed by {@link AgentSetup#close()} when it stops the queue, which waits for the in-flight task to complete.
     */
    Runnable buildMemoryFinalDerivation(MemoryIngestMode ingestMode, MemoryWiring memoryWiring,
            DerivationQueueManager queue, OrcaAgentExecutor agentExecutor, SessionId sessionId,
            OutputFormatter outputFormatter) {
        // This runnable *is* session-end ingest. Under execution-end the executor seam has already fed every
        // execution's messages, so running this too would send the whole transcript a second time — the duplicate the
        // delta exists to avoid, and an LLM extraction bill for it. Under off, neither runs.
        if (ingestMode != MemoryIngestMode.SESSION_END || !memoryWiring.isEnabled() || queue == null) {
            return null;
        }
        final Workspace workspace = memoryWiring.workspace;
        final PeerView observer = memoryWiring.observer;
        return () -> enqueueFinalDerivation(queue, agentExecutor, sessionId, workspace, observer, outputFormatter);
    }

    /**
     * Builds the optional Honcho-analogue dreamer (background consolidation) subsystem.
     *
     * <p>
     * Wires the configured {@link SurprisalScorer} (LLM judge by default; embedding cosine when
     * {@code memory.dreamer.scorer.type=embedding}) → {@link RandomWalkDreamer} → {@link DefaultDreamerEngine}, spins
     * up a dedicated Quartz {@link Scheduler} (RAMJobStore + SimpleThreadPool) so the dreamer cron does not contend
     * with the foreground task scheduler, registers the configured workspace via {@link InMemoryWorkspaceStore}, and
     * schedules the {@link DreamerJobRegistrar} for the workspace declared on the memory block.
     *
     * <p>
     * Returns {@code null} when memory is disabled, the dreamer block is missing/disabled, or the chosen scorer's
     * required credentials are absent (embedding scorer needs an API key) — we fail soft rather than crashing the
     * agent at startup.
     */
    DreamerSubsystem buildDreamerSubsystem(MemoryWiring memoryWiring, ObservationStore observationStore,
            RepresentationStore representationStore, LlmClient llmClient, String llmModelName,
            MemoryConfig memoryConfig, OutputFormatter outputFormatter) {
        if (!memoryWiring.isEnabled() || observationStore == null || memoryConfig == null) {
            return null;
        }
        final MemoryDreamerConfig dreamerConfig = memoryConfig.getDreamer();
        if (dreamerConfig == null || !dreamerConfig.isEnabled()) {
            return null;
        }
        final String notReadyReason = dreamerConfig.notReadyReason();
        if (notReadyReason != null) {
            outputFormatter.displayInfo("Peer memory dreamer disabled: " + notReadyReason);
            return null;
        }

        try {
            final SurprisalScorer scorer = createSurprisalScorer(dreamerConfig, llmClient, llmModelName);
            final RandomWalkDreamer strategy = new RandomWalkDreamer(observationStore, scorer, llmClient, llmModelName,
                    dreamerConfig.resolvedSurprisalThreshold(), dreamerConfig.resolvedWalkSeedCount(),
                    dreamerConfig.resolvedNeighborTopK());
            // Wire the representation store so the dreamer refreshes each subject's cross-session GLOBAL
            // representation (the producer for findLatestGlobal / MemoryRecall GLOBAL mode).
            final DreamerEngine engine = new DefaultDreamerEngine(observationStore, strategy,
                    DefaultDreamerEngine.DEFAULT_MAX_SUBJECTS_PER_CYCLE, representationStore,
                    DefaultDreamerEngine.DEFAULT_GLOBAL_REPRESENTATION_LIMIT);

            final WorkspaceStore workspaceStore = new InMemoryWorkspaceStore();
            workspaceStore.create(memoryWiring.workspace);

            final Scheduler scheduler = createDreamerScheduler();
            scheduler.start();
            final DreamerJobRegistrar registrar = new DreamerJobRegistrar(scheduler, workspaceStore, engine);
            final String cron = dreamerConfig.resolvedCron();
            registrar.register(memoryWiring.workspace.getId(), cron);

            outputFormatter.displayInfo("Peer memory dreamer enabled: workspace=" + memoryWiring.workspace.getId()
                    + " scorer=" + dreamerConfig.resolvedScorerType().name().toLowerCase(java.util.Locale.ROOT)
                    + " cron='" + cron + "' (single node — in-memory job store; running a second CLI on this "
                    + "workspace consolidates it twice)");
            return new DreamerSubsystem(scheduler);
        } catch (RuntimeException | SchedulerException e) {
            log.warn("Failed to start peer-memory dreamer: {}", e.getMessage(), e);
            outputFormatter.displayInfo("Peer memory dreamer disabled: " + e.getMessage());
            return null;
        }
    }

    /**
     * Selects and builds the {@link SurprisalScorer} implementation based on
     * {@link MemoryDreamerConfig#resolvedScorerType()}. Adding a new scorer means adding a new branch here — the
     * dreamer itself depends only on the {@link SurprisalScorer} abstraction.
     */
    private static SurprisalScorer createSurprisalScorer(MemoryDreamerConfig dreamerConfig, LlmClient llmClient,
            String llmModelName) {
        final MemoryDreamerConfig.ScorerType type = dreamerConfig.resolvedScorerType();
        return switch (type) {
            case EMBEDDING -> new EmbeddingSurprisalScorer(createEmbeddingClient(dreamerConfig));
            case LLM -> new LlmJudgeSurprisalScorer(llmClient, resolveJudgeModel(dreamerConfig, llmModelName));
        };
    }

    /**
     * Resolves the model name for the LLM-judge scorer: {@code scorer.llm.model} when set, otherwise the global agent
     * model. Keeping the override optional means callers can run the dreamer with a cheaper model than the foreground
     * agent without duplicating credentials.
     */
    private static String resolveJudgeModel(MemoryDreamerConfig dreamerConfig, String fallbackModel) {
        final MemoryDreamerConfig.ScorerConfig scorer = dreamerConfig.getScorer();
        if (scorer != null && scorer.getLlm() != null) {
            final String override = scorer.getLlm().getModel();
            if (override != null && !override.isBlank()) {
                return override;
            }
        }
        return fallbackModel;
    }

    /**
     * Builds the {@link EmbeddingClient} that backs the embedding scorer. Currently only OpenAI-compatible embeddings
     * are wired in the CLI; future swaps (e.g., a self-hosted endpoint) replace the implementation behind the
     * {@link EmbeddingClient} interface without touching the dreamer.
     */
    private static EmbeddingClient createEmbeddingClient(MemoryDreamerConfig dreamerConfig) {
        final MemoryDreamerConfig.ScorerConfig scorer = dreamerConfig.getScorer();
        if (scorer == null || scorer.getEmbedding() == null) {
            throw new IllegalStateException(
                    "scorer.embedding block is required when scorer.type=embedding (validate config via isReady())");
        }
        final MemoryDreamerConfig.EmbeddingScorerConfig emb = scorer.getEmbedding();
        final OpenAIEmbeddingConfig.Builder builder = OpenAIEmbeddingConfig.builder().apiKey(emb.getApiKey());
        if (emb.getBaseUrl() != null && !emb.getBaseUrl().isBlank()) {
            builder.baseUrl(emb.getBaseUrl());
        }
        if (emb.getModel() != null && !emb.getModel().isBlank()) {
            builder.model(emb.getModel());
        }
        if (emb.getDimensions() != null) {
            builder.dimensions(emb.getDimensions());
        }
        return new OpenAIEmbeddingClient(builder.build());
    }

    /**
     * Builds a dedicated Quartz scheduler for the dreamer.
     *
     * <p>
     * <b>This scheduler is single-node by construction.</b> {@code RAMJobStore} holds jobs in this JVM's heap, so two
     * CLI processes configured against the same workspace each run the full consolidation cycle over the same
     * memories — there is no shared lock to lose. Clustering the dreamer means giving it a shared JDBC job store, not
     * merging it into another scheduler: the foreground {@link SchedulingEngine} runs the in-memory default
     * {@code TaskScheduler} here, so folding the two together would buy a smaller thread count and nothing else.
     *
     * <p>
     * RAMJobStore is also why the instance name carries a sequence suffix. Quartz's {@code SchedulerRepository} is
     * keyed by instance name per JVM, so a fixed name would make a second dreamer in one process fail or silently
     * attach to the first. The suffix is a within-process disambiguator, not a node identity — once a shared job
     * store exists, the name becomes the clustering key and must be stable across nodes instead.
     *
     * <p>
     * A 2-thread SimpleThreadPool keeps overhead minimal; being independent of the foreground
     * {@link SchedulingEngine} means a long-running cron cycle does not interfere with user-facing scheduled tasks.
     */
    private static Scheduler createDreamerScheduler() throws SchedulerException {
        final Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName",
                "AimonDreamerScheduler-" + DREAMER_SCHEDULER_SEQ.incrementAndGet());
        props.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "2");
        props.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
        props.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        return new StdSchedulerFactory(props).getScheduler();
    }

    private static void enqueueFinalDerivation(DerivationQueueManager queue, OrcaAgentExecutor agentExecutor,
            SessionId sessionId, Workspace workspace, PeerView observer, OutputFormatter outputFormatter) {
        final var transcriptManager = agentExecutor.getTranscriptManager();
        final var memory = transcriptManager.initialize(sessionId, null);
        final var messages = memory.getMessages();
        if (messages.isEmpty()) {
            log.debug("Peer memory final derivation skipped: conversation has no messages");
            return;
        }
        final DerivationTask task = DerivationTask.builder().workspace(workspace).sessionId(sessionId.value())
                .observer(observer).messages(messages).build();
        outputFormatter
                .displayInfo("Peer memory: enqueuing final derivation for " + messages.size() + " message(s)...");
        queue.enqueue(task);
    }

    /**
     * Holder for the per-context memory wiring produced by {@link #buildMemoryWiring(MemoryConfig)}. The
     * workspace/observer fields are null when memory is disabled — callers must check {@link #isEnabled()} before
     * dereferencing.
     */
    static final class MemoryWiring {
        private final Workspace workspace;
        private final PeerView observer;

        MemoryWiring(Workspace workspace, PeerView observer) {
            this.workspace = workspace;
            this.observer = observer;
        }

        static MemoryWiring disabled() {
            return new MemoryWiring(null, null);
        }

        boolean isEnabled() {
            return workspace != null && observer != null;
        }

        Workspace getWorkspace() {
            return workspace;
        }

        PeerView getObserver() {
            return observer;
        }
    }

    /**
     * Wraps the long-lived Quartz {@link Scheduler} that drives the dreamer cron. The {@link AgentSetup#close()}
     * tear-down asks the scheduler to shut down (waits for in-flight jobs); the registrar itself does not need to be
     * exposed because no caller currently re-tunes the cron at runtime.
     */
    static final class DreamerSubsystem {
        private final Scheduler scheduler;

        DreamerSubsystem(Scheduler scheduler) {
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        }

        Scheduler getScheduler() {
            return scheduler;
        }

        void close() {
            try {
                scheduler.shutdown(true);
            } catch (SchedulerException e) {
                log.warn("Dreamer scheduler shutdown failed: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Creates a wiki knowledge store whose VFS is resolved lazily from the Orca agent runtime registered for each
     * scope.
     *
     * <p>
     * The wiki data is stored under {@code .aimon/wiki} inside the agent runtime.s filesystem. The locator looks
     * up the context directly via the {@link AgentRuntimeRegistry} and extracts the filesystem from the
     * resolved {@link OrcaAgentRuntime}, so the corresponding agent runtime must be registered before any
     * wiki operation runs.
     *
     * <p>
     * Note: the same {@link LlmClient} instance is shared between the main agent reasoning loop and the wiki page
     * generator. This is a deliberate trade-off for simplicity — wiki generation uses the provider's default model
     * configuration. If wiki-specific model tuning (e.g., a cheaper model for indexing) is needed, create a separate
     * {@link LlmClient} instance with its own configuration.
     */
    private static WikiKnowledgeStore createWikiKnowledgeStore(AgentRuntimeRegistry agentRuntimeRegistry,
            LlmClient llmClient) {
        final WikiPageGenerator pageGenerator = LlmWikiPageGenerator.builder().llmClient(llmClient).build();
        return new WikiKnowledgeStore(new DefaultWikiKnowledgeBase(
                ContextResolvingWikiStorageLocator.defaultLayout(id -> agentRuntimeRegistry
                        .getAs(id, OrcaAgentRuntime.class).map(OrcaAgentRuntime::getFileSystem), ".aimon/wiki"),
                pageGenerator));
    }

    /**
     * Gets the directory where the jar file is located.
     *
     * <p>
     * If running from an IDE (not from jar), returns the current working directory.
     *
     * @return The directory path where the jar is located, or current directory if not running from jar
     */
    private static String getJarDirectory() {
        try {
            // Get the location of the current class
            final String jarPath = AgentSetupFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI()
                    .getPath();

            // If running from a jar file, get its parent directory
            if (jarPath.endsWith(".jar")) {
                return new File(jarPath).getParent();
            }

            // If running from IDE (classes directory), use current working directory
            return System.getProperty("user.dir");
        } catch (Exception e) {
            // Fallback to current working directory
            return System.getProperty("user.dir");
        }
    }
}
