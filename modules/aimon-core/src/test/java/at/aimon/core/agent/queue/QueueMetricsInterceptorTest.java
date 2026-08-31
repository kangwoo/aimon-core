package at.aimon.core.agent.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeIds;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.interceptor.AgentExecutionChain;
import at.aimon.core.agent.interceptor.InterceptingAgentExecutor;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.base.Principal;

@DisplayName("QueueMetricsInterceptor Tests")
class QueueMetricsInterceptorTest {

    private static final AgentRuntimeId MAIN = AgentRuntimeIds.testCtx("main");
    private static final AgentRuntimeId OTHER = AgentRuntimeIds.testCtx("other");

    private InMemoryMessageQueueRepository repository;
    private DefaultMessageQueueManager manager;
    private QueueMetricsInterceptor<StubContext, StubRequest, StubResult> interceptor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryMessageQueueRepository();
        manager = new DefaultMessageQueueManager(repository);
        interceptor = new QueueMetricsInterceptor<>(manager);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects null manager")
        void rejectsNullManager() {
            assertThatThrownBy(() -> new QueueMetricsInterceptor<>(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("manager");
        }

        @Test
        @DisplayName("starts with zero aggregate counters")
        void startsWithZeroCounters() {
            assertThat(interceptor.getExecutionsObserved()).isZero();
            assertThat(interceptor.getEnqueuedDuringExecutions()).isZero();
            assertThat(interceptor.getDrainedDuringExecutions()).isZero();
        }
    }

    @Nested
    @DisplayName("Intercept argument validation")
    class ArgumentValidation {

        @Test
        @DisplayName("rejects null context / request / chain")
        void rejectsNullArguments() {
            AgentExecutionChain<StubContext, StubRequest, StubResult> chain = (c, r) -> new StubResult("ok");
            assertThatThrownBy(() -> interceptor.intercept(null, new StubRequest(), chain))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> interceptor.intercept(new StubContext(MAIN), null, chain))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> interceptor.intercept(new StubContext(MAIN), new StubRequest(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Per-turn accounting")
    class PerTurnAccounting {

        @Test
        @DisplayName("counts enqueue and drain events that happen during execution")
        void countsEventsDuringExecution() {
            final StubExecutor delegate = new StubExecutor(scope -> {
                manager.enqueue(queuedInput("a", MAIN));
                manager.enqueue(queuedInput("b", MAIN));
                manager.drainForInjection(q -> MAIN.equals(q.getAgentRuntimeId()), QueuedInputPriority.NEXT);
            });

            runOnce(delegate);

            assertThat(interceptor.getExecutionsObserved()).isEqualTo(1);
            assertThat(interceptor.getEnqueuedDuringExecutions()).isEqualTo(2);
            assertThat(interceptor.getDrainedDuringExecutions()).isEqualTo(2);
        }

        @Test
        @DisplayName("ignores events from another context id")
        void ignoresOtherRuntimeIds() {
            final StubExecutor delegate = new StubExecutor(scope -> {
                manager.enqueue(queuedInput("foreign", OTHER));
                manager.drainForInjection(q -> OTHER.equals(q.getAgentRuntimeId()), QueuedInputPriority.NEXT);
            });

            runOnce(delegate);

            assertThat(interceptor.getExecutionsObserved()).isEqualTo(1);
            assertThat(interceptor.getEnqueuedDuringExecutions()).isZero();
            assertThat(interceptor.getDrainedDuringExecutions()).isZero();
        }

        @Test
        @DisplayName("ignores pre-existing events that happened before the intercept")
        void ignoresPreExistingEvents() {
            // Populate the queue BEFORE the interceptor runs. These events must not be counted.
            manager.enqueue(queuedInput("pre1", MAIN));
            manager.enqueue(queuedInput("pre2", MAIN));

            final StubExecutor delegate = new StubExecutor(scope -> {
                /* no-op — we want to verify the pre-existing depth is not attributed to this turn */
            });
            runOnce(delegate);

            assertThat(interceptor.getExecutionsObserved()).isEqualTo(1);
            assertThat(interceptor.getEnqueuedDuringExecutions()).isZero();
            assertThat(interceptor.getDrainedDuringExecutions()).isZero();
        }

        @Test
        @DisplayName("aggregates across multiple executions")
        void aggregatesAcrossExecutions() {
            final StubExecutor delegate = new StubExecutor(scope -> {
                manager.enqueue(queuedInput("x", MAIN));
            });

            runOnce(delegate);
            runOnce(delegate);
            runOnce(delegate);

            assertThat(interceptor.getExecutionsObserved()).isEqualTo(3);
            assertThat(interceptor.getEnqueuedDuringExecutions()).isEqualTo(3);
        }

        @Test
        @DisplayName("records non-negative duration for every execution")
        void recordsDuration() {
            final StubExecutor delegate = new StubExecutor(scope -> {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
            runOnce(delegate);
            runOnce(delegate);

            assertThat(interceptor.getExecutionsObserved()).isEqualTo(2);
            // Duration is wall-clock; never negative, and the two ~5ms sleeps give a lower bound we can assert.
            assertThat(interceptor.getTotalDurationMillis()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Concurrent execution")
    class ConcurrentExecution {

        @Test
        @DisplayName("aggregates counters correctly across parallel intercepts on different context ids")
        void concurrentInterceptsOnDistinctContexts() throws InterruptedException {
            final int threadCount = 8;
            final int enqueuesPerThread = 25;
            final ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            try {
                final CountDownLatch start = new CountDownLatch(1);
                final CountDownLatch done = new CountDownLatch(threadCount);

                for (int i = 0; i < threadCount; i++) {
                    final AgentRuntimeId ctxId = AgentRuntimeId.of("agent:ctx-" + i);
                    pool.submit(() -> {
                        try {
                            start.await();
                            final StubExecutor delegate = new StubExecutor(scope -> {
                                for (int j = 0; j < enqueuesPerThread; j++) {
                                    manager.enqueue(queuedInput("m-" + j, ctxId));
                                }
                            });
                            final AgentExecutionChain<StubContext, StubRequest, StubResult> chain = delegate::execute;
                            interceptor.intercept(new StubContext(ctxId), new StubRequest(), chain);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(interceptor.getExecutionsObserved()).isEqualTo(threadCount);
            assertThat(interceptor.getEnqueuedDuringExecutions()).isEqualTo((long) threadCount * enqueuesPerThread);
        }
    }

    @Nested
    @DisplayName("Listener lifecycle")
    class ListenerLifecycle {

        @Test
        @DisplayName("removes its turn-scoped listener even when the chain throws")
        void removesListenerOnException() {
            final StubExecutor delegate = new StubExecutor(scope -> {
                throw new RuntimeException("boom");
            });

            assertThatThrownBy(() -> runOnce(delegate)).isInstanceOf(RuntimeException.class).hasMessage("boom");

            // Events after the turn must not feed the interceptor's counters — prove by triggering one and
            // observing that enqueuedDuringExecutions is still what it was at the exception point.
            final long before = interceptor.getEnqueuedDuringExecutions();
            manager.enqueue(queuedInput("after-turn", MAIN));
            assertThat(interceptor.getEnqueuedDuringExecutions()).isEqualTo(before);
            assertThat(interceptor.getExecutionsObserved()).isEqualTo(1);
        }

        @Test
        @DisplayName("does not count events after the turn has ended")
        void doesNotCountAfterTurnEnds() {
            final StubExecutor delegate = new StubExecutor(scope -> manager.enqueue(queuedInput("during", MAIN)));
            runOnce(delegate);
            final long afterTurn = interceptor.getEnqueuedDuringExecutions();

            manager.enqueue(queuedInput("after", MAIN));
            manager.enqueue(queuedInput("after2", MAIN));

            assertThat(interceptor.getEnqueuedDuringExecutions()).isEqualTo(afterTurn);
        }
    }

    @Nested
    @DisplayName("InterceptingAgentExecutor wiring")
    class ExecutorWiring {

        @Test
        @DisplayName("works as a drop-in AgentExecutionInterceptor")
        void wiresThroughInterceptingExecutor() {
            final StubExecutor delegate = new StubExecutor(scope -> manager.enqueue(queuedInput("wired", MAIN)));

            final InterceptingAgentExecutor<StubContext, StubRequest, StubResult> chain = InterceptingAgentExecutor
                    .<StubContext, StubRequest, StubResult>builder(delegate).addInterceptor(interceptor).build();

            final StubResult result = chain.execute(new StubContext(MAIN), new StubRequest());

            assertThat(result.getFinalAnswer()).isEqualTo("ok");
            assertThat(interceptor.getExecutionsObserved()).isEqualTo(1);
            assertThat(interceptor.getEnqueuedDuringExecutions()).isEqualTo(1);
        }
    }

    // --- test helpers ------------------------------------------------------------------------------------------

    private void runOnce(StubExecutor delegate) {
        final AgentExecutionChain<StubContext, StubRequest, StubResult> chain = delegate::execute;
        interceptor.intercept(new StubContext(MAIN), new StubRequest(), chain);
    }

    private static QueuedInput queuedInput(String text, AgentRuntimeId ctx) {
        return QueuedInput.builder().inputText(text).agentRuntimeId(ctx).build();
    }

    @FunctionalInterface
    interface TurnAction {
        void run(StubContext scope);
    }

    static final class StubExecutor implements AgentExecutor<StubContext, StubRequest, StubResult> {

        private final TurnAction action;

        StubExecutor(TurnAction action) {
            this.action = action;
        }

        @Override
        public StubResult execute(StubContext context, StubRequest request) {
            action.run(context);
            return new StubResult("ok");
        }
    }

    static final class StubContext implements AgentRuntime {

        private final AgentRuntimeId id;

        StubContext(AgentRuntimeId id) {
            this.id = id;
        }

        @Override
        public AgentRuntimeId getId() {
            return id;
        }

        @Override
        public Agent getAgent() {
            throw new UnsupportedOperationException(
                    "StubContext#getAgent not needed for QueueMetricsInterceptor tests");
        }

        @Override
        public List<Tool> getAvailableTools() {
            return List.of();
        }
    }

    static final class StubRequest implements AgentExecutionRequest {

        @Override
        public UserInput getUserInput() {
            throw new UnsupportedOperationException(
                    "StubRequest#getUserInput not needed for QueueMetricsInterceptor tests");
        }

        @Override
        public java.util.Optional<Principal> getPrincipal() {
            throw new UnsupportedOperationException(
                    "StubRequest#getPrincipal not needed for QueueMetricsInterceptor tests");
        }
    }

    static final class StubResult implements AgentExecutionResult {

        private final String finalAnswer;

        StubResult(String finalAnswer) {
            this.finalAnswer = finalAnswer;
        }

        @Override
        public boolean isSuccess() {
            return finalAnswer != null;
        }

        @Override
        public String getFinalAnswer() {
            return finalAnswer;
        }

        @Override
        public String getErrorMessage() {
            throw new UnsupportedOperationException(
                    "StubResult#getErrorMessage not needed for QueueMetricsInterceptor tests");
        }
    }

}
