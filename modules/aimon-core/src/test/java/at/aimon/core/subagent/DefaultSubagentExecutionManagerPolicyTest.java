package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionDeadline;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.DefaultSubagentExecutor;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;

/**
 * The manager side of an execution policy: the policy and its deadline reach the executor unchanged, a policy that
 * cannot be enforced is refused before anything runs, and a fork that does not stop is reported, not waited for.
 */
@DisplayName("DefaultSubagentExecutionManager execution policy")
class DefaultSubagentExecutionManagerPolicyTest {

    private static final String NAME = "verifier";

    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        bgPool.shutdownNow();
    }

    @Test
    @DisplayName("the launch's policy reaches the executor unchanged, with its caps as the budget and a deadline")
    void policyReachesTheExecutor() {
        final RecordingExecutor executor = RecordingExecutor.answering();
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("review-verifier").maxIterations(8)
                .maxTokens(300_000).maxToolCalls(20).maxWallClock(Duration.ofSeconds(120)).build();

        final SubagentExecutionResult result = manager(executor).execute(launch().executionPolicy(policy).build(),
                "task-1", NAME, "verify", "");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isExitConfirmed()).isTrue();
        final SubagentExecutionRequest request = executor.request.get();
        assertThat(request.getExecutionPolicy()).containsSame(policy);
        assertThat(request.getBudget()).contains(policy.toBudget());
        assertThat(request.getDeadline()).hasValueSatisfying(
                deadline -> assertThat(deadline.remaining()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(120)));
        // A fork under a deadline runs on a thread of its own, so it can be left behind if it will not stop.
        assertThat(executor.thread.get().getName()).startsWith("subagent-fg-");
    }

    @Test
    @DisplayName("a launch without a policy runs on the calling thread with no budget and no deadline, as before")
    void noPolicyIsUnchanged() {
        final RecordingExecutor executor = RecordingExecutor.answering();

        manager(executor).execute(launch().build(), "task-1", NAME, "verify", "");

        assertThat(executor.request.get().getExecutionPolicy()).isEmpty();
        assertThat(executor.request.get().getBudget()).isEmpty();
        assertThat(executor.request.get().getDeadline()).isEmpty();
        assertThat(executor.thread.get()).isSameAs(Thread.currentThread());
    }

    @Test
    @DisplayName("the fork's deadline is the earlier of its policy's and its spawner's")
    void parentDeadlineBoundsTheFork() {
        final RecordingExecutor executor = RecordingExecutor.answering();
        final ExecutionDeadline parent = ExecutionDeadline.after(Duration.ofSeconds(2));

        manager(executor).execute(launch()
                .executionPolicy(SubagentExecutionPolicy.builder("verifier").maxWallClock(Duration.ofHours(1)).build())
                .parentDeadline(parent).build(), "task-1", NAME, "verify", "");

        assertThat(executor.request.get().getDeadline()).containsSame(parent);
    }

    @Test
    @DisplayName("a policy the executor cannot enforce is refused before the fork runs")
    void unsupportedPolicyIsRefused() {
        final RecordingExecutor executor = new RecordingExecutor(Set.of(), (c, r) -> success());

        final SubagentExecutionResult result = manager(executor).execute(
                launch().executionPolicy(SubagentExecutionPolicy.builder("verifier").maxToolCalls(3).build()).build(),
                "task-1", NAME, "verify", "");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("MAX_TOOL_CALLS").contains("verifier");
        assertThat(executor.calls).hasValue(0);
        assertThat(manager(executor).supportedPolicyCapabilities()).isEmpty();
    }

    @Test
    @DisplayName("a policy for a subagent backed by a code behavior is refused")
    void codeBehaviorIsRefused() {
        final InMemorySubagentBehaviorRegistry behaviors = new InMemorySubagentBehaviorRegistry();
        final AtomicInteger behaviorRuns = new AtomicInteger();
        behaviors.register(NAME, (ctx, req, support) -> {
            behaviorRuns.incrementAndGet();
            return support.success("done");
        });
        final RecordingExecutor executor = RecordingExecutor.answering();

        final SubagentExecutionResult result = new DefaultSubagentExecutionManager(executor, bgPool, null, behaviors)
                .execute(launch().executionPolicy(SubagentExecutionPolicy.builder("verifier").maxIterations(2).build())
                        .build(), "task-1", NAME, "verify", "");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("code behavior");
        assertThat(behaviorRuns).hasValue(0);
    }

    @Test
    @DisplayName("an inherited context engine is handed to the executor; asking for one with none to inherit is refused")
    void inheritedContextEngine() {
        final RecordingExecutor executor = RecordingExecutor.answering();
        final SubagentExecutionPolicy inherit = SubagentExecutionPolicy.builder("verifier").inheritContextEngine(true)
                .build();
        final ContextEngine parentEngine = ContextEngine.passthrough();

        manager(executor).execute(launch().executionPolicy(inherit).parentContextEngine(parentEngine).build(), "t1",
                NAME, "verify", "");
        assertThat(executor.context.get().getContextEngine()).containsSame(parentEngine);

        final SubagentExecutionResult refused = manager(executor).execute(launch().executionPolicy(inherit).build(),
                "t2", NAME, "verify", "");
        assertThat(refused.isSuccess()).isFalse();
        assertThat(refused.getErrorMessage()).contains("context engine");

        manager(executor).execute(launch().parentContextEngine(parentEngine).build(), "t3", NAME, "verify", "");
        assertThat(executor.context.get().getContextEngine()).as("not inherited without the policy asking").isEmpty();
    }

    @Test
    @DisplayName("compaction settings are refused under a passthrough engine, and accepted when a compacting one is inherited")
    void contextAxesNeedACompactingEngine() {
        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(
                new DefaultSubagentExecutor(new CountingClient(), new DefaultToolExecutionManager(),
                        new DefaultHookExecutionManager()),
                bgPool, null);
        final SubagentExecutionPolicy own = SubagentExecutionPolicy.builder("verifier").compactionInstructions("keep")
                .build();
        final SubagentExecutionResult refused = manager.execute(launch().executionPolicy(own).build(), "t1", NAME,
                "verify", "");
        assertThat(refused.isSuccess()).isFalse();
        assertThat(refused.getErrorMessage()).contains("COMPACTION_INSTRUCTIONS");

        final SubagentExecutionPolicy inheriting = SubagentExecutionPolicy.builder("verifier")
                .compactionInstructions("keep").inheritContextEngine(true).build();
        final SubagentExecutionResult viaPassthrough = manager.execute(
                launch().executionPolicy(inheriting).parentContextEngine(ContextEngine.passthrough()).build(), "t2",
                NAME, "verify", "");
        assertThat(viaPassthrough.isSuccess()).isFalse();

        final SubagentExecutionResult accepted = manager.execute(launch().executionPolicy(inheriting)
                .parentContextEngine(at.aimon.core.agent.context.DefaultContextEngine.builder().build()).build(), "t3",
                NAME, "verify", "");
        assertThat(accepted.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    @DisplayName("a fork that ignores its deadline is reported unexited, listed as lingering, and delisted when it exits")
    void uncooperativeForkIsReportedNotWaitedFor() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Void> exited = new CompletableFuture<>();
        final RecordingExecutor executor = new RecordingExecutor(EnumSet.allOf(SubagentPolicyCapability.class),
                (c, r) -> {
                    awaitIgnoringInterrupts(release);
                    exited.complete(null);
                    return success();
                });
        final HookExecutionManager hooks = mock(HookExecutionManager.class);
        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(executor, bgPool, hooks);
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("verifier")
                .maxWallClock(Duration.ofMillis(100)).exitGracePeriod(Duration.ofMillis(100)).build();

        final SubagentExecutionResult result = manager.execute(launch().executionPolicy(policy).build(), "task-1", NAME,
                "verify", "");

        assertThat(result.isExitConfirmed()).isFalse();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
        assertThat(manager.lingeringRuns()).singleElement().satisfies(run -> {
            assertThat(run.getTaskId()).isEqualTo("task-1");
            assertThat(run.getStopReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
            assertThat(run.isBackground()).isFalse();
        });
        verify(hooks, times(1)).executeSubagentStop(any());

        release.countDown();
        exited.get(10, TimeUnit.SECONDS);
        awaitTrue(() -> manager.lingeringRuns().isEmpty());
        // The late result was dropped: the caller had its answer, and SubagentStop fired once.
        verify(hooks, times(1)).executeSubagentStop(any());
    }

    @Test
    @DisplayName("a fork that stops when interrupted at its deadline is waited for and its own result returned")
    void cooperativeForkIsWaitedFor() {
        final RecordingExecutor executor = new RecordingExecutor(EnumSet.allOf(SubagentPolicyCapability.class),
                (c, r) -> {
                    try {
                        Thread.sleep(TimeUnit.MINUTES.toMillis(5));
                        return success();
                    } catch (InterruptedException e) {
                        return failure(CompletionReason.WALL_CLOCK_EXCEEDED);
                    }
                });
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("verifier")
                .maxWallClock(Duration.ofMillis(50)).exitGracePeriod(Duration.ofSeconds(10)).build();

        final SubagentExecutionResult result = manager(executor).execute(launch().executionPolicy(policy).build(),
                "task-1", NAME, "verify", "");

        assertThat(result.isExitConfirmed()).isTrue();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
    }

    @Test
    @DisplayName("a spawner's cancellation of an uncooperative fork is reported INTERRUPTED and unexited")
    void cancellationOfAnUncooperativeFork() {
        final CountDownLatch release = new CountDownLatch(1);
        try (InterruptCoordinator parent = new DefaultInterruptCoordinator()) {
            final RecordingExecutor executor = new RecordingExecutor(EnumSet.allOf(SubagentPolicyCapability.class),
                    (c, r) -> {
                        parent.requestInterrupt(InterruptReason.USER_SIGINT);
                        awaitIgnoringInterrupts(release);
                        return success();
                    });
            final DefaultSubagentExecutionManager manager = manager(executor);
            final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("verifier")
                    .maxWallClock(Duration.ofHours(1)).exitGracePeriod(Duration.ofMillis(100)).build();

            final SubagentExecutionResult result = manager.execute(
                    launch().executionPolicy(policy).cancellationSignal(parent.getSignal()).build(), "task-1", NAME,
                    "verify", "");

            assertThat(result.isExitConfirmed()).isFalse();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
            assertThat(manager.lingeringRuns()).singleElement()
                    .satisfies(run -> assertThat(run.getStopReason()).isEqualTo(CompletionReason.INTERRUPTED));
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("a background task whose deadline passes while it waits for a worker never starts")
    void backgroundDeadlinePassesInTheQueue() throws Exception {
        final CountDownLatch occupied = new CountDownLatch(1);
        bgPool.submit(() -> {
            awaitIgnoringInterrupts(occupied);
        });
        final CountingClient llm = new CountingClient();
        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(
                new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager()),
                bgPool, null);
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("verifier")
                .maxWallClock(Duration.ofMillis(50)).build();
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name(NAME).systemPrompt("you verify").build());

        final CompletableFuture<SubagentExecutionResult> future = manager.executeInBackground(
                launch().subagentRegistry(registry).executionPolicy(policy).build(), "bg-1", NAME, "verify", "");
        // Let the deadline pass while the only worker is busy, then free it.
        new CountDownLatch(1).await(200, TimeUnit.MILLISECONDS);
        occupied.countDown();

        final SubagentExecutionResult result = future.get(10, TimeUnit.SECONDS);
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
        assertThat(result.getErrorMessage()).contains("before it started");
        assertThat(llm.calls).hasValue(0);
    }

    private DefaultSubagentExecutionManager manager(SubagentExecutor executor) {
        return new DefaultSubagentExecutionManager(executor, bgPool, null);
    }

    private static SubagentLaunchContext.Builder launch() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name(NAME).systemPrompt("you verify").build());
        return SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(registry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).defaultModel(LlmModel.builder().name("gpt-4").build());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                if (latch.await(30, TimeUnit.SECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not met in time");
            }
            new CountDownLatch(1).await(10, TimeUnit.MILLISECONDS);
        }
    }

    private static SubagentExecutionResult success() {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success("ok", SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }

    private static SubagentExecutionResult failure(CompletionReason reason) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.failure("stopped", SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build(), reason, null);
    }

    /** Records what it was handed and on which thread, and answers with a scripted body. */
    private static final class RecordingExecutor implements SubagentExecutor {
        final AtomicReference<SubagentExecutionContext> context = new AtomicReference<>();
        final AtomicReference<SubagentExecutionRequest> request = new AtomicReference<>();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        private final Set<SubagentPolicyCapability> supported;
        private final BiFunction<SubagentExecutionContext, SubagentExecutionRequest, SubagentExecutionResult> body;

        RecordingExecutor(Set<SubagentPolicyCapability> supported,
                BiFunction<SubagentExecutionContext, SubagentExecutionRequest, SubagentExecutionResult> body) {
            this.supported = supported;
            this.body = body;
        }

        static RecordingExecutor answering() {
            return new RecordingExecutor(EnumSet.allOf(SubagentPolicyCapability.class), (c, r) -> success());
        }

        @Override
        public SubagentExecutionResult execute(SubagentExecutionContext ctx, SubagentExecutionRequest req) {
            calls.incrementAndGet();
            context.set(ctx);
            request.set(req);
            thread.set(Thread.currentThread());
            return body.apply(ctx, req);
        }

        @Override
        public Set<SubagentPolicyCapability> supportedPolicyCapabilities() {
            return supported;
        }
    }

    /** Counts calls; answers text. */
    private static final class CountingClient implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            calls.incrementAndGet();
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "Counting";
        }
    }
}
