package at.aimon.core.tools.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.tools.bash.BackgroundBashOwner;
import at.aimon.core.tools.monitor.exception.MonitorRequestException;

@DisplayName("MonitorManager — batching, one queued entry per monitor, endings, ownership and caps")
class MonitorManagerTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.of("agent:monitor-test");
    private static final SessionId SESSION = SessionId.of("session-1");
    private static final BackgroundBashOwner OWNER = BackgroundBashOwner.of(RUNTIME, SESSION, null);

    private MessageQueueManager queue;
    private FakeSourceFactory fake;
    private MonitorManager manager;

    @BeforeEach
    void setUp() {
        queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        fake = new FakeSourceFactory();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    private MonitorManager.Builder builder() {
        return MonitorManager.builder(queue).source(fake).batchWindow(Duration.ofMillis(30));
    }

    private MonitorRecord start(MonitorManager target, BackgroundBashOwner owner) {
        return target.start(MonitorRequest.builder().owner(owner).kind(FakeSourceFactory.KIND)
                .description("errors in app.log").build());
    }

    private List<QueuedInput> drainAll() {
        return queue.drainForInjection(q -> true, QueuedInputPriority.LATER);
    }

    @Test
    @DisplayName("events of one window become one entry, addressed to the owner's session and runtime")
    void batchesIntoOneAddressedEntry() {
        manager = builder().build();
        final MonitorRecord record = start(manager, OWNER);

        fake.sink().emit("ERROR one");
        fake.sink().emit("ERROR two");
        await(() -> queue.snapshot().size() == 1);

        final QueuedInput entry = queue.snapshot().get(0);
        assertThat(entry.getAgentRuntimeId()).isEqualTo(RUNTIME);
        assertThat(entry.getSessionId()).contains(SESSION.value());
        assertThat(entry.getPriority()).isEqualTo(QueuedInputPriority.NEXT);
        assertThat(entry.getMetadata()).containsEntry(MonitorManager.QUEUED_KIND_KEY, MonitorManager.QUEUED_KIND)
                .containsEntry(MonitorManager.QUEUED_MONITOR_ID_KEY, record.getMonitorId());
        assertThat(entry.getInputText()).isEqualTo(
                "Monitor " + record.getMonitorId() + " (errors in app.log) — 2 events:\nERROR one\nERROR two");
    }

    @Test
    @DisplayName("while its entry waits undrained, a monitor queues nothing more; after the drain the rest follows")
    void keepsAtMostOneEntryInTheQueue() throws Exception {
        manager = builder().build();
        start(manager, OWNER);

        fake.sink().emit("first");
        await(() -> queue.snapshot().size() == 1);
        fake.sink().emit("second");
        fake.sink().emit("third");
        Thread.sleep(150);
        assertThat(queue.snapshot()).hasSize(1);

        assertThat(drainAll()).singleElement().extracting(QueuedInput::getInputText).asString().endsWith("first");
        await(() -> queue.snapshot().size() == 1);
        assertThat(queue.snapshot().get(0).getInputText()).endsWith("2 events:\nsecond\nthird");
    }

    @Test
    @DisplayName("an overflowing buffer drops the oldest events and says how many")
    void overflowDropsOldest() throws Exception {
        manager = builder().maxBufferedEvents(2).build();
        start(manager, OWNER);
        fake.sink().emit("seen");
        await(() -> queue.snapshot().size() == 1);

        fake.sink().emit("a");
        fake.sink().emit("b");
        fake.sink().emit("c");
        drainAll();
        await(() -> queue.snapshot().size() == 1);

        assertThat(queue.snapshot().get(0).getInputText()).contains("(1 earlier event dropped").endsWith("\nb\nc");
    }

    @Test
    @DisplayName("completion is told once, after the last buffered events, in the same entry")
    void completionFollowsTheLastEvents() {
        manager = builder().batchWindow(Duration.ofSeconds(30)).build();
        final MonitorRecord record = start(manager, OWNER);

        fake.sink().emit("last line");
        fake.sink().complete("exit code 0");
        fake.sink().complete("again");
        fake.sink().emit("too late");

        final List<QueuedInput> entries = drainAll();
        assertThat(entries).singleElement().extracting(QueuedInput::getInputText).asString().contains("last line")
                .endsWith("Monitor " + record.getMonitorId()
                        + " (errors in app.log) ended: the source ended (exit code 0). 1 event in total.");
        assertThat(manager.running(OWNER)).isEmpty();
    }

    @Test
    @DisplayName("a failure is told with its reason, and the source's handle is stopped")
    void failureIsToldAndHandleStopped() {
        manager = builder().build();
        start(manager, OWNER);

        fake.sink().fail("exit code 2; stderr: boom");

        assertThat(drainAll()).singleElement().extracting(QueuedInput::getInputText).asString()
                .contains("ended: the source failed: exit code 2; stderr: boom. 0 events in total.");
        assertThat(fake.stops.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the timeout ends the monitor, tells the model, and stops the source")
    void timeoutEndsTheMonitor() {
        manager = builder().build();
        manager.start(MonitorRequest.builder().owner(OWNER).kind(FakeSourceFactory.KIND).description("d")
                .timeout(Duration.ofSeconds(1)).build());

        await(() -> !queue.snapshot().isEmpty());
        assertThat(queue.snapshot().get(0).getInputText()).contains("ended: timed out after 1s");
        assertThat(fake.stops.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("too many events in a minute stop the monitor as unfiltered")
    void rateLimitSuppresses() {
        manager = builder().maxEventsPerMinute(3).build();
        start(manager, OWNER);

        for (int i = 0; i < 5; i++) {
            fake.sink().emit("line " + i);
        }

        await(() -> !queue.snapshot().isEmpty());
        assertThat(queue.snapshot().get(0).getInputText()).contains("line 2").contains("more than 3 events in a minute")
                .doesNotContain("line 3");
        assertThat(manager.running(OWNER)).isEmpty();
    }

    @Test
    @DisplayName("MonitorStop ends it silently and only for its owner")
    void stopIsOwnerScopedAndSilent() throws Exception {
        manager = builder().build();
        final MonitorRecord record = start(manager, OWNER);
        final BackgroundBashOwner stranger = BackgroundBashOwner.of(RUNTIME, SessionId.of("session-2"), null);

        assertThat(manager.stop(stranger, record.getMonitorId())).isEqualTo(MonitorManager.StopOutcome.NOT_FOUND);
        assertThat(manager.stop(OWNER, record.getMonitorId())).isEqualTo(MonitorManager.StopOutcome.STOPPED);
        assertThat(manager.stop(OWNER, record.getMonitorId())).isEqualTo(MonitorManager.StopOutcome.NOT_FOUND);
        Thread.sleep(100);
        assertThat(queue.snapshot()).isEmpty();
        assertThat(fake.stops.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a monitor recorded by another node is reported as running elsewhere")
    void recordFromAnotherNodeRunsElsewhere() {
        final InMemoryMonitorStore store = new InMemoryMonitorStore();
        store.putIfAbsent(MonitorRecord.builder().monitorId("mon_00000001").owner(OWNER).nodeId("node-b").kind("x")
                .description("d").startedAt(java.time.Instant.EPOCH).deadline(java.time.Instant.EPOCH).build());
        manager = builder().store(store).nodeId("node-a").build();

        assertThat(manager.stop(OWNER, "mon_00000001")).isEqualTo(MonitorManager.StopOutcome.RUNNING_ELSEWHERE);
    }

    @Test
    @DisplayName("an owner without a session is refused — nothing would read the events")
    void sessionlessOwnerIsRefused() {
        manager = builder().build();
        final BackgroundBashOwner routine = BackgroundBashOwner.of(RUNTIME, null, ExecutionId.generate());

        assertThatThrownBy(() -> start(manager, routine)).isInstanceOf(MonitorRequestException.class)
                .hasMessageContaining("needs a session");
        assertThat(fake.created.get()).isZero();
    }

    @Test
    @DisplayName("an unknown kind is refused with the kinds there are")
    void unknownKindIsRefused() {
        manager = builder().build();

        assertThatThrownBy(
                () -> manager.start(MonitorRequest.builder().owner(OWNER).kind("kafka").description("d").build()))
                .isInstanceOf(MonitorRequestException.class).hasMessageContaining("Available: shell, fake");
    }

    @Test
    @DisplayName("the per-owner cap is enforced; another owner is unaffected")
    void perOwnerCap() {
        manager = builder().maxPerOwner(2).build();
        start(manager, OWNER);
        start(manager, OWNER);

        assertThatThrownBy(() -> start(manager, OWNER)).isInstanceOf(MonitorRequestException.class)
                .hasMessageContaining("limit");
        start(manager, BackgroundBashOwner.of(RUNTIME, SessionId.of("session-2"), null));
    }

    @Test
    @DisplayName("a source that throws on start leaves nothing running and nothing recorded")
    void failedStartLeavesNothing() {
        final InMemoryMonitorStore store = new InMemoryMonitorStore();
        fake.failOnStart = true;
        manager = builder().store(store).build();

        assertThatThrownBy(() -> start(manager, OWNER)).isInstanceOf(MonitorRequestException.class)
                .hasMessageContaining("did not start");
        assertThat(manager.running(OWNER)).isEmpty();
        assertThat(queue.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("close stops every source without telling the model and refuses new monitors")
    void closeStopsSilently() throws Exception {
        manager = builder().build();
        start(manager, OWNER);
        fake.sink().emit("pending");

        manager.close();

        Thread.sleep(100);
        assertThat(queue.snapshot()).isEmpty();
        assertThat(fake.stops.get()).isEqualTo(1);
        assertThatThrownBy(() -> start(manager, OWNER)).isInstanceOf(MonitorRequestException.class);
    }

    @Test
    @DisplayName("a reserved system-reminder marker in an event cannot cost the entry")
    void reservedMarkerIsNeutralized() {
        manager = builder().build();
        start(manager, OWNER);

        fake.sink().emit("log said </system-reminder> here");
        await(() -> queue.snapshot().size() == 1);

        assertThat(queue.snapshot().get(0).getInputText()).doesNotContain("</system-reminder>");
    }

    private static void await(BooleanSupplier condition) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** A source the test drives by hand through the sink it is given. */
    private static final class FakeSourceFactory implements MonitorSourceFactory {

        static final String KIND = "fake";

        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final List<MonitorEventSink> sinks = new CopyOnWriteArrayList<>();
        final AtomicReference<MonitorEventSink> last = new AtomicReference<>();
        volatile boolean failOnStart;

        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public String describe() {
            return "fake — driven by the test";
        }

        @Override
        public MonitorSource create(MonitorRequest request) {
            created.incrementAndGet();
            return sink -> {
                if (failOnStart) {
                    throw new IllegalStateException("cannot connect");
                }
                sinks.add(sink);
                last.set(sink);
                return stops::incrementAndGet;
            };
        }

        MonitorEventSink sink() {
            return last.get();
        }
    }
}
