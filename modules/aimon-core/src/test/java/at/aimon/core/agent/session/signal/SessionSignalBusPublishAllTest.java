package at.aimon.core.agent.session.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;

@DisplayName("SessionSignalBus.publishAll default")
class SessionSignalBusPublishAllTest {

    private static final SessionId ID = SessionId.of("s-publish-all");

    @Test
    @DisplayName("delivers what publishing one at a time delivers, in list order")
    void matchesSequentialPublish() {
        final InMemorySignalBus bus = new InMemorySignalBus();
        final List<Object> received = new ArrayList<>();
        bus.subscribe(ID, s -> received.add(s.getPayload().get("n")));

        bus.publishAll(List.of(signal(0), signal(1), signal(2)));
        bus.publish(signal(3));
        bus.publishAll(List.of());

        assertThat(received).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("a signal that cannot be published does not take the later ones with it")
    void aFailureIsReportedAfterTheRestWereAttempted() {
        final List<Object> published = new ArrayList<>();
        final SessionSignalBus bus = new SessionSignalBus() {
            @Override
            public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
                return () -> {
                };
            }

            @Override
            public void publish(SessionSignal signal) {
                final Object n = signal.getPayload().get("n");
                if (Integer.valueOf(1).equals(n) || Integer.valueOf(3).equals(n)) {
                    throw new IllegalStateException("refused " + n);
                }
                published.add(n);
            }
        };

        assertThatThrownBy(() -> bus.publishAll(List.of(signal(0), signal(1), signal(2), signal(3), signal(4))))
                .isInstanceOf(IllegalStateException.class).hasMessage("refused 1")
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
        assertThat(published).containsExactly(0, 2, 4);
    }

    private static SessionSignal signal(int n) {
        return SessionSignal.builder().sessionId(ID).kind(SessionSignal.SignalKind.EVENT).originNodeId("node-a")
                .payload(Map.of("n", n)).build();
    }
}
