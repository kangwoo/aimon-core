package at.aimon.core.agent.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeIds;

@DisplayName("LoggingMessageQueueListener Tests")
class LoggingMessageQueueListenerTest {

    private static final AgentRuntimeId CTX = AgentRuntimeIds.testCtx("ctx-1");

    private static QueuedInput input(String text) {
        return QueuedInput.builder().inputText(text).agentRuntimeId(CTX).build();
    }

    @Test
    @DisplayName("starts with zero counts for every change type")
    void startsWithZeroCounts() {
        LoggingMessageQueueListener listener = new LoggingMessageQueueListener();

        for (MessageQueueListener.ChangeType type : MessageQueueListener.ChangeType.values()) {
            assertThat(listener.getCount(type)).isZero();
        }
        assertThat(listener.getEnqueuedCount()).isZero();
        assertThat(listener.getDrainedCount()).isZero();
    }

    @Test
    @DisplayName("increments the matching counter on each event")
    void incrementsMatchingCounter() {
        LoggingMessageQueueListener listener = new LoggingMessageQueueListener();

        listener.onEvent(new MessageQueueListener.Event(input("a"), MessageQueueListener.ChangeType.ENQUEUED));
        listener.onEvent(new MessageQueueListener.Event(input("b"), MessageQueueListener.ChangeType.ENQUEUED));
        listener.onEvent(new MessageQueueListener.Event(input("a"), MessageQueueListener.ChangeType.DRAINED));

        assertThat(listener.getEnqueuedCount()).isEqualTo(2);
        assertThat(listener.getDrainedCount()).isEqualTo(1);
        assertThat(listener.getCount(MessageQueueListener.ChangeType.REMOVED)).isZero();
    }

    @Test
    @DisplayName("rejects null event")
    void rejectsNullEvent() {
        LoggingMessageQueueListener listener = new LoggingMessageQueueListener();
        assertThatThrownBy(() -> listener.onEvent(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects null change type argument")
    void rejectsNullChangeType() {
        LoggingMessageQueueListener listener = new LoggingMessageQueueListener();
        assertThatThrownBy(() -> listener.getCount(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("integrates with DefaultMessageQueueManager: counts enqueue and drain")
    void integratesWithManager() {
        InMemoryMessageQueueRepository repository = new InMemoryMessageQueueRepository();
        DefaultMessageQueueManager manager = new DefaultMessageQueueManager(repository);
        LoggingMessageQueueListener listener = new LoggingMessageQueueListener();
        manager.addListener(listener);

        manager.enqueue(input("a"));
        manager.enqueue(input("b"));
        manager.drainForInjection(q -> true, QueuedInputPriority.LATER);

        assertThat(listener.getEnqueuedCount()).isEqualTo(2);
        assertThat(listener.getDrainedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("is safe under concurrent event delivery")
    void concurrentIncrementsAreLossless() throws InterruptedException {
        final int threads = 8;
        final int perThread = 500;
        final LoggingMessageQueueListener listener = new LoggingMessageQueueListener();
        final MessageQueueListener.Event event = new MessageQueueListener.Event(input("x"),
                MessageQueueListener.ChangeType.ENQUEUED);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    for (int j = 0; j < perThread; j++) {
                        listener.onEvent(event);
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(listener.getEnqueuedCount()).isEqualTo((long) threads * perThread);
    }
}
