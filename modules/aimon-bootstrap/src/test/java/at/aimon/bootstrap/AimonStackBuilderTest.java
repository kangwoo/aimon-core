package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.assemble.MemoryAssembly;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.AgentWorkspaceLayout;
import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.bootstrap.spec.SchedulingSpec;
import at.aimon.bootstrap.spec.SkillApprovalSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.base.Principal;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.InMemoryRepresentationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledExecutionGuard;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.TaskScheduler;
import at.aimon.core.tools.memory.MemoryRecallTool;
import at.aimon.core.tools.memory.MemorySearchTool;
import at.aimon.core.tools.memory.ObserveTool;

/**
 * Stands a real stack up and tears it down — the whole point of the neutral layer being pure Java.
 *
 * <p>
 * No container, no context, no annotations: if these pass, the assembly is correct independently of whatever
 * framework ends up calling it.
 */
class AimonStackBuilderTest {

    /** Never called — no test here runs a turn. */
    private static final LlmClient STUB_LLM = new LlmClient() {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

    };

    /**
     * Stands in for a scheduler the deployment supplied — enough for the builder to see that the trigger half was
     * replaced. Nothing here fires, which is exactly what a durability assertion wants.
     */
    private static final TaskScheduler NO_OP_SCHEDULER = new TaskScheduler() {

        @Override
        public void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression) {
            // no trigger, no firing
        }

        @Override
        public void unschedule(ScheduledTaskId taskId) {
            // nothing was scheduled
        }

        @Override
        public boolean exists(ScheduledTaskId taskId) {
            return false;
        }

        @Override
        public void clear() {
            // nothing to clear
        }

        @Override
        public void start() {
            // nothing to start
        }

        @Override
        public void shutdown() {
            // nothing to drain
        }

    };

    private static AgentBundle bundle(String name) {
        return AgentBundle.builder()
                .agent(DefaultAgent.builder().name(name).systemPrompt("You are a test agent.").maxIterations(5).build())
                .build();
    }

    private static AimonStackSpec.Builder specFor(Path workspace, String agentName) {
        return AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.of(bundle(agentName)));
    }

    @Test
    @DisplayName("assembles a working stack from a minimal spec")
    void assemblesMinimalStack(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").build())) {
            assertThat(stack.sessionRouter()).isNotNull();
            assertThat(stack.agentExecutor()).isNotNull();
            assertThat(stack.sessionRecordStore()).isNotNull();
            assertThat(stack.fileSystem(stack.primaryRuntimeId())).isPresent();
            assertThat(stack.primaryRuntimeId()).isEqualTo(AgentRuntimeId.from(bundle("ops").getAgent()));
            assertThat(stack.runtime(stack.primaryRuntimeId())).isPresent();
            assertThat(stack.isClosed()).isFalse();
        }
    }

    @Test
    @DisplayName("the runtime the opener will resolve is the one registered in the shared registry")
    void registersRuntimeInSharedRegistry(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").build())) {
            // Instance identity, not merely presence: a second equivalent registry would make every cron re-fire
            // and every session open fail to resolve, with no error at assembly time.
            assertThat(stack.agentRuntimeRegistry().get(stack.primaryRuntimeId()))
                    .containsSame(stack.runtime(stack.primaryRuntimeId()).orElseThrow());
        }
    }

    @Test
    @DisplayName("assemble() constructs everything and registers nothing")
    void assembleDoesNotStart(@TempDir Path workspace) {
        // The gap between the two is what lets a container own the moment of registration. If assembly registered
        // as it built, a host would have no way to hold the registry empty until the rest of its beans exist.
        try (AimonStack stack = AimonStackBuilder.assemble(specFor(workspace, "ops").build())) {
            assertThat(stack.isStarted()).isFalse();
            assertThat(stack.runtime(stack.primaryRuntimeId())).as("built, just not reachable yet").isPresent();
            assertThat(stack.agentRuntimeRegistry().get(stack.primaryRuntimeId())).isEmpty();

            assertThat(stack.health().isServing()).isFalse();
            // "Not registered yet" and "no longer registered" are the same missing entry and opposite problems;
            // an operator reading this before start-up finished should not be sent looking for a crash.
            assertThat(stack.health().describe()).contains("startRuntimes() has not");
        }
    }

    @Test
    @DisplayName("start() after assemble() is what build() does, and repeats harmlessly")
    void startIsIdempotent(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.assemble(specFor(workspace, "ops").build())) {
            assertThat(stack.start()).isSameAs(stack);
            assertThat(stack.isStarted()).isTrue();
            assertThat(stack.agentRuntimeRegistry().get(stack.primaryRuntimeId())).isPresent();
            assertThat(stack.health().isServing()).isTrue();

            // A host that calls start() itself and one that inherits build()'s call must not add up to two
            // registrations, two reapers and two sweepers.
            assertThatCode(stack::start).doesNotThrowAnyException();
            assertThat(stack.agentRuntimeRegistry().get(stack.primaryRuntimeId()))
                    .containsSame(stack.runtime(stack.primaryRuntimeId()).orElseThrow());
        }
    }

    @Test
    @DisplayName("stopping scheduling twice is not an error, and neither is closing after it")
    void schedulingStopsOnceHoweverManyCallers(@TempDir Path workspace) {
        // Two callers own this edge — a container lifecycle and the ordered teardown — and on a normal shutdown
        // both fire. The second one through has to find the work already done rather than shut the pools twice.
        final AimonStack stack = AimonStackBuilder
                .build(specFor(workspace, "ops").scheduling(SchedulingSpec.enabled()).build());
        try {
            assertThat(stack.schedulingEngine()).isPresent();
            assertThatCode(stack::stopScheduling).doesNotThrowAnyException();
            assertThatCode(stack::stopScheduling).doesNotThrowAnyException();
            assertThat(stack.health().isServing()).as("a stopped scheduler fires nothing").isFalse();
        } finally {
            assertThatCode(stack::close).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a discriminator produces the agent:<name>:<discriminator> runtime id")
    void discriminatorShapesRuntimeId(@TempDir Path workspace) {
        final AimonStackSpec spec = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                .llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.builder().bundle(bundle("ops")).discriminator("tenant-a").build()).build();

        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            assertThat(stack.primaryRuntimeId().toString()).isEqualTo("agent:ops:tenant-a");
        }
    }

    @Test
    @DisplayName("scheduling off is a recorded degradation, not a failure")
    void schedulingOffDegrades(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").build())) {
            assertThat(stack.schedulingEngine()).isEmpty();
            assertThat(stack.degradations().has("scheduling")).isTrue();
            assertThat(stack.health().getStatus()).isEqualTo(HealthReport.Status.DEGRADED);
            assertThat(stack.health().isServing()).isTrue();
        }
    }

    @Test
    @DisplayName("scheduling on produces an engine and drops the degradation")
    void schedulingOnRemovesDegradation(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace, "ops").scheduling(SchedulingSpec.enabled()).build())) {
            assertThat(stack.schedulingEngine()).isPresent();
            assertThat(stack.degradations().has("scheduling")).isFalse();
        }
    }

    @Test
    @DisplayName("scheduling on with no repository says the tasks are gone after a restart")
    void schedulingWithoutRepositoryAnnouncesInMemoryTasks(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace, "ops").scheduling(SchedulingSpec.enabled()).build())) {
            assertThat(stack.degradations().has("scheduling-durability")).isTrue();
            assertThat(stack.degradations().describe()).contains("gone after a restart");
        }
    }

    @Test
    @DisplayName("a repository over the default scheduler is announced as the other half missing")
    void schedulingWithRepositoryOnlyAnnouncesTheTriggerHalf(@TempDir Path workspace) {
        // The dangerous shape is not "nothing is durable" but "one half is" — the stored task outlives the restart
        // and nothing is left scheduled to fire it, which looks like the feature working until the hour comes.
        final SchedulingSpec scheduling = SchedulingSpec.enabled()
                .withTaskRepository(new InMemoryScheduledTaskRepository());

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").scheduling(scheduling).build())) {
            assertThat(stack.degradations().has("scheduling-durability")).isTrue();
            assertThat(stack.degradations().describe()).contains("the triggers do not");
        }
    }

    @Test
    @DisplayName("both halves replaced, and the stack stops guessing")
    void schedulingWithBothHalvesSuppliedIsSilent(@TempDir Path workspace) {
        // Whether the two supplied implementations are genuinely durable is not something the builder can inspect.
        // Having been handed both, it says nothing rather than guessing in either direction.
        final SchedulingSpec scheduling = SchedulingSpec.enabled(executor -> NO_OP_SCHEDULER)
                .withTaskRepository(new InMemoryScheduledTaskRepository());

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").scheduling(scheduling).build())) {
            assertThat(stack.degradations().has("scheduling-durability")).isFalse();
        }
    }

    @Test
    @DisplayName("the supplied repository is the one the engine writes its tasks into")
    void suppliedRepositoryReceivesRegisteredTasks(@TempDir Path workspace) {
        // The seam is only worth having if the repository reaches the task manager. A spec that carried it and a
        // builder that dropped it would still pass every assertion above.
        final InMemoryScheduledTaskRepository repository = new InMemoryScheduledTaskRepository();
        final SchedulingSpec scheduling = SchedulingSpec.enabled(executor -> NO_OP_SCHEDULER)
                .withTaskRepository(repository);

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").scheduling(scheduling).build())) {
            final ScheduledTask task = ScheduledTask.builder().id(ScheduledTaskId.of("nightly")).name("nightly sweep")
                    .cronExpression("0 3 * * *").owner(Principal.user("ops")).boundRuntimeId(stack.primaryRuntimeId())
                    .addStep(RoutineStep.of("Read", "{}")).build();

            stack.schedulingEngine().orElseThrow().getTaskManager().register(task);

            assertThat(repository.findById(ScheduledTaskId.of("nightly"))).isPresent();
        }
    }

    @Test
    @DisplayName("a repository on a disabled spec is refused rather than silently unread")
    void repositoryOnDisabledSchedulingIsRefused() {
        assertThatThrownBy(() -> SchedulingSpec.disabled().withTaskRepository(new InMemoryScheduledTaskRepository()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Scheduling is disabled");
    }

    @Test
    @DisplayName("the supplied interrupt bus is both subscribed to and published on")
    void suppliedInterruptBusIsWiredInBothDirections(@TempDir Path workspace) {
        // Both directions matter and they are wired in different places — the engine subscribes, the task manager
        // publishes. A spec that reached only one of them would leave a node that hears stop requests but never
        // makes any, or the reverse, and either half alone is silently useless.
        final RecordingInterruptBus bus = new RecordingInterruptBus();
        final SchedulingSpec scheduling = SchedulingSpec.enabled(executor -> NO_OP_SCHEDULER)
                .withTaskRepository(new InMemoryScheduledTaskRepository()).withInterruptBus(bus);

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").scheduling(scheduling).build())) {
            assertThat(bus.subscriptions).as("the engine must subscribe when it is built").isEqualTo(1);

            final ScheduledTask task = ScheduledTask.builder().id(ScheduledTaskId.of("nightly")).name("nightly sweep")
                    .cronExpression("0 3 * * *").owner(Principal.user("ops")).boundRuntimeId(stack.primaryRuntimeId())
                    .addStep(RoutineStep.of("Read", "{}")).build();
            stack.schedulingEngine().orElseThrow().getTaskManager().register(task);
            stack.schedulingEngine().orElseThrow().getTaskManager().cancel(ScheduledTaskId.of("nightly"),
                    Principal.user("ops"));

            assertThat(bus.published).containsExactly(ScheduledTaskId.of("nightly"));
        }
    }

    @Test
    @DisplayName("the supplied execution guard is the one asked before a fire")
    void suppliedExecutionGuardIsConsultedBeforeAFire(@TempDir Path workspace) {
        // Denying every fire makes the assertion unambiguous: if the guard reaching the engine were dropped, the
        // default would grant the fire and the run would proceed, so "was asked" and "was obeyed" are checked at once.
        final DenyingExecutionGuard guard = new DenyingExecutionGuard();
        final InMemoryScheduledTaskRepository repository = new InMemoryScheduledTaskRepository();
        final SchedulingSpec scheduling = SchedulingSpec.enabled(executor -> NO_OP_SCHEDULER)
                .withTaskRepository(repository).withExecutionGuard(guard);

        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").scheduling(scheduling).build())) {
            final ScheduledTask task = ScheduledTask.builder().id(ScheduledTaskId.of("nightly")).name("nightly sweep")
                    .cronExpression("0 3 * * *").owner(Principal.user("ops")).boundRuntimeId(stack.primaryRuntimeId())
                    .addStep(RoutineStep.of("Read", "{}")).build();
            stack.schedulingEngine().orElseThrow().getTaskManager().register(task);

            stack.schedulingEngine().orElseThrow().getTaskManager().executeTask(ScheduledTaskId.of("nightly"));

            assertThat(guard.asked).containsExactly(ScheduledTaskId.of("nightly"));
            assertThat(repository.findById(ScheduledTaskId.of("nightly")).orElseThrow().getLastExecutedAt())
                    .as("a denied fire must not have run").isEmpty();
        }
    }

    @Test
    @DisplayName("an interrupt bus or execution guard on a disabled spec is refused")
    void clusterSeamsOnDisabledSchedulingAreRefused() {
        assertThatThrownBy(() -> SchedulingSpec.disabled().withInterruptBus(new RecordingInterruptBus()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Scheduling is disabled");
        assertThatThrownBy(() -> SchedulingSpec.disabled().withExecutionGuard(new DenyingExecutionGuard()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Scheduling is disabled");
    }

    /** Records what the stack published and how many times it subscribed, without delivering anything. */
    private static final class RecordingInterruptBus implements ScheduledTaskInterruptBus {

        private final List<ScheduledTaskId> published = new ArrayList<>();
        private int subscriptions;

        @Override
        public void publish(ScheduledTaskId taskId, InterruptReason reason) {
            published.add(taskId);
        }

        @Override
        public Subscription subscribe(InterruptListener listener) {
            subscriptions++;
            return () -> {
            };
        }
    }

    /** Refuses every fire and remembers being asked. */
    private static final class DenyingExecutionGuard implements ScheduledExecutionGuard {

        private final List<ScheduledTaskId> asked = new ArrayList<>();

        @Override
        public Optional<ExecutionLease> tryBegin(ScheduledTaskId taskId) {
            asked.add(taskId);
            return Optional.empty();
        }
    }

    @Test
    @DisplayName("suspend approval assembles with no channel at all and says so")
    void suspendApprovalDegrades(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder
                .build(specFor(workspace, "ops").skillApproval(SkillApprovalSpec.suspend()).build())) {
            // Withholding the channel is what makes the pre-flight scanner suspend rather than resolve inline,
            // so "no channel" must not be mistaken for a broken build.
            assertThat(stack.degradations().has("skill-approval")).isTrue();
            assertThat(stack.degradations().describe()).contains("pending-turn registry");
            assertThat(stack.pendingTurnRegistry()).isNotNull();
        }
    }

    @Test
    @DisplayName("a configured pending-turn TTL survives assembly")
    void pendingTurnTtlIsAccepted(@TempDir Path workspace) {
        final SkillApprovalSpec approval = SkillApprovalSpec.suspend().withPendingTurnTtl(Duration.ofMinutes(5));

        assertThat(approval.getPendingTurnTtl()).contains(Duration.ofMinutes(5));
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").skillApproval(approval).build())) {
            assertThat(stack.health().isServing()).isTrue();
        }
    }

    @Test
    @DisplayName("the teardown plan is ordered by phase and printable")
    void exposesTeardownPlan(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").build())) {
            final List<String> plan = stack.teardownPlan();

            assertThat(plan).isNotEmpty();
            // Sessions drain first and the skill hook shell dies last, whatever was registered in between.
            assertThat(plan.get(0)).contains(TeardownPhase.SESSIONS.name());
            assertThat(plan.get(plan.size() - 1)).contains(TeardownPhase.SKILL_HOOK_SHELL.name());
        }
    }

    /**
     * B-4 — the hook thread pool is created three interfaces below the builder, by the hook execution manager's own
     * default, and neither interface on the way down declares a lifecycle. The narrowing therefore happens once here,
     * at assembly. Without this entry nothing in the process holds a handle able to stop that pool.
     */
    @Test
    @DisplayName("the teardown plan retires the hook executor before the skill hook shell")
    void teardownPlanClosesTheHookExecutionManager(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").build())) {
            final List<String> plan = stack.teardownPlan();

            final int hookExecutor = indexOfPhase(plan, TeardownPhase.HOOK_EXECUTOR);
            assertThat(hookExecutor).as("plan was %s", plan).isNotNegative();
            assertThat(plan.get(hookExecutor)).contains("hookExecutionManager");
            // A hook body runs on this pool and may call into the shell, so the caller stops before the callee.
            assertThat(hookExecutor).isLessThan(indexOfPhase(plan, TeardownPhase.SKILL_HOOK_SHELL));
        }
    }

    /**
     * The memory block moved from the front of shutdown to after {@link TeardownPhase#CHECKPOINTS}, and the risk that
     * move carries is exactly one thing: the final derivation reads the transcript, and it now reads it after the
     * sessions have drained and the checkpoint mailbox has closed. The design recorded that as unmeasured. This
     * measures it.
     *
     * <p>
     * It holds because the record store behind the transcript manager is application-scoped —
     * {@link TeardownPhase#SESSIONS} releases leases, it does not close the store — so a phase after it reads the
     * same, and by then completely written, history.
     */
    @Test
    @DisplayName("a MEMORY_FINAL_DERIVATION entry still reads the transcript after SESSIONS and CHECKPOINTS have run")
    void memoryFinalDerivationStillReadsTheTranscriptAfterTheMove(@TempDir Path workspace) {
        final SessionId sessionId = SessionId.of("teardown-order");
        final AtomicInteger messagesSeenAtTeardown = new AtomicInteger(-1);

        final AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").build());
        // A transcript the way a finished session leaves one behind.
        final TranscriptBuffer live = stack.agentExecutor().getTranscriptManager().initialize(sessionId, "system");
        live.addUserMessage("what is the deploy window");
        live.addAssistantMessage("Fridays, 14:00 UTC");
        stack.agentExecutor().getTranscriptManager().save(live);

        stack.own(TeardownPhase.MEMORY_FINAL_DERIVATION, "readsTranscript", () -> messagesSeenAtTeardown
                .set(stack.agentExecutor().getTranscriptManager().initialize(sessionId, null).size()));
        final List<String> plan = stack.teardownPlan();
        stack.close();

        // The phases that precede it now — sessions drained, mailbox closed — left the history intact.
        assertThat(messagesSeenAtTeardown).hasValue(2);
        // And it really did run after them, rather than the read having succeeded because it ran first.
        assertThat(indexOfPhase(plan, TeardownPhase.MEMORY_FINAL_DERIVATION)).as("plan was %s", plan)
                .isGreaterThan(indexOfPhase(plan, TeardownPhase.SESSIONS));
    }

    @Test
    @DisplayName("the memory block runs after the session block, so a session-end write finds a live backend")
    void memoryBlockFollowsTheSessionBlock() {
        // Ordering is the whole contract of this enum, and the two ingest modes fire while sessions drain: a memory
        // block ahead of them would hand the last write a closed backend and a stopped queue.
        assertThat(TeardownPhase.SESSIONS.ordinal()).isLessThan(TeardownPhase.CHECKPOINTS.ordinal());
        assertThat(TeardownPhase.CHECKPOINTS.ordinal()).isLessThan(TeardownPhase.MEMORY_FINAL_DERIVATION.ordinal());
        assertThat(TeardownPhase.MEMORY_FINAL_DERIVATION.ordinal()).isLessThan(TeardownPhase.MEMORY_QUEUE.ordinal());
        assertThat(TeardownPhase.MEMORY_QUEUE.ordinal()).isLessThan(TeardownPhase.DREAMER.ordinal());
        assertThat(TeardownPhase.DREAMER.ordinal()).isLessThan(TeardownPhase.MEMORY_MAINTENANCE.ordinal());
        // The backend last of the block: the four above all write through it.
        assertThat(TeardownPhase.MEMORY_MAINTENANCE.ordinal()).isLessThan(TeardownPhase.MEMORY_BACKEND.ordinal());
        assertThat(TeardownPhase.MEMORY_BACKEND.ordinal()).isLessThan(TeardownPhase.SESSION_TRANSPORT.ordinal());
    }

    private static int indexOfPhase(List<String> plan, TeardownPhase phase) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.get(i).contains(phase.name())) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("close() is idempotent and reports DOWN afterwards")
    void closeIsIdempotent(@TempDir Path workspace) {
        final AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").build());

        stack.close();
        assertThatCode(stack::close).doesNotThrowAnyException();

        assertThat(stack.isClosed()).isTrue();
        assertThat(stack.health().getStatus()).isEqualTo(HealthReport.Status.DOWN);
        assertThat(stack.health().isServing()).isFalse();
    }

    @Test
    @DisplayName("every declared agent and tenant gets a runtime, and the first declared is the primary")
    void standsUpEveryDeclaredRuntime(@TempDir Path workspace) {
        final AimonStackSpec spec = specFor(workspace, "ops")
                .agent(AgentSpec.builder().bundle(bundle("ops")).discriminator("acme").build())
                .agent(AgentSpec.of(bundle("audit")))
                .agent(AgentSpec.builder().bundle(bundle("audit")).discriminator("acme").build()).build();

        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            assertThat(stack.runtimes()).hasSize(4);
            assertThat(stack.runtimes().keySet()).map(AgentRuntimeId::value).containsExactlyInAnyOrder("agent:ops",
                    "agent:ops:acme", "agent:audit", "agent:audit:acme");
            // Distinct objects, not four views of one: two tenants of the same agent share nothing but a name.
            assertThat(stack.runtimes().values()).doesNotHaveDuplicates();
            assertThat(stack.primaryRuntimeId().value()).isEqualTo("agent:ops");
            for (AgentRuntimeId id : stack.runtimes().keySet()) {
                assertThat(stack.agentRuntimeRegistry().get(id)).isPresent();
            }
            assertThat(stack.health().isServing()).isTrue();
        }
    }

    @Test
    @DisplayName("a file written by one runtime is invisible to every other")
    void eachRuntimeGetsItsOwnFileSystem(@TempDir Path workspace) {
        // The row this exists for: with one shared file system every assertion below still passes for the
        // writer and silently fails only for the reader, which is a leak nobody notices until a customer does.
        final AimonStackSpec spec = specFor(workspace, "ops")
                .agent(AgentSpec.builder().bundle(bundle("ops")).discriminator("acme").build())
                .agent(AgentSpec.of(bundle("audit"))).build();

        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            final VirtualFileSystem opsDefault = fileSystemOf(stack, "agent:ops");
            opsDefault.write("secret.txt", "ops-only");

            assertThat(opsDefault.exists("secret.txt")).isTrue();
            assertThat(fileSystemOf(stack, "agent:ops:acme").exists("secret.txt")).isFalse();
            assertThat(fileSystemOf(stack, "agent:audit").exists("secret.txt")).isFalse();
        }
    }

    @Test
    @DisplayName("each runtime's workspace sits under its own agent and tenant directory")
    void workspacesAreLaidOutByAgentAndTenant(@TempDir Path workspace) {
        final AimonStackSpec spec = specFor(workspace, "ops")
                .agent(AgentSpec.builder().bundle(bundle("ops")).discriminator("acme").build()).build();

        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            assertThat(fileSystemOf(stack, "agent:ops").getWorkingDirectory())
                    .isEqualTo(workspace + "/ops/" + AgentWorkspaceLayout.NO_DISCRIMINATOR);
            assertThat(fileSystemOf(stack, "agent:ops:acme").getWorkingDirectory()).isEqualTo(workspace + "/ops/acme");
        }
    }

    @Test
    @DisplayName("each agent's customizers run against that agent's runtime only")
    void customizersAreAppliedPerAgent(@TempDir Path workspace) {
        final List<String> seen = new ArrayList<>();
        final AimonStackSpec spec = AimonStackSpec.builder().workspaceRoot(workspace.toString())
                .llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.builder().bundle(bundle("ops"))
                        .addCustomizer(runtime -> seen.add("ops:" + runtime.getId().value())).build())
                .agent(AgentSpec.builder().bundle(bundle("audit"))
                        .addCustomizer(runtime -> seen.add("audit:" + runtime.getId().value())).build())
                .build();

        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            assertThat(seen).containsExactly("ops:agent:ops", "audit:agent:audit");
        }
    }

    @Test
    @DisplayName("closing unregisters every runtime, not just the primary")
    void closeUnregistersEveryRuntime(@TempDir Path workspace) {
        final AimonStackSpec spec = specFor(workspace, "ops").agent(AgentSpec.of(bundle("audit"))).build();
        final AimonStack stack = AimonStackBuilder.build(spec);
        final List<AgentRuntimeId> ids = List.copyOf(stack.runtimes().keySet());

        stack.close();

        for (AgentRuntimeId id : ids) {
            assertThat(stack.agentRuntimeRegistry().get(id)).isEmpty();
        }
        // Each runtime's file system is a separate teardown entry, because AgentRuntime.close() does not reach
        // it — one entry for two runtimes would leak one workspace handle per tenant ever created.
        assertThat(stack.teardownPlan()).anyMatch(line -> line.contains("fileSystem(agent:ops)"))
                .anyMatch(line -> line.contains("fileSystem(agent:audit)"));
    }

    @Test
    @DisplayName("sharing one supplied file system across agents is allowed, and recorded as a degradation")
    void aSharedFileSystemAcrossAgentsIsADegradation(@TempDir Path workspace) {
        final LocalFileSystem caller = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        caller.initialize();
        try {
            final AimonStackSpec spec = AimonStackSpec.builder().llm(LlmSpec.of(STUB_LLM))
                    .agent(AgentSpec.of(bundle("ops"))).agent(AgentSpec.of(bundle("audit")))
                    .fileSystem(FileSystemSpec.supplied(caller)).build();

            try (AimonStack stack = AimonStackBuilder.build(spec)) {
                // Allowed: two agents sharing a workspace is a legitimate design, and only the caller knows
                // whether these two are collaborators or two customers. Silence is what is not allowed.
                assertThat(stack.fileSystem(AgentRuntimeId.of("agent:ops"))).contains(caller);
                assertThat(stack.fileSystem(AgentRuntimeId.of("agent:audit"))).contains(caller);
                assertThat(stack.degradations().describe()).contains("file-system-isolation");
            }
        } finally {
            caller.close();
        }
    }

    @Test
    @DisplayName("one agent does not get a degradation for sharing a file system with nobody")
    void aSingleAgentSharesWithNobody(@TempDir Path workspace) {
        final LocalFileSystem caller = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        caller.initialize();
        try {
            final AimonStackSpec spec = AimonStackSpec.builder().llm(LlmSpec.of(STUB_LLM))
                    .agent(AgentSpec.of(bundle("ops"))).fileSystem(FileSystemSpec.supplied(caller)).build();

            try (AimonStack stack = AimonStackBuilder.build(spec)) {
                assertThat(stack.degradations().describe()).doesNotContain("file-system-isolation");
            }
        } finally {
            caller.close();
        }
    }

    @Test
    @DisplayName("a per-runtime factory is invoked once per runtime, with that runtime's id")
    void theFileSystemFactorySeesEveryRuntimeId(@TempDir Path workspace) {
        final List<AgentRuntimeId> asked = new ArrayList<>();
        final AimonStackSpec spec = AimonStackSpec.builder().llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.of(bundle("ops")))
                .agent(AgentSpec.builder().bundle(bundle("ops")).discriminator("acme").build())
                .fileSystem(FileSystemSpec.factory(id -> {
                    asked.add(id);
                    final LocalFileSystem fs = new LocalFileSystem(
                            new LocalFileSystemConfig(workspace.resolve(id.value().replace(':', '_')).toString()));
                    fs.initialize();
                    return fs;
                })).build();

        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            assertThat(asked).map(AgentRuntimeId::value).containsExactly("agent:ops", "agent:ops:acme");
            assertThat(stack.fileSystem(AgentRuntimeId.of("agent:ops")))
                    .isNotEqualTo(stack.fileSystem(AgentRuntimeId.of("agent:ops:acme")));
        }
    }

    private static VirtualFileSystem fileSystemOf(AimonStack stack, String runtimeId) {
        return stack.fileSystem(AgentRuntimeId.of(runtimeId))
                .orElseThrow(() -> new AssertionError("no file system for " + runtimeId));
    }

    @Test
    @DisplayName("a supplied file system is used but never closed by the stack")
    void suppliedFileSystemIsNotClosed(@TempDir Path workspace) {
        final LocalFileSystem caller = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        caller.initialize();
        try {
            final AimonStackSpec spec = AimonStackSpec.builder().llm(LlmSpec.of(STUB_LLM))
                    .agent(AgentSpec.of(bundle("ops"))).fileSystem(FileSystemSpec.supplied(caller)).build();

            final AimonStack stack = AimonStackBuilder.build(spec);
            assertThat(stack.fileSystem(stack.primaryRuntimeId())).contains(caller);
            stack.close();

            // Still usable: the stack borrowed it, so shutting the stack down must not have closed it.
            assertThatCode(() -> caller.exists("/")).doesNotThrowAnyException();
        } finally {
            caller.close();
        }
    }

    @Test
    @DisplayName("a fixed-peer memory spec puts the memory tools on every runtime")
    void memorySpecRegistersToolsOnEveryRuntime(@TempDir Path workspace) {
        final MemorySpec memory = MemorySpec.forPeer(Workspace.builder().id("ws-1").build(), Principal.user("kangwoo"))
                .representationStore(new InMemoryRepresentationStore()).observationStore(new InMemoryObservationStore())
                .build();

        final AimonStackSpec spec = specFor(workspace, "ops").agent(AgentSpec.of(bundle("sre"))).memory(memory).build();
        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            // Both runtimes, not only the primary: a tool provider that reaches the first declared agent and no
            // other is the failure StackAgentRuntimeProvisioner exists to prevent.
            for (AgentRuntimeId runtimeId : List.of(AgentRuntimeId.of("agent:ops"), AgentRuntimeId.of("agent:sre"))) {
                final ToolRegistry tools = stack.runtime(runtimeId).orElseThrow().getToolRegistry();
                assertThat(tools.findByName(MemoryRecallTool.TOOL_NAME)).isPresent();
                assertThat(tools.findByName(MemorySearchTool.TOOL_NAME)).isPresent();
                assertThat(tools.findByName(ObserveTool.TOOL_NAME)).isPresent();
            }
            assertThat(stack.degradations().has(MemoryAssembly.CAPABILITY_INGEST)).isTrue();
        }
    }

    @Test
    @DisplayName("per-caller memory registers no memory tools, and the stack says so out loud")
    void perCallerMemoryRegistersNoTools(@TempDir Path workspace) {
        final MemorySpec memory = MemorySpec.perCaller(Workspace.builder().id("ws-1").build())
                .representationStore(new InMemoryRepresentationStore()).build();

        final AimonStackSpec spec = specFor(workspace, "ops").memory(memory).build();
        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            // Absent by design rather than by omission: with no fixed observer in the tool context, these three
            // would answer "no workspace in context" to every call the model made.
            final ToolRegistry tools = stack.runtime(stack.primaryRuntimeId()).orElseThrow().getToolRegistry();
            assertThat(tools.findByName(MemoryRecallTool.TOOL_NAME)).isEmpty();
            assertThat(tools.findByName(ObserveTool.TOOL_NAME)).isEmpty();
            assertThat(stack.degradations().has(MemoryAssembly.CAPABILITY_TOOLS)).isTrue();
        }
    }

    @Test
    @DisplayName("no memory spec means no memory tools and no memory degradations")
    void noMemorySpecIsNotADegradedStack(@TempDir Path workspace) {
        try (AimonStack stack = AimonStackBuilder.build(specFor(workspace, "ops").build())) {
            assertThat(stack.runtime(stack.primaryRuntimeId()).orElseThrow().getToolRegistry()
                    .findByName(MemoryRecallTool.TOOL_NAME)).isEmpty();
            assertThat(stack.degradations().has(MemoryAssembly.CAPABILITY_INGEST)).isFalse();
            assertThat(stack.degradations().has(MemoryAssembly.CAPABILITY_TOOLS)).isFalse();
        }
    }
}
