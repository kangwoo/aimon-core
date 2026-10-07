package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
 * Regression for a turn whose result arrived tens of seconds after its execution finished.
 *
 * <p>
 * The relay published one signal per event and, at the end of the turn, published whatever was still buffered on the
 * turn thread before the result could be announced. On a bus where a publish is a database round trip, a model's
 * deltas arrive far faster than they can be published, the buffer fills, and the turn then waited for the whole
 * backlog — about a thousand round trips. The relay now publishes in batches, one drain at a time and one batch per
 * dispatcher task, bounds how long {@code close()} waits, and keeps a turn's leftover frames ahead of the next turn's.
 */
@DisplayName("SessionEventRelay drains in batches and close() does not wait for a slow bus")
@Timeout(30)
class SessionEventRelayBatchDrainTest {

    private static final SessionId CONV = SessionId.of("c-relay-batch");
    private static final AgentRuntimeId CTX = AgentRuntimeId.of("agent:test-1");
    private static final TurnId TURN = TurnId.of("t-relay-batch");
    private static final Instant TS = Instant.parse("2026-01-01T00:00:00Z");

    private final ExecutorService dispatcher = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        dispatcher.shutdownNow();
    }

    @Test
    @DisplayName("a backlog costs round trips by the batch, not by the event")
    void backlogIsPublishedInBatches() throws Exception {
        // 30ms a round trip: one per event would make this turn's close() take about thirty seconds.
        final RoundTripBus bus = new RoundTripBus(30L);
        final int deltas = 1_000;

        final long startedNanos;
        try (SessionEventRelay relay = relay(bus, dispatcher, Duration.ofSeconds(20), null)) {
            // The first frame goes out alone and is held at the bus, so the rest are certainly a backlog by the time
            // the next batch is taken — the count below does not depend on how fast this loop runs.
            bus.holdFirstRoundTrip();
            relay.accept(delta(0));
            bus.awaitHeld();
            for (int i = 1; i < deltas; i++) {
                relay.accept(delta(i));
            }
            relay.accept(completed());
            assertThat(relay.getDroppedEventCount()).isZero();
            startedNanos = System.nanoTime();
            bus.releaseHeld();
        }
        final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertThat(bus.types).hasSize(deltas + 1);
        assertThat(bus.chunks).as("published in the order accepted").isEqualTo(range(deltas));
        assertThat(bus.types.get(deltas)).isEqualTo("ExecutionCompleted");
        // One for the frame that was held, then the other thousand in batches of MAX_BATCH.
        assertThat(bus.roundTrips.get()).as("round trips").isEqualTo(1 + 4);
        assertThat(elapsedMs).as("close() must not pay a round trip per event").isLessThan(5_000L);
    }

    @Test
    @DisplayName("a relay that never stops streaming does not keep the dispatcher from another relay")
    void aStreamingRelayYieldsTheDispatcher() throws Exception {
        // One thread, so there is nothing to hide behind: a drain that looped until its buffer was empty would hold
        // it for as long as the producer below keeps the buffer non-empty.
        final ExecutorService single = Executors.newSingleThreadExecutor();
        final RoundTripBus bus = new RoundTripBus(5L);
        final AtomicBoolean streaming = new AtomicBoolean(true);
        final SessionEventRelay busy = relay(bus, single, Duration.ofSeconds(20), null);
        final Thread producer = new Thread(() -> {
            int i = 0;
            while (streaming.get()) {
                busy.accept(delta(i++));
                if (i % 20 == 0) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
            }
        });
        producer.start();
        try {
            bus.awaitRoundTrips(3);
            final SessionEventRelay other = new SessionEventRelay(SessionId.of("c-relay-other"),
                    TurnId.of("t-relay-other"), new InProcessEventPublisher(), bus, "node-a", single,
                    Duration.ofSeconds(20));
            other.accept(iterationStarted(7));
            final long startedNanos = System.nanoTime();
            other.close();
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            assertThat(producer.isAlive()).as("the busy relay is still streaming").isTrue();
            assertThat(other.getDroppedEventCount()).isZero();
            assertThat(bus.types).contains("IterationStarted");
            assertThat(elapsedMs).as("the other relay got its turn on the dispatcher").isLessThan(2_000L);
        } finally {
            streaming.set(false);
            producer.join(5_000L);
            busy.close();
            single.shutdownNow();
        }
    }

    @Test
    @DisplayName("frames a turn left behind at its deadline are published before the next turn's")
    void aSuccessorWaitsForItsPredecessor() throws Exception {
        final HangingBus bus = new HangingBus();
        final SessionEventRelay first = relay(bus, dispatcher, Duration.ofMillis(100), null);
        first.accept(iterationStarted(1));
        assertThat(bus.entered.await(5, TimeUnit.SECONDS)).isTrue();
        first.accept(completed());
        first.close();
        final CountDownLatch firstDone = new CountDownLatch(1);
        first.whenQuiescent(firstDone::countDown);
        assertThat(firstDone.getCount()).as("the first turn's terminal frame is still to be published").isEqualTo(1L);

        final SessionEventRelay second = new SessionEventRelay(CONV, TurnId.of("t-relay-batch-2"),
                new InProcessEventPublisher(), bus, "node-a", dispatcher, Duration.ofSeconds(20)).after(first);
        second.accept(iterationStarted(2));
        second.accept(completed());
        // Long enough for a successor that did not wait to have reached the bus.
        Thread.sleep(100L);
        bus.release.countDown();
        second.close();

        assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(bus.turns).containsExactly("t-relay-batch", "t-relay-batch", "t-relay-batch-2", "t-relay-batch-2");
        assertThat(bus.types).containsExactly("IterationStarted", "ExecutionCompleted", "IterationStarted",
                "ExecutionCompleted");
    }

    @Test
    @DisplayName("frames keep their order when several dispatcher threads and close() drain at once")
    void orderSurvivesConcurrentDrains() throws Exception {
        for (int round = 0; round < 20; round++) {
            // A little latency in the bus is what gives a second drain the chance to overtake the first.
            final RoundTripBus bus = new RoundTripBus(1L);
            final int deltas = 600;
            try (SessionEventRelay relay = relay(bus, dispatcher, Duration.ofSeconds(20), null)) {
                for (int i = 0; i < deltas; i++) {
                    relay.accept(delta(i));
                    if (i % 50 == 0) {
                        Thread.yield();
                    }
                }
                relay.accept(completed());
            }
            assertThat(bus.chunks).as("round %d", round).isEqualTo(range(deltas));
            assertThat(bus.types.get(bus.types.size() - 1)).isEqualTo("ExecutionCompleted");
        }
    }

    @Test
    @DisplayName("close() returns at its deadline on a bus that never answers, and gives up deltas only")
    void closeIsBoundedWhenTheBusHangs() throws Exception {
        final HangingBus bus = new HangingBus();
        final Duration timeout = Duration.ofMillis(200);
        final SessionEventRelay relay = relay(bus, dispatcher, timeout, null);

        relay.accept(iterationStarted(1));
        assertThat(bus.entered.await(5, TimeUnit.SECONDS)).as("the first publish is in flight").isTrue();
        for (int i = 0; i < 100; i++) {
            relay.accept(delta(i));
        }
        relay.accept(completed());

        final long startedNanos = System.nanoTime();
        relay.close();
        final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertThat(elapsedMs).as("close() waited for the timeout and no longer").isBetween(150L, 2_000L);
        assertThat(relay.getDroppedEventCount()).as("the buffered deltas are abandoned and counted").isEqualTo(100L);

        // The bus recovers: what close() left behind is the terminal frame, and it still goes out.
        bus.release.countDown();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bus.types.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(bus.types).containsExactly("IterationStarted", "ExecutionCompleted");
    }

    @Test
    @DisplayName("a zero timeout does not wait at all")
    void zeroTimeoutDoesNotWait() throws Exception {
        final HangingBus bus = new HangingBus();
        final SessionEventRelay relay = relay(bus, dispatcher, Duration.ZERO, null);
        relay.accept(iterationStarted(1));
        assertThat(bus.entered.await(5, TimeUnit.SECONDS)).isTrue();
        relay.accept(delta(0));

        final long startedNanos = System.nanoTime();
        relay.close();

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)).isLessThan(1_000L);
        assertThat(relay.getDroppedEventCount()).isEqualTo(1L);
        bus.release.countDown();
    }

    @Test
    @DisplayName("a bus that fails a batch does not stop the batches after it")
    void aFailedBatchDoesNotStopTheDrain() throws Exception {
        final RoundTripBus bus = new RoundTripBus(0L);
        bus.failFirst.set(1);
        try (SessionEventRelay relay = relay(bus, dispatcher, Duration.ofSeconds(20), null)) {
            relay.accept(iterationStarted(1));
            bus.awaitRoundTrips(1);
            relay.accept(completed());
        }
        assertThat(bus.types).containsExactly("ExecutionCompleted");
    }

    private static SessionEventRelay relay(SessionSignalBus bus, ExecutorService dispatcher, Duration closeTimeout,
            SessionEventRelay predecessor) {
        return new SessionEventRelay(CONV, TURN, new InProcessEventPublisher(), bus, "node-a", dispatcher, closeTimeout)
                .after(predecessor);
    }

    private static List<Integer> range(int n) {
        final List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
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

    private static void record(SessionSignal signal, List<String> types, List<Integer> chunks) {
        types.add(String.valueOf(signal.getPayload().get("type")));
        if (signal.getPayload().get("chunk") instanceof Integer i) {
            chunks.add(i);
        }
    }

    /** A bus where every call — one signal or a list of them — costs one fixed round trip, as an insert does. */
    private static final class RoundTripBus implements SessionSignalBus {
        private final long roundTripMs;
        private final List<String> types = new CopyOnWriteArrayList<>();
        private final List<Integer> chunks = new CopyOnWriteArrayList<>();
        private final AtomicInteger roundTrips = new AtomicInteger();
        private final AtomicInteger failFirst = new AtomicInteger();

        private volatile CountDownLatch hold;
        private final CountDownLatch held = new CountDownLatch(1);

        RoundTripBus(long roundTripMs) {
            this.roundTripMs = roundTripMs;
        }

        void holdFirstRoundTrip() {
            hold = new CountDownLatch(1);
        }

        void awaitHeld() throws InterruptedException {
            assertThat(held.await(5, TimeUnit.SECONDS)).as("the first round trip reached the bus").isTrue();
        }

        void releaseHeld() {
            hold.countDown();
        }

        void awaitRoundTrips(int n) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (roundTrips.get() < n && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
        }

        @Override
        public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
            return () -> {
            };
        }

        @Override
        public void publish(SessionSignal signal) {
            publishAll(List.of(signal));
        }

        @Override
        public void publishAll(List<SessionSignal> signals) {
            try {
                final CountDownLatch gate = hold;
                if (gate != null && held.getCount() == 1) {
                    held.countDown();
                    gate.await();
                }
                Thread.sleep(roundTripMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            roundTrips.incrementAndGet();
            if (failFirst.getAndDecrement() > 0) {
                throw new IllegalStateException("bus refused the batch");
            }
            for (SessionSignal signal : signals) {
                record(signal, types, chunks);
            }
        }
    }

    /** A bus whose publishes do not return until the test lets them. */
    private static final class HangingBus implements SessionSignalBus {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<String> types = new CopyOnWriteArrayList<>();
        private final List<Integer> chunks = new CopyOnWriteArrayList<>();
        private final List<String> turns = new CopyOnWriteArrayList<>();

        @Override
        public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
            return () -> {
            };
        }

        @Override
        public void publish(SessionSignal signal) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            record(signal, types, chunks);
            turns.add(String.valueOf(signal.getPayload().get(AgentExecutionEventPayload.KEY_TURN)));
        }
    }
}
