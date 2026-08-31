package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.BudgetTracker;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.StreamingAgentExecutor;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Verifies the {@link DefaultLiveSession#status()} runtime snapshot:
 * <ul>
 * <li>an idle session (no turn submitted) reports {@link LiveSessionStatus.Phase#IDLE} with no live progress;
 * <li>a running turn reports {@link LiveSessionStatus.Phase#RUNNING}, is interruptible, and surfaces the turn's live
 * {@link BudgetTracker} counters via {@link LiveSessionStatus.TurnProgress};
 * <li>the snapshot reverts to {@code IDLE} once the turn settles (active refs cleared);
 * <li>completed turns are folded into {@link LiveSessionStatus#getSessionTotals() sessionTotals};
 * <li>a closed session reports {@link LiveSessionStatus.Phase#CLOSED};
 * <li>{@link LiveSessionStatus#getQueueDepth()} reflects the wired mid-turn queue.
 * </ul>
 *
 * <p>
 * The tests bypass {@link at.aimon.core.agent.impl.orca.OrcaAgentExecutor} and wire a {@link CapturingExecutor} that
 * publishes a {@link DefaultInterruptCoordinator} and a caller-supplied {@link BudgetTracker} through the request's
 * observers, then parks on a controllable future — isolating the session's status wiring from the executor internals.
 */
@DisplayName("DefaultLiveSession#status() runtime snapshot")
class DefaultLiveSessionStatusTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("idle session before any submit reports IDLE with no turn progress")
    void idleBeforeSubmit() {
        final OrcaAgentRuntime context = createContext();
        final CapturingExecutor executor = new CapturingExecutor(newTracker());

        try (DefaultLiveSession session = new DefaultLiveSession(SessionId.of("status-idle"), context, executor,
                LiveSessionOptions.defaults())) {
            final LiveSessionStatus status = session.status();

            assertThat(status.getSessionId()).isEqualTo(SessionId.of("status-idle"));
            assertThat(status.getPhase()).isEqualTo(LiveSessionStatus.Phase.IDLE);
            assertThat(status.isInterruptible()).isFalse();
            assertThat(status.getQueueDepth()).isZero();
            assertThat(status.getTurnProgress()).isEmpty();
            assertThat(status.getSessionTotals().getTurnCount()).isZero();
            assertThat(status.getSessionTotals().getTokenUsage().getTotalTokens()).isZero();
            assertThat(status.getOptions()).isPresent();
        }
    }

    @Test
    @DisplayName("running turn exposes RUNNING + interruptible + live progress, then reverts to IDLE after completion")
    void runningTurnExposesLiveProgress() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final BudgetTracker tracker = newTracker(ExecutionBudget.builder().maxTokens(100).build());
        tracker.recordIteration();
        tracker.recordIteration();
        tracker.recordTokens(TokenUsage.of(10, 5, 15));
        final CapturingExecutor executor = new CapturingExecutor(tracker);
        final SessionId sessionId = SessionId.of("status-running");

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults())) {
            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();

            final LiveSessionStatus running = session.status();
            assertThat(running.getPhase()).isEqualTo(LiveSessionStatus.Phase.RUNNING);
            assertThat(running.isInterruptible()).isTrue();
            assertThat(running.getTurnProgress()).isPresent();
            final LiveSessionStatus.TurnProgress progress = running.getTurnProgress().orElseThrow();
            assertThat(progress.getIterations()).isEqualTo(2);
            assertThat(progress.getTokenUsage().getTotalTokens()).isEqualTo(15);
            // used (15) is paired with the turn's enforced budget max (100) for "used / max" rendering.
            assertThat(progress.getBudget().getMaxTokens()).contains(100);
            // newTracker() uses a Clock fixed at EPOCH, so elapsed() is deterministically ZERO here.
            assertThat(progress.getElapsed()).isEqualTo(Duration.ZERO);
            // No prior turn has completed yet, so session totals are still empty.
            assertThat(running.getSessionTotals().getTurnCount()).isZero();

            executor.completeNext(result(sessionId));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);

            final LiveSessionStatus afterDone = session.status();
            assertThat(afterDone.getPhase()).isEqualTo(LiveSessionStatus.Phase.IDLE);
            assertThat(afterDone.isInterruptible()).isFalse();
            assertThat(afterDone.getTurnProgress()).isEmpty();
            // The single completed turn is folded into the session-cumulative totals.
            assertThat(afterDone.getSessionTotals().getTurnCount()).isEqualTo(1);
            assertThat(afterDone.getSessionTotals().getIterations()).isEqualTo(2);
            assertThat(afterDone.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(15);
        }
    }

    @Test
    @DisplayName("sessionTotals accumulate token + iteration counts across multiple completed turns")
    void conversationTotalsAccumulateAcrossTurns() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final BudgetTracker t1 = newTracker();
        t1.recordIteration();
        t1.recordTokens(TokenUsage.of(10, 5, 15));
        final BudgetTracker t2 = newTracker();
        t2.recordIteration();
        t2.recordIteration();
        t2.recordTokens(TokenUsage.of(20, 10, 30));
        final CapturingExecutor executor = new CapturingExecutor(t1, t2);
        final SessionId sessionId = SessionId.of("status-totals");

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults())) {
            // The stub publishes its observers synchronously inside submitAsync, so the turn is in flight on return.
            final CompletionStage<?> first = session.submitAsync("a", e -> {
            });
            executor.completeNext(result(sessionId));
            first.toCompletableFuture().get(1, TimeUnit.SECONDS);

            final LiveSessionStatus afterFirst = session.status();
            assertThat(afterFirst.getSessionTotals().getTurnCount()).isEqualTo(1);
            assertThat(afterFirst.getSessionTotals().getIterations()).isEqualTo(1);
            assertThat(afterFirst.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(15);

            final CompletionStage<?> second = session.submitAsync("b", e -> {
            });
            executor.completeNext(result(sessionId));
            second.toCompletableFuture().get(1, TimeUnit.SECONDS);

            final LiveSessionStatus afterSecond = session.status();
            assertThat(afterSecond.getSessionTotals().getTurnCount()).isEqualTo(2);
            assertThat(afterSecond.getSessionTotals().getIterations()).isEqualTo(3);
            assertThat(afterSecond.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(45);
        }
    }

    @Test
    @DisplayName("closed session reports CLOSED")
    void closedSession() {
        final OrcaAgentRuntime context = createContext();
        final CapturingExecutor executor = new CapturingExecutor(newTracker());

        final DefaultLiveSession session = new DefaultLiveSession(SessionId.of("status-closed"), context, executor,
                LiveSessionOptions.defaults());
        session.close();

        assertThat(session.status().getPhase()).isEqualTo(LiveSessionStatus.Phase.CLOSED);
    }

    @Test
    @DisplayName("session totals survive close()")
    void totalsSurviveClose() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final BudgetTracker tracker = newTracker();
        tracker.recordIteration();
        tracker.recordTokens(TokenUsage.of(4, 2, 6));
        final CapturingExecutor executor = new CapturingExecutor(tracker);
        final SessionId sessionId = SessionId.of("status-close-retain");

        final DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults());
        final CompletionStage<?> stage = session.submitAsync("hi", e -> {
        });
        executor.completeNext(result(sessionId));
        stage.toCompletableFuture().get(1, TimeUnit.SECONDS);

        session.close();

        final LiveSessionStatus afterClose = session.status();
        assertThat(afterClose.getPhase()).isEqualTo(LiveSessionStatus.Phase.CLOSED);
        // Cumulative totals outlive close().
        assertThat(afterClose.getSessionTotals().getTurnCount()).isEqualTo(1);
        assertThat(afterClose.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(6);
    }

    @Test
    @DisplayName("a turn that publishes no tracker (e.g. a slash-command turn) is excluded from session totals")
    void turnWithoutTrackerIsExcludedFromTotals() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final CapturingExecutor executor = new CapturingExecutor(newTracker()).withoutObservers();
        final SessionId sessionId = SessionId.of("status-command");

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults())) {
            final CompletionStage<?> stage = session.submitAsync("/help", e -> {
            });
            // No handles published, so the session never observes the turn — it still reads IDLE.
            assertThat(session.status().getPhase()).isEqualTo(LiveSessionStatus.Phase.IDLE);

            executor.completeNext(result(sessionId));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);

            final LiveSessionStatus after = session.status();
            assertThat(after.getSessionTotals().getTurnCount()).isZero();
        }
    }

    @Test
    @DisplayName("queueDepth reflects the wired mid-turn queue while a turn is in flight")
    void queueDepthReflectsWiredQueue() throws Exception {
        final OrcaAgentRuntime context = createContext();
        final CapturingExecutor executor = new CapturingExecutor(newTracker());
        final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        final SessionId sessionId = SessionId.of("status-queue");

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                LiveSessionOptions.defaults(), queue)) {
            final SubmitOutcome first = session.offerAsync("first", e -> {
            });
            assertThat(first.getKind()).isEqualTo(SubmitOutcome.Kind.EXECUTED);
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();

            final SubmitOutcome second = session.offerAsync("second", e -> {
            });
            assertThat(second.getKind()).isEqualTo(SubmitOutcome.Kind.QUEUED);

            assertThat(session.status().getQueueDepth()).isEqualTo(1);

            executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(sessionId),
                    ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH)));
            first.getResultStage().orElseThrow().toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private BudgetTracker newTracker() {
        return newTracker(ExecutionBudget.unlimited());
    }

    private BudgetTracker newTracker(ExecutionBudget budget) {
        return new BudgetTracker(budget, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static OrcaAgentExecutionResult result(SessionId sessionId) {
        return OrcaAgentExecutionResult.success("done", SessionSnapshot.of(sessionId),
                ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH));
    }

    private OrcaAgentRuntime createContext() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:test-status"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    /**
     * Streaming executor stub that emulates the Orca executor's loop-entry contract: per invocation it creates a fresh
     * {@link DefaultInterruptCoordinator}, publishes it together with the {@link BudgetTracker} for that turn to the
     * request's observers, then parks on a caller-controlled future released via {@link #completeNext}. Supplying more
     * than one tracker models successive turns (turn N publishes tracker N, then the last tracker repeats).
     */
    private static final class CapturingExecutor
            implements
                AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult>,
                StreamingAgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> {

        private final List<BudgetTracker> trackers;
        private final List<CompletableFuture<OrcaAgentExecutionResult>> inflight = new ArrayList<>();
        private final List<CountDownLatch> observerLatches = new ArrayList<>();
        private boolean publishObservers = true;
        private int invocations;

        CapturingExecutor(BudgetTracker... trackers) {
            this.trackers = List.of(trackers);
        }

        /** Emulates a turn that bypasses the ReAct loop (e.g. a slash command): no observer handles are published. */
        CapturingExecutor withoutObservers() {
            this.publishObservers = false;
            return this;
        }

        @Override
        public OrcaAgentExecutionResult execute(OrcaAgentRuntime agentRuntime,
                OrcaAgentExecutionRequest executionRequest) {
            throw new UnsupportedOperationException("CapturingExecutor is streaming-only");
        }

        @Override
        public java.util.concurrent.Flow.Publisher<AgentExecutionEvent> events(OrcaAgentRuntime context,
                OrcaAgentExecutionRequest request) {
            throw new UnsupportedOperationException("CapturingExecutor.events is unused by these tests");
        }

        @Override
        public synchronized CompletionStage<OrcaAgentExecutionResult> executeAsync(OrcaAgentRuntime context,
                OrcaAgentExecutionRequest request, Consumer<AgentExecutionEvent> listener) {
            invocations++;
            final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
            final BudgetTracker tracker = trackers.get(Math.min(invocations - 1, trackers.size() - 1));
            if (publishObservers) {
                request.getInterruptObserver().accept(coordinator);
                request.getBudgetObserver().accept(tracker);
            }
            for (int i = 0; i < Math.min(invocations, observerLatches.size()); i++) {
                observerLatches.get(i).countDown();
            }

            final CompletableFuture<OrcaAgentExecutionResult> future = new CompletableFuture<>();
            inflight.add(future);
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
