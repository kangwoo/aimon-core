package at.aimon.cli.repl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.session.routing.wake.WakePolicy;

@DisplayName("ReplWaker — when the idle REPL may be woken, and with what")
class ReplWakerTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.of("agent:cli");
    private static final SessionId SESSION = SessionId.of("default");

    private MessageQueueManager queue;
    private final AtomicInteger nudges = new AtomicInteger();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T09:00:00Z"));
    private ReplWaker waker;

    @BeforeEach
    void setUp() {
        queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
    }

    @AfterEach
    void tearDown() {
        if (waker != null) {
            waker.close();
        }
    }

    private ReplWaker waker(WakePolicy policy) {
        waker = new ReplWaker(queue, RUNTIME, SESSION, policy, clock, nudges::incrementAndGet);
        return waker;
    }

    private static WakePolicy quick() {
        // Long enough that two enqueues in a row land inside one debounce on a busy machine.
        return WakePolicy.builder().debounce(Duration.ofMillis(200)).build();
    }

    private static QueuedInput event(String session, String kind, String text) {
        return QueuedInput.builder().agentRuntimeId(RUNTIME).sessionId(session).inputText(text)
                .metadata(Map.of("kind", kind)).build();
    }

    @Test
    @DisplayName("an event for this session nudges once after the debounce; others do not")
    void nudgesForItsOwnEventsOnly() throws Exception {
        waker(quick());

        queue.enqueue(event("other", "monitor-event", "not mine"));
        queue.enqueue(event(SESSION.value(), "subagent-task-completed", "not a waking kind"));
        Thread.sleep(400);
        assertThat(nudges).hasValue(0);

        queue.enqueue(event(SESSION.value(), "monitor-event", "one"));
        queue.enqueue(event(SESSION.value(), "monitor-event", "two"));
        await(() -> nudges.get() == 1);
        Thread.sleep(400);
        assertThat(nudges).as("a burst is one nudge").hasValue(1);
    }

    @Test
    @DisplayName("take drains this session's waking entries into one input of background-wake reminders")
    void takeBuildsTheWakeInput() {
        waker(quick());
        queue.enqueue(event(SESSION.value(), "monitor-event", "ERROR one"));
        queue.enqueue(event(SESSION.value(), "monitor-event", "ERROR two"));
        queue.enqueue(event("other", "monitor-event", "theirs"));

        final ReplWaker.Wake wake = waker.take().orElseThrow();

        assertThat(wake.getInput()).contains("<system-reminder key=\"background-wake\">").contains("ERROR one")
                .contains("ERROR two").doesNotContain("theirs");
        assertThat(wake.getReason()).isEqualTo("monitor-event");
        assertThat(queue.snapshot()).extracting(QueuedInput::getInputText).containsExactly("theirs");
        assertThat(waker.take()).as("nothing left to wake for").isEmpty();
    }

    @Test
    @DisplayName("the streak cap holds until a prompt of the user's")
    void consecutiveCapResetsOnAUserTurn() {
        waker(WakePolicy.builder().debounce(Duration.ofMillis(30)).maxConsecutive(1).build());

        queue.enqueue(event(SESSION.value(), "monitor-event", "first"));
        assertThat(waker.take()).isPresent();
        queue.enqueue(event(SESSION.value(), "monitor-event", "second"));
        assertThat(waker.isDue()).as("the REPL must not give up its prompt for a wake that will not run").isFalse();
        assertThat(waker.take()).as("one wake in a row is the cap").isEmpty();
        assertThat(queue.snapshot()).hasSize(1);

        waker.userTurn();
        assertThat(waker.isDue()).isTrue();
        assertThat(waker.take()).isPresent();
        assertThat(waker.isDue()).as("nothing left waiting").isFalse();
    }

    @Test
    @DisplayName("the hourly cap holds until the oldest wake leaves the hour")
    void hourlyCap() {
        waker(WakePolicy.builder().debounce(Duration.ofMillis(30)).maxPerHour(1).build());

        queue.enqueue(event(SESSION.value(), "monitor-event", "first"));
        assertThat(waker.take()).isPresent();
        waker.userTurn();
        queue.enqueue(event(SESSION.value(), "monitor-event", "second"));
        assertThat(waker.isDue()).isFalse();
        assertThat(waker.take()).isEmpty();

        clock.advance(Duration.ofMinutes(61));
        assertThat(waker.isDue()).isTrue();
        assertThat(waker.take()).isPresent();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(10);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
