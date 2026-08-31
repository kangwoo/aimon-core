package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantTextDelta;

/**
 * Contract for the relay's per-turn event stamp.
 *
 * <p>
 * {@link AgentExecutionEvent} carries no turn identity, so a remote subscriber used to have to infer turn boundaries
 * from arrival order — which fails exactly when it matters, i.e. when a subscriber attaches while turn 2 is already
 * running and cannot tell turn 1's trailing frames from its own. The relay closes that gap by stamping the turn it
 * serves onto every payload it publishes, which is only sound because one relay serves one turn.
 */
@DisplayName("SessionEventRelay stamps its turn onto every relayed EVENT")
class SessionEventRelayTurnStampTest {

    private static final SessionId CONV = SessionId.of("c-relay-stamp");
    private static final AgentRuntimeId CTX = AgentRuntimeId.of("agent:test-1");
    private static final Instant TS = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("every relayed frame carries the relay's turn id")
    void everyFrameCarriesTheTurnId() {
        final RecordingSignalBus bus = new RecordingSignalBus();
        final TurnId turn = TurnId.of("turn-1");

        try (SessionEventRelay relay = new SessionEventRelay(CONV, turn, new InProcessEventPublisher(), bus, "node-a",
                new SameThreadDispatcher())) {
            assertThat(relay.getTurnId()).isEqualTo(turn);
            relay.accept(delta(0));
            relay.accept(delta(1));
        }

        assertThat(bus.turnStamps()).as("all frames of one turn are stamped with that turn").containsExactly("turn-1",
                "turn-1");
    }

    @Test
    @DisplayName("two consecutive turns of one conversation are distinguishable in the relayed stream")
    void consecutiveTurnsAreDistinguishable() {
        final RecordingSignalBus bus = new RecordingSignalBus();
        final InProcessEventPublisher publisher = new InProcessEventPublisher();

        try (SessionEventRelay first = new SessionEventRelay(CONV, TurnId.of("turn-1"), publisher, bus, "node-a",
                new SameThreadDispatcher())) {
            first.accept(delta(0));
        }
        try (SessionEventRelay second = new SessionEventRelay(CONV, TurnId.of("turn-2"), publisher, bus, "node-a",
                new SameThreadDispatcher())) {
            second.accept(delta(1));
        }

        // Before the stamp existed both frames were indistinguishable on the wire: same session, same everything
        // but a delta payload. A subscriber attached for turn-2 had no way to reject the turn-1 frame.
        assertThat(bus.turnStamps()).containsExactly("turn-1", "turn-2");
    }

    private static AgentExecutionEvent delta(int chunk) {
        return AssistantTextDelta.builder().timestamp(TS).agentRuntimeId(CTX).iteration(1).delta("d" + chunk)
                .chunkIndex(chunk).build();
    }

    /** Records the {@code turn} stamp of every relayed EVENT signal, in publish order. */
    private static final class RecordingSignalBus implements SessionSignalBus {
        private final List<String> stamps = new CopyOnWriteArrayList<>();

        List<String> turnStamps() {
            return new ArrayList<>(stamps);
        }

        @Override
        public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
            return () -> {
            };
        }

        @Override
        public void publish(SessionSignal signal) {
            stamps.add(String.valueOf(signal.getPayload().get(AgentExecutionEventPayload.KEY_TURN)));
        }
    }

    /** Drains inline so the assertions do not have to await a dispatcher thread. */
    private static final class SameThreadDispatcher extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
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
