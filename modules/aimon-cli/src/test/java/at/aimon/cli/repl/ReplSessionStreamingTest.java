package at.aimon.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.factory.AgentSetupFactory;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.interrupt.InterruptReason;
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
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantMessageReceived;
import at.aimon.core.agent.stream.AssistantTextDelta;
import at.aimon.core.agent.stream.AssistantTextStreamCompleted;
import at.aimon.core.agent.stream.ExecutionCompleted;
import at.aimon.core.agent.stream.IterationCompleted;
import at.aimon.core.agent.stream.IterationStarted;
import at.aimon.core.agent.stream.SkillTurnSuspendedEvent;
import at.aimon.core.agent.stream.ToolResultReady;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.skill.policy.pending.InMemoryPendingTurnRegistry;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.pending.PendingTurn;
import at.aimon.core.skill.policy.pending.PendingTurnId;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;

/**
 * STREAM-04 integration coverage: verifies that {@link ReplSession} routes a user prompt through
 * {@link at.aimon.core.agent.stream.StreamingAgentExecutor#executeAsync} and renders per-event output via
 * {@link OutputFormatter#displayEvent(AgentExecutionEvent)} instead of the legacy "[Agent thinking...]" spinner.
 *
 * <p>
 * Tests drive the REPL through its package-private {@code processInput} path so no real JLine terminal is required. The
 * fake {@link OrcaAgentExecutor} invokes the listener with a scripted event sequence, so assertions operate on the
 * captured {@link System#out} stream.
 */
@DisplayName("ReplSession streaming output (STREAM-04)")
class ReplSessionStreamingTest {

    private static final String LEGACY_SPINNER = "[Agent thinking...]";

    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    private CliSettings settings;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(capturedOut));
        settings = new CliSettings();
        // Deterministic assertions against plain text — no ANSI escapes.
        settings.setColorOutput(false);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    @DisplayName("processInput routes a prompt through executeAsync and dispatches each event to OutputFormatter")
    void streamingPathDispatchesEachEventAndRendersFinalResult() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-1");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);

        // Scripted event sequence — covers every branch in OutputFormatter.displayEvent.
        final List<AgentExecutionEvent> scripted = List.of(
                IterationStarted.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .plannedIteration(1).build(),
                AssistantMessageReceived.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .messageSummary("Thinking about the request").build(),
                ToolUseStarted.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .toolName("Read").toolUseId("tool-1").build(),
                ToolResultReady.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .toolName("Read").toolUseId("tool-1").success(false).errorMessage("permission denied").build(),
                IterationCompleted.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .completedIteration(1).willContinue(false).build(),
                ExecutionCompleted.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .completionReason(CompletionReason.COMPLETED).totalIterations(1).elapsed(Duration.ZERO)
                        .build());

        when(executor.executeAsync(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<AgentExecutionEvent> listener = invocation.getArgument(2, Consumer.class);
            for (AgentExecutionEvent event : scripted) {
                listener.accept(event);
            }
            return CompletableFuture.completedFuture(
                    OrcaAgentExecutionResult.success("All done.", SessionSnapshot.of(SessionId.of("default")),
                            ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH)));
        });

        // Spy so we can assert that each scripted event actually reached the OutputFormatter — not just the listener
        // accumulator. The no-op branches (IterationCompleted, ExecutionCompleted, CompactBoundary, ExecutionError,
        // ToolUseStarted) produce no stdout and are only observable via the spy.
        final OutputFormatter formatter = spy(new OutputFormatter(settings));
        final LiveSession liveSession = new DefaultLiveSession(SessionId.of("default"), context, executor,
                LiveSessionOptions.defaults(), queueManager);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        session.processInput("hello agent");

        // Every scripted event must have been dispatched through the central displayEvent entry point in order.
        final ArgumentCaptor<AgentExecutionEvent> dispatched = ArgumentCaptor.forClass(AgentExecutionEvent.class);
        verify(formatter, Mockito.times(scripted.size())).displayEvent(dispatched.capture());
        assertThat(dispatched.getAllValues()).containsExactlyElementsOf(scripted);

        // And each per-type dispatch method must have fired exactly once — including the intentional no-ops, which
        // stdout assertions cannot observe.
        verify(formatter).displayIterationStarted(Mockito.any(IterationStarted.class));
        verify(formatter).displayAssistantMessageReceived(Mockito.any(AssistantMessageReceived.class));
        verify(formatter).displayToolUseStarted(Mockito.any(ToolUseStarted.class));
        verify(formatter).displayToolResultReady(Mockito.any(ToolResultReady.class));
        verify(formatter).displayIterationCompleted(Mockito.any(IterationCompleted.class));
        verify(formatter).displayExecutionCompleted(Mockito.any(ExecutionCompleted.class));

        final String output = capturedOut.toString();

        // STREAM-04 invariant: the legacy spinner is gone.
        assertThat(output).doesNotContain(LEGACY_SPINNER);

        // IterationStarted(iteration=1) renders the new progress marker.
        assertThat(output).contains("[Agent running...]");

        // AssistantMessageReceived surfaces the intermediate summary.
        assertThat(output).contains("Thinking about the request");

        // ToolResultReady with success=false surfaces a tool-error banner; success cases stay silent (covered by
        // ToolCallDisplayHook elsewhere).
        assertThat(output).contains("Tool 'Read' failed: permission denied");

        // Final result renders via displaySuccessResult — this output is the canonical final answer, not a duplicate
        // of the ExecutionCompleted event (which is deliberately a no-op in OutputFormatter).
        assertThat(output).contains("All done.");

        // Turn completed cleanly — session busy flag released, nothing left queued.
        assertThat(queueManager.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("turn-end drain still replays queued prompts through the streaming executeAsync path")
    void turnEndDrainReplaysEnqueuedPromptsThroughStreamingPath() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-2");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);
        when(executor.executeAsync(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(CompletableFuture
                .completedFuture(OrcaAgentExecutionResult.success("ok", SessionSnapshot.of(SessionId.of("default")),
                        ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH))));

        final OutputFormatter formatter = new OutputFormatter(settings);
        final LiveSession liveSession = new DefaultLiveSession(SessionId.of("default"), context, executor,
                LiveSessionOptions.defaults(), queueManager);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        queueManager.enqueue(QueuedInput.builder().inputText("queued after kickoff").priority(QueuedInputPriority.NEXT)
                .agentRuntimeId(agentRuntimeId).build());

        session.processInput("kickoff");

        // kickoff + the queued prompt = two streaming executor invocations; nothing stays behind.
        Mockito.verify(executor, Mockito.times(2)).executeAsync(Mockito.any(), Mockito.any(), Mockito.any());
        assertThat(queueManager.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("SIGINT handler requests cooperative interrupt first")
    void sigintHandlerRequestsCooperativeInterruptFirst() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-3");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);

        final OutputFormatter formatter = new OutputFormatter(settings);
        // Mocked LiveSession so we can verify the interrupt call without standing up the Orca coordinator machinery.
        final LiveSession liveSession = mock(LiveSession.class);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        // Future stays pending until we observe the fallback cancel — mirrors an in-flight turn blocked inside a tool.
        final CompletableFuture<at.aimon.core.agent.AgentExecutionResult> pending = new CompletableFuture<>();

        session.requestInterruptWithFallback(pending);

        // Cooperative request fires synchronously on the signal-handler thread so the executor can start winding down
        // before the fallback grace period elapses.
        verify(liveSession).interrupt(InterruptReason.USER_SIGINT);

        // Grace period is 500ms; 2s is a comfortable upper bound without making the test flaky.
        final long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!pending.isDone() && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Test thread interrupted while waiting for fallback cancel", e);
            }
        }
        assertThat(pending.isCancelled()).isTrue();
        assertThatThrownBy(pending::join).isInstanceOf(CancellationException.class);
    }

    @Test
    @DisplayName("SIGINT handler skips fallback cancel when the turn completes cooperatively")
    void sigintHandlerSkipsFallbackWhenCooperativeCompletes() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-4");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);

        final OutputFormatter formatter = new OutputFormatter(settings);
        final LiveSession liveSession = mock(LiveSession.class);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        // Future completes normally (cooperative path) with an INTERRUPTED result before the fallback fires.
        final OrcaAgentExecutionResult interrupted = OrcaAgentExecutionResult.failure("Execution interrupted",
                SessionSnapshot.of(SessionId.of("default")),
                ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH), List.of(),
                CompletionReason.INTERRUPTED);
        final CompletableFuture<at.aimon.core.agent.AgentExecutionResult> cooperative = CompletableFuture
                .completedFuture(interrupted);

        session.requestInterruptWithFallback(cooperative);

        verify(liveSession).interrupt(InterruptReason.USER_SIGINT);

        // Watch the full grace window: the cooperative future must stay non-cancelled for the entire period. A plain
        // Thread.sleep + one-shot assertion would miss a regression where the fallback's scheduled cancel ever became
        // effective mid-window; polling the invariant continuously catches it regardless of scheduler timing.
        final long deadlineNanos = System.nanoTime() + Duration.ofMillis(700L).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            assertThat(cooperative.isCancelled()).isFalse();
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Test thread interrupted while watching grace window", e);
            }
        }
        assertThat(cooperative.join()).isSameAs(interrupted);
    }

    @Test
    @DisplayName("SIGINT drops the just-suspended pending turn from the registry (SK-11.5 Phase 6)")
    void sigintDropsJustSuspendedPendingTurnFromRegistry() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-5");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);

        // Real registry: the assertion target. A live PendingTurn is pre-registered to model "the executor just
        // suspended this turn and emitted a SkillTurnSuspendedEvent before the user hit Ctrl+C".
        final PendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        final PendingTurnId turnId = PendingTurnId.generate();
        final Instant createdAt = Instant.now();
        final PendingTurn turn = PendingTurn.builder().id(turnId).agentRuntimeId(agentRuntimeId)
                .pendingSkills(List
                        .of(PendingSkillRequest.builder().toolUseId("tu_sigint").skillName("commit").args("").build()))
                .createdAt(createdAt).ttl(Duration.ofMinutes(5)).build();
        registry.register(turn);

        final OutputFormatter formatter = new OutputFormatter(settings);
        // Mocked LiveSession so we can verify the cooperative interrupt fired without standing up the Orca
        // coordinator.
        final LiveSession liveSession = mock(LiveSession.class);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).pendingTurnRegistry(registry).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        // Drive the event capture path the executor would normally hit: this sets the volatile latestPendingTurnId
        // that requestInterruptWithFallback consumes. Going through captureAndDispatchEvent (the real production
        // entry point) instead of poking private state keeps the test honest about what wiring needs to hold.
        session.captureAndDispatchEvent(
                SkillTurnSuspendedEvent.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .pendingTurnId(turnId).pendingSkills(turn.getPendingSkills()).build());

        // Future stays pending — only relevant so the fallback cancel has something to cancel; the assertion this test
        // owns is registry eviction, which happens synchronously in dropPendingTurnIfAny() before the schedule.
        final CompletableFuture<at.aimon.core.agent.AgentExecutionResult> pending = new CompletableFuture<>();

        session.requestInterruptWithFallback(pending);

        // Cooperative interrupt still fires — SIGINT semantics are unchanged; pending-turn drop is additive (Phase 6).
        verify(liveSession).interrupt(InterruptReason.USER_SIGINT);
        // The just-suspended turn is gone from the registry, so the timeout reaper has nothing to sweep later.
        assertThat(registry.get(turnId)).as("Ctrl+C must drop the just-suspended pending turn synchronously").isEmpty();
    }

    @Test
    @DisplayName("SIGINT does not touch the registry when no turn suspended in the active interaction (SK-11.5 Phase 6)")
    void sigintLeavesUnrelatedPendingTurnsUntouched() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-6");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);

        // Pre-existing unrelated pending turn from some prior interaction. The point of this test is that the SIGINT
        // path only ever evicts the turn the user just observed suspending in this session — never an older turn that
        // happens to live in the same registry. This is the contract latestPendingTurnId enforces.
        final PendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        final PendingTurnId unrelatedId = PendingTurnId.generate();
        final Instant createdAt = Instant.now();
        final PendingTurn unrelated = PendingTurn.builder().id(unrelatedId).agentRuntimeId(agentRuntimeId)
                .pendingSkills(List
                        .of(PendingSkillRequest.builder().toolUseId("tu_prior").skillName("deploy").args("").build()))
                .createdAt(createdAt).ttl(Duration.ofMinutes(5)).build();
        registry.register(unrelated);

        final OutputFormatter formatter = new OutputFormatter(settings);
        final LiveSession liveSession = mock(LiveSession.class);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).pendingTurnRegistry(registry).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        // No SkillTurnSuspendedEvent dispatched in this turn → latestPendingTurnId stays null.
        final CompletableFuture<at.aimon.core.agent.AgentExecutionResult> pending = new CompletableFuture<>();

        session.requestInterruptWithFallback(pending);

        verify(liveSession).interrupt(InterruptReason.USER_SIGINT);
        assertThat(registry.get(unrelatedId)).as("SIGINT must not drop an unrelated pending turn").contains(unrelated);
    }

    @Test
    @DisplayName("PSTREAM-11: scripted AssistantTextDelta chunks render inline and wasStreamed=true suppresses re-print")
    void streamingDeltasRenderInlineAndSuppressFinalAnswerWhenWasStreamed() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-7");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);

        // Scripted streaming sequence mirrors the PSTREAM-11 DoD: progressive delta chunks, stream completion, and an
        // execution-completed marker for the REPL footer.
        final String finalAnswer = "The answer is 42";
        final List<AgentExecutionEvent> scripted = List.of(
                IterationStarted.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .plannedIteration(1).build(),
                AssistantTextDelta.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .delta("The answer ").chunkIndex(0).build(),
                AssistantTextDelta.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .delta("is 42").chunkIndex(1).build(),
                AssistantTextStreamCompleted.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId)
                        .iteration(1).totalLength(finalAnswer.length()).build(),
                IterationCompleted.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .completedIteration(1).willContinue(false).build(),
                ExecutionCompleted.builder().timestamp(Instant.EPOCH).agentRuntimeId(agentRuntimeId).iteration(1)
                        .completionReason(CompletionReason.COMPLETED).totalIterations(1).elapsed(Duration.ZERO)
                        .build());

        when(executor.executeAsync(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<AgentExecutionEvent> listener = invocation.getArgument(2, Consumer.class);
            for (AgentExecutionEvent event : scripted) {
                listener.accept(event);
            }
            // wasStreamed=true: deltas have already painted the answer inline, so displaySuccessResult must NOT
            // re-print the final answer. The iteration footer should still render.
            return CompletableFuture.completedFuture(
                    OrcaAgentExecutionResult.success(finalAnswer, SessionSnapshot.of(SessionId.of("default")),
                            ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH), List.of(),
                            CompletionReason.COMPLETED, true));
        });

        final OutputFormatter formatter = new OutputFormatter(settings);
        final LiveSession liveSession = new DefaultLiveSession(SessionId.of("default"), context, executor,
                LiveSessionOptions.defaults(), queueManager);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        session.processInput("stream some text");

        final String output = capturedOut.toString();

        // Each delta is painted inline, yielding the contiguous answer text in stdout — the whole point of streaming.
        // And displaySuccessResult must not duplicate it: exactly one occurrence of the answer text is expected.
        // Counting via split is robust against future iteration-footer format changes that might coincidentally
        // contain a substring of the answer (indexOf-based scanning would false-negative in that case).
        final int occurrences = output.split(java.util.regex.Pattern.quote(finalAnswer), -1).length - 1;
        assertThat(occurrences).as("wasStreamed=true must paint the answer exactly once (no duplicate re-print)")
                .isEqualTo(1);
        // Iteration footer still renders so users see how much work ran.
        assertThat(output).contains("[Completed in");
        // Turn completed cleanly.
        assertThat(queueManager.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("cancelling the in-flight CompletableFuture surfaces '[Aborted]' and releases the guard")
    void cancellingFutureRendersAbortedBannerAndReleasesGuard() {
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-8");
        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        final OrcaAgentRuntime context = mock(OrcaAgentRuntime.class);
        when(context.getId()).thenReturn(agentRuntimeId);

        // Pre-cancel the future before it reaches ReplSession. CompletableFuture#cancel marks the future as completed
        // exceptionally with CancellationException immediately, regardless of whether anyone has called join() yet —
        // so ReplSession's subsequent future.join() in awaitAndRender throws CancellationException without any
        // cross-thread coordination. This models the observable outcome of the Ctrl+C signal handler (which itself
        // just calls future.cancel(true)) without depending on JLine or sleep-based timing.
        final CompletableFuture<OrcaAgentExecutionResult> cancelled = new CompletableFuture<>();
        cancelled.cancel(true);
        when(executor.executeAsync(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(cancelled);

        final OutputFormatter formatter = new OutputFormatter(settings);
        final LiveSession liveSession = new DefaultLiveSession(SessionId.of("default"), context, executor,
                LiveSessionOptions.defaults(), queueManager);
        final AgentSetupFactory.AgentSetup agentSetup = AgentSetupFactory.AgentSetup.builder().agentExecutor(executor)
                .agentRuntime(context).outputFormatter(formatter).messageQueueManager(queueManager)
                .liveSession(liveSession).build();
        final ReplSession session = new ReplSession(agentSetup, settings, null);

        session.processInput("long running prompt");

        assertThat(capturedOut.toString()).contains("[Aborted]");
        assertThat(queueManager.snapshot()).isEmpty();
    }
}
