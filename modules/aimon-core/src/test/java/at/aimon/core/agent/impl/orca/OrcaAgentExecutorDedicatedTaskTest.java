package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.Terminator;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.ToolResultReady;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DedicatedThreadConfig;
import at.aimon.core.agent.tool.DedicatedThreadDispatcher;
import at.aimon.core.agent.tool.DedicatedThreadPermits;
import at.aimon.core.agent.tool.DedicatedThreadPermitsAccess;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ParallelToolDispatcher;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolConcurrencyConfig;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentLaunchContext;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.toolinvocation.approval.SideEffectApprovalGate;
import at.aimon.core.tools.ForkAccess;
import at.aimon.core.tools.task.TaskTool;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;

/**
 * Parallel foreground {@code Task}, end to end through {@link OrcaAgentExecutor}: the real executor → dispatcher →
 * {@code executeSingleTool} → {@link TaskTool#execute} path, with a fake {@link SubagentExecutionManager} standing in
 * for the subagent runs so each test controls what a "subagent" does and on which thread it ran.
 */
@DisplayName("OrcaAgentExecutor parallel foreground Task (DEDICATED_THREAD)")
class OrcaAgentExecutorDedicatedTaskTest {

    private static final LlmModel MODEL = LlmModel.builder().name("test-model").build();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("on: three foreground Tasks run at once on dedicated threads; results commit in tool_use order")
    void threeTasksRunConcurrentlyInOrder() {
        final CyclicBarrier barrier = new CyclicBarrier(3);
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final AtomicInteger brokenBarriers = new AtomicInteger();
        final SubagentExecutionManager manager = fakeManager(launch -> {
            threads.add(Thread.currentThread());
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                brokenBarriers.incrementAndGet();
            }
            return success();
        });
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(LlmResponse.of("", threeTasks(), TokenUsage.of(10, 5, 15)));
        llm.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(20, 5, 25)));
        final OrcaAgentExecutor executor = executor(llm, manager);
        executor.parallelToolDispatcher = dedicated(4);
        final Thread caller = Thread.currentThread();

        final OrcaAgentExecutionResult result = executor.execute(runtime(registryWith(manager)), request());

        assertThat(result.isSuccess()).isTrue();
        assertThat(brokenBarriers).hasValue(0);
        assertThat(threads).hasSize(3).doesNotHaveDuplicates().doesNotContain(caller);
        assertThat(llm.lastToolResultIds).containsExactly("t1", "t2", "t3");
        // The forks' tokens stay theirs: the turn counts only its own two LLM calls.
        assertThat(result.getMetadata().getTokenUsage().getTotalTokens()).isEqualTo(40);
    }

    @Test
    @DisplayName("off (default dispatcher): the same batch runs one Task after another on the calling thread")
    void offRunsSequentiallyOnTheCallingThread() {
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> attributes = new CopyOnWriteArrayList<>();
        final SubagentExecutionManager manager = fakeManager(launch -> {
            threads.add(Thread.currentThread());
            attributes.add(launch.getExecutionAttributes());
            return success();
        });
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(LlmResponse.of("", threeTasks(), TokenUsage.empty()));
        llm.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutionResult result = executor(llm, manager).execute(runtime(registryWith(manager)),
                request());

        assertThat(result.isSuccess()).isTrue();
        assertThat(threads).containsOnly(Thread.currentThread()).hasSize(3);
        assertThat(attributes).allSatisfy(a -> assertThat(ForkAccess.taskIdOf(a)).isEmpty());
        assertThat(llm.lastToolResultIds).containsExactly("t1", "t2", "t3");
    }

    @Test
    @DisplayName("cancel: every running child sees the signal and its terminator; the caller is left with no flag")
    void parentCancelReachesEveryChild() throws Exception {
        final CountDownLatch allRunning = new CountDownLatch(3);
        final Map<String, Boolean> interrupted = new ConcurrentHashMap<>();
        final List<CancellationSignal> signals = new CopyOnWriteArrayList<>();
        final SubagentExecutionManager manager = fakeManager(launch -> {
            signals.add(launch.getCancellationSignal());
            allRunning.countDown();
            try {
                Thread.sleep(10_000);
                interrupted.put(Thread.currentThread().getName(), false);
            } catch (InterruptedException e) {
                interrupted.put(Thread.currentThread().getName(), true);
            }
            return success();
        });
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(LlmResponse.of("", threeTasks(), TokenUsage.empty()));
        llm.enqueue(LlmResponse.text("should not be reached"));
        final OrcaAgentExecutor executor = executor(llm, manager);
        executor.parallelToolDispatcher = dedicated(4);
        final AtomicReference<InterruptCoordinator> coordinator = new AtomicReference<>();
        executor.interruptCoordinatorFactory = () -> {
            final DefaultInterruptCoordinator created = new DefaultInterruptCoordinator();
            coordinator.compareAndSet(null, created);
            return created;
        };

        final TurnThread turn = new TurnThread(() -> executor.execute(runtime(registryWith(manager)), request()));
        turn.start();
        assertThat(allRunning.await(5, TimeUnit.SECONDS)).isTrue();
        coordinator.get().requestInterrupt(InterruptReason.USER_SIGINT);
        turn.join(10_000);

        assertThat(turn.isAlive()).isFalse();
        assertThat(turn.result.get().getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(signals).hasSize(3).allMatch(CancellationSignal::isCancelled);
        assertThat(interrupted).hasSize(3).doesNotContainValue(false);
        assertThat(turn.flagAfter.get()).as("no stale interrupt on the turn's thread").isFalse();
    }

    @Test
    @DisplayName("cancel with the inline lane active: all three children stop, the permits come back, no stale flag")
    void parentCancelWithInlineLane() throws Exception {
        final CountDownLatch allRunning = new CountDownLatch(3);
        final Map<String, Boolean> interrupted = new ConcurrentHashMap<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final SubagentExecutionManager manager = fakeManager(launch -> {
            threads.add(Thread.currentThread());
            allRunning.countDown();
            try {
                Thread.sleep(10_000);
                interrupted.put(Thread.currentThread().getName(), false);
            } catch (InterruptedException e) {
                interrupted.put(Thread.currentThread().getName(), true);
            }
            return success();
        });
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(LlmResponse.of("", threeTasks(), TokenUsage.empty()));
        llm.enqueue(LlmResponse.text("should not be reached"));
        final OrcaAgentExecutor executor = executor(llm, manager);
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(2);
        final DedicatedThreadPermits permits = DedicatedThreadPermits.of(config);
        executor.parallelToolDispatcher = new DedicatedThreadDispatcher(DefaultParallelToolDispatcher.sequential(),
                config, permits);
        final AtomicReference<InterruptCoordinator> coordinator = new AtomicReference<>();
        executor.interruptCoordinatorFactory = () -> {
            final DefaultInterruptCoordinator created = new DefaultInterruptCoordinator();
            coordinator.compareAndSet(null, created);
            return created;
        };

        final TurnThread turn = new TurnThread(() -> executor.execute(runtime(registryWith(manager)), request()));
        turn.start();
        assertThat(allRunning.await(5, TimeUnit.SECONDS)).isTrue();
        coordinator.get().requestInterrupt(InterruptReason.USER_SIGINT);
        turn.join(10_000);

        assertThat(turn.isAlive()).isFalse();
        // Two children on dedicated threads, the third inline on the turn's own thread.
        assertThat(threads).hasSize(3).contains(turn);
        assertThat(interrupted).hasSize(3).doesNotContainValue(false);
        assertThat(turn.result.get().getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(turn.flagAfter.get()).as("no stale interrupt on the turn's thread").isFalse();
        assertThat(DedicatedThreadPermitsAccess.availablePermits(permits)).isEqualTo(2);
    }

    @Test
    @DisplayName("late terminator: an interrupt fired after the Task returned stays on its dedicated thread; "
            + "the next turn's first tool on the caller sees none")
    void lateInterruptNeverReachesTheCaller() throws Exception {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);
        final CountDownLatch firing = new CountDownLatch(1);
        final SubagentExecutionManager manager = fakeManager(launch -> {
            running.countDown();
            // Cooperative: notice the cancel through the signal, not through an interrupt.
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!launch.getCancellationSignal().isCancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            // The coordinator trips the signal before it walks its terminators. A Task that returned in that gap would
            // unregister its terminator, leaving nothing to fire late, so hold it until the terminator has been picked.
            try {
                firing.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success();
        });
        final FlagProbeTool probe = new FlagProbeTool();
        final DefaultToolRegistry registry = registryWith(manager);
        registry.register(probe);
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(LlmResponse.of("", List.of(task("t1")), TokenUsage.empty()));
        // The interrupted turn makes no further LLM call, so the next response is the second turn's.
        llm.enqueue(LlmResponse.of("", List.of(ToolUse.of("p1", "FlagProbe", Map.of())), TokenUsage.empty()));
        llm.enqueue(LlmResponse.text("second turn done"));
        final OrcaAgentExecutor executor = executor(llm, manager);
        executor.parallelToolDispatcher = dedicated(4);
        final AtomicReference<LateCoordinator> coordinator = new AtomicReference<>();
        executor.interruptCoordinatorFactory = () -> {
            final LateCoordinator created = new LateCoordinator(firing, returned);
            coordinator.compareAndSet(null, created);
            return created;
        };
        // The Task's own PostTool hook runs after runForeground returned: that is where "returned" is signalled, so
        // the terminator fires into the tail of the dedicated thread, after unregister became a no-op.
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        hooks.register(HookEventType.POST_TOOL, context -> {
            if (TaskTool.TOOL_NAME.equals(context.getToolUse().getName())) {
                returned.countDown();
            }
            return HookResult.success();
        });

        final TurnThread turn = new TurnThread(() -> {
            final OrcaAgentExecutionResult first = executor.execute(runtime(registry, hooks), request());
            // Same executor, same thread, straight into the next turn.
            executor.interruptCoordinatorFactory = DefaultInterruptCoordinator::new;
            final OrcaAgentExecutionResult second = executor.execute(runtime(registry, hooks), request());
            return second.getCompletionReason() == CompletionReason.COMPLETED ? first : second;
        });
        turn.start();
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        final Thread canceller = new Thread(() -> coordinator.get().requestInterrupt(InterruptReason.USER_SIGINT));
        canceller.start();
        turn.join(10_000);
        canceller.join(5_000);

        assertThat(turn.isAlive()).isFalse();
        assertThat(turn.result.get().getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(coordinator.get().firedOn).isNotEmpty().doesNotContain(turn);
        assertThat(probe.sawInterrupt).as("the next turn's tool on the caller thread").containsExactly(false);
        assertThat(turn.flagAfter.get()).isFalse();
    }

    @Test
    @DisplayName("approval: three parallel Tasks in one session ask 'Allow Task?' once, without a fork label")
    void parallelTasksAskOnce() {
        final AtomicInteger prompts = new AtomicInteger();
        final List<String> promptTexts = new CopyOnWriteArrayList<>();
        final AskPromptHandler handler = prompt -> {
            prompts.incrementAndGet();
            promptTexts.add(prompt);
            try {
                // Hold the answer so the other two arrive while the question is open.
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Decision.ALLOW;
        };
        final SubagentExecutionManager manager = fakeManager(launch -> success());
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(LlmResponse.of("", threeTasks(), TokenUsage.empty()));
        llm.enqueue(LlmResponse.text("done"));
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final OrcaAgentExecutor executor = OrcaAgentExecutor.builder().llmClient(llm)
                .transcriptManager(new DefaultTranscriptManager(new InMemorySessionRecordStore()))
                .toolExecutionManager(toolManager).hookExecutionManager(hookManager)
                .commandExecutionManager(new DefaultCommandExecutionManager(llm)).subagentExecutionManager(manager)
                .approvalGate(new SideEffectApprovalGate(handler)).parallelToolDispatcher(dedicated(4)).build();

        final OrcaAgentExecutionResult result = executor.execute(runtime(registryWith(manager)), request());

        assertThat(result.isSuccess()).isTrue();
        assertThat(prompts).hasValue(1);
        // The question is the parent's, not a fork's, so it carries no [task ...] label.
        assertThat(promptTexts).singleElement().asString().startsWith("Allow 'Task(");
        assertThat(llm.lastToolResultIds).containsExactly("t1", "t2", "t3");
    }

    @Test
    @DisplayName("tracing: the three TOOL spans share the iteration; each fork gets metadata of its own TOOL span")
    void tracingNestsEachForkUnderItsOwnToolSpan() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final List<LlmCallMetadata> forwarded = new CopyOnWriteArrayList<>();
        final CyclicBarrier barrier = new CyclicBarrier(3);
        final SubagentExecutionManager manager = fakeManager(launch -> {
            forwarded.add(launch.getParentLlmCallMetadata());
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // A broken barrier only weakens the concurrency claim; the span assertions below still hold.
            }
            return success();
        });
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(LlmResponse.of("", threeTasks(), TokenUsage.empty()));
        llm.enqueue(LlmResponse.text("done"));
        final OrcaAgentExecutor executor = executor(llm, manager);
        executor.parallelToolDispatcher = dedicated(4);
        executor.tracer = new DefaultTracer(store, SpanExporter.noop());

        executor.execute(runtime(registryWith(manager)),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-par")).build());

        final List<TraceSpan> toolSpans = store.bySession("conv-par").stream().filter(s -> s.getType() == SpanType.TOOL)
                .toList();
        assertThat(toolSpans).hasSize(3);
        assertThat(toolSpans.stream().map(s -> s.getParentSpanId().orElseThrow()).distinct()).hasSize(1);
        assertThat(forwarded).hasSize(3);
        final Set<String> forwardedParents = Collections.newSetFromMap(new ConcurrentHashMap<>());
        forwarded.forEach(m -> forwardedParents.add(m.toString()));
        assertThat(forwardedParents).as("each fork carries different (its own TOOL span's) metadata").hasSize(3);
    }

    @Test
    @DisplayName("streaming overlap, on: Read runs eagerly, the Task tail goes to the dispatcher and fans out")
    void harvestTailFansOutWhenOn() {
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final AtomicInteger brokenBarriers = new AtomicInteger();
        final SubagentExecutionManager manager = fakeManager(launch -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                brokenBarriers.incrementAndGet();
            }
            return success();
        });
        final List<String> events = new CopyOnWriteArrayList<>();
        final DefaultToolRegistry registry = registryWith(manager);
        registry.register(new SafeReadTool());
        final List<ToolUse> batch = List.of(ToolUse.of("r1", "SafeRead", Map.of()), task("t1"), task("t2"));

        try (DefaultParallelToolDispatcher pool = overlapPool()) {
            final RecordingDispatcher recording = new RecordingDispatcher(new DedicatedThreadDispatcher(pool,
                    DedicatedThreadConfig.enabled(4), DedicatedThreadPermits.of(DedicatedThreadConfig.enabled(4))));
            final OrcaAgentExecutor executor = streamingExecutor(new StreamingBatchClient(batch), manager, recording);
            recordEvents(executor, events);

            final OrcaAgentExecutionResult result = executor.execute(runtime(registry), request());

            assertThat(result.isSuccess()).isTrue();
            assertThat(brokenBarriers).as("the two Tasks of the tail ran at the same time").hasValue(0);
            assertThat(recording.batches).containsExactly(List.of("t1", "t2"));
            assertThat(events).containsSubsequence("started:r1", "ready:r1", "started:t1", "started:t2");
        }
    }

    @Test
    @DisplayName("streaming overlap, off: the tail never reaches dispatch and the event order is the inline harvest's")
    void harvestTailStaysInlineWhenOff() {
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final SubagentExecutionManager manager = fakeManager(launch -> {
            threads.add(Thread.currentThread());
            return success();
        });
        final List<String> events = new CopyOnWriteArrayList<>();
        final DefaultToolRegistry registry = registryWith(manager);
        registry.register(new SafeReadTool());
        final List<ToolUse> batch = List.of(ToolUse.of("r1", "SafeRead", Map.of()), task("t1"), task("t2"));

        try (DefaultParallelToolDispatcher pool = overlapPool()) {
            final RecordingDispatcher recording = new RecordingDispatcher(pool);
            final OrcaAgentExecutor executor = streamingExecutor(new StreamingBatchClient(batch), manager, recording);
            recordEvents(executor, events);
            final AtomicReference<Thread> caller = new AtomicReference<>(Thread.currentThread());

            final OrcaAgentExecutionResult result = executor.execute(runtime(registry), request());

            assertThat(result.isSuccess()).isTrue();
            assertThat(recording.batches).as("no dispatch for the tail").isEmpty();
            assertThat(events).containsExactly("started:r1", "ready:r1", "started:t1", "ready:t1", "started:t2",
                    "ready:t2");
            assertThat(threads).containsOnly(caller.get());
        }
    }

    @Test
    @DisplayName("factory: enabled config installs the decorator and labels forks; default installs neither")
    void factoryWiring() {
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        final OrcaAgentExecutor plain = new OrcaAgentExecutorFactory().create(llm,
                new DefaultTranscriptManager(new InMemorySessionRecordStore()));
        assertThat(plain.parallelToolDispatcher).isNotInstanceOf(DedicatedThreadDispatcher.class);

        final OrcaAgentExecutorFactory factory = new OrcaAgentExecutorFactory()
                .withDedicatedThreadConfig(DedicatedThreadConfig.enabled(2))
                .withToolConcurrencyConfig(ToolConcurrencyConfig.enabled(3));
        final OrcaAgentExecutor enabled = factory.create(llm,
                new DefaultTranscriptManager(new InMemorySessionRecordStore()));
        assertThat(enabled.parallelToolDispatcher).isInstanceOf(DedicatedThreadDispatcher.class);
        assertThat(enabled.parallelToolDispatcher.routesHarvestTail()).isTrue();

        assertThat(forkStartAttributes(new OrcaAgentExecutorFactory())).doesNotContainKey(ForkAccess.TASK_ID_ATTRIBUTE);
        assertThat(forkStartAttributes(factory)).containsEntry(ForkAccess.TASK_ID_ATTRIBUTE, "task-f");
    }

    @Test
    @DisplayName("factory: reconfiguring dedicated threads resizes the permit set used by later executors")
    void factoryReconfigurationChangesTheBound() {
        final OrcaAgentExecutorFactory factory = new OrcaAgentExecutorFactory()
                .withDedicatedThreadConfig(DedicatedThreadConfig.enabled(2));
        final DedicatedThreadPermits first = factory.dedicatedThreadPermits();
        assertThat(factory.dedicatedThreadPermits()).isSameAs(first);
        assertThat(DedicatedThreadPermitsAccess.availablePermits(first)).isEqualTo(2);

        factory.withDedicatedThreadConfig(DedicatedThreadConfig.enabled(5));
        final DedicatedThreadPermits second = factory.dedicatedThreadPermits();

        assertThat(second).isNotSameAs(first);
        assertThat(DedicatedThreadPermitsAccess.availablePermits(second)).isEqualTo(5);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Runs one fork through the manager a factory builds and returns the attributes its SubagentStart hook saw. */
    private Map<String, Object> forkStartAttributes(OrcaAgentExecutorFactory factory) {
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.enqueue(LlmResponse.text("fork done"));
        final SubagentExecutionManager manager = factory.createDefaultSubagentExecutionManager(llm,
                new DefaultToolExecutionManager(), new DefaultHookExecutionManager());
        final InMemorySubagentRegistry subagents = new InMemorySubagentRegistry();
        subagents.register(Subagent.builder().name("explorer").systemPrompt("explore").maxIterations(2).build());
        final AtomicReference<SubagentStartContext> start = new AtomicReference<>();
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        hooks.register(HookEventType.SUBAGENT_START, context -> {
            start.set(context);
            return HookResult.success();
        });
        manager.execute(SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(subagents).toolRegistry(new DefaultToolRegistry()).hookRegistry(hooks)
                .defaultModel(MODEL).build(), "task-f", "explorer", "go", "d");
        return start.get().getExecutionAttributes();
    }

    private static DedicatedThreadDispatcher dedicated(int maxThreads) {
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(maxThreads);
        return new DedicatedThreadDispatcher(DefaultParallelToolDispatcher.sequential(), config,
                DedicatedThreadPermits.of(config));
    }

    private static DefaultParallelToolDispatcher overlapPool() {
        return new DefaultParallelToolDispatcher(
                ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(4).streamingOverlap(true).build());
    }

    private static void recordEvents(OrcaAgentExecutor executor, List<String> events) {
        executor.addEventListener(event -> {
            if (event instanceof ToolUseStarted started) {
                events.add("started:" + started.getToolUseId());
            } else if (event instanceof ToolResultReady ready) {
                events.add("ready:" + ready.getToolUseId());
            }
        });
    }

    private static ToolUse task(String id) {
        return ToolUse.of(id, TaskTool.TOOL_NAME,
                Map.of("subagent_name", "Explore", "prompt", "look " + id, "description", "d " + id));
    }

    private static List<ToolUse> threeTasks() {
        return List.of(task("t1"), task("t2"), task("t3"));
    }

    private static SubagentExecutionResult success() {
        final Instant now = Instant.now();
        final ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(1)
                .tokenUsage(TokenUsage.of(100, 200, 300)).timestamps(now, now).build();
        return SubagentExecutionResult.success("done", SessionSnapshot.of(SessionId.generate(), "sys", List.of()),
                metadata);
    }

    private static SubagentExecutionManager fakeManager(Function<SubagentLaunchContext, SubagentExecutionResult> run) {
        final SubagentExecutionManager manager = mock(SubagentExecutionManager.class);
        when(manager.execute(any(SubagentLaunchContext.class), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> run.apply(invocation.getArgument(0)));
        return manager;
    }

    private DefaultToolRegistry registryWith(SubagentExecutionManager manager) {
        final InMemorySubagentRegistry subagents = new InMemorySubagentRegistry();
        subagents.register(Subagent.builder().name("Explore").systemPrompt("explore").maxIterations(2).build());
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new TaskTool(MODEL, subagents, registry, new DefaultHookRegistry(), manager));
        return registry;
    }

    private OrcaAgentExecutionRequest request() {
        return OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build();
    }

    private OrcaAgentRuntime runtime(DefaultToolRegistry toolRegistry) {
        return runtime(toolRegistry, new DefaultHookRegistry());
    }

    private OrcaAgentRuntime runtime(DefaultToolRegistry toolRegistry, DefaultHookRegistry hookRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:par-task"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry)
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).controlFileSystem(fileSystem)
                .executionEnvironmentProvider(TestExecutionEnvironments.provider(fileSystem)).build();
    }

    private static OrcaAgentExecutor executor(LlmClient client, SubagentExecutionManager manager) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, new DefaultCommandExecutionManager(client), manager);
    }

    private static OrcaAgentExecutor streamingExecutor(LlmClient client, SubagentExecutionManager manager,
            ParallelToolDispatcher dispatcher) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        return OrcaAgentExecutor.builder().llmClient(client)
                .transcriptManager(new DefaultTranscriptManager(new InMemorySessionRecordStore()))
                .toolExecutionManager(toolManager).hookExecutionManager(hookManager)
                .commandExecutionManager(new DefaultCommandExecutionManager(client)).subagentExecutionManager(manager)
                .useStreaming(true).parallelToolDispatcher(dispatcher).build();
    }

    /** Runs a turn on its own thread and records the thread's interrupt flag right after it returns. */
    private static final class TurnThread extends Thread {
        private final java.util.function.Supplier<OrcaAgentExecutionResult> body;
        private final AtomicReference<OrcaAgentExecutionResult> result = new AtomicReference<>();
        private final AtomicBoolean flagAfter = new AtomicBoolean();

        TurnThread(java.util.function.Supplier<OrcaAgentExecutionResult> body) {
            super("turn");
            this.body = body;
        }

        @Override
        public void run() {
            result.set(body.get());
            flagAfter.set(Thread.interrupted());
        }
    }

    /**
     * A coordinator whose terminators fire only once {@code returned} is down — so the interrupt reaches a Task's
     * thread after the Task already returned, the late-interrupt window — and which records the threads they hit.
     * {@code firing} goes down when a terminator is picked, before it waits.
     */
    private static final class LateCoordinator implements InterruptCoordinator {
        private final DefaultInterruptCoordinator delegate = new DefaultInterruptCoordinator();
        private final CountDownLatch firing;
        private final CountDownLatch returned;
        private final List<Thread> firedOn = new CopyOnWriteArrayList<>();

        LateCoordinator(CountDownLatch firing, CountDownLatch returned) {
            this.firing = firing;
            this.returned = returned;
        }

        @Override
        public CancellationSignal getSignal() {
            return delegate.getSignal();
        }

        @Override
        public void requestInterrupt(InterruptReason reason) {
            delegate.requestInterrupt(reason);
        }

        @Override
        public TerminatorRegistrar newTerminatorRegistrar() {
            final TerminatorRegistrar inner = delegate.newTerminatorRegistrar();
            final Map<Terminator, Terminator> wrapped = Collections.synchronizedMap(new IdentityHashMap<>());
            return new TerminatorRegistrar() {
                @Override
                public void register(Terminator terminator) {
                    final Thread target = Thread.currentThread();
                    final Terminator late = () -> {
                        firing.countDown();
                        try {
                            returned.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        firedOn.add(target);
                        terminator.terminate();
                    };
                    wrapped.put(terminator, late);
                    inner.register(late);
                }

                @Override
                public void unregister(Terminator terminator) {
                    final Terminator late = wrapped.remove(terminator);
                    if (late != null) {
                        inner.unregister(late);
                    }
                }

                @Override
                public void close() {
                    inner.close();
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }
    }

    /** A SEQUENTIAL tool that records whether its thread carried an interrupt when it ran. */
    private static final class FlagProbeTool extends AbstractTool {
        private final List<Boolean> sawInterrupt = new CopyOnWriteArrayList<>();

        FlagProbeTool() {
            super("FlagProbe", "Records the interrupt flag", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            sawInterrupt.add(Thread.currentThread().isInterrupted());
            return ToolResult.success("probed");
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return SideEffectLevel.READ_ONLY;
        }
    }

    /** A read-only CONCURRENT_SAFE tool, eligible for eager dispatch. */
    private static final class SafeReadTool extends AbstractTool {
        SafeReadTool() {
            super("SafeRead", "Reads nothing", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("read");
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.NON_INTERRUPTIBLE;
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return SideEffectLevel.READ_ONLY;
        }
    }

    /** Wraps a dispatcher, recording the ids of every batch handed to {@code dispatch}. */
    private static final class RecordingDispatcher implements ParallelToolDispatcher {
        private final ParallelToolDispatcher delegate;
        private final List<List<String>> batches = new CopyOnWriteArrayList<>();

        RecordingDispatcher(ParallelToolDispatcher delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ToolUseResult> dispatch(List<ToolUse> toolUses, ToolRegistry registry,
                Function<ToolUse, ToolUseResult> runner, Consumer<ToolUse> onStarted,
                BiConsumer<ToolUse, ToolUseResult> onCompleted) {
            batches.add(toolUses.stream().map(ToolUse::getId).toList());
            return delegate.dispatch(toolUses, registry, runner, onStarted, onCompleted);
        }

        @Override
        public boolean routesHarvestTail() {
            return delegate.routesHarvestTail();
        }

        @Override
        public boolean supportsEagerDispatch() {
            return delegate.supportsEagerDispatch();
        }

        @Override
        public boolean isEagerEligible(ToolUse toolUse, ToolRegistry registry) {
            return delegate.isEagerEligible(toolUse, registry);
        }

        @Override
        public Optional<java.util.concurrent.CompletableFuture<ToolUseResult>> submitEager(ToolUse toolUse,
                Function<ToolUse, ToolUseResult> runner) {
            return delegate.submitEager(toolUse, runner);
        }

        @Override
        public int eagerPermits() {
            return delegate.eagerPermits();
        }
    }

    /** Streams every tool_use of one batch as a closed block, then answers with plain text. */
    private static final class StreamingBatchClient implements LlmClient {
        private static final TokenUsage USAGE = TokenUsage.of(10, 10, 20);
        private final List<ToolUse> batch;
        private int calls;

        StreamingBatchClient(List<ToolUse> batch) {
            this.batch = batch;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("streaming-only fake");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            if (calls++ == 0) {
                for (int i = 0; i < batch.size(); i++) {
                    sink.accept(LlmStreamChunk.toolUseReady(i, batch.get(i)));
                }
                sink.accept(
                        LlmStreamChunk.streamEnd(batch.size(), USAGE, Optional.of("tool_use"), StopReason.TOOL_USE));
                return LlmResponse.of("", batch, USAGE, StopReason.TOOL_USE);
            }
            sink.accept(LlmStreamChunk.textDelta(0, "done"));
            sink.accept(LlmStreamChunk.streamEnd(1, USAGE, Optional.of("end_turn"), StopReason.END_TURN));
            return LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN);
        }

        @Override
        public String getProviderName() {
            return "StreamingBatch";
        }
    }

    /** Scripted responses in order; records the tool_result ids the latest call carried, in message order. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = Collections.synchronizedList(new ArrayList<>());
        private volatile List<String> lastToolResultIds = List.of();

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        private LlmResponse next(List<Message> messages) {
            for (Message message : messages) {
                if (message.hasToolResults()) {
                    lastToolResultIds = message.getToolUseResults().stream().map(ToolUseResult::getToolUseId).toList();
                }
            }
            synchronized (responses) {
                return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.remove(0);
            }
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
                LlmCancellation cancellation) {
            return next(messages);
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
            return next(messages);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return next(messages);
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }
}
