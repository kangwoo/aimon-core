package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.behavior.SubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.InMemoryBackgroundTaskStore;

/**
 * Boundedness contract for the shared background subagent pool under <b>concurrent</b> producers. The pool built by
 * {@link DefaultSubagentExecutionManager#newBackgroundExecutor(SubagentBackgroundConfig)} is a fixed
 * {@link java.util.concurrent.ThreadPoolExecutor} ({@code core == max == maxConcurrency}, a bounded queue, and an
 * abort-on-saturation policy) shared by every session that launches background work. When many callers fan tasks
 * in
 * at once, two invariants must hold: the number of subagents executing simultaneously never exceeds
 * {@code maxConcurrency}, and every task that cannot be admitted (all workers busy and the queue full) is settled
 * deterministically as {@code FAILED} with a "saturated" reason rather than silently dropped or run over-cap.
 *
 * <p>
 * All admitted subagents are pinned on a shared gate so the pool is provably saturated at the moment the overflow tasks
 * are submitted; the test then asserts exact accounting (admitted == {@code maxConcurrency + queueCapacity}, the rest
 * rejected, nothing lost) and that the observed high-water mark of concurrent executions equals the cap — never above.
 */
@DisplayName("Subagent background pool boundedness under concurrent producers")
class SubagentBackgroundPoolBoundednessTest {

    private static final String SUBAGENT = "explore";

    private final SubagentExecutor reactExecutor = mock(SubagentExecutor.class);
    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private DefaultSubagentExecutionManager newManager(ExecutorService executorService) {
        this.pool = executorService;
        return new DefaultSubagentExecutionManager(reactExecutor, executorService, null,
                SubagentBehaviorRegistry.empty(), null, new InMemoryBackgroundTaskStore());
    }

    private static InMemorySubagentRegistry registryWithExplore() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name(SUBAGENT).systemPrompt("(data)").build());
        return registry;
    }

    private static SubagentExecutionEnvironment.Builder envBuilder(SubagentRegistry registry) {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(registry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build());
    }

    private static SubagentExecutionResult reactResult(String answer) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }

    @Test
    @DisplayName("concurrent submissions never exceed the worker cap; overflow settles FAILED-saturated, nothing lost")
    void concurrentSubmissionsRespectTheWorkerCap() throws InterruptedException {
        final int maxConcurrency = 2;
        final int queueCapacity = 4;
        final int admittedCapacity = maxConcurrency + queueCapacity; // 6
        final int totalTasks = 12;

        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxObserved = new AtomicInteger();
        final CountDownLatch bothWorkersRunning = new CountDownLatch(maxConcurrency);
        final CountDownLatch gate = new CountDownLatch(1);

        when(reactExecutor.execute(any(), any())).thenAnswer(inv -> {
            final int current = active.incrementAndGet();
            maxObserved.accumulateAndGet(current, Math::max);
            bothWorkersRunning.countDown();
            try {
                gate.await();
                return reactResult("done");
            } finally {
                active.decrementAndGet();
            }
        });

        final DefaultSubagentExecutionManager manager = newManager(DefaultSubagentExecutionManager
                .newBackgroundExecutor(SubagentBackgroundConfig.of(maxConcurrency, queueCapacity)));

        // Fan all submissions in concurrently from a pool of producer threads, released together.
        final ExecutorService producers = Executors.newFixedThreadPool(totalTasks);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch producersDone = new CountDownLatch(totalTasks);
        final List<CompletableFuture<SubagentExecutionResult>> futures = new CopyOnWriteArrayList<>();
        final List<String> taskIds = new ArrayList<>();
        for (int i = 0; i < totalTasks; i++) {
            taskIds.add("t-" + i);
        }

        try {
            for (int i = 0; i < totalTasks; i++) {
                final String taskId = taskIds.get(i);
                producers.submit(() -> {
                    try {
                        startGate.await();
                        futures.add(manager.executeInBackground(envBuilder(registryWithExplore()).build(), taskId,
                                SUBAGENT, "go", "desc"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        producersDone.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(producersDone.await(10, TimeUnit.SECONDS)).as("all submissions issued").isTrue();
            assertThat(bothWorkersRunning.await(5, TimeUnit.SECONDS)).as("the pool reached its worker cap").isTrue();
            assertThat(futures).as("every submission returned a future").hasSize(totalTasks);

            // The pool is now saturated (all workers pinned, queue full). Release the gate and collect every outcome.
            gate.countDown();

            int successes = 0;
            int saturatedFailures = 0;
            for (CompletableFuture<SubagentExecutionResult> future : futures) {
                final SubagentExecutionResult result = future.get(30, TimeUnit.SECONDS);
                if (result.isSuccess()) {
                    successes++;
                } else {
                    assertThat(result.getErrorMessage()).as("overflow rejection reason").contains("saturated");
                    saturatedFailures++;
                }
            }

            assertThat(maxObserved.get()).as("simultaneous executions never exceed the worker cap")
                    .isLessThanOrEqualTo(maxConcurrency);
            assertThat(maxObserved.get()).as("the pool genuinely ran up to its cap").isEqualTo(maxConcurrency);
            assertThat(successes).as("exactly workers+queue tasks are admitted").isEqualTo(admittedCapacity);
            assertThat(saturatedFailures).as("all overflow tasks are rejected as FAILED")
                    .isEqualTo(totalTasks - admittedCapacity);
            assertThat(successes + saturatedFailures).as("no submitted task is lost").isEqualTo(totalTasks);
            assertThat(active.get()).as("all workers drained").isZero();

            // Every task reached a terminal durable state — admitted ones COMPLETED, overflow ones FAILED.
            long terminal = 0;
            for (String taskId : taskIds) {
                final BackgroundTask task = awaitTerminal(manager, taskId);
                if (task.getState() == BackgroundTaskState.COMPLETED || task.getState() == BackgroundTaskState.FAILED) {
                    terminal++;
                }
            }
            assertThat(terminal).as("all tasks reach a terminal durable state").isEqualTo(totalTasks);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new AssertionError("background task did not settle", e);
        } finally {
            gate.countDown();
            producers.shutdownNow();
        }
    }

    private static BackgroundTask awaitTerminal(DefaultSubagentExecutionManager manager, String taskId)
            throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            final java.util.Optional<BackgroundTask> snapshot = manager.status(taskId);
            if (snapshot.isPresent() && snapshot.get().getState().isTerminal()) {
                return snapshot.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("task " + taskId + " did not reach a terminal state in time");
    }
}
