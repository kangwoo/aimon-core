package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
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
 * Turn-addressing contract on {@link DefaultLiveSession}: which turn is running, and what happens when an interrupt
 * names a turn.
 *
 * <p>
 * The distinction that matters here is between {@link LiveSession#interrupt(InterruptReason)} — stop whatever is
 * running — and {@link LiveSession#interrupt(TurnId, InterruptReason)} — stop <em>this</em> turn. A user's cancel
 * travels across a network hop, and by the time it lands their turn may have finished and the next one begun. The
 * unaddressed form kills that innocent successor; the addressed form must not.
 *
 * <p>
 * Wiring mirrors {@link DefaultLiveSessionInterruptTest}: a stub streaming executor publishes a
 * {@link DefaultInterruptCoordinator} through the request's interrupt observer and parks on a controllable future, so
 * the
 * session's own bookkeeping is observable without running a real ReAct loop.
 */
@DisplayName("DefaultLiveSession turn addressing")
class DefaultLiveSessionTurnIdTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("currentTurnId() is empty while idle, names the running turn, and clears when it settles")
    void currentTurnIdTracksTheActiveTurn() throws Exception {
        final SessionId sessionId = SessionId.of("turnid-tracking");
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();
        final TurnId turnId = TurnId.of("turn-alpha");

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, createRuntime(), executor,
                LiveSessionOptions.defaults())) {
            assertThat(session.currentTurnId()).as("no turn has started yet").isEmpty();

            final CompletionStage<?> stage = session.submitAsync(turnId, "hi", SubmitOptions.empty(), e -> {
            });
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();

            assertThat(session.currentTurnId()).as("the caller's id, not one the session invented").contains(turnId);

            executor.completeNext(result(sessionId));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertThat(session.currentTurnId()).as("a settled turn is no longer addressable").isEmpty();
        }
    }

    @Test
    @DisplayName("an unaddressed submitAsync still issues a turn id, so every turn is addressable")
    void unaddressedSubmitStillIssuesAnId() throws Exception {
        final SessionId sessionId = SessionId.of("turnid-implicit");
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, createRuntime(), executor,
                LiveSessionOptions.defaults())) {
            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();

            assertThat(session.currentTurnId()).isPresent();

            executor.completeNext(result(sessionId));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("interrupt(turnId) trips the coordinator when it names the running turn")
    void addressedInterruptTripsMatchingTurn() throws Exception {
        final SessionId sessionId = SessionId.of("turnid-match");
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();
        final TurnId turnId = TurnId.of("turn-match");

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, createRuntime(), executor,
                LiveSessionOptions.defaults())) {
            final CompletionStage<?> stage = session.submitAsync(turnId, "hi", SubmitOptions.empty(), e -> {
            });
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();
            final InterruptCoordinator coordinator = executor.capturedCoordinator.get();

            session.interrupt(turnId, InterruptReason.USER_SIGINT);

            assertThat(coordinator.getSignal().isCancelled()).isTrue();
            assertThat(coordinator.getSignal().getReason()).contains(InterruptReason.USER_SIGINT);

            executor.completeNext(result(sessionId));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("interrupt(turnId) naming a different turn leaves the running one alone")
    void addressedInterruptIgnoresMismatchedTurn() throws Exception {
        final SessionId sessionId = SessionId.of("turnid-mismatch");
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();

        try (DefaultLiveSession session = new DefaultLiveSession(sessionId, createRuntime(), executor,
                LiveSessionOptions.defaults())) {
            final CompletionStage<?> stage = session.submitAsync(TurnId.of("turn-2"), "hi", SubmitOptions.empty(),
                    e -> {
                    });
            assertThat(executor.awaitObserverInvoked(1, TimeUnit.SECONDS)).isTrue();
            final InterruptCoordinator coordinator = executor.capturedCoordinator.get();

            // This is the late cancel of the previous turn arriving after turn-2 already started.
            session.interrupt(TurnId.of("turn-1"), InterruptReason.USER_SIGINT);

            assertThat(coordinator.getSignal().isCancelled()).as("turn-2 must survive a cancel meant for turn-1")
                    .isFalse();

            // The unaddressed form has the opposite contract: it stops whatever is running.
            session.interrupt(InterruptReason.USER_SIGINT);
            assertThat(coordinator.getSignal().isCancelled()).isTrue();

            executor.completeNext(result(sessionId));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("interrupt(turnId) on an idle session is a silent no-op, and null arguments are rejected")
    void addressedInterruptOnIdleSessionIsNoOp() {
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();

        try (DefaultLiveSession session = new DefaultLiveSession(SessionId.of("turnid-idle"), createRuntime(), executor,
                LiveSessionOptions.defaults())) {
            session.interrupt(TurnId.of("turn-nobody-is-running"), InterruptReason.USER_SIGINT);
            assertThat(executor.invocationCount()).isZero();

            assertThatThrownBy(() -> session.interrupt(null, InterruptReason.USER_SIGINT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> session.interrupt(TurnId.of("t"), null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    @DisplayName("a submit rejected because the session is closed leaves no turn addressable")
    void closedSessionLeavesNoStaleTurnId() {
        final CapturingStreamingExecutor executor = new CapturingStreamingExecutor();
        final DefaultLiveSession session = new DefaultLiveSession(SessionId.of("turnid-closed"), createRuntime(),
                executor, LiveSessionOptions.defaults());
        session.close();

        assertThatThrownBy(() -> session.submitAsync(TurnId.of("turn-late"), "hi", SubmitOptions.empty(), e -> {
        })).isInstanceOf(IllegalStateException.class);

        assertThat(session.currentTurnId()).as("a turn that never started must not stay addressable").isEmpty();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static OrcaAgentExecutionResult result(SessionId sessionId) {
        return OrcaAgentExecutionResult.success("done", SessionSnapshot.of(sessionId),
                ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH));
    }

    private OrcaAgentRuntime createRuntime() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:test-3"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    /**
     * Publishes a fresh {@link DefaultInterruptCoordinator} per invocation and parks on a caller-released future — same
     * stub contract as {@link DefaultLiveSessionInterruptTest}'s, kept local so neither test constrains the other.
     */
    private static final class CapturingStreamingExecutor
            implements
                AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult>,
                StreamingAgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> {

        final AtomicReference<InterruptCoordinator> capturedCoordinator = new AtomicReference<>();
        private final List<CompletableFuture<OrcaAgentExecutionResult>> inflight = new ArrayList<>();
        private final List<OrcaAgentExecutionResult> preseeded = new ArrayList<>();
        private final CountDownLatch observerLatch = new CountDownLatch(1);
        private int invocations;

        @Override
        public OrcaAgentExecutionResult execute(OrcaAgentRuntime agentRuntime,
                OrcaAgentExecutionRequest executionRequest) {
            throw new UnsupportedOperationException("CapturingStreamingExecutor is streaming-only");
        }

        @Override
        public java.util.concurrent.Flow.Publisher<AgentExecutionEvent> events(OrcaAgentRuntime runtime,
                OrcaAgentExecutionRequest request) {
            throw new UnsupportedOperationException("CapturingStreamingExecutor.events is unused by these tests");
        }

        @Override
        public synchronized CompletionStage<OrcaAgentExecutionResult> executeAsync(OrcaAgentRuntime runtime,
                OrcaAgentExecutionRequest request, Consumer<AgentExecutionEvent> listener) {
            invocations++;
            final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
            capturedCoordinator.set(coordinator);
            request.getInterruptObserver().accept(coordinator);
            observerLatch.countDown();

            final CompletableFuture<OrcaAgentExecutionResult> future = new CompletableFuture<>();
            if (!preseeded.isEmpty()) {
                future.complete(preseeded.remove(0));
            } else {
                inflight.add(future);
            }
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
            return observerLatch.await(timeout, unit);
        }
    }
}
