package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.StreamingAgentExecutor;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Verifies behaviour of {@link DefaultLiveSession} across the pipeline items that shaped it:
 * <ul>
 * <li>SESSION-01 — multi-turn session continuity and the Scheduling Lifecycle contract on
 * {@link DefaultLiveSession#close()}.
 * <li>SESSION-02 — default budget injection from {@link LiveSessionOptions}.
 * <li>SESSION-04 — {@code offerAsync} busy/idle routing, message-queue auto-enqueue, and the busy-flag release
 * contract on stage completion (success / failure / cancellation).
 * </ul>
 */
@DisplayName("DefaultLiveSession (SESSION-01 / SESSION-02 / SESSION-04)")
class DefaultLiveSessionTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Multi-turn conversation continuity")
    class MultiTurn {

        @Test
        @DisplayName("multiple submits reuse the same SessionId and preserve the TranscriptBuffer")
        void multipleSubmitsPreserveMemory() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            llmClient.enqueue(LlmResponse.of("first-answer", List.of(), TokenUsage.of(5, 5, 10)));
            llmClient.enqueue(LlmResponse.of("second-answer", List.of(), TokenUsage.of(5, 5, 10)));

            final OrcaAgentExecutor executor = createExecutor(llmClient, repository);
            final OrcaAgentRuntime context = createContext();
            final SessionId sessionId = SessionId.of("session-multi-turn");

            final DefaultLiveSession session = new DefaultLiveSession(sessionId, context, executor,
                    LiveSessionOptions.defaults());
            try {
                // Turn 1
                final OrcaAgentExecutionResult r1 = (OrcaAgentExecutionResult) session.submit("hello");
                assertThat(r1.isSuccess()).isTrue();
                assertThat(r1.getFinalAnswer()).isEqualTo("first-answer");

                // Turn 2
                final OrcaAgentExecutionResult r2 = (OrcaAgentExecutionResult) session.submit("again");
                assertThat(r2.isSuccess()).isTrue();
                assertThat(r2.getFinalAnswer()).isEqualTo("second-answer");
            } finally {
                session.close();
            }

            // SessionId stays the same across turns
            assertThat(session.getSessionId()).isEqualTo(sessionId);

            // The transcript persisted and accumulated: both user inputs must be present in the repository.
            final Optional<SessionRecordView> stored = repository.load(sessionId);
            assertThat(stored).isPresent();
            final List<Message> storedMessages = stored.get().getMessages();
            // At minimum: user1, assistant1, user2, assistant2 — memory must not reset between turns.
            assertThat(storedMessages.size()).isGreaterThanOrEqualTo(4);

            // Second LLM call must see the first-turn exchange in its history (memory continuity).
            assertThat(llmClient.capturedMessages).hasSize(2);
            final List<Message> secondCallHistory = llmClient.capturedMessages.get(1);
            assertThat(secondCallHistory.size()).isGreaterThan(llmClient.capturedMessages.get(0).size());
        }
    }

    @Nested
    @DisplayName("Default ExecutionBudget injection")
    class BudgetInjection {

        @Test
        @DisplayName("options without a budget injects ExecutionBudget.unlimited() (legacy unbounded behavior)")
        void defaultBudgetIsUnlimited() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            llmClient.enqueue(LlmResponse.of("answer", List.of(), TokenUsage.of(5, 5, 10)));

            final OrcaAgentExecutor executor = createExecutor(llmClient, repository);
            final OrcaAgentRuntime context = createContext();

            final LiveSessionOptions defaults = LiveSessionOptions.defaults();
            assertThat(defaults.getBudget()).isEqualTo(ExecutionBudget.unlimited());

            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    defaults);
            try {
                final OrcaAgentExecutionResult r = (OrcaAgentExecutionResult) session.submit("hi");
                assertThat(r.isSuccess()).isTrue();
                // Unlimited budget => executor completes with the natural reason, not a budget-triggered one.
                assertThat(r.getCompletionReason()).isEqualTo(at.aimon.core.agent.budget.CompletionReason.COMPLETED);
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("options with explicit budget propagates to the request and is enforced by the executor")
        void explicitBudgetIsEnforced() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            // Make the LLM request a tool use so the loop would continue past iteration 1 if the budget didn't stop it.
            llmClient.enqueue(LlmResponse.of("keep going",
                    List.of(at.aimon.core.llm.ToolUse.of("tu-1", "NoopSessionTool", Map.of())),
                    TokenUsage.of(10, 10, 20)));
            llmClient.enqueue(LlmResponse.of("safety-net", List.of(), TokenUsage.of(1, 1, 2)));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new NoopSessionTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient, repository);
            final OrcaAgentRuntime context = createContext(toolRegistry);

            final LiveSessionOptions options = LiveSessionOptions.builder()
                    .budget(ExecutionBudget.builder().maxIterations(1).build()).build();

            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor, options);
            try {
                final OrcaAgentExecutionResult r = (OrcaAgentExecutionResult) session.submit("hi");
                assertThat(r.isSuccess()).isFalse();
                assertThat(r.getCompletionReason())
                        .isEqualTo(at.aimon.core.agent.budget.CompletionReason.MAX_ITERATIONS);
            } finally {
                session.close();
            }
        }
    }

    @Nested
    @DisplayName("close() — Scheduling Lifecycle contract")
    class CloseContract {

        @Test
        @DisplayName("close() does NOT close the agent-scoped agentRuntime (scope contract)")
        void closeDoesNotDelegateToAgentRuntime() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            llmClient.enqueue(LlmResponse.of("answer", List.of(), TokenUsage.of(1, 1, 2)));

            final OrcaAgentExecutor executor = createExecutor(llmClient, repository);
            // Spy on the real context so we can observe which lifecycle methods the session invokes.
            // Per the agent-scoped lifetime model, LiveSession.close() must NOT close the AEC —
            // the AEC is shared across every session against the same agent and is torn down only by
            // OrcaAgentRuntimeManager.destroyRuntime at app shutdown / agent removal.
            final OrcaAgentRuntime context = spy(createContext());

            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());

            // Run one turn so the session is fully active.
            session.submit("hi");

            // close() must NEVER cascade to context.close() — even across repeated calls.
            session.close();
            verify(context, never()).close();

            session.close();
            verify(context, never()).close();
        }

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            llmClient.enqueue(LlmResponse.of("answer", List.of(), TokenUsage.of(1, 1, 2)));

            final OrcaAgentExecutor executor = createExecutor(llmClient, repository);
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());

            assertThat(session.isClosed()).isFalse();
            session.close();
            assertThat(session.isClosed()).isTrue();
            assertThatNoException().isThrownBy(session::close);
            assertThat(session.isClosed()).isTrue();
        }

        @Test
        @DisplayName("submit() after close() throws IllegalStateException")
        void submitAfterCloseThrows() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final CapturingLlmClient llmClient = new CapturingLlmClient();

            final OrcaAgentExecutor executor = createExecutor(llmClient, repository);
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());

            session.close();
            assertThatThrownBy(() -> session.submit("hi")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }
    }

    @Nested
    @DisplayName("submitAsync streaming delegation (SESSION-03)")
    class StreamingSubmit {

        @Test
        @DisplayName("streaming executor path: listener receives the executor's emitted events and the stage completes "
                + "with the result")
        void streamingExecutorDeliversEvents() throws Exception {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            // Single-iteration LLM response so the Orca loop emits IterationStarted/Completed + ExecutionCompleted.
            llmClient.enqueue(LlmResponse.of("streamed-answer", List.of(), TokenUsage.of(5, 5, 10)));

            final OrcaAgentExecutor executor = createExecutor(llmClient, repository);
            final OrcaAgentRuntime context = createContext();

            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());
            try {
                final List<AgentExecutionEvent> events = new ArrayList<>();
                final CompletionStage<AgentExecutionResult> stage = session.submitAsync("hello", events::add);
                final AgentExecutionResult result = stage.toCompletableFuture().get();

                assertThat(result.isSuccess()).isTrue();
                assertThat(result.getFinalAnswer()).isEqualTo("streamed-answer");
                // STREAM-04 invariant: the streaming path emits at least one event — the executor's event bus always
                // publishes IterationStarted + ExecutionCompleted for a successful single-iteration run.
                assertThat(events).isNotEmpty();
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("non-streaming executor fallback: stage completes with synchronous submit result and no events "
                + "are delivered")
        void nonStreamingExecutorFallsBackToSynchronousSubmit() throws Exception {
            final OrcaAgentRuntime context = createContext();
            final SessionId id = SessionId.of("fallback");
            final OrcaAgentExecutionResult canned = OrcaAgentExecutionResult.success("sync-answer",
                    SessionSnapshot.of(id),
                    ExecutionMetadata.simple(Duration.ZERO, java.time.Instant.EPOCH, java.time.Instant.EPOCH));
            final NonStreamingExecutorStub executor = new NonStreamingExecutorStub(canned);

            final DefaultLiveSession session = new DefaultLiveSession(id, context, executor,
                    LiveSessionOptions.defaults());
            try {
                final List<AgentExecutionEvent> events = new ArrayList<>();
                final AgentExecutionResult result = session.submitAsync("hi", events::add).toCompletableFuture().get();

                assertThat(result.isSuccess()).isTrue();
                assertThat(result.getFinalAnswer()).isEqualTo("sync-answer");
                // Default fallback path emits zero events (per LiveSession#submitAsync Javadoc).
                assertThat(events).isEmpty();
                assertThat(executor.invoked.get()).isTrue();
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("null arguments are rejected synchronously")
        void nullArgumentsRejected() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final OrcaAgentExecutor executor = createExecutor(new CapturingLlmClient(), repository);
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());
            try {
                assertThatThrownBy(() -> session.submitAsync(null, e -> {
                })).isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> session.submitAsync("x", null)).isInstanceOf(NullPointerException.class);
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("submitAsync after close() throws IllegalStateException on the streaming path")
        void submitAsyncAfterCloseThrows() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final OrcaAgentExecutor executor = createExecutor(new CapturingLlmClient(), repository);
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());
            session.close();
            assertThatThrownBy(() -> session.submitAsync("hi", e -> {
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
    }

    @Nested
    @DisplayName("Runtime options mutation (setOptions)")
    class OptionsMutation {

        @Test
        @DisplayName("setOptions swaps the backing options reference so subsequent submits see the new budget")
        void setOptionsSwapsBudgetVisibleToNextSubmit() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            // Force two iterations so a maxIterations=1 budget produces an observable MAX_ITERATIONS completion.
            llmClient.enqueue(LlmResponse.of("keep going",
                    List.of(at.aimon.core.llm.ToolUse.of("tu-1", "NoopSessionTool", Map.of())),
                    TokenUsage.of(5, 5, 10)));
            llmClient.enqueue(LlmResponse.of("safety-net", List.of(), TokenUsage.of(1, 1, 2)));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new NoopSessionTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient, repository);
            final OrcaAgentRuntime context = createContext(toolRegistry);

            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());
            try {
                // Tighten the budget after construction — the next submit must honour the replacement reference.
                session.setOptions(LiveSessionOptions.builder()
                        .budget(ExecutionBudget.builder().maxIterations(1).build()).build());

                final OrcaAgentExecutionResult r = (OrcaAgentExecutionResult) session.submit("go");
                assertThat(r.getCompletionReason())
                        .isEqualTo(at.aimon.core.agent.budget.CompletionReason.MAX_ITERATIONS);
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("setOptions rejects null")
        void setOptionsRejectsNull() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final OrcaAgentExecutor executor = createExecutor(new CapturingLlmClient(), repository);
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());
            try {
                assertThatThrownBy(() -> session.setOptions(null)).isInstanceOf(NullPointerException.class);
            } finally {
                session.close();
            }
        }
    }

    @Nested
    @DisplayName("offerAsync busy routing (SESSION-04)")
    class OfferAsync {

        @Test
        @DisplayName("idle session executes inline and returns an EXECUTED outcome")
        void idleSessionExecutesInline() throws Exception {
            final OrcaAgentRuntime context = createContext();
            final SessionId id = SessionId.of("offer-idle");
            final BlockingStreamingExecutorStub executor = new BlockingStreamingExecutorStub();
            // Complete immediately — the session should see busy=false both before and after the call.
            executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(id),
                    ExecutionMetadata.simple(Duration.ZERO, java.time.Instant.EPOCH, java.time.Instant.EPOCH)));

            final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
            final DefaultLiveSession session = new DefaultLiveSession(id, context, executor,
                    LiveSessionOptions.defaults(), queue);
            try {
                final SubmitOutcome outcome = session.offerAsync("hi", e -> {
                });

                assertThat(outcome.getKind()).isEqualTo(SubmitOutcome.Kind.EXECUTED);
                assertThat(outcome.getResultStage()).isPresent();
                final AgentExecutionResult result = outcome.getResultStage().get().toCompletableFuture().get();
                assertThat(result.getFinalAnswer()).isEqualTo("done");

                // Nothing was enqueued — the session was never observed busy from a peer submitter's standpoint.
                assertThat(queue.snapshot()).isEmpty();
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("busy session with a wired queue enqueues the second input with NEXT priority and returns QUEUED")
        void busySessionEnqueuesSecondInput() throws Exception {
            final OrcaAgentRuntime context = createContext();
            final SessionId id = SessionId.of("offer-busy-queued");
            final BlockingStreamingExecutorStub executor = new BlockingStreamingExecutorStub();
            final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

            final DefaultLiveSession session = new DefaultLiveSession(id, context, executor,
                    LiveSessionOptions.defaults(), queue);
            try {
                // Kick off the first turn — it parks on the uncompleted future inside the executor stub.
                final SubmitOutcome first = session.offerAsync("first", e -> {
                });
                assertThat(first.getKind()).isEqualTo(SubmitOutcome.Kind.EXECUTED);
                assertThat(executor.awaitInvoked(1, 1, TimeUnit.SECONDS)).isTrue();

                // Second call observes the busy flag and must be enqueued instead of running concurrently.
                final SubmitOutcome second = session.offerAsync("second", e -> {
                });
                assertThat(second.getKind()).isEqualTo(SubmitOutcome.Kind.QUEUED);
                assertThat(second.getQueuePosition()).isEqualTo(1);
                assertThat(second.getQueuedInput()).isPresent();
                assertThat(second.getQueuedInput().get().getInputText()).isEqualTo("second");
                assertThat(second.getQueuedInput().get().getPriority()).isEqualTo(QueuedInputPriority.NEXT);
                assertThat(second.getQueuedInput().get().getAgentRuntimeId()).isEqualTo(context.getId());

                // No second executor invocation happened — the session serialized on its own busy flag.
                assertThat(executor.invocationCount()).isEqualTo(1);

                // Release the in-flight turn; busy flips false via whenComplete.
                executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(id),
                        ExecutionMetadata.simple(Duration.ZERO, java.time.Instant.EPOCH, java.time.Instant.EPOCH)));
                first.getResultStage().get().toCompletableFuture().get(1, TimeUnit.SECONDS);
            } finally {
                session.close();
            }
        }

        /**
         * The queue carries text and nothing else — a deferred input is replayed as a {@code <system-reminder>}
         * block — so an input that is not text has no form to wait in. Neither answer offerAsync is allowed to give
         * is therefore available: it cannot defer the input, and running it beside the turn in flight would hand two
         * turns the same transcript. It refuses, and both halves are pinned below — the exception, and that nothing
         * was queued or started in its place.
         */
        @Test
        @DisplayName("busy session refuses a non-text input rather than queueing or running it")
        void busySessionRefusesANonTextInputItCannotDefer() throws Exception {
            final OrcaAgentRuntime context = createContext();
            final SessionId id = SessionId.of("offer-busy-multimodal");
            final BlockingStreamingExecutorStub executor = new BlockingStreamingExecutorStub();
            final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

            final DefaultLiveSession session = new DefaultLiveSession(id, context, executor,
                    LiveSessionOptions.defaults(), queue);
            try {
                final SubmitOutcome first = session.offerAsync("first", e -> {
                });
                assertThat(first.getKind()).isEqualTo(SubmitOutcome.Kind.EXECUTED);
                assertThat(executor.awaitInvoked(1, 1, TimeUnit.SECONDS)).isTrue();

                assertThatThrownBy(() -> session.offerAsync(ImageInput.of(new byte[]{1, 2, 3}, "image/png"),
                        SubmitOptions.empty(), e -> {
                        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("carries text only");

                assertThat(queue.snapshot()).as("nothing may be queued in the input's place").isEmpty();
                assertThat(executor.invocationCount()).as("nor may a second turn have been started").isEqualTo(1);

                executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(id),
                        ExecutionMetadata.simple(Duration.ZERO, java.time.Instant.EPOCH, java.time.Instant.EPOCH)));
                first.getResultStage().get().toCompletableFuture().get(1, TimeUnit.SECONDS);
            } finally {
                session.close();
            }
        }

        /** An idle session has no such conflict: there is nothing to defer behind, so the turn simply runs. */
        @Test
        @DisplayName("idle session runs a non-text input inline")
        void idleSessionRunsANonTextInput() throws Exception {
            final OrcaAgentRuntime context = createContext();
            final SessionId id = SessionId.of("offer-idle-multimodal");
            final BlockingStreamingExecutorStub executor = new BlockingStreamingExecutorStub();
            final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

            final DefaultLiveSession session = new DefaultLiveSession(id, context, executor,
                    LiveSessionOptions.defaults(), queue);
            try {
                final SubmitOutcome outcome = session.offerAsync(ImageInput.of(new byte[]{1, 2, 3}, "image/png"),
                        SubmitOptions.empty(), e -> {
                        });

                assertThat(outcome.getKind()).isEqualTo(SubmitOutcome.Kind.EXECUTED);
                assertThat(executor.awaitInvoked(1, 1, TimeUnit.SECONDS)).isTrue();

                executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(id),
                        ExecutionMetadata.simple(Duration.ZERO, java.time.Instant.EPOCH, java.time.Instant.EPOCH)));
                outcome.getResultStage().get().toCompletableFuture().get(1, TimeUnit.SECONDS);
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("busy flag is released once the in-flight stage completes, so a subsequent offer runs inline")
        void busyFlagIsReleasedOnStageCompletion() throws Exception {
            final OrcaAgentRuntime context = createContext();
            final SessionId id = SessionId.of("offer-busy-release");
            final BlockingStreamingExecutorStub executor = new BlockingStreamingExecutorStub();
            final MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

            final DefaultLiveSession session = new DefaultLiveSession(id, context, executor,
                    LiveSessionOptions.defaults(), queue);
            try {
                final SubmitOutcome first = session.offerAsync("first", e -> {
                });
                assertThat(executor.awaitInvoked(1, 1, TimeUnit.SECONDS)).isTrue();

                executor.completeNext(OrcaAgentExecutionResult.success("done", SessionSnapshot.of(id),
                        ExecutionMetadata.simple(Duration.ZERO, java.time.Instant.EPOCH, java.time.Instant.EPOCH)));
                first.getResultStage().get().toCompletableFuture().get(1, TimeUnit.SECONDS);

                // Seed the next completion before offering — so the second turn can progress through the stub.
                executor.completeNext(OrcaAgentExecutionResult.success("done-2", SessionSnapshot.of(id),
                        ExecutionMetadata.simple(Duration.ZERO, java.time.Instant.EPOCH, java.time.Instant.EPOCH)));

                final SubmitOutcome second = session.offerAsync("second", e -> {
                });
                assertThat(second.getKind()).isEqualTo(SubmitOutcome.Kind.EXECUTED);
                second.getResultStage().get().toCompletableFuture().get(1, TimeUnit.SECONDS);

                // Both turns ran as their own executor invocation — nothing leaked into the queue.
                assertThat(executor.invocationCount()).isEqualTo(2);
                assertThat(queue.snapshot()).isEmpty();
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("busy session without a wired queue falls back to a concurrent submitAsync (no enqueue)")
        void busySessionWithoutQueueFallsBackToConcurrentSubmit() throws Exception {
            final OrcaAgentRuntime context = createContext();
            final SessionId id = SessionId.of("offer-busy-no-queue");
            final BlockingStreamingExecutorStub executor = new BlockingStreamingExecutorStub();

            // 4-arg constructor => no MessageQueueManager wired.
            final DefaultLiveSession session = new DefaultLiveSession(id, context, executor,
                    LiveSessionOptions.defaults());
            try {
                final SubmitOutcome first = session.offerAsync("first", e -> {
                });
                assertThat(first.getKind()).isEqualTo(SubmitOutcome.Kind.EXECUTED);
                assertThat(executor.awaitInvoked(1, 1, TimeUnit.SECONDS)).isTrue();

                // Second offer while busy — no queue wired, so the session falls back to a fresh submitAsync. Both
                // executor invocations are observed because the busy flag no longer guards the path.
                final SubmitOutcome second = session.offerAsync("second", e -> {
                });
                assertThat(second.getKind()).isEqualTo(SubmitOutcome.Kind.EXECUTED);
                assertThat(second.getQueuedInput()).isEmpty();
                assertThat(executor.awaitInvoked(2, 1, TimeUnit.SECONDS)).isTrue();

                final OrcaAgentExecutionResult done = OrcaAgentExecutionResult.success("done", SessionSnapshot.of(id),
                        ExecutionMetadata.simple(Duration.ZERO, java.time.Instant.EPOCH, java.time.Instant.EPOCH));
                executor.completeNext(done);
                executor.completeNext(done);
                first.getResultStage().get().toCompletableFuture().get(1, TimeUnit.SECONDS);
                second.getResultStage().get().toCompletableFuture().get(1, TimeUnit.SECONDS);
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("null arguments are rejected synchronously")
        void nullArgumentsRejected() {
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context,
                    new BlockingStreamingExecutorStub(), LiveSessionOptions.defaults());
            try {
                assertThatThrownBy(() -> session.offerAsync(null, e -> {
                })).isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> session.offerAsync("x", null)).isInstanceOf(NullPointerException.class);
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("offerAsync after close() throws IllegalStateException")
        void offerAsyncAfterCloseThrows() {
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context,
                    new BlockingStreamingExecutorStub(), LiveSessionOptions.defaults());
            session.close();
            assertThatThrownBy(() -> session.offerAsync("hi", e -> {
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        }
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("null arguments throw NullPointerException")
        void nullArgumentsRejected() {
            final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
            final OrcaAgentExecutor executor = createExecutor(new CapturingLlmClient(), repository);
            final OrcaAgentRuntime context = createContext();
            final SessionId id = SessionId.generate();
            final LiveSessionOptions options = LiveSessionOptions.defaults();

            assertThatThrownBy(() -> new DefaultLiveSession(null, context, executor, options))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new DefaultLiveSession(id, null, executor, options))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new DefaultLiveSession(id, context, null, options))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new DefaultLiveSession(id, context, executor, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Per-turn SubmitOptions plumbing")
    class SubmitOptionsPlumbing {

        @Test
        @DisplayName("empty SubmitOptions preserves executor defaults — no per-turn metadata leaks into the request")
        void emptyOptionsPreserveDefaults() {
            final RequestCapturingExecutorStub executor = new RequestCapturingExecutorStub();
            final OrcaAgentRuntime context = createContext();
            final SessionId id = SessionId.generate();
            final DefaultLiveSession session = new DefaultLiveSession(id, context, executor,
                    LiveSessionOptions.defaults());
            try {
                session.submit("hi", SubmitOptions.empty());

                final OrcaAgentExecutionRequest captured = executor.lastRequest();
                assertThat(captured).isNotNull();
                assertThat(captured.getPrincipal()).isEmpty();
                assertThat(captured.getSystemPromptVariables()).isEmpty();
                assertThat(captured.getExecutionAttributes()).isEmpty();
                assertThat(captured.getLlmCallMetadata()).isEqualTo(LlmCallMetadata.empty());
                // Executor's compile-time default is true; an empty SubmitOptions must not flip it.
                assertThat(captured.isUserContextInjectionEnabled()).isTrue();
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("submit() forwards every SubmitOptions field to the underlying request")
        void submitForwardsAllFields() {
            final RequestCapturingExecutorStub executor = new RequestCapturingExecutorStub();
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());
            try {
                final at.aimon.core.base.Principal principal = at.aimon.core.base.Principal.user("u-7", "alice");
                final LlmCallMetadata metadata = LlmCallMetadata.builder().traceId("trace-9").tag("tenant", "acme")
                        .build();
                final SubmitOptions options = SubmitOptions.builder().principal(principal).llmCallMetadata(metadata)
                        .systemPromptVariable("region", "eu").executionAttribute("ab.x", true)
                        .userContextInjection(false).build();

                session.submit("hi", options);

                final OrcaAgentExecutionRequest captured = executor.lastRequest();
                assertThat(captured.getPrincipal()).contains(principal);
                assertThat(captured.getLlmCallMetadata()).isSameAs(metadata);
                assertThat(captured.getSystemPromptVariables()).containsEntry("region", "eu");
                assertThat(captured.getExecutionAttributes()).containsEntry("ab.x", true);
                assertThat(captured.isUserContextInjectionEnabled()).isFalse();
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("submitAsync() (non-streaming fallback) also forwards SubmitOptions to the request")
        void submitAsyncForwardsOptionsOnFallbackPath() throws Exception {
            final RequestCapturingExecutorStub executor = new RequestCapturingExecutorStub();
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());
            try {
                final SubmitOptions options = SubmitOptions.builder().executionAttribute("trace.id", "abc")
                        .userContextInjection(false).build();

                session.submitAsync("hello", options, e -> {
                }).toCompletableFuture().get();

                final OrcaAgentExecutionRequest captured = executor.lastRequest();
                assertThat(captured.getExecutionAttributes()).containsEntry("trace.id", "abc");
                assertThat(captured.isUserContextInjectionEnabled()).isFalse();
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("explicit userContextInjection(true) round-trips even when the executor default already matches")
        void userContextInjectionTrueRoundTrip() {
            final RequestCapturingExecutorStub executor = new RequestCapturingExecutorStub();
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());
            try {
                session.submit("hi", SubmitOptions.builder().userContextInjection(true).build());
                assertThat(executor.lastRequest().isUserContextInjectionEnabled()).isTrue();
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("legacy submit(String) overload uses SubmitOptions.empty() — request reflects executor defaults")
        void legacyOverloadUsesEmptyOptions() {
            final RequestCapturingExecutorStub executor = new RequestCapturingExecutorStub();
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());
            try {
                session.submit("hi");

                final OrcaAgentExecutionRequest captured = executor.lastRequest();
                assertThat(captured.getPrincipal()).isEmpty();
                assertThat(captured.getSystemPromptVariables()).isEmpty();
                assertThat(captured.getExecutionAttributes()).isEmpty();
                assertThat(captured.isUserContextInjectionEnabled()).isTrue();
            } finally {
                session.close();
            }
        }

        @Test
        @DisplayName("submit(String, SubmitOptions) rejects null SubmitOptions")
        void submitRejectsNullOptions() {
            final RequestCapturingExecutorStub executor = new RequestCapturingExecutorStub();
            final OrcaAgentRuntime context = createContext();
            final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
                    LiveSessionOptions.defaults());
            try {
                assertThatThrownBy(() -> session.submit("hi", (SubmitOptions) null))
                        .isInstanceOf(NullPointerException.class);
            } finally {
                session.close();
            }
        }
    }

    // ============================================================
    // Helper methods / stubs
    // ============================================================

    private OrcaAgentRuntime createContext() {
        return createContext(new DefaultToolRegistry());
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:test-1"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient client, InMemorySessionRecordStore repository) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(repository), toolManager, hookManager,
                commandManager, subagentManager);
    }

    /**
     * Capturing LLM client: records every invocation's messages list (for memory-continuity assertions) and returns
     * pre-queued responses in order. Supports an optional delay per response to exercise wall-clock budgets if needed.
     */
    private static final class CapturingLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        final List<List<Message>> capturedMessages = new ArrayList<>();
        private Duration delayBeforeResponse = Duration.ZERO;

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            capturedMessages.add(new ArrayList<>(messages));
            if (!delayBeforeResponse.isZero()) {
                try {
                    Thread.sleep(delayBeforeResponse.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (responses.isEmpty()) {
                return LlmResponse.text("unexpected-extra-call");
            }
            return responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Capturing";
        }

    }

    /**
     * Executor that implements only the base {@link AgentExecutor} — deliberately NOT {@code StreamingAgentExecutor}
     * — so the streaming fallback branch of {@link DefaultLiveSession#submitAsync} can be exercised. Real production
     * executors (notably {@link OrcaAgentExecutor}) implement both interfaces, which is why a hand-rolled stub is
     * needed here.
     */
    private static final class NonStreamingExecutorStub
            implements
                AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> {
        private final OrcaAgentExecutionResult canned;
        private final AtomicBoolean invoked = new AtomicBoolean(false);

        NonStreamingExecutorStub(OrcaAgentExecutionResult canned) {
            this.canned = canned;
        }

        @Override
        public OrcaAgentExecutionResult execute(OrcaAgentRuntime agentRuntime,
                OrcaAgentExecutionRequest executionRequest) {
            invoked.set(true);
            return canned;
        }
    }

    /**
     * Streaming executor stub that holds every invocation's completion under caller control. {@code offerAsync} tests
     * need to observe the session in its busy state (between the CAS acquire and the {@code whenComplete} release), so
     * the stub returns a fresh {@link CompletableFuture} per call and exposes {@link #completeNext} so the test decides
     * when the stage completes.
     *
     * <p>
     * Pre-seeding model: tests may call {@link #completeNext} either before or after {@link #executeAsync}. If the
     * invocation has already happened, the stub completes the next pending future immediately; otherwise the result
     * is queued and applied to the future returned by the next {@code executeAsync} call.
     *
     * <p>
     * Implements {@link StreamingAgentExecutor} so {@link DefaultLiveSession#submitAsync} picks the streaming branch
     * (and therefore the {@code whenComplete} hook that resets the busy flag). The base {@link AgentExecutor#execute}
     * method is unused by {@code submitAsync} on this path but still has to compile — it throws so any accidental
     * synchronous invocation fails loudly.
     */
    private static final class BlockingStreamingExecutorStub
            implements
                AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult>,
                StreamingAgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> {

        private final List<CompletableFuture<OrcaAgentExecutionResult>> inflight = new ArrayList<>();
        private final List<OrcaAgentExecutionResult> preseeded = new ArrayList<>();
        private final List<CountDownLatch> invokeLatches = new ArrayList<>();
        private int invocations;

        @Override
        public OrcaAgentExecutionResult execute(OrcaAgentRuntime agentRuntime,
                OrcaAgentExecutionRequest executionRequest) {
            throw new UnsupportedOperationException("BlockingStreamingExecutorStub is streaming-only");
        }

        @Override
        public java.util.concurrent.Flow.Publisher<AgentExecutionEvent> events(OrcaAgentRuntime context,
                OrcaAgentExecutionRequest request) {
            throw new UnsupportedOperationException("BlockingStreamingExecutorStub.events is unused by these tests");
        }

        @Override
        public synchronized CompletionStage<OrcaAgentExecutionResult> executeAsync(OrcaAgentRuntime context,
                OrcaAgentExecutionRequest request, Consumer<AgentExecutionEvent> listener) {
            invocations++;
            final CompletableFuture<OrcaAgentExecutionResult> future = new CompletableFuture<>();
            // If a result was pre-seeded for this invocation, complete immediately. Otherwise park the future so a
            // later completeNext() call can finish it.
            if (!preseeded.isEmpty()) {
                future.complete(preseeded.remove(0));
            } else {
                inflight.add(future);
            }
            if (invokeLatches.size() >= invocations) {
                invokeLatches.get(invocations - 1).countDown();
            }
            return future;
        }

        synchronized void completeNext(OrcaAgentExecutionResult result) {
            for (CompletableFuture<OrcaAgentExecutionResult> future : inflight) {
                if (!future.isDone()) {
                    future.complete(result);
                    return;
                }
            }
            // No pending future — stash for the next executeAsync invocation.
            preseeded.add(result);
        }

        synchronized int invocationCount() {
            return invocations;
        }

        boolean awaitInvoked(int count, long timeout, TimeUnit unit) throws InterruptedException {
            final CountDownLatch latch;
            synchronized (this) {
                while (invokeLatches.size() < count) {
                    invokeLatches.add(new CountDownLatch(1));
                }
                // If the Nth invocation already happened, the corresponding latch was never created beforehand —
                // count it down retroactively so awaitInvoked returns true without blocking.
                for (int i = 0; i < Math.min(invocations, invokeLatches.size()); i++) {
                    invokeLatches.get(i).countDown();
                }
                latch = invokeLatches.get(count - 1);
            }
            return latch.await(timeout, unit);
        }
    }

    /**
     * Records every {@link OrcaAgentExecutionRequest} the session passes through and returns a canned success result.
     * Used by the {@code SubmitOptionsPlumbing} tests to assert that per-turn {@link SubmitOptions} fields are merged
     * into the underlying request without interference from the real Orca executor pipeline.
     */
    private static final class RequestCapturingExecutorStub
            implements
                AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> {

        private volatile OrcaAgentExecutionRequest lastRequest;

        @Override
        public OrcaAgentExecutionResult execute(OrcaAgentRuntime agentRuntime,
                OrcaAgentExecutionRequest executionRequest) {
            this.lastRequest = executionRequest;
            return OrcaAgentExecutionResult.success("captured", SessionSnapshot.of(executionRequest.getSessionId()),
                    ExecutionMetadata.simple(Duration.ZERO, java.time.Instant.EPOCH, java.time.Instant.EPOCH));
        }

        OrcaAgentExecutionRequest lastRequest() {
            return lastRequest;
        }
    }

    /** Minimal tool used to keep the ReAct loop running so budget limits can trip. */
    private static final class NoopSessionTool extends at.aimon.core.agent.tool.AbstractTool {
        static final String TOOL_NAME = "NoopSessionTool";

        NoopSessionTool() {
            super(TOOL_NAME, "no-op tool for session tests",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public at.aimon.core.agent.tool.ToolResult execute(at.aimon.core.agent.tool.ToolInput input,
                at.aimon.core.agent.tool.ToolContext context) {
            return at.aimon.core.agent.tool.ToolResult.success("noop");
        }
    }
}
