package at.aimon.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.factory.AgentSetupFactory;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.command.execution.ExecutionMetadata;

/**
 * Integration test for CQ-04: verifies that inputs arriving while the agent slot is busy are buffered in the shared
 * {@link MessageQueueManager} rather than executed synchronously.
 */
@DisplayName("ReplSession queue routing (CQ-04)")
class ReplSessionQueueTest {

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("input during active turn is enqueued instead of executed")
    void inputDuringActiveTurnIsEnqueued() throws Exception {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-1");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);

        final CountDownLatch executing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        // STREAM-04: ReplSession now routes prompts through StreamingAgentExecutor.executeAsync. We emulate the
        // previous "first call blocks until the second prompt is enqueued" shape by completing the returned
        // CompletionStage on a helper thread only once `release` has been counted down.
        when(executor.executeAsync(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            executing.countDown();
            return supplyBlocking(release,
                    () -> OrcaAgentExecutionResult.success("done", SessionSnapshot.of(SessionId.of("default")),
                            ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH)));
        });

        final CliSettings settings = new CliSettings();
        final OutputFormatter formatter = new OutputFormatter(settings);
        final LiveSession liveSession = new DefaultLiveSession(SessionId.of("default"), context, executor,
                LiveSessionOptions.defaults(), queueManager);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).build();

        final ReplSession session = new ReplSession(agentSetup, settings, null);

        pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        // Worker 1: first prompt — holds the guard via the blocking executor.
        pool.submit(() -> {
            try {
                barrier.await();
                session.processInput("first prompt");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Worker 2: second prompt — must observe the guard as busy and enqueue.
        pool.submit(() -> {
            try {
                barrier.await();
                assertThat(executing.await(2, TimeUnit.SECONDS)).as("agent should be actively executing").isTrue();
                session.processInput("second prompt");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait until the second prompt has been enqueued before releasing the executor.
        for (int i = 0; i < 40; i++) {
            if (!queueManager.snapshot().isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }

        assertThat(queueManager.snapshot()).hasSize(1);
        QueuedInput buffered = queueManager.snapshot().get(0);
        assertThat(buffered.getInputText()).isEqualTo("second prompt");
        assertThat(buffered.getPriority()).isEqualTo(QueuedInputPriority.NEXT);
        assertThat(buffered.getAgentRuntimeId()).isEqualTo(agentRuntimeId);

        // While the guard is still held (executor blocked on `release`), only one turn has executed: the CQ-04
        // invariant — the second input did not bypass the guard and run concurrently.
        Mockito.verify(executor, Mockito.times(1)).executeAsync(Mockito.any(), Mockito.any(), Mockito.any());

        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

        // After the first turn finishes the CQ-05 turn-end drain replays the queued prompt as its own turn, so the
        // executor is now observed exactly twice and the queue is empty.
        Mockito.verify(executor, Mockito.times(2)).executeAsync(Mockito.any(), Mockito.any(), Mockito.any());
        assertThat(queueManager.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("input while idle executes synchronously and leaves queue empty")
    void inputWhileIdleExecutesSynchronously() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-2");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);
        when(executor.executeAsync(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(CompletableFuture
                .completedFuture(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(SessionId.of("default")),
                        ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH))));

        final CliSettings settings = new CliSettings();
        final OutputFormatter formatter = new OutputFormatter(settings);
        final LiveSession liveSession = new DefaultLiveSession(SessionId.of("default"), context, executor,
                LiveSessionOptions.defaults(), queueManager);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        session.processInput("idle prompt");

        Mockito.verify(executor).executeAsync(Mockito.any(), Mockito.any(), Mockito.any());
        assertThat(queueManager.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("turn-end drain replays queued prompts through the agent path (CQ-05)")
    void turnEndDrainReplaysQueuedPrompts() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-3");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);
        when(executor.executeAsync(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(CompletableFuture
                .completedFuture(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(SessionId.of("default")),
                        ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH))));

        final CliSettings settings = new CliSettings();
        final OutputFormatter formatter = new OutputFormatter(settings);
        final LiveSession liveSession = new DefaultLiveSession(SessionId.of("default"), context, executor,
                LiveSessionOptions.defaults(), queueManager);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        // Pre-stage two queued prompts as if they had been enqueued while a prior turn was active.
        queueManager.enqueue(QueuedInput.builder().inputText("queued 1").priority(QueuedInputPriority.NEXT)
                .agentRuntimeId(agentRuntimeId).build());
        queueManager.enqueue(QueuedInput.builder().inputText("queued 2").priority(QueuedInputPriority.NEXT)
                .agentRuntimeId(agentRuntimeId).build());

        session.processInput("first");

        // Initial prompt + both queued prompts = three executor invocations; nothing left in the queue.
        Mockito.verify(executor, Mockito.times(3)).executeAsync(Mockito.any(), Mockito.any(), Mockito.any());
        assertThat(queueManager.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("turn-end drain routes locally-handled slash commands away from the executor (CQ-05)")
    void turnEndDrainRoutesLocalSlashCommandsLocally() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-4");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);
        when(executor.executeAsync(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(CompletableFuture
                .completedFuture(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(SessionId.of("default")),
                        ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH))));

        final CliSettings settings = new CliSettings();
        final OutputFormatter formatter = new OutputFormatter(settings);
        final LiveSession liveSession = new DefaultLiveSession(SessionId.of("default"), context, executor,
                LiveSessionOptions.defaults(), queueManager);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        // Order: prompt, /quit (locally handled), prompt. /quit must take the command path, prompts must each
        // become their own executor call.
        queueManager.enqueue(QueuedInput.builder().inputText("prompt A").priority(QueuedInputPriority.NEXT)
                .agentRuntimeId(agentRuntimeId).build());
        queueManager.enqueue(QueuedInput.builder().inputText("/quit").priority(QueuedInputPriority.NEXT)
                .agentRuntimeId(agentRuntimeId).build());
        queueManager.enqueue(QueuedInput.builder().inputText("prompt B").priority(QueuedInputPriority.NEXT)
                .agentRuntimeId(agentRuntimeId).build());

        session.processInput("kickoff");

        // kickoff + prompt A + prompt B = three executor calls. /quit must NOT have been forwarded to the executor.
        Mockito.verify(executor, Mockito.times(3)).executeAsync(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(executor, Mockito.never()).executeAsync(Mockito.any(),
                Mockito.argThat(req -> req != null && "/quit".equals(req.getUserInput().asText())), Mockito.any());
        assertThat(queueManager.snapshot()).isEmpty();
    }

    /**
     * Builds a {@link CompletionStage} that completes with the result of {@code resultSupplier} only after
     * {@code gate} is counted down. The wait runs on a helper thread so the caller thread is never blocked.
     */
    private static CompletionStage<OrcaAgentExecutionResult> supplyBlocking(CountDownLatch gate,
            java.util.function.Supplier<OrcaAgentExecutionResult> resultSupplier) {
        final CompletableFuture<OrcaAgentExecutionResult> future = new CompletableFuture<>();
        final Thread helper = new Thread(() -> {
            try {
                gate.await();
                future.complete(resultSupplier.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
        }, "ReplSessionQueueTest-executeAsync-helper");
        helper.setDaemon(true);
        helper.start();
        return future;
    }
}
