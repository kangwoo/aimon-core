package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryTaskStopSignal — single-JVM loopback bus for cross-node stop wiring")
class InMemoryTaskStopSignalTest {

    private InMemoryTaskStopSignal signal;

    @BeforeEach
    void setUp() {
        signal = new InMemoryTaskStopSignal();
    }

    @Test
    @DisplayName("broadcastStop delivers the taskId to a subscribed handler")
    void broadcastReachesHandler() {
        List<String> received = new ArrayList<>();
        signal.subscribe(received::add);

        signal.broadcastStop("t1");

        assertThat(received).containsExactly("t1");
    }

    @Test
    @DisplayName("broadcastStop fans out to every subscribed handler")
    void broadcastFansOut() {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        signal.subscribe(a::add);
        signal.subscribe(b::add);

        signal.broadcastStop("t1");

        assertThat(a).containsExactly("t1");
        assertThat(b).containsExactly("t1");
    }

    @Test
    @DisplayName("closing a subscription stops further delivery to that handler")
    void closedSubscriptionNoLongerReceives() {
        List<String> received = new ArrayList<>();
        TaskStopSignal.Subscription sub = signal.subscribe(received::add);

        signal.broadcastStop("t1");
        sub.close();
        signal.broadcastStop("t2");

        assertThat(received).containsExactly("t1");
    }

    @Test
    @DisplayName("a throwing handler is isolated: other handlers still receive the broadcast")
    void throwingHandlerIsIsolated() {
        AtomicInteger survivor = new AtomicInteger();
        signal.subscribe(taskId -> {
            throw new IllegalStateException("boom");
        });
        signal.subscribe(taskId -> survivor.incrementAndGet());

        signal.broadcastStop("t1");

        assertThat(survivor).hasValue(1);
    }

    @Test
    @DisplayName("broadcast with no subscribers is a harmless no-op")
    void broadcastWithoutSubscribersIsNoOp() {
        signal.broadcastStop("t1");
    }

    @Test
    @DisplayName("null arguments are rejected")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> signal.broadcastStop(null));
        assertThatNullPointerException().isThrownBy(() -> signal.subscribe(null));
    }
}
