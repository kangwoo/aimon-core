package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.StreamingAgentExecutor;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Verifies the interrupt contract on {@link DefaultLiveSession}:
 * <ul>
 * <li>{@link LiveSession#interrupt(InterruptReason) session.interrupt(reason)} trips the turn's active
 * {@link InterruptCoordinator} so cooperative tools / checkpoints observe the cancellation.
 * <li>A {@link QueuedInputPriority#NOW NOW}-priority enqueue on the wired {@link MessageQueueManager} automatically
 * routes to {@code session.interrupt(NOW_PRIORITY_INPUT)} when the input targets this session's context id.
 * <li>{@link QueuedInputPriority#NEXT NEXT}-priority enqueues and NOW enqueues targeting a different context id are
 * ignored.
 * <li>Calls to {@code session.interrupt(...)} on an idle session are silent no-ops (no active coordinator exists).
 * </ul>
 *
 * <p>
 * The tests bypass {@link at.aimon.core.agent.impl.orca.OrcaAgentExecutor} and wire a
 * {@link CapturingStreamingExecutor}
 * that publishes a {@link DefaultInterruptCoordinator} through the request's interrupt observer and parks on a
 * controllable future. This keeps the session-wiring surface (observer capture, ref clearing, queue listener
 * registration / filtering, interrupt forwarding, close-time unsubscribe) isolated from the executor's own interrupt
 * handling — which has dedicated coverage in {@code OrcaAgentExecutorInterruptTest}.
 */
@DisplayName("DefaultLiveSession interrupt wiring")
class DefaultLiveSessionInterruptTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("session.interrupt(USER_SIGINT) trips the active coordinator mid-turn")
    void externalInterruptTripsActiveCoordinator() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final SessionId sessionId = SessionId.of("irq04-external");
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults())) {
            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            // Wait until the executor has published the coordinator via the interrupt observer.
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();
            final InterruptCoordinator coordinator = executor.capturedCoordinator.get();
            assertThat(coordinator).isNotNull();
            assertThat(coordinator.getSignal().isCancelled()).isFalse();

            session.interrupt(InterruptReason.USER_SIGINT);

            assertThat(coordinator.getSignal().isCancelled()).isTrue();
            assertThat(coordinator.getSignal().getReason()).contains(InterruptReason.USER_SIGINT);

            // Let the in-flight stage finish so try-with-resources close() sees a clean session.
            executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(sessionId),
                    ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH)));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("NOW-priority enqueue on the wired queue trips the active coordinator with NOW_PRIORITY_INPUT")
    void nowPriorityEnqueueTripsActiveCoordinator() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final SessionId sessionId = SessionId.of("irq04-now");
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();
        final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults(), queue)) {
            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();
            final InterruptCoordinator coordinator = executor.capturedCoordinator.get();

            queue.enqueue(QueuedInput.builder().inputText("now please").priority(QueuedInputPriority.NOW)
                    .agentRuntimeId(context.getId()).build());

            assertThat(coordinator.getSignal().isCancelled()).isTrue();
            assertThat(coordinator.getSignal().getReason()).contains(InterruptReason.NOW_PRIORITY_INPUT);
            // CQ-03 retention: the NOW entry stays in the queue until the next turn drains it.
            assertThat(queue.snapshot()).hasSize(1);
            assertThat(queue.snapshot().get(0).getPriority()).isEqualTo(QueuedInputPriority.NOW);

            executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(sessionId),
                    ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH)));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("NEXT-priority enqueue does NOT trip the active coordinator (no interrupt)")
    void nextPriorityEnqueueDoesNotInterrupt() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final SessionId sessionId = SessionId.of("irq04-next-skip");
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();
        final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults(), queue)) {
            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();
            final InterruptCoordinator coordinator = executor.capturedCoordinator.get();

            queue.enqueue(QueuedInput.builder().inputText("later").priority(QueuedInputPriority.NEXT)
                    .agentRuntimeId(context.getId()).build());

            assertThat(coordinator.getSignal().isCancelled()).isFalse();
            assertThat(queue.snapshot()).hasSize(1);

            executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(sessionId),
                    ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH)));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("NOW enqueue targeting a DIFFERENT context id does NOT trip this session's coordinator")
    void nowPriorityForDifferentContextIsIgnored() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final SessionId sessionId = SessionId.of("irq04-now-isolation");
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();
        final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults(), queue)) {
            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();
            final InterruptCoordinator coordinator = executor.capturedCoordinator.get();

            final AgentRuntimeId otherRuntimeId = AgentRuntimeId.of("agent:test-1");
            queue.enqueue(QueuedInput.builder().inputText("for-sibling").priority(QueuedInputPriority.NOW)
                    .agentRuntimeId(otherRuntimeId).build());

            assertThat(coordinator.getSignal().isCancelled()).isFalse();

            executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(sessionId),
                    ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH)));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("interrupt() on an idle session is a silent no-op (no active coordinator)")
    void interruptOnIdleSessionIsNoOp() {
        final OrcaAgentRuntime context = createContext();
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();

        try (DefaultLiveSession session = new DefaultLiveSession(SessionId.of("irq04-idle"), context, executor,
                LiveSessionOptions.defaults())) {
            // No submit yet — no active coordinator. Must not throw.
            session.interrupt(InterruptReason.USER_SIGINT);
            session.interrupt(InterruptReason.NOW_PRIORITY_INPUT);
            // Still idle afterwards.
            assertThat(executor.invocationCount()).isZero();
        }
    }

    @Test
    @DisplayName("after a turn completes, a subsequent interrupt() is a no-op (active ref is cleared)")
    void interruptAfterTurnCompletionIsNoOp() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final SessionId sessionId = SessionId.of("irq04-post-turn");
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults())) {
            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();
            final InterruptCoordinator coordinator = executor.capturedCoordinator.get();

            executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(sessionId),
                    ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH)));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);

            // The session's active-coordinator ref must have been cleared in whenComplete, so the stale coordinator is
            // not reachable through session.interrupt().
            session.interrupt(InterruptReason.USER_SIGINT);
            assertThat(coordinator.getSignal().isCancelled()).isFalse();
        }
    }

    @Test
    @DisplayName("close() trips the in-flight turn's coordinator with SESSION_RELEASED rather than stranding it")
    void closeTripsInFlightCoordinator() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final SessionId sessionId = SessionId.of("irq04-close-inflight");
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();

        final DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults());
        final CompletionStage<?> stage = session.submitAsync("hi", e -> {
        });
        assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();
        final InterruptCoordinator coordinator = executor.capturedCoordinator.get();
        assertThat(coordinator.getSignal().isCancelled()).isFalse();

        session.close();

        // Evicting the turn without tripping it would leave a running turn nothing can ever cancel: after close() the
        // coordinator is unreachable through session.interrupt(), so close() is the last moment it can be asked to
        // stop.
        assertThat(coordinator.getSignal().isCancelled()).isTrue();
        assertThat(coordinator.getSignal().getReason()).contains(InterruptReason.SESSION_RELEASED);

        // close() requests cancellation, it does not join — the turn is still in flight and settles on its own.
        assertThat(stage.toCompletableFuture().isDone()).isFalse();
        executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(sessionId),
                ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH)));
        stage.toCompletableFuture().get(1, TimeUnit.SECONDS);

        // Idempotent: the turn is already evicted, so a second close() has nothing to trip and must not throw.
        session.close();
    }

    @Test
    @DisplayName("close() on an idle session trips nothing and stays a silent no-op")
    void closeOnIdleSessionTripsNothing() {
        final OrcaAgentRuntime context = createContext();
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();

        final DefaultLiveSession session = new DefaultLiveSession(SessionId.of("irq04-close-idle"), context, executor,
                LiveSessionOptions.defaults());
        session.close();

        assertThat(session.isClosed()).isTrue();
        assertThat(executor.capturedCoordinator.get()).isNull();
        assertThat(executor.invocationCount()).isZero();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private OrcaAgentRuntime createContext() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:test-2"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    /**
     * Streaming executor stub that emulates the Orca executor's interrupt contract: creates a fresh
     * {@link DefaultInterruptCoordinator} per invocation and publishes it to the request's interrupt observer before
     * parking on a caller-controlled future. Tests observe the published coordinator via
     * {@link #capturedCoordinator} and release the parked future via {@link #completeNext}.
     */
    private static final class CapturingStreamingExecutor
            implements
                AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult>,
                StreamingAgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> {

        final AtomicReference<InterruptCoordinator> capturedCoordinator = new AtomicReference<>();
        private final List<CompletableFuture<OrcaAgentExecutionResult>> inflight = new ArrayList<>();
        private final List<OrcaAgentExecutionResult> preseeded = new ArrayList<>();
        private final List<CountDownLatch> observerLatches = new ArrayList<>();
        private int invocations;

        @Override
        public OrcaAgentExecutionResult execute(OrcaAgentRuntime agentRuntime,
                OrcaAgentExecutionRequest executionRequest) {
            throw new UnsupportedOperationException("CapturingStreamingExecutor is streaming-only");
        }

        @Override
        public java.util.concurrent.Flow.Publisher<AgentExecutionEvent> events(OrcaAgentRuntime context,
                OrcaAgentExecutionRequest request) {
            throw new UnsupportedOperationException("CapturingStreamingExecutor.events is unused by these tests");
        }

        @Override
        public synchronized CompletionStage<OrcaAgentExecutionResult> executeAsync(OrcaAgentRuntime context,
                OrcaAgentExecutionRequest request, Consumer<AgentExecutionEvent> listener) {
            invocations++;
            // Emulate the executor: construct a per-invocation coordinator and publish it to the observer.
            final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
            capturedCoordinator.set(coordinator);
            request.getInterruptObserver().accept(coordinator);
            // Signal any awaiters that the observer has been invoked.
            for (int i = 0; i < Math.min(invocations, observerLatches.size()); i++) {
                observerLatches.get(i).countDown();
            }

            final CompletableFuture<OrcaAgentExecutionResult> future = new CompletableFuture<>();
            if (!preseeded.isEmpty()) {
                future.complete(preseeded.remove(0));
            } else {
                inflight.add(future);
            }
            // Mirror the executor's turn-end contract: close the coordinator when the stage settles.
            future.whenComplete((r, t) -> coordinator.close());
            return future;
        }

        synchronized void completeNext(OrcaAgentExecutionResult result) {
            for (CompletableFuture<OrcaAgentExecutionResult> future : inflight) {
                if (!future.isDone()) {
                    future.complete(result);
                    return;
                }
            }
            preseeded.add(result);
        }

        synchronized int invocationCount() {
            return invocations;
        }

        boolean awaitObserverInvoked(long timeout, TimeUnit unit) throws InterruptedException {
            final CountDownLatch latch;
            synchronized (this) {
                if (observerLatches.isEmpty()) {
                    observerLatches.add(new CountDownLatch(1));
                }
                if (invocations >= 1) {
                    observerLatches.get(0).countDown();
                }
                latch = observerLatches.get(0);
            }
            return latch.await(timeout, unit);
        }
    }
}
