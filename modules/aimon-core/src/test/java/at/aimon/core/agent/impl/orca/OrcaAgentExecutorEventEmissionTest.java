package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantMessageReceived;
import at.aimon.core.agent.stream.CompactBoundary;
import at.aimon.core.agent.stream.ExecutionCompleted;
import at.aimon.core.agent.stream.ExecutionError;
import at.aimon.core.agent.stream.IterationCompleted;
import at.aimon.core.agent.stream.IterationStarted;
import at.aimon.core.agent.stream.ToolResultReady;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * STREAM-03 event emission unit tests for {@link OrcaAgentExecutor}.
 *
 * <p>
 * Scenarios covered:
 *
 * <ol>
 * <li>Happy-path 8-event ordering (IterationStarted, AssistantMessageReceived, ToolUseStarted, ToolResultReady,
 * IterationCompleted(willContinue=true), IterationStarted, IterationCompleted(false), ExecutionCompleted). The
 * terminal iteration intentionally omits AssistantMessageReceived because the final answer is surfaced via
 * AgentExecutionResult#getFinalAnswer and ExecutionCompleted; re-emitting it here would cause REPL renderers to
 * double-print the final answer.
 * <li>{@link ExecutionError} publication when the LLM call throws
 * <li>Legacy path: no listeners registered, executor behaves exactly as before
 * <li>Rogue-listener isolation: a listener that throws does not abort the ReAct loop
 * <li>{@link OrcaAgentExecutor#removeEventListener(Consumer)} stops delivery
 * <li>{@link OrcaAgentExecutor#events(OrcaAgentRuntime, OrcaAgentExecutionRequest)} {@code Flow.Publisher}
 * completes via {@code onComplete()} after the terminal event
 * <li>{@link OrcaAgentExecutor#executeAsync(OrcaAgentRuntime, OrcaAgentExecutionRequest, Consumer)}
 * {@link CompletionStage} resolves with the final result and streams events to the supplied listener
 * </ol>
 */
@DisplayName("OrcaAgentExecutor STREAM-03 event emission")
class OrcaAgentExecutorEventEmissionTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Happy path: 2-iteration ReAct loop")
    class HappyPath {

        @Test
        @DisplayName("emits 8 events in wall-clock order for a tool-then-answer run")
        void emitsEightEventsInOrder() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            // Iteration 1: assistant responds with a tool use.
            llmClient.enqueue(LlmResponse.of("calling tool", List.of(ToolUse.of("tu-1", "NoopTool", Map.of("k", "v"))),
                    TokenUsage.of(10, 5, 15)));
            // Iteration 2: assistant responds with final answer (no tool uses).
            llmClient.enqueue(LlmResponse.of("final answer", List.of(), TokenUsage.of(8, 4, 12)));

            final DefaultToolRegistry registry = new DefaultToolRegistry();
            registry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final List<AgentExecutionEvent> seen = new ArrayList<>();
            executor.addEventListener(seen::add);

            final OrcaAgentExecutionResult result = executor.execute(createContext(registry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getFinalAnswer()).isEqualTo("final answer");

            // Expected sequence: 8 events (the terminal iteration does NOT emit AssistantMessageReceived — the
            // final answer is carried on AgentExecutionResult and signaled by ExecutionCompleted).
            assertThat(seen).hasSize(8);
            assertThat(seen.get(0)).isInstanceOfSatisfying(IterationStarted.class,
                    e -> assertThat(e.getIteration()).isEqualTo(1));
            assertThat(seen.get(1)).isInstanceOfSatisfying(AssistantMessageReceived.class, e -> {
                assertThat(e.getIteration()).isEqualTo(1);
                assertThat(e.getMessageSummary()).isEqualTo("calling tool");
            });
            assertThat(seen.get(2)).isInstanceOfSatisfying(ToolUseStarted.class, e -> {
                assertThat(e.getIteration()).isEqualTo(1);
                assertThat(e.getToolName()).isEqualTo("NoopTool");
                assertThat(e.getToolUseId()).isEqualTo("tu-1");
                assertThat(e.getInputSummary()).containsEntry("k", "v");
            });
            assertThat(seen.get(3)).isInstanceOfSatisfying(ToolResultReady.class, e -> {
                assertThat(e.getIteration()).isEqualTo(1);
                assertThat(e.getToolName()).isEqualTo("NoopTool");
                assertThat(e.getToolUseId()).isEqualTo("tu-1");
                assertThat(e.isSuccess()).isTrue();
            });
            assertThat(seen.get(4)).isInstanceOfSatisfying(IterationCompleted.class, e -> {
                assertThat(e.getIteration()).isEqualTo(1);
                assertThat(e.isWillContinue()).isTrue();
            });
            assertThat(seen.get(5)).isInstanceOfSatisfying(IterationStarted.class,
                    e -> assertThat(e.getIteration()).isEqualTo(2));
            assertThat(seen.get(6)).isInstanceOfSatisfying(IterationCompleted.class, e -> {
                assertThat(e.getIteration()).isEqualTo(2);
                assertThat(e.isWillContinue()).isFalse();
            });
            assertThat(seen.get(7)).isInstanceOfSatisfying(ExecutionCompleted.class, e -> {
                assertThat(e.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
                assertThat(e.getTotalIterations()).isEqualTo(2);
            });
            assertThat(seen).filteredOn(AssistantMessageReceived.class::isInstance).hasSize(1);
        }
    }

    @Nested
    @DisplayName("LLM failure path")
    class LlmFailurePath {

        @Test
        @DisplayName("LLM throws => ExecutionError published with cause and ERROR completion reason")
        void publishesExecutionErrorOnLlmFailure() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.setThrowOnCall(new LlmClientException("boom"));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final List<AgentExecutionEvent> seen = new ArrayList<>();
            executor.addEventListener(seen::add);

            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isFalse();

            // IterationStarted(1) + ExecutionError, no AssistantMessageReceived (LLM failed before responding)
            assertThat(seen).hasSize(2);
            assertThat(seen.get(0)).isInstanceOf(IterationStarted.class);
            assertThat(seen.get(1)).isInstanceOfSatisfying(ExecutionError.class, e -> {
                assertThat(e.getCause()).isPresent();
                assertThat(e.getCause().get()).isInstanceOf(LlmClientException.class);
                assertThat(e.getErrorMessage()).contains("boom");
                assertThat(e.getCompletionReason()).contains(CompletionReason.ERROR);
            });
        }
    }

    @Nested
    @DisplayName("Listener contract")
    class ListenerContract {

        @Test
        @DisplayName("no listeners registered => legacy behavior preserved (no exceptions, result is success)")
        void legacyBehaviorWithZeroListeners() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            // No addEventListener call.

            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
            assertThat(result.getFinalAnswer()).isEqualTo("done");
        }

        @Test
        @DisplayName("rogue listener throwing RuntimeException does not abort execution or other listeners")
        void rogueListenerIsolated() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final AtomicBoolean rogueCalled = new AtomicBoolean(false);
            final List<AgentExecutionEvent> goodListenerSeen = new ArrayList<>();

            executor.addEventListener(event -> {
                rogueCalled.set(true);
                throw new RuntimeException("rogue listener failure");
            });
            executor.addEventListener(goodListenerSeen::add);

            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            // Rogue listener fired; good listener still received all events; execution succeeded.
            assertThat(rogueCalled).isTrue();
            assertThat(result.isSuccess()).isTrue();
            assertThat(goodListenerSeen).isNotEmpty();
            assertThat(goodListenerSeen).last().isInstanceOf(ExecutionCompleted.class);
        }

        @Test
        @DisplayName("removeEventListener stops future event delivery to the removed listener")
        void removeEventListenerStopsDelivery() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final List<AgentExecutionEvent> seen = new ArrayList<>();
            final Consumer<AgentExecutionEvent> listener = seen::add;

            executor.addEventListener(listener);
            final boolean removed = executor.removeEventListener(listener);
            assertThat(removed).isTrue();

            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(seen).isEmpty();

            // Double removal returns false.
            assertThat(executor.removeEventListener(listener)).isFalse();
        }
    }

    @Nested
    @DisplayName("StreamingAgentExecutor#events Flow.Publisher")
    class FlowPublisherApi {

        @Test
        @DisplayName("events() publisher calls onComplete after terminal ExecutionCompleted")
        void flowPublisherCompletesAfterTerminal() throws InterruptedException {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);

            final Flow.Publisher<AgentExecutionEvent> publisher = executor.events(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            final CopyOnWriteArrayList<AgentExecutionEvent> seen = new CopyOnWriteArrayList<>();
            final CountDownLatch onCompleteLatch = new CountDownLatch(1);
            final AtomicBoolean onErrorCalled = new AtomicBoolean(false);

            publisher.subscribe(new Flow.Subscriber<AgentExecutionEvent>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(AgentExecutionEvent item) {
                    seen.add(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    onErrorCalled.set(true);
                }

                @Override
                public void onComplete() {
                    onCompleteLatch.countDown();
                }
            });

            assertThat(onCompleteLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(onErrorCalled).isFalse();
            assertThat(seen).isNotEmpty();
            assertThat(seen).last().isInstanceOf(ExecutionCompleted.class);
        }
    }

    @Nested
    @DisplayName("StreamingAgentExecutor#executeAsync")
    class ExecuteAsyncApi {

        @Test
        @DisplayName("executeAsync resolves CompletionStage with final result and streams events to listener")
        void executeAsyncResolvesAndStreamsEvents() throws Exception {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final ConcurrentLinkedQueue<AgentExecutionEvent> seen = new ConcurrentLinkedQueue<>();

            final CompletionStage<OrcaAgentExecutionResult> stage = executor.executeAsync(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build(),
                    seen::add);

            final OrcaAgentExecutionResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getFinalAnswer()).isEqualTo("done");

            final List<AgentExecutionEvent> events = new ArrayList<>(seen);
            assertThat(events).isNotEmpty();
            assertThat(events).last().isInstanceOf(ExecutionCompleted.class);
        }

        @Test
        @DisplayName("executeAsync shuts down its per-call executor after completion (no leaked orca-executeAsync "
                + "threads)")
        void executeAsyncDoesNotLeakExecutors() throws Exception {
            final int invocations = 8;
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            for (int i = 0; i < invocations; i++) {
                llmClient.enqueue(LlmResponse.of("done-" + i, List.of(), TokenUsage.of(5, 5, 10)));
            }

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final List<CompletionStage<OrcaAgentExecutionResult>> stages = new ArrayList<>();
            for (int i = 0; i < invocations; i++) {
                stages.add(executor.executeAsync(createContext(),
                        OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build(),
                        e -> {
                        }));
            }
            for (CompletionStage<OrcaAgentExecutionResult> stage : stages) {
                stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            }

            // Poll up to 5 seconds for all orca-executeAsync threads to terminate after shutdown().
            final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            long surviving = Long.MAX_VALUE;
            while (System.nanoTime() < deadline) {
                surviving = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.getName().startsWith("orca-executeAsync")).filter(Thread::isAlive).count();
                if (surviving == 0) {
                    break;
                }
                Thread.sleep(25);
            }
            assertThat(surviving).as("no orca-executeAsync threads remain alive after all stages settle").isZero();
        }
    }

    @Nested
    @DisplayName("Terminal event symmetry")
    class TerminalEventSymmetry {

        @Test
        @DisplayName("max-iterations exhaustion emits ExecutionCompleted(MAX_ITERATIONS), not ExecutionError")
        void maxIterationsEmitsExecutionCompleted() {
            // Always return a tool-use response so the ReAct loop never terminates naturally; after maxIterations=2,
            // the executor finalises via handleMaxIterations — a normal return that emits
            // ExecutionCompleted(MAX_ITERATIONS) for symmetry with budget-driven STOP (no exception is thrown).
            final LlmClient llmClient = new LoopingLlmClient();

            final DefaultToolRegistry registry = new DefaultToolRegistry();
            registry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final List<AgentExecutionEvent> seen = new ArrayList<>();
            executor.addEventListener(seen::add);

            final OrcaAgentExecutionResult result = executor.execute(createContextWithMaxIterations(registry, 2),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isFalse();
            assertThat(seen).noneMatch(e -> e instanceof ExecutionError);
            assertThat(seen).last().isInstanceOfSatisfying(ExecutionCompleted.class,
                    e -> assertThat(e.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS));

            // Invariant: handleMaxIterations must NOT emit its own IterationCompleted. Each of the two loop-tail
            // iterations already published exactly one IterationCompleted(willContinue=true); the ceiling finaliser
            // adds none. A regression that double-emits (or duplicates the loop-tail emit) would push this past two.
            assertThat(seen).filteredOn(IterationCompleted.class::isInstance).hasSize(2)
                    .allSatisfy(e -> assertThat(((IterationCompleted) e).isWillContinue()).isTrue());
        }

        @Test
        @DisplayName("a normally-finishing turn pairs every IterationStarted with an IterationCompleted")
        void normalTurnPairsIterationEvents() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("calling tool", List.of(ToolUse.of("tu-1", NoopTool.TOOL_NAME, Map.of())),
                    TokenUsage.of(1, 1, 2)));
            llmClient.enqueue(LlmResponse.text("final answer"));

            final DefaultToolRegistry registry = new DefaultToolRegistry();
            registry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final List<AgentExecutionEvent> seen = new ArrayList<>();
            executor.addEventListener(seen::add);

            executor.execute(createContext(registry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            // The baseline the failure case below is measured against: two iterations in, two iterations out.
            assertThat(countOf(seen, IterationStarted.class)).isEqualTo(2);
            assertThat(countOf(seen, IterationCompleted.class)).isEqualTo(2);
        }

        @Test
        @DisplayName("an LLM failure mid-loop leaves its IterationStarted unmatched: the loop returns straight out of "
                + "the catch block without an IterationCompleted, and ExecutionError is the only terminal marker")
        void errorPathLeavesTheFinalIterationUnclosed() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("calling tool", List.of(ToolUse.of("tu-1", NoopTool.TOOL_NAME, Map.of())),
                    TokenUsage.of(1, 1, 2)));
            // Iteration 1 completes normally; iteration 2 blows up after its IterationStarted has been published.
            llmClient.setThrowFromCall(2, new IllegalStateException("provider exploded"));

            final DefaultToolRegistry registry = new DefaultToolRegistry();
            registry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final List<AgentExecutionEvent> seen = new ArrayList<>();
            executor.addEventListener(seen::add);

            final OrcaAgentExecutionResult result = executor.execute(createContext(registry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isFalse();

            // Characterisation of a real asymmetry: the ReAct loop's `catch (Exception e) -> handleExecutionError`
            // (and, identically, `catch (CancelledExecutionException) -> handleInterrupted`) returns without emitting
            // the iteration's IterationCompleted, so iteration 2 is announced but never closed. Nothing downstream is
            // corrupted — ExecutionError is still the terminal event, so a consumer that keys off the terminal event
            // is fine — but a consumer that pairs IterationStarted/IterationCompleted to drive per-iteration UI (a
            // progress row, a span, a spinner) is left with a dangling open iteration and must special-case the
            // terminal event to close it. Emitting IterationCompleted(willContinue=false) on both catch paths before
            // the terminal event would make the stream self-describing; if that is done, flip these two counts to 2/2.
            assertThat(countOf(seen, IterationStarted.class)).isEqualTo(2);
            assertThat(countOf(seen, IterationCompleted.class))
                    .as("the failing iteration publishes no IterationCompleted").isEqualTo(1);

            assertThat(seen).last().isInstanceOf(ExecutionError.class);
            assertThat(seen).filteredOn(ExecutionCompleted.class::isInstance)
                    .as("an errored turn must not also claim completion").isEmpty();
        }

        private long countOf(List<AgentExecutionEvent> events, Class<? extends AgentExecutionEvent> type) {
            return events.stream().filter(type::isInstance).count();
        }
    }

    @Nested
    @DisplayName("CompactBoundary emission")
    class CompactionBoundaryEmission {

        @Test
        @DisplayName("a COMPACT gate emits exactly one CompactBoundary, ordered immediately before that iteration's "
                + "IterationStarted, carrying the summarization strategy and a non-growing message count")
        void compactGateEmitsCompactBoundary() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            // Iteration 1: tool use — grows memory so the iteration-2 gate has a span worth compacting.
            llmClient.enqueue(LlmResponse.of("calling tool", List.of(ToolUse.of("tu-1", "NoopTool", Map.of("k", "v"))),
                    TokenUsage.of(10, 5, 15)));
            // Iteration 2: final answer — terminates the loop.
            llmClient.enqueue(LlmResponse.of("final answer", List.of(), TokenUsage.of(8, 4, 12)));

            final DefaultToolRegistry registry = new DefaultToolRegistry();
            registry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final List<AgentExecutionEvent> seen = new ArrayList<>();
            executor.addEventListener(seen::add);

            // Guard compacts on its SECOND gate call (start of iteration 2), where memory holds the iteration-1 span.
            final OrcaAgentExecutionResult result = executor.execute(
                    createContextWithGuard(registry, new ScriptedCompactionGuard(2)),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();

            // Exactly one boundary, for iteration 2, with the fixed summarization strategy and a shrunk (never grown)
            // message count.
            assertThat(seen).filteredOn(CompactBoundary.class::isInstance).hasSize(1).first()
                    .isInstanceOfSatisfying(CompactBoundary.class, e -> {
                        assertThat(e.getIteration()).isEqualTo(2);
                        assertThat(e.getStrategyName()).isEqualTo(OrcaAgentExecutor.COMPACTION_STRATEGY_NAME);
                        assertThat(e.getStrategyName()).isNotEmpty();
                        assertThat(e.getMessagesAfter()).isLessThan(e.getMessagesBefore());
                        assertThat(e.getMessagesAfter()).isGreaterThanOrEqualTo(0);
                    });

            // Ordering: the boundary sits immediately before iteration 2's IterationStarted (the compaction happened
            // just before the LLM call that iteration precedes).
            final int boundaryIndex = indexOfFirst(seen, CompactBoundary.class);
            assertThat(seen.get(boundaryIndex + 1)).isInstanceOfSatisfying(IterationStarted.class,
                    started -> assertThat(started.getIteration()).isEqualTo(2));
        }

        @Test
        @DisplayName("a NONE gate emits no CompactBoundary")
        void noneGateEmitsNoCompactBoundary() {
            assertNoCompactBoundary(new ScriptedCompactionGuard(-1, CompactionDecision.Action.NONE));
        }

        @Test
        @DisplayName("a WARN gate emits no CompactBoundary")
        void warnGateEmitsNoCompactBoundary() {
            assertNoCompactBoundary(new ScriptedCompactionGuard(-1, CompactionDecision.Action.WARN));
        }

        private void assertNoCompactBoundary(CompactionGuard guard) {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("calling tool", List.of(ToolUse.of("tu-1", "NoopTool", Map.of("k", "v"))),
                    TokenUsage.of(10, 5, 15)));
            llmClient.enqueue(LlmResponse.of("final answer", List.of(), TokenUsage.of(8, 4, 12)));

            final DefaultToolRegistry registry = new DefaultToolRegistry();
            registry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final List<AgentExecutionEvent> seen = new ArrayList<>();
            executor.addEventListener(seen::add);

            final OrcaAgentExecutionResult result = executor.execute(createContextWithGuard(registry, guard),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(seen).noneMatch(CompactBoundary.class::isInstance);
        }

        private int indexOfFirst(List<AgentExecutionEvent> events, Class<? extends AgentExecutionEvent> type) {
            for (int i = 0; i < events.size(); i++) {
                if (type.isInstance(events.get(i))) {
                    return i;
                }
            }
            throw new AssertionError("No " + type.getSimpleName() + " event found");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Shared fixture helpers
    // ---------------------------------------------------------------------------------------------------------------

    private OrcaAgentRuntime createContext() {
        return createContext(new DefaultToolRegistry());
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry) {
        return createContextWithMaxIterations(toolRegistry, 10);
    }

    private OrcaAgentRuntime createContextWithMaxIterations(DefaultToolRegistry toolRegistry, int maxIterations) {
        return createContext(toolRegistry, maxIterations, null);
    }

    private OrcaAgentRuntime createContextWithGuard(DefaultToolRegistry toolRegistry, CompactionGuard guard) {
        return createContext(toolRegistry, 10, guard);
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry, int maxIterations,
            CompactionGuard compactionGuard) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(maxIterations)
                        .systemPrompt("You are a test agent").build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .compactionGuard(compactionGuard).environment(Environment.createDefault()).build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    /**
     * Minimal scripted LLM client used to drive the ReAct loop through pre-determined responses, optionally throwing
     * on the first call to exercise the error path.
     */
    private static final class SequencedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private Optional<RuntimeException> throwOnCall = Optional.empty();
        private Duration delayBeforeResponse = Duration.ZERO;
        private int throwFromCall = -1;
        private int callCount;

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        void setThrowOnCall(RuntimeException exception) {
            this.throwOnCall = Optional.of(exception);
        }

        /** Throws only from the {@code call}-th (1-based) invocation onward, so earlier iterations run normally. */
        void setThrowFromCall(int call, RuntimeException exception) {
            this.throwFromCall = call;
            this.throwOnCall = Optional.of(exception);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            callCount++;
            if (throwOnCall.isPresent() && (throwFromCall < 0 || callCount >= throwFromCall)) {
                throw throwOnCall.get();
            }
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
            return "Sequenced";
        }

    }

    /**
     * Always returns a tool-use response, so the ReAct loop never terminates and the executor eventually hits the
     * agent-metadata iteration ceiling, finalising via {@code handleMaxIterations} with
     * {@link CompletionReason#MAX_ITERATIONS}. Used by the terminal-symmetry test.
     */
    private static final class LoopingLlmClient implements LlmClient {
        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return LlmResponse.of("keep looping", List.of(ToolUse.of("tu", NoopTool.TOOL_NAME, Map.of())),
                    TokenUsage.of(1, 1, 2));
        }

        @Override
        public String getProviderName() {
            return "Looping";
        }

    }

    /** Minimal tool used to exercise the tool-boundary emissions. */
    private static final class NoopTool extends AbstractTool {
        static final String TOOL_NAME = "NoopTool";

        NoopTool() {
            super(TOOL_NAME, "no-op tool for event-emission tests",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("noop");
        }
    }

    /**
     * Deterministic {@link CompactionGuard} stub. Performs a single COMPACT on its {@code compactOnCall}-th gate
     * invocation — faithfully rewriting memory in place (collapsing the whole span into one summary message, as the
     * real guard does) so the observed {@code messagesAfter} is strictly below {@code messagesBefore}. Every other gate
     * returns the configured non-compacting action ({@code NONE} by default, {@code WARN} for the negative WARN case).
     * A {@code compactOnCall} that is never reached (e.g. {@code -1}) makes the guard never compact.
     */
    private static final class ScriptedCompactionGuard implements CompactionGuard {
        private final int compactOnCall;
        private final CompactionDecision.Action nonCompactAction;
        private int calls;

        ScriptedCompactionGuard(int compactOnCall) {
            this(compactOnCall, CompactionDecision.Action.NONE);
        }

        ScriptedCompactionGuard(int compactOnCall, CompactionDecision.Action nonCompactAction) {
            this.compactOnCall = compactOnCall;
            this.nonCompactAction = nonCompactAction;
        }

        @Override
        public synchronized CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model,
                HookRegistry hookRegistry, Environment environment) {
            calls++;
            if (calls != compactOnCall) {
                return nonCompactAction == CompactionDecision.Action.WARN
                        ? CompactionDecision.warn("below compaction threshold")
                        : CompactionDecision.none("below compaction threshold");
            }
            final int before = memory.size();
            // Rewrite memory in place, exactly as a real guard would after summarisation, shrinking the span to one
            // message so the CompactBoundary reports messagesAfter < messagesBefore.
            memory.replaceWith(List.of(Message.user("[compacted summary of " + before + " messages]")));
            final CompactionMetadata metadata = CompactionMetadata.builder().preCompactTokenCount(4000)
                    .postCompactTokenCount(400).messagesSummarized(before).trigger(CompactionTrigger.AUTO)
                    .startedAt(Instant.EPOCH).completedAt(Instant.EPOCH).build();
            return CompactionDecision.compact(CompactionResult.success("summary", metadata), "test-forced compaction");
        }
    }
}
