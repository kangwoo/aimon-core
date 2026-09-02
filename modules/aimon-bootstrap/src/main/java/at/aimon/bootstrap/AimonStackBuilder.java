package at.aimon.bootstrap;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.assemble.MemoryAssembly;
import at.aimon.bootstrap.assemble.StackAgentRuntimeProvisioner;
import at.aimon.bootstrap.assemble.StackLiveSessionOpener;
import at.aimon.bootstrap.assemble.StackPaths;
import at.aimon.bootstrap.exception.AimonBootstrapException;
import at.aimon.bootstrap.runtime.AgentRuntimeResolver;
import at.aimon.bootstrap.runtime.SchedulingLifecycle;
import at.aimon.bootstrap.spec.AgentDescriptor;
import at.aimon.bootstrap.spec.AgentRuntimeSpec;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.ExecutorSpec;
import at.aimon.bootstrap.spec.SchedulingSpec;
import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.bootstrap.spec.SkillApprovalSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser;
import at.aimon.core.agent.impl.AdaptiveAgentBundleLoader;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.AgentBundleLoader;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorFactory;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.hook.rewake.impl.DefaultRewakeFireListener;
import at.aimon.core.hook.rewake.impl.DefaultRewakeService;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.llm.cost.TablePricedCostEstimator;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.scheduling.SchedulingEngineBuilder;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.parser.MarkdownSkillParser;
import at.aimon.core.skill.parser.SkillHookSetParser;
import at.aimon.core.skill.parser.SkillParser;
import at.aimon.core.skill.policy.RuleBasedSkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillPreflightScanner;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.agent.ApprovalCachingSkillInvocationPolicy;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;
import at.aimon.core.skill.policy.approval.AllowListSkillApprovalChannel;
import at.aimon.core.skill.policy.approval.DenyAllSkillApprovalChannel;
import at.aimon.core.skill.policy.approval.SkillApprovalChannel;
import at.aimon.core.skill.policy.pending.InMemoryPendingTurnRegistry;
import at.aimon.core.skill.policy.pending.PendingTurnReaper;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionScopedSkillInvocationPolicy;
import at.aimon.core.skill.render.ShellArgumentTokenizer;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.session.routing.DeploymentMode;
import at.aimon.session.routing.SessionRouter;
import at.aimon.session.routing.builder.SessionRouterBuilder;

/**
 * Turns an {@link AimonStackSpec} into a running {@link AimonStack}.
 *
 * <p>
 * This is the single place in the project where the neutral collaborator graph is assembled — the same graph the
 * CLI's {@code AgentSetupFactory} builds by hand, minus everything CLI-specific (terminal formatting, REPL
 * commands, the interactive approval prompt). Two properties matter more than the wiring itself.
 *
 * <h2>1. Every closeable is registered as it is constructed</h2>
 *
 * <p>
 * Not at the end, and not in a separate list that has to be kept in sync. The line that creates a resource is
 * the line that hands it to the {@link TeardownRegistry}, so a resource cannot be added without being given a
 * teardown phase. Shutdown order then comes from {@link TeardownPhase}'s declaration order rather than from
 * whatever order the fields happen to appear in.
 *
 * <h2>2. A failed build closes what it already built</h2>
 *
 * <p>
 * Assembly touches real resources — a shell, a background mailbox thread, a file system, a scheduler. If step
 * eleven throws, the ten before it are already holding threads and handles. Every path out of this class
 * therefore runs {@link TeardownRegistry#closeAll()} before rethrowing, which is why the registry is created
 * first and the whole body sits inside one try/catch. A half-built stack that leaks is worse than no stack:
 * the process looks fine and never exits.
 *
 * <h2>3. Assembly constructs; it does not start</h2>
 *
 * <p>
 * Nothing in {@link #assemble(AimonStackSpec)} registers a runtime, starts a sweeper or starts the scheduler.
 * That is {@link AimonStack#start()}, and the separation exists because "the collaborator graph stands" and "this
 * process is serving" are different moments for any host that has a listening socket: runtimes must be
 * resolvable before the socket opens, and the scheduler must not fire before it. {@link #build(AimonStackSpec)}
 * does both in one call for the callers that have no such moment.
 *
 * <h2>Instance sharing is load-bearing</h2>
 *
 * <p>
 * Several collaborators must be the <i>same object</i> in more than one place, and wiring a fresh equivalent
 * instance produces no error — only a behaviour that quietly stops working:
 *
 * <ul>
 * <li>the {@code AgentRuntimeRegistry} given to the scheduling engine and the one runtimes register into — a
 * split here makes every cron re-fire fail to resolve its runtime;
 * <li>the approval stores read by the policy chain and written by the approval channel — a split makes every
 * granted approval invisible to the check that follows it;
 * <li>the {@code SessionApprovalStore} given to the runtime factory and to the session router — a split makes
 * the router's purge-on-delete a no-op, so a later session reusing a {@code SessionId} inherits approvals it
 * was never granted.
 * </ul>
 *
 * <h2>What is shared and what is per-agent</h2>
 *
 * <p>
 * One stack stands up every agent in the spec, and the division below the {@code --- Agent runtimes ---} banner
 * is the point of the class. <b>Shared:</b> the executor, the session router, the scheduling engine, the
 * approval stores and policy chain, the runtime registry — one instance each, because a second copy of any of
 * them silently breaks a behaviour rather than failing. <b>Per agent:</b> the bundle, the file system, the skill
 * registry, and the runtime — because a second agent reaching one of those is an agent reading another agent's
 * files or running another agent's skills.
 *
 * <p>
 * The two lists meet at the {@code SkillPreflightScanner}, which lives inside the shared executor and has to
 * resolve skill names belonging to whichever agent is executing. It therefore takes a
 * {@code SkillRegistryResolver} rather than a registry, and this builder points that resolver at the live
 * {@code AgentRuntimeRegistry}. A fixed registry there would resolve every agent's skill names through the first
 * agent's bundle: the scan would skip what it could not find, {@code SkillTool} would re-check the policy and
 * refuse on {@code ASK}, and the skill would be never prompted for and always denied — with no error anywhere.
 * Reading through the registry also means a runtime created after assembly resolves without this class knowing
 * it exists.
 */
public final class AimonStackBuilder {

    /** Version string surfaced by the {@code /version} command. */
    private static final String RUNTIME_VERSION = "1.0.0";

    private static final Logger log = LoggerFactory.getLogger(AimonStackBuilder.class);

    private AimonStackBuilder() {
    }

    /**
     * Assembles and starts a stack.
     *
     * <p>
     * The two halves are separable — see {@link #assemble(AimonStackSpec)} — and this method is the answer for
     * every caller that has no reason to separate them: a test, the CLI, an embedding whose surrounding process
     * is already up by the time it wants an agent. A container that has its own notion of "the application is
     * ready" wants the other one.
     *
     * @param spec
     *            the description to build from (must not be null)
     * @return a running stack the caller owns and must {@link AimonStack#close()}
     * @throws AimonBootstrapException
     *             if assembly or start-up failed; everything already built has been closed before this is thrown
     */
    public static AimonStack build(AimonStackSpec spec) {
        final AimonStack stack = assemble(spec);
        try {
            stack.start();
        } catch (RuntimeException e) {
            log.error("Stack start-up failed; closing the assembled stack");
            closeQuietly(stack::close, e);
            if (e instanceof AimonBootstrapException) {
                throw e;
            }
            throw new AimonBootstrapException("Failed to start the AIMON stack: " + e.getMessage(), e);
        }
        return stack;
    }

    // Sits between build() and assemble() rather than after them, because the two assemble overloads have to be
    // adjacent (checkstyle:OverloadMethodsDeclarationOrder) and both callers of this are above it.
    private static void closeQuietly(Runnable close, RuntimeException primary) {
        try {
            close.run();
        } catch (RuntimeException teardownFailure) {
            // The build failure is what the caller needs to see; the teardown failure rides along so neither is
            // lost. Swapping them would report "3 resources failed to close" for a stack that failed to start.
            primary.addSuppressed(teardownFailure);
        }
    }

    /**
     * Assembles a stack without starting it — nothing is registered, no sweep runs, no cron fires.
     *
     * <p>
     * Every resource exists and every teardown entry is in place, so the returned stack is closeable and
     * complete; what has not happened is the three things that make it <i>reachable</i>. Its runtimes are not in
     * the {@code AgentRuntimeRegistry}, so no schedule and no session can resolve one, and neither background
     * sweeper is running. {@link AimonStack#start()} does exactly that and nothing else.
     *
     * <p>
     * This exists for hosts that decide when an application starts serving, which is most containers and all of
     * the ones with an HTTP listener. The order that matters is: runtimes registered <b>before</b> the socket
     * opens, scheduling started <b>after</b> it — otherwise a cron that fires during start-up either finds no
     * runtime or answers a request the front end is not ready to serve. A stack built by {@link #build} has made
     * that choice already, which is fine when there is no socket and wrong when there is.
     *
     * @param spec
     *            the description to build from (must not be null)
     * @return an assembled but unstarted stack the caller owns and must {@link AimonStack#close()}
     * @throws AimonBootstrapException
     *             if assembly failed; everything already built has been closed before this is thrown
     */
    public static AimonStack assemble(AimonStackSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");

        final TeardownRegistry teardown = new TeardownRegistry();
        final RuntimeDegradations.Collector degradations = RuntimeDegradations.collector();
        try {
            return assemble(spec, teardown, degradations);
        } catch (RuntimeException e) {
            log.error("Stack assembly failed after {} resource(s) were already constructed; closing them now",
                    teardown.entries().size());
            closeQuietly(teardown::closeAll, e);
            if (e instanceof AimonBootstrapException) {
                throw e;
            }
            throw new AimonBootstrapException("Failed to assemble the AIMON stack: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("checkstyle:MethodLength")
    private static AimonStack assemble(AimonStackSpec spec, TeardownRegistry teardown,
            RuntimeDegradations.Collector degradations) {

        // --- Skill hook shell -----------------------------------------------------------------------------
        // Closed last of everything: a declarative skill hook can run a shell action during any later phase's
        // teardown, and a shell closed early turns that into a failure inside shutdown itself. Skipped entirely
        // when the caller supplies a parser — a second shell would be a second process pool, and only one of
        // them would be on the teardown plan.
        final SkillParser skillParser = spec.getSkillParser().orElseGet(() -> {
            final LocalShell skillHookShell = teardown.own(TeardownPhase.SKILL_HOOK_SHELL, "skillHookShell",
                    new LocalShell());
            return new MarkdownSkillParser(new ShellArgumentTokenizer(),
                    new SkillHookSetParser(new DefaultShellActionExecutor(skillHookShell)));
        });

        // --- Session storage ------------------------------------------------------------------------------
        // The mailbox owns a background thread that writes transcript checkpoints. It is closed after the
        // sessions that feed it, so no turn is still producing checkpoints when the writer goes away.
        final SessionCheckpointMailbox sessionCheckpoints = teardown.own(TeardownPhase.CHECKPOINTS,
                "sessionCheckpoints", SessionCheckpointMailbox.background());
        final SessionRecordStore sessionRecordStore = spec.getSession().getRecordStore()
                .orElseGet(InMemorySessionRecordStore::new);
        if (spec.getSession().getRecordStore().isEmpty()) {
            degradations.add("session-durability",
                    "Sessions are held in memory only. Transcripts, session totals and budget overrides are lost on"
                            + " restart, and a second instance cannot serve a session this one started.");
        }
        final TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecordStore,
                sessionCheckpoints);
        final MessageQueueManager messageQueueManager = new DefaultMessageQueueManager(
                spec.getMessageQueueRepository().orElseGet(InMemoryMessageQueueRepository::new));

        // --- Skill approval -------------------------------------------------------------------------------
        // Both stores are shared by the policy chain (reads) and the approval channel (writes). Same instances,
        // deliberately: see the class javadoc. Borrowed when supplied — neither is on the teardown plan, because
        // whoever built the connection underneath closes the thing on top of it.
        final SkillApprovalSpec approvalSpec = spec.getSkillApproval();
        final AgentApprovalStore agentApprovalStore = approvalSpec.getAgentApprovalStore()
                .orElseGet(InMemoryAgentApprovalStore::new);
        final SessionApprovalStore sessionApprovalStore = approvalSpec.getSessionApprovalStore()
                .orElseGet(InMemorySessionApprovalStore::new);
        final SkillInvocationPolicy skillInvocationPolicy = new SessionScopedSkillInvocationPolicy(sessionApprovalStore,
                new ApprovalCachingSkillInvocationPolicy(agentApprovalStore, RuleBasedSkillInvocationPolicy.builder()
                        .defaultDecision(approvalSpec.getDefaultDecision()).build()));
        final SkillApprovalChannel approvalChannel = resolveApprovalChannel(approvalSpec, sessionApprovalStore,
                agentApprovalStore, degradations);

        // --- Pending turns --------------------------------------------------------------------------------
        final PendingTurnRegistry pendingTurnRegistry = approvalSpec.getPendingTurnRegistry()
                .orElseGet(InMemoryPendingTurnRegistry::new);
        final PendingTurnReaper.Builder reaperBuilder = PendingTurnReaper.builder().registry(pendingTurnRegistry)
                .interval(approvalSpec.getPendingTurnSweepInterval());
        approvalSpec.getPendingTurnExpirationListener().ifPresent(reaperBuilder::expirationListener);
        final PendingTurnReaper pendingTurnReaper = teardown.own(TeardownPhase.PENDING_TURNS, "pendingTurnReaper",
                reaperBuilder.build());
        if (spec.getSession().getMode() == DeploymentMode.DISTRIBUTED) {
            announceNodeLocalApprovals(approvalSpec, degradations);
        }

        // --- Subagents ------------------------------------------------------------------------------------
        final InMemorySubagentRegistry codeSubagentRegistry = new InMemorySubagentRegistry();
        final InMemorySubagentBehaviorRegistry codeSubagentBehaviorRegistry = new InMemorySubagentBehaviorRegistry();

        // --- Runtime registry -----------------------------------------------------------------------------
        // Application-scoped, and created here rather than inside the scheduling engine: the engine borrows it,
        // the session opener reads it, and the runtimes register into it. One instance, three readers.
        final AgentRuntimeRegistry agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();

        // --- Knowledge store ------------------------------------------------------------------------------
        // Resolved here rather than read straight off the spec: a store whose pages live in an agent's own file
        // system has to reach the registry, and the registry only exists from the line above.
        final KnowledgeStore knowledgeStore = resolveKnowledgeStore(spec, agentRuntimeRegistry);

        // --- Rewake ---------------------------------------------------------------------------------------
        // Three steps, and the order is forced: the listener needs the registry, the service needs the listener,
        // and the listener needs the service back. The cycle is closed by construction, not by a setter anyone
        // may forget — but it is still a setter, so it is called on the very next line.
        final DefaultRewakeFireListener rewakeFireListener = new DefaultRewakeFireListener(agentRuntimeRegistry);
        final DefaultRewakeService rewakeService = teardown.own(TeardownPhase.REWAKE, "rewakeService",
                new DefaultRewakeService(rewakeFireListener));
        rewakeFireListener.bindRewakeService(rewakeService);

        // --- Skill pre-flight -----------------------------------------------------------------------------
        // Resolved through the registry rather than closed over: the scanner is inside the shared executor, so
        // it is asked about whichever agent is running, and the registries are per-agent. Reading live also
        // covers runtimes registered after assembly. See the class javadoc for what a fixed registry would do.
        final SkillPreflightScanner skillPreflightScanner = SkillPreflightScanner
                .builder().policy(skillInvocationPolicy).registries(id -> agentRuntimeRegistry
                        .getAs(id, OrcaAgentRuntime.class).map(OrcaAgentRuntime::getSkillRegistry))
                .approvalChannel(approvalChannel).build();

        // --- Memory ---------------------------------------------------------------------------------------
        // Assembled before the executor because the executor takes the provider it produces, and before the
        // runtime factory because the runtimes take its enricher and tool provider. What it leaves out — always
        // the write path, and the tools in per-caller mode — it records as a degradation rather than as silence.
        final MemoryAssembly memory = MemoryAssembly.from(spec.getMemory().orElse(null), degradations);

        // --- Executor -------------------------------------------------------------------------------------
        final ExecutorSpec executorSpec = spec.getExecutor();
        final OrcaAgentExecutorFactory executorFactory = new OrcaAgentExecutorFactory()
                .withMessageQueueManager(messageQueueManager).withSkillPreflightScanner(skillPreflightScanner)
                .withPendingTurnRegistry(pendingTurnRegistry).withSubagentBehaviorRegistry(codeSubagentBehaviorRegistry)
                .withRewakeService(rewakeService).withUseStreaming(executorSpec.isStreaming()).withCostEstimator(
                        executorSpec.getCostEstimator().orElseGet(TablePricedCostEstimator::withDefaultPrices));
        spec.getSkillApproval().getPendingTurnTtl().ifPresent(executorFactory::withPendingTurnTtl);
        executorSpec.getTracer().ifPresent(executorFactory::withTracer);
        executorSpec.getTracePayloadPolicy().ifPresent(executorFactory::withTracePayloadPolicy);
        // The spec rejects having both, so the order of this or() decides nothing — it is written caller-first so
        // that a supplied provider keeps working if the rejection is ever relaxed.
        executorSpec.getMemoryContextProvider().or(memory::getContextProvider)
                .ifPresent(executorFactory::withMemoryContextProvider);
        if (executorSpec.getTracer().isEmpty() && executorSpec.getTracePayloadPolicy().isPresent()) {
            degradations.add("tracing",
                    "A trace payload policy is configured but no tracer is, so nothing records spans and the policy"
                            + " has no effect. Usually a tracer that was removed without its policy.");
        }
        final OrcaAgentExecutor agentExecutor = executorFactory.create(spec.getLlm().getClient(), transcriptManager);
        // The hook thread pool is created three interfaces down from here, by the hook execution manager's own
        // default. Neither HookExecutor nor HookExecutionManager declares a lifecycle — executing a hook is a
        // stateless service and most implementations have nothing to release — so the narrowing happens once, here,
        // where the concrete assembly that was just built is still known. createDefaultHookExecutionManager() is
        // overridable, so a stack whose manager is not closeable simply registers nothing.
        if (agentExecutor.getHookExecutionManager() instanceof AutoCloseable closeableHookExecution) {
            teardown.own(TeardownPhase.HOOK_EXECUTOR, "hookExecutionManager", closeableHookExecution);
        }

        // --- Scheduling -----------------------------------------------------------------------------------
        // The teardown entry holds the lifecycle rather than the engine, so "the container already stopped it"
        // and "nobody did" are the same code path. See SchedulingLifecycle.
        final SchedulingLifecycle schedulingLifecycle = createSchedulingEngine(spec, agentRuntimeRegistry, teardown,
                degradations);

        // --- Agent runtimes -------------------------------------------------------------------------------
        // One pass per declared agent. Everything above is shared; everything inside the loop is per-agent, and
        // the boundary is not cosmetic — a file system or skill registry that crosses it is one agent reading
        // another's files or running another's skills, with nothing in any log to say so.
        final ToolSpec toolSpec = spec.getTools();
        final List<ToolContextEnricher> toolContextEnrichers = new ArrayList<>(toolSpec.getContextEnrichers());
        memory.getContextEnricher().ifPresent(toolContextEnrichers::add);
        final OrcaAgentRuntimeFactory runtimeFactory = new OrcaAgentRuntimeFactory(RUNTIME_VERSION,
                StackPaths.COMMANDS_DIRECTORY, StackPaths.AGENTS_DIRECTORY, StackPaths.USER_SKILLS_DIRECTORY,
                knowledgeStore).withCodeSubagentRegistry(codeSubagentRegistry)
                .withPendingTurnRegistry(pendingTurnRegistry).withAgentApprovalStore(agentApprovalStore)
                .withSessionApprovalStore(sessionApprovalStore).withSkillInvocationPolicy(skillInvocationPolicy)
                .withToolContextEnrichers(toolContextEnrichers).withRewakeService(rewakeService)
                .withWorkflowRunnerEnabled(toolSpec.isWorkflowRunnerEnabled());
        final ScheduledTaskManager taskManager = schedulingLifecycle == null
                ? null
                : schedulingLifecycle.engine().getTaskManager();

        // Bundles are loaded once and kept as templates: the same description builds agent:ops at startup and
        // agent:ops:acme when that tenant first appears, so the two can never drift apart.
        final Map<AgentRuntimeId, AgentSpec> declared = new LinkedHashMap<>();
        final List<AgentDescriptor> agentDescriptors = new ArrayList<>();
        final StackAgentRuntimeProvisioner.Builder provisionerBuilder = StackAgentRuntimeProvisioner.builder()
                .fileSystemSpec(spec.getFileSystem()).toolSpec(toolSpec).runtimeFactory(runtimeFactory)
                .knowledgeToolsEnabled(knowledgeStore != null).memoryToolProvider(memory.getToolProvider().orElse(null))
                .agentExecutor(agentExecutor).taskManager(taskManager).skillParser(skillParser)
                .credentialStore(spec.getCredentialStore().orElse(null))
                .credentialStoreFactory(spec.getCredentialStoreFactory().orElse(null))
                .agentCustomizers(spec.getAgentCustomizers());
        for (AgentSpec agentSpec : spec.getAgents()) {
            final AgentBundle agentBundle = agentSpec.getBundle()
                    .orElseGet(() -> loadBundle(agentSpec.getBundleName(), skillParser));
            // The ref decides the id, not the name inside the bundle. Deriving it from the definition would mean
            // a caller cannot know what to route on without opening the file, and an edit to a frontmatter line
            // would silently move an agent out from under every session, schedule and approval that named it.
            final AgentRuntimeId runtimeId = agentSpec.getDiscriminator()
                    .map(d -> AgentRuntimeId.fromName(agentSpec.getName(), d))
                    .orElseGet(() -> AgentRuntimeId.fromName(agentSpec.getName()));
            if (declared.containsKey(runtimeId)) {
                // The spec rejects duplicate (ref, discriminator) pairs already, so reaching here means the two
                // agreed on neither. Kept as a guard rather than an assertion: the second registration would
                // otherwise replace the first, leaving a runtime nothing can reach and one agent's sessions
                // running the other's tools.
                throw new AimonBootstrapException("Two agents resolved to the same runtime id " + runtimeId
                        + ". Agent refs must be unique, or differ by discriminator.");
            }
            declared.put(runtimeId, agentSpec);
            final StackAgentRuntimeProvisioner.AgentTemplate template = StackAgentRuntimeProvisioner.AgentTemplate
                    .of(agentSpec, agentBundle);
            provisionerBuilder.template(runtimeId.agentName(), template);
            // Described from the template rather than from the spec, so what a host lists is the same value the
            // customizers were asked about — including the bundle name after it has defaulted to the ref.
            agentDescriptors.add(template.describe(runtimeId));
        }
        final StackAgentRuntimeProvisioner provisioner = provisionerBuilder.build();

        final Map<AgentRuntimeId, OrcaAgentRuntime> runtimes = new LinkedHashMap<>();
        final Map<AgentRuntimeId, VirtualFileSystem> fileSystems = new LinkedHashMap<>();
        for (AgentRuntimeId runtimeId : declared.keySet()) {
            // The same call the resolver makes for a tenant, differing only in where the resources it creates are
            // recorded: these belong to the process, a tenant's belong to its runtime.
            final StackAgentRuntimeProvisioner.Assembly assembly = provisioner.createRuntime(runtimeId,
                    (label, resource) -> teardown.own(TeardownPhase.AGENT_RESOURCES, label, resource));
            final OrcaAgentRuntime runtime = teardown.own(TeardownPhase.AGENT_RUNTIMES, "runtime(" + runtimeId + ")",
                    assembly.getRuntime());
            // Registration is AimonStack.startRuntimes()'s job, not this loop's — a runtime in the registry is a
            // runtime a cron fire or an inbound session can reach, and that must not become true until the host
            // says the application is starting. The unregister entry is still recorded here, because teardown
            // entries have to exist before the thing they undo does; unregistering an id that was never
            // registered is a no-op.
            teardown.own(TeardownPhase.RUNTIME_REGISTRY, "unregister(" + runtimeId + ")",
                    () -> agentRuntimeRegistry.unregister(runtimeId));
            runtimes.put(runtimeId, runtime);
            fileSystems.put(runtimeId, assembly.getFileSystem());
        }
        final AgentRuntimeId primaryRuntimeId = runtimes.keySet().iterator().next();
        if (runtimes.size() > 1 && spec.getFileSystem().isShared()) {
            degradations.add("file-system-isolation", "All " + runtimes.size()
                    + " agent runtimes share one caller-supplied file system, so each can read and overwrite what"
                    + " the others write. Correct for agents meant to collaborate through a shared workspace, and"
                    + " a data leak between tenants otherwise — use FileSystemSpec.localAt or .factory for one per"
                    + " runtime.");
        }

        // --- Tenant runtimes ------------------------------------------------------------------------------
        // The second creation point, and the last one: everything with a discriminator is built here, on first
        // use, through the same provisioner the loop above used. Registered after the eager runtimes so it closes
        // before them — within a phase the plan runs in reverse registration order, and a tenant runtime is the
        // more likely of the two to still be doing something.
        final AgentRuntimeSpec agentRuntimeSpec = spec.getAgentRuntimes();
        final AgentRuntimeResolver.Builder resolverBuilder = AgentRuntimeResolver
                .builder(agentRuntimeRegistry, provisioner).declaredIds(declared.keySet())
                .eviction(agentRuntimeSpec.getEviction()).idleTtl(agentRuntimeSpec.getIdleTtl())
                .maxEntries(agentRuntimeSpec.getMaxEntries());
        agentRuntimeSpec.getSweepInterval().ifPresent(resolverBuilder::sweepInterval);
        final AgentRuntimeResolver agentRuntimeResolver = teardown.own(TeardownPhase.AGENT_RUNTIMES,
                "agentRuntimeResolver", resolverBuilder.build());

        // --- Session router -------------------------------------------------------------------------------
        // Registered before start-up of anything else so that a failure below still drains sessions first.
        final SessionRouter sessionRouter = buildSessionRouter(spec, agentRuntimeResolver, agentExecutor,
                messageQueueManager, sessionRecordStore, sessionApprovalStore);
        final Duration drainTimeout = spec.getSession().getDrainTimeout();
        teardown.own(TeardownPhase.SESSIONS, "sessionRouter.closeGracefully(" + drainTimeout + ")",
                () -> closeRouter(sessionRouter, drainTimeout));

        // --- Assembled ------------------------------------------------------------------------------------
        // Nothing is started here. Everything that has to happen before this stack can serve is behind
        // AimonStack.start(), which runs after the last teardown entry above is in place — so whatever it starts
        // has a matching close even if the line after it throws.
        final AimonStack stack = new AimonStack(spec, teardown, sessionRouter, agentExecutor, agentRuntimeRegistry,
                schedulingLifecycle, sessionRecordStore, messageQueueManager, pendingTurnRegistry, pendingTurnReaper,
                fileSystems, primaryRuntimeId, runtimes, agentDescriptors, agentRuntimeResolver, degradations.build());
        log.info("AIMON stack assembled: {} teardown entrie(s), agent(s) {}", teardown.entries().size(),
                runtimes.keySet());
        if (!stack.degradations().isEmpty()) {
            log.info("{}", stack.degradations().describe());
        }
        return stack;
    }

    private static AgentBundle loadBundle(String bundleName, SkillParser skillParser) {
        final AgentBundleLoader loader = new AdaptiveAgentBundleLoader(StackPaths.AGENT_BUNDLE_BASE_PATH,
                new MarkdownAgentDefinitionParser(), Thread.currentThread().getContextClassLoader(), skillParser);
        return loader.load(bundleName);
    }

    private static KnowledgeStore resolveKnowledgeStore(AimonStackSpec spec,
            AgentRuntimeRegistry agentRuntimeRegistry) {
        if (spec.getKnowledgeStoreFactory().isEmpty()) {
            return spec.getKnowledgeStore().orElse(null);
        }
        return Objects.requireNonNull(spec.getKnowledgeStoreFactory().get().create(agentRuntimeRegistry),
                "The knowledge store factory returned null");
    }

    /**
     * Builds the approval channel for the configured mode, or returns {@code null} for
     * {@link SkillApprovalSpec.ChannelMode#SUSPEND} — the one mode whose behaviour <i>is</i> the absence of a
     * channel.
     */
    private static SkillApprovalChannel resolveApprovalChannel(SkillApprovalSpec spec,
            SessionApprovalStore sessionApprovalStore, AgentApprovalStore agentApprovalStore,
            RuntimeDegradations.Collector degradations) {
        switch (spec.getChannelMode()) {
            case SUPPLIED :
                return spec.getSuppliedChannel().orElseThrow();
            case SUPPLIED_FACTORY :
                return Objects.requireNonNull(
                        spec.getChannelFactory().orElseThrow().create(sessionApprovalStore, agentApprovalStore),
                        "The skill approval channel factory returned null");
            case SUSPEND :
                // Null is the request, not an omission: the pre-flight scanner resolves an ASK inline whenever it
                // has a channel, so the only way to reach the suspend path is to withhold one.
                degradations.add("skill-approval",
                        "Skills requiring approval suspend the turn instead of being answered. Nothing in the stack"
                                + " delivers the question or answers it — a deployment that does not read the"
                                + " pending-turn registry and approve out of band has turned every such skill into a"
                                + " turn that stops until its TTL expires.");
                return null;
            case ALLOW_LIST :
                if (spec.getAllowedSkills().isEmpty()) {
                    degradations.add("skill-approval",
                            "The allow-list is empty, so every skill requiring approval is denied. This is the same"
                                    + " behaviour as the deny-all channel; if that was not intended, the allow-list"
                                    + " was probably bound from a missing configuration key.");
                }
                return new AllowListSkillApprovalChannel(sessionApprovalStore, agentApprovalStore,
                        spec.getAllowedSkills());
            case DENY_ALL :
            default :
                degradations.add("skill-approval",
                        "Every skill that reaches the approval channel is denied. Nothing can grant approval at"
                                + " runtime, so skills outside the policy rules will never run.");
                return new DenyAllSkillApprovalChannel(sessionApprovalStore, agentApprovalStore);
        }
    }

    /**
     * Announces the approval-axis stores that are still node-local under distributed sessions.
     *
     * <p>
     * Distributing sessions does not distribute everything keyed by one. All three fail closed — an approval is
     * re-asked, a suspended turn expires — so the consequence is friction rather than an escalation, which is
     * exactly why it would otherwise go unnoticed.
     *
     * <p>
     * Named one by one rather than as a single sentence about "approvals", because the three are supplied
     * independently and the half-configured shapes are the ones worth reading about. A shared pending-turn
     * registry over node-local approval stores finds the suspended turn from another node and then releases it
     * into a node with no record of the decision; shared approval stores under a node-local registry stop the
     * re-asking but leave {@code /approve} unable to find the turn. Nothing is announced when all three were
     * supplied — whether those implementations genuinely span nodes is not something the builder can inspect,
     * and having been handed them it says nothing rather than guessing.
     *
     * @param approvalSpec
     *            the approval spec, read for which stores the caller supplied
     * @param degradations
     *            the collector the announcement is added to
     */
    private static void announceNodeLocalApprovals(SkillApprovalSpec approvalSpec,
            RuntimeDegradations.Collector degradations) {
        final List<String> nodeLocal = new ArrayList<>();
        if (approvalSpec.getSessionApprovalStore().isEmpty()) {
            nodeLocal.add("a skill approved for a session asks again once that session moves to another node"
                    + " (SkillApprovalSpec.withSessionApprovalStore)");
        }
        if (approvalSpec.getAgentApprovalStore().isEmpty()) {
            nodeLocal.add("an 'always allow in this agent' answer is unknown to every other node"
                    + " (SkillApprovalSpec.withAgentApprovalStore)");
        }
        if (approvalSpec.getPendingTurnRegistry().isEmpty()) {
            nodeLocal.add("an /approve for a turn suspended elsewhere finds nothing to release"
                    + " (SkillApprovalSpec.withPendingTurnRegistry)");
        }
        if (nodeLocal.isEmpty()) {
            return;
        }
        final String quantifier = nodeLocal.size() == 3 ? "all 3 of the" : nodeLocal.size() + " of the 3";
        degradations.add("distributed-approvals",
                "Sessions are distributed but " + quantifier + " approval-axis stores "
                        + (nodeLocal.size() == 1 ? "is" : "are") + " still node-local: " + String.join("; ", nodeLocal)
                        + ".");
    }

    private static SchedulingLifecycle createSchedulingEngine(AimonStackSpec spec,
            AgentRuntimeRegistry agentRuntimeRegistry, TeardownRegistry teardown,
            RuntimeDegradations.Collector degradations) {
        if (!spec.getScheduling().isEnabled()) {
            degradations.add("scheduling",
                    "No scheduling engine. Cron and one-shot task registration fails at the moment a skill or tool"
                            + " attempts it, not at startup.");
            return null;
        }
        announceSchedulingDurability(spec.getScheduling(), degradations);
        final SchedulingEngineBuilder builder = SchedulingEngineBuilder.create()
                .agentRuntimeRegistry(agentRuntimeRegistry);
        spec.getScheduling().getTaskScheduler().ifPresent(builder::taskScheduler);
        spec.getScheduling().getTaskSchedulerFactory().ifPresent(builder::taskSchedulerFactory);
        spec.getScheduling().getTaskRepository().ifPresent(builder::taskRepository);
        spec.getScheduling().getInterruptBus().ifPresent(builder::interruptBus);
        spec.getScheduling().getExecutionGuard().ifPresent(builder::executionGuard);
        // Registered even though it has not been started: the engine's builder already stood up the pools it
        // will run on, so an assembled-but-never-started stack still has them to release.
        return teardown.own(TeardownPhase.SCHEDULING, "schedulingEngine", new SchedulingLifecycle(builder.build()));
    }

    /**
     * Says what a restart will lose, in the terms the deployment actually chose.
     *
     * <p>
     * A scheduled task needs both halves to survive: the trigger, which the scheduler holds, and the record it names,
     * which the repository holds. The announcement is graded rather than fixed because a fixed one is wrong for two
     * deployments in opposite directions — silent when the default repository quietly drops everything, and crying
     * degradation at a deployment that supplied a durable one. What the stack can actually see is which halves were
     * left at their defaults, so that is what it reports.
     */
    private static void announceSchedulingDurability(SchedulingSpec scheduling,
            RuntimeDegradations.Collector degradations) {
        if (scheduling.getTaskRepository().isEmpty()) {
            // Announced rather than left to be discovered, because a durable scheduler makes it look solved: the
            // triggers come back after a restart and the tasks they name do not, so every firing lands on
            // "task not found" in the log.
            degradations.add("scheduling-durability",
                    "Scheduled tasks are held in memory and are gone after a restart. A durable scheduler backend does"
                            + " not change this — its triggers survive and then fire against tasks that no longer"
                            + " exist. Supply a durable ScheduledTaskRepository through the scheduling spec.");
            return;
        }
        final boolean schedulerSupplied = scheduling.getTaskScheduler().isPresent()
                || scheduling.getTaskSchedulerFactory().isPresent();
        if (!schedulerSupplied) {
            degradations.add("scheduling-durability",
                    "Task records go to the supplied repository, but the triggers do not: the default in-memory"
                            + " scheduler holds them, so a restart leaves the stored tasks with nothing scheduled to"
                            + " fire them.");
        }
        // Both halves were replaced deliberately. Whether either is genuinely durable is a property of the supplied
        // implementations, which this builder cannot inspect and will not guess at.
    }

    /**
     * Builds the router, handing it whichever session SPIs the spec carries.
     *
     * <p>
     * The SPIs are passed through unconditionally rather than only in {@code DISTRIBUTED} mode. A single-node
     * deployment that supplies a Redis lease store is not a mistake to correct — it is a deployment that wants
     * its leases visible to an operator, or one node of a cluster being brought up first — and silently dropping
     * the collaborator it configured would be the worse answer. The mode decides what may be <em>defaulted</em>,
     * which is the builder's own rule; it does not decide what may be supplied.
     */
    private static SessionRouter buildSessionRouter(AimonStackSpec spec, AgentRuntimeResolver agentRuntimeResolver,
            OrcaAgentExecutor agentExecutor, MessageQueueManager messageQueueManager,
            SessionRecordStore sessionRecordStore, SessionApprovalStore sessionApprovalStore) {
        final SessionSpec session = spec.getSession();
        final StackLiveSessionOpener opener = new StackLiveSessionOpener(agentRuntimeResolver, agentExecutor,
                messageQueueManager, agentExecutor.getHookExecutionManager(), sessionRecordStore);
        final SessionRouterBuilder builder = SessionRouter.builder().sessionOpener(opener)
                .sessionRecordStore(sessionRecordStore).sessionApprovalStore(sessionApprovalStore)
                .mode(session.getMode());
        session.getNodeId().ifPresent(builder::nodeId);
        session.getLeaseStore().ifPresent(builder::sessionLeaseStore);
        session.getSignalBus().ifPresent(builder::signalBus);
        session.getInbox().ifPresent(builder::sessionInbox);
        session.getIdempotencyStore().ifPresent(builder::idempotencyStore);
        session.getIdleTtl().ifPresent(builder::idleTtl);
        session.getMaxCachedSessions().ifPresent(builder::maxCachedSessions);
        return builder.build();
    }

    private static void closeRouter(SessionRouter sessionRouter, Duration drainTimeout) {
        if (!sessionRouter.closeGracefully(drainTimeout)) {
            // Not an error worth failing shutdown over — the router has already forced its sessions closed. But
            // it does mean a turn was cut off mid-flight, which is the difference between a clean deploy and a
            // user seeing a truncated answer, so it must not be silent.
            log.warn("Session router did not drain within {}; in-flight turns were interrupted", drainTimeout);
        }
    }
}
