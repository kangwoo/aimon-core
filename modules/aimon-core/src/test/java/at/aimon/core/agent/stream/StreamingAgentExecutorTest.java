package at.aimon.core.agent.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeIds;
import at.aimon.core.agent.budget.CompletionReason;

/**
 * Contract tests for {@link StreamingAgentExecutor}.
 *
 * <p>
 * Rather than testing the interface itself (it has no behavior), these tests exercise the expected contract through a
 * small in-line fake implementation that emits a fixed, deterministic event sequence. A passing suite is evidence that
 * the documented contract can be satisfied with a straightforward implementation and gives real-world implementations
 * a reference oracle.
 */
@DisplayName("StreamingAgentExecutor contract")
class StreamingAgentExecutorTest {

    private static final Instant TS = Instant.parse("2025-01-15T10:00:00Z");

    private static AgentRuntimeId ctxId() {
        return AgentRuntimeIds.testCtx("ctx-1");
    }

    private static List<AgentExecutionEvent> fixedEventSequence() {
        AgentRuntimeId id = ctxId();
        List<AgentExecutionEvent> events = new ArrayList<>();
        events.add(
                IterationStarted.builder().timestamp(TS).agentRuntimeId(id).iteration(1).plannedIteration(1).build());
        events.add(IterationCompleted.builder().timestamp(TS).agentRuntimeId(id).iteration(1).build());
        events.add(ExecutionCompleted.builder().timestamp(TS).agentRuntimeId(id).iteration(0)
                .completionReason(CompletionReason.COMPLETED).totalIterations(1).build());
        return Collections.unmodifiableList(events);
    }

    private static StubResult stubResult() {
        return new StubResult();
    }

    @Nested
    @DisplayName("events() publisher")
    class PublisherTests {

        @Test
        @DisplayName("delivers events to a Flow.Subscriber in order, then onComplete")
        void deliversInOrderThenOnComplete() throws Exception {
            List<AgentExecutionEvent> expected = fixedEventSequence();
            FakeStreamingExecutor exec = new FakeStreamingExecutor(expected, stubResult());

            RecordingSubscriber subscriber = new RecordingSubscriber();
            exec.events(new StubContext(), new StubRequest()).subscribe(subscriber);

            assertThat(subscriber.awaitTerminal(2, TimeUnit.SECONDS)).isTrue();
            assertThat(subscriber.events).containsExactlyElementsOf(expected);
            assertThat(subscriber.completed).isTrue();
            assertThat(subscriber.error).isNull();
        }

        @Test
        @DisplayName("last domain event is a terminal event (ExecutionCompleted)")
        void terminalEventIsEmittedBeforeOnComplete() throws Exception {
            List<AgentExecutionEvent> expected = fixedEventSequence();
            FakeStreamingExecutor exec = new FakeStreamingExecutor(expected, stubResult());

            RecordingSubscriber subscriber = new RecordingSubscriber();
            exec.events(new StubContext(), new StubRequest()).subscribe(subscriber);
            assertThat(subscriber.awaitTerminal(2, TimeUnit.SECONDS)).isTrue();

            AgentExecutionEvent last = subscriber.events.get(subscriber.events.size() - 1);
            assertThat(last).isInstanceOf(ExecutionCompleted.class);
            assertThat(subscriber.completed).isTrue();
        }

        @Test
        @DisplayName("null context throws NullPointerException")
        void nullContextThrows() {
            FakeStreamingExecutor exec = new FakeStreamingExecutor(fixedEventSequence(), stubResult());
            assertThatThrownBy(() -> exec.events(null, new StubRequest())).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("context");
        }

        @Test
        @DisplayName("null request throws NullPointerException")
        void nullRequestThrows() {
            FakeStreamingExecutor exec = new FakeStreamingExecutor(fixedEventSequence(), stubResult());
            assertThatThrownBy(() -> exec.events(new StubContext(), null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("request");
        }
    }

    @Nested
    @DisplayName("executeAsync() convenience API")
    class ExecuteAsyncTests {

        @Test
        @DisplayName("completes stage with the final result and invokes listener for every event")
        void completesWithResultAndInvokesListener() throws Exception {
            List<AgentExecutionEvent> expected = fixedEventSequence();
            StubResult expectedResult = stubResult();
            FakeStreamingExecutor exec = new FakeStreamingExecutor(expected, expectedResult);

            List<AgentExecutionEvent> seen = Collections.synchronizedList(new ArrayList<>());
            CompletionStage<StubResult> stage = exec.executeAsync(new StubContext(), new StubRequest(), seen::add);

            StubResult actual = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(actual).isSameAs(expectedResult);
            assertThat(seen).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("listener exceptions do not prevent subsequent events from being delivered")
        void listenerExceptionDoesNotBlockFurtherEvents() throws Exception {
            List<AgentExecutionEvent> expected = fixedEventSequence();
            FakeStreamingExecutor exec = new FakeStreamingExecutor(expected, stubResult());

            List<AgentExecutionEvent> seen = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger calls = new AtomicInteger();
            AtomicBoolean thrownOnce = new AtomicBoolean();
            Consumer<AgentExecutionEvent> listener = event -> {
                int n = calls.incrementAndGet();
                seen.add(event);
                if (n == 1 && thrownOnce.compareAndSet(false, true)) {
                    throw new RuntimeException("boom");
                }
            };

            CompletionStage<StubResult> stage = exec.executeAsync(new StubContext(), new StubRequest(), listener);
            stage.toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertThat(seen).containsExactlyElementsOf(expected);
            assertThat(calls.get()).isEqualTo(expected.size());
        }

        @Test
        @DisplayName("null listener throws NullPointerException")
        void nullListenerThrows() {
            FakeStreamingExecutor exec = new FakeStreamingExecutor(fixedEventSequence(), stubResult());
            assertThatThrownBy(() -> exec.executeAsync(new StubContext(), new StubRequest(), null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("listener");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Test doubles
    // ---------------------------------------------------------------------------------------------

    /**
     * Minimal in-line fake that implements the streaming contract against a pre-baked event list.
     *
     * <p>
     * Publisher semantics: unicast, synchronous, single-threaded — requests are honoured as a simple index cursor and
     * the terminal domain event is always delivered before {@code onComplete}. Good enough to assert the documented
     * contract without depending on any real executor.
     */
    private static final class FakeStreamingExecutor
            implements
                StreamingAgentExecutor<StubContext, StubRequest, StubResult> {

        private final List<AgentExecutionEvent> events;
        private final StubResult result;

        FakeStreamingExecutor(List<AgentExecutionEvent> events, StubResult result) {
            this.events = List.copyOf(events);
            this.result = result;
        }

        @Override
        public Flow.Publisher<AgentExecutionEvent> events(StubContext context, StubRequest request) {
            if (context == null) {
                throw new NullPointerException("context cannot be null");
            }
            if (request == null) {
                throw new NullPointerException("request cannot be null");
            }
            return new FixedSequencePublisher(events);
        }

        @Override
        public CompletionStage<StubResult> executeAsync(StubContext context, StubRequest request,
                Consumer<AgentExecutionEvent> listener) {
            if (context == null) {
                throw new NullPointerException("context cannot be null");
            }
            if (request == null) {
                throw new NullPointerException("request cannot be null");
            }
            if (listener == null) {
                throw new NullPointerException("listener cannot be null");
            }
            CompletableFuture<StubResult> future = new CompletableFuture<>();
            try {
                for (AgentExecutionEvent event : events) {
                    try {
                        listener.accept(event);
                    } catch (RuntimeException ex) {
                        // Contract: listener exceptions must be swallowed by the implementation.
                    }
                }
                future.complete(result);
            } catch (RuntimeException ex) {
                future.completeExceptionally(ex);
            }
            return future;
        }
    }

    /** Synchronous, unicast, single-consumption publisher for a fixed event list. */
    private static final class FixedSequencePublisher implements Flow.Publisher<AgentExecutionEvent> {

        private final List<AgentExecutionEvent> events;

        FixedSequencePublisher(List<AgentExecutionEvent> events) {
            this.events = events;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super AgentExecutionEvent> subscriber) {
            subscriber.onSubscribe(new FixedSequenceSubscription(subscriber, events));
        }
    }

    private static final class FixedSequenceSubscription implements Flow.Subscription {

        private final Flow.Subscriber<? super AgentExecutionEvent> subscriber;
        private final List<AgentExecutionEvent> events;
        private int index;
        private boolean cancelled;
        private boolean terminated;

        FixedSequenceSubscription(Flow.Subscriber<? super AgentExecutionEvent> subscriber,
                List<AgentExecutionEvent> events) {
            this.subscriber = subscriber;
            this.events = events;
        }

        @Override
        public void request(long n) {
            if (cancelled || terminated) {
                return;
            }
            if (n <= 0) {
                cancelled = true;
                subscriber.onError(new IllegalArgumentException("request() n must be positive"));
                return;
            }
            long remaining = n;
            while (remaining > 0 && index < events.size() && !cancelled) {
                subscriber.onNext(events.get(index++));
                remaining--;
            }
            if (index >= events.size() && !cancelled && !terminated) {
                terminated = true;
                subscriber.onComplete();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /** Subscriber that records every signal and provides a latch for terminal events. */
    private static final class RecordingSubscriber implements Flow.Subscriber<AgentExecutionEvent> {

        final List<AgentExecutionEvent> events = new ArrayList<>();
        volatile boolean completed;
        volatile Throwable error;
        private final CountDownLatch terminal = new CountDownLatch(1);
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AgentExecutionEvent event) {
            events.add(event);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            completed = true;
            terminal.countDown();
        }

        boolean awaitTerminal(long timeout, TimeUnit unit) throws InterruptedException {
            return terminal.await(timeout, unit);
        }
    }

    private static final class StubContext implements AgentRuntime {
        @Override
        public AgentRuntimeId getId() {
            return ctxId();
        }

        @Override
        public at.aimon.core.agent.Agent getAgent() {
            return null;
        }

        @Override
        public List<at.aimon.core.agent.tool.Tool> getAvailableTools() {
            return List.of();
        }
    }

    private static final class StubRequest implements AgentExecutionRequest {
        @Override
        public at.aimon.core.agent.input.UserInput getUserInput() {
            return at.aimon.core.agent.input.TextInput.of("stub");
        }

        @Override
        public java.util.Optional<at.aimon.core.base.Principal> getPrincipal() {
            return java.util.Optional.empty();
        }
    }

    private static final class StubResult implements AgentExecutionResult {
        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public String getFinalAnswer() {
            return "done";
        }

        @Override
        public String getErrorMessage() {
            return null;
        }

        @Override
        public CompletionReason getCompletionReason() {
            return CompletionReason.COMPLETED;
        }
    }
}
