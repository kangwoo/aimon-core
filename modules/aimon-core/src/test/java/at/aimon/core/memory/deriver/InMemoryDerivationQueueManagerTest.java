package at.aimon.core.memory.deriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.DefaultRedactionPolicy;
import at.aimon.core.memory.redaction.RedactionMatch;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.memory.redaction.RedactionResult;

@DisplayName("InMemoryDerivationQueueManager")
class InMemoryDerivationQueueManagerTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice"));
    private static final PeerView BOB = PeerView.of(WS, Principal.user("bob"));
    private static final DeriverProperties FAST_PROPS = DeriverProperties.of(2, 8000, Duration.ofMillis(20));

    private InMemoryDerivationQueueManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
            manager = null;
        }
    }

    @Test
    @DisplayName("redaction is applied at enqueue time and reaches the deriver")
    void redactionApplied() throws Exception {
        CountDownLatch processed = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> seenContent = new ConcurrentLinkedQueue<>();
        Deriver deriver = ctx -> {
            for (Message m : ctx.getMessages()) {
                seenContent.add(m.getContent());
            }
            processed.countDown();
            return DerivationResult.empty();
        };

        manager = new InMemoryDerivationQueueManager(deriver, new DefaultRedactionPolicy(), FAST_PROPS);
        manager.start();

        String secret = "AKIAIOSFODNN7EXAMPLE";
        manager.enqueue(task(List.of(Message.user("aws key " + secret))));

        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seenContent).hasSize(1);
        assertThat(seenContent.peek()).doesNotContain(secret);
    }

    @Test
    @DisplayName("redaction policy that mutates content cannot be skipped — every task goes through redact()")
    void redactionGateCannotBeBypassed() throws Exception {
        AtomicInteger redactCalls = new AtomicInteger();
        RedactionPolicy alwaysModifies = content -> {
            redactCalls.incrementAndGet();
            return RedactionResult.of("[REDACTED]",
                    List.of(RedactionMatch.of("TEST", 0, content.length(), "[REDACTED]")));
        };
        CountDownLatch processed = new CountDownLatch(2);
        ConcurrentLinkedQueue<String> seenContent = new ConcurrentLinkedQueue<>();
        Deriver deriver = ctx -> {
            ctx.getMessages().forEach(m -> seenContent.add(m.getContent()));
            processed.countDown();
            return DerivationResult.empty();
        };

        manager = new InMemoryDerivationQueueManager(deriver, alwaysModifies, FAST_PROPS);
        manager.start();
        manager.enqueue(task(List.of(Message.user("first"))));
        manager.enqueue(task(List.of(Message.user("second"))));

        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(redactCalls.get()).isGreaterThanOrEqualTo(2);
        assertThat(seenContent).allMatch("[REDACTED]"::equals);
    }

    @Test
    @DisplayName("tasks sharing a work unit are serialized one at a time")
    void perWorkUnitSerialization() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(5);
        Deriver deriver = ctx -> {
            int running = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(running, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
                done.countDown();
            }
            return DerivationResult.empty();
        };

        manager = new InMemoryDerivationQueueManager(deriver, new DefaultRedactionPolicy(),
                DeriverProperties.of(4, 8000, Duration.ofMillis(10)));
        manager.start();
        for (int i = 0; i < 5; i++) {
            manager.enqueue(task(List.of(Message.user("msg-" + i))));
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get()).as("only one worker handles a given work unit at a time").isEqualTo(1);
    }

    @Test
    @DisplayName("different work units run in parallel up to worker pool size")
    void differentUnitsRunInParallel() throws Exception {
        int workers = 3;
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(3);
        Deriver deriver = ctx -> {
            int running = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(running, Math::max);
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
                done.countDown();
            }
            return DerivationResult.empty();
        };

        manager = new InMemoryDerivationQueueManager(deriver, new DefaultRedactionPolicy(),
                DeriverProperties.of(workers, 8000, Duration.ofMillis(10)));
        manager.start();
        manager.enqueue(taskFor("sess-a", ALICE, "msg-a"));
        manager.enqueue(taskFor("sess-b", BOB, "msg-b"));
        manager.enqueue(taskFor("sess-c", ALICE, "msg-c"));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get()).as("distinct work units run concurrently").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("enqueue throws IllegalStateException after stop()")
    void enqueueAfterStopRejects() {
        manager = new InMemoryDerivationQueueManager(noopDeriver(), new DefaultRedactionPolicy(), FAST_PROPS);
        manager.start();
        manager.stop();

        assertThatThrownBy(() -> manager.enqueue(task(List.of(Message.user("x")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stopped");
    }

    @Test
    @DisplayName("start() is idempotent")
    void startIsIdempotent() {
        manager = new InMemoryDerivationQueueManager(noopDeriver(), new DefaultRedactionPolicy(), FAST_PROPS);
        manager.start();
        manager.start();

        assertThat(manager.stats().getActiveWorkers()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("stop() drains in-flight tasks")
    void stopDrainsInFlight() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        Deriver slow = ctx -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            completed.incrementAndGet();
            return DerivationResult.empty();
        };

        manager = new InMemoryDerivationQueueManager(slow, new DefaultRedactionPolicy(), FAST_PROPS);
        manager.start();
        manager.enqueue(taskFor("s-1", ALICE, "a"));
        manager.enqueue(taskFor("s-2", BOB, "b"));

        Thread.sleep(50);
        manager.stop();

        assertThat(completed.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("stats() reports completed tasks")
    void statsTracksCompletion() throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        Deriver counting = ctx -> {
            done.countDown();
            return DerivationResult.empty();
        };
        manager = new InMemoryDerivationQueueManager(counting, new DefaultRedactionPolicy(), FAST_PROPS);
        manager.start();
        manager.enqueue(taskFor("s-a", ALICE, "x"));
        manager.enqueue(taskFor("s-b", BOB, "y"));

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        QueueStats stats = manager.stats();
        assertThat(stats.getCompletedTasks()).isEqualTo(2);
        assertThat(stats.getFailedTasks()).isZero();
    }

    @Test
    @DisplayName("deriver throwing is recorded as a failed task without killing the worker")
    void failedTasksContinueWorking() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);
        Deriver flaky = ctx -> {
            int n = seen.incrementAndGet();
            done.countDown();
            if (n == 1) {
                throw new IllegalStateException("boom");
            }
            return DerivationResult.empty();
        };

        manager = new InMemoryDerivationQueueManager(flaky, new DefaultRedactionPolicy(), FAST_PROPS);
        manager.start();
        manager.enqueue(taskFor("s-a", ALICE, "x"));
        manager.enqueue(taskFor("s-b", BOB, "y"));

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        QueueStats stats = manager.stats();
        assertThat(stats.getFailedTasks()).isEqualTo(1);
        assertThat(stats.getCompletedTasks()).isEqualTo(1);
    }

    private static DerivationTask task(List<Message> messages) {
        return DerivationTask.builder().workspace(WS).sessionId("sess-1").observer(ALICE).messages(messages).build();
    }

    private static DerivationTask taskFor(String sessionId, PeerView observer, String text) {
        return DerivationTask.builder().workspace(WS).sessionId(sessionId).observer(observer)
                .messages(List.of(Message.user(text))).build();
    }

    private static Deriver noopDeriver() {
        return ctx -> DerivationResult.empty();
    }
}
