package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantTextDelta;
import at.aimon.core.agent.stream.ExecutionCompleted;
import at.aimon.core.agent.stream.IterationStarted;

/**
 * Regression for the relay's remote-buffer overflow policy.
 *
 * <p>
 * The buffer used to reject whatever arrived once it was full. Because terminal frames are always the <em>last</em>
 * events of a turn, an overflowing turn dropped exactly the frame that tells a remote subscriber the turn is over, and
 * that subscriber never completed. The relay now discards the oldest droppable text delta to make room, and only
 * sacrifices a structural frame when the buffer holds nothing else.
 */
@DisplayName("SessionEventRelay remote buffer overflow prefers dropping text deltas")
class SessionEventRelayOverflowTest {

    private static final SessionId CONV = SessionId.of("c-relay-overflow");
    private static final AgentRuntimeId CTX = AgentRuntimeId.of("agent:test-1");
    private static final TurnId TURN = TurnId.of("t-relay-overflow");
    private static final Instant TS = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("a terminal frame arriving at a full buffer evicts a text delta instead of itself")
    void terminalFrameSurvivesOverflow() {
        final RecordingSignalBus bus = new RecordingSignalBus();
        final InProcessEventPublisher publisher = new InProcessEventPublisher();

        try (SessionEventRelay relay = new SessionEventRelay(CONV, TURN, publisher, bus, "node-a",
                new NoopDispatcher())) {
            for (int i = 0; i < SessionEventRelay.RELAY_QUEUE_CAPACITY; i++) {
                relay.accept(delta(i));
            }
            assertThat(relay.getDroppedEventCount()).as("the buffer must not overflow before it is full").isZero();

            relay.accept(completed());
            assertThat(relay.getDroppedEventCount()).as("making room costs exactly one event").isEqualTo(1L);
        }

        assertThat(types(bus)).as("the terminal frame must reach the remote channel").contains("ExecutionCompleted");
        assertThat(bus.published).hasSize(SessionEventRelay.RELAY_QUEUE_CAPACITY);
        assertThat(bus.published.get(bus.published.size() - 1)).isEqualTo("ExecutionCompleted");
    }

    @Test
    @DisplayName("the discarded delta is the oldest one, so the surviving deltas are the most recent")
    void oldestDeltaIsTheOneDiscarded() {
        final RecordingSignalBus bus = new RecordingSignalBus();
        final InProcessEventPublisher publisher = new InProcessEventPublisher();

        try (SessionEventRelay relay = new SessionEventRelay(CONV, TURN, publisher, bus, "node-a",
                new NoopDispatcher())) {
            for (int i = 0; i < SessionEventRelay.RELAY_QUEUE_CAPACITY; i++) {
                relay.accept(delta(i));
            }
            relay.accept(completed());
        }

        assertThat(bus.chunkIndexes()).as("chunk 0 is the delta that was sacrificed").doesNotContain(0).startsWith(1, 2,
                3);
    }

    @Test
    @DisplayName("a text delta arriving at a buffer of only structural frames is dropped, not swapped in")
    void deltaIsDroppedWhenNothingCheaperIsBuffered() {
        final RecordingSignalBus bus = new RecordingSignalBus();
        final InProcessEventPublisher publisher = new InProcessEventPublisher();

        try (SessionEventRelay relay = new SessionEventRelay(CONV, TURN, publisher, bus, "node-a",
                new NoopDispatcher())) {
            for (int i = 0; i < SessionEventRelay.RELAY_QUEUE_CAPACITY; i++) {
                relay.accept(iterationStarted(i));
            }
            relay.accept(delta(999));
            assertThat(relay.getDroppedEventCount()).isEqualTo(1L);
        }

        assertThat(types(bus)).as("no structural frame may be sacrificed for a delta").containsOnly("IterationStarted");
        assertThat(bus.published).hasSize(SessionEventRelay.RELAY_QUEUE_CAPACITY);
    }

    @Test
    @DisplayName("with only structural frames buffered the oldest is sacrificed, keeping the newest terminal frame")
    void structuralOverflowSacrificesTheOldestFrame() {
        final RecordingSignalBus bus = new RecordingSignalBus();
        final InProcessEventPublisher publisher = new InProcessEventPublisher();

        try (SessionEventRelay relay = new SessionEventRelay(CONV, TURN, publisher, bus, "node-a",
                new NoopDispatcher())) {
            for (int i = 0; i < SessionEventRelay.RELAY_QUEUE_CAPACITY; i++) {
                relay.accept(iterationStarted(i));
            }
            relay.accept(completed());
            assertThat(relay.getDroppedEventCount()).isEqualTo(1L);
        }

        assertThat(bus.published).hasSize(SessionEventRelay.RELAY_QUEUE_CAPACITY);
        assertThat(bus.published.get(bus.published.size() - 1)).isEqualTo("ExecutionCompleted");
    }

    private static List<String> types(RecordingSignalBus bus) {
        return new ArrayList<>(bus.published);
    }

    private static AgentExecutionEvent delta(int chunk) {
        return AssistantTextDelta.builder().timestamp(TS).agentRuntimeId(CTX).iteration(1).delta("d" + chunk)
                .chunkIndex(chunk).build();
    }

    private static AgentExecutionEvent iterationStarted(int iteration) {
        return IterationStarted.builder().timestamp(TS).agentRuntimeId(CTX).iteration(iteration)
                .plannedIteration(iteration).build();
    }

    private static AgentExecutionEvent completed() {
        return ExecutionCompleted.builder().timestamp(TS).agentRuntimeId(CTX).iteration(0)
                .completionReason(CompletionReason.COMPLETED).totalIterations(1).elapsed(Duration.ofMillis(5)).build();
    }

    /** Records the {@code type} of every relayed EVENT signal, in publish order. */
    private static final class RecordingSignalBus implements SessionSignalBus {
        private final List<String> published = new CopyOnWriteArrayList<>();
        private final List<Integer> chunks = new CopyOnWriteArrayList<>();

        List<Integer> chunkIndexes() {
            return new ArrayList<>(chunks);
        }

        @Override
        public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
            return () -> {
            };
        }

        @Override
        public void publish(SessionSignal signal) {
            final Object type = signal.getPayload().get("type");
            published.add(String.valueOf(type));
            final Object chunk = signal.getPayload().get("chunk");
            if (chunk instanceof Integer i) {
                chunks.add(i);
            }
        }
    }

    /**
     * Never runs the drain task, so the test controls exactly when the buffer is drained: {@code close()} drains
     * synchronously. Without this the dispatcher thread would race the producer and the buffer would rarely fill.
     */
    private static final class NoopDispatcher extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            // intentionally dropped
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
