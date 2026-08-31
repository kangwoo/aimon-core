package at.aimon.core.agent.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeIds;

@DisplayName("DefaultMessageQueueManager Tests")
class DefaultMessageQueueManagerTest {

    private static final AgentRuntimeId CTX_A = AgentRuntimeIds.testCtx("ctx-A");
    private static final AgentRuntimeId CTX_B = AgentRuntimeIds.testCtx("ctx-B");
    private static final Predicate<QueuedInput> ANY = q -> true;

    private InMemoryMessageQueueRepository repository;
    private DefaultMessageQueueManager manager;

    @BeforeEach
    void setUp() {
        repository = new InMemoryMessageQueueRepository();
        manager = new DefaultMessageQueueManager(repository);
    }

    private static QueuedInput input(String text, QueuedInputPriority priority, AgentRuntimeId ctx) {
        return QueuedInput.builder().inputText(text).priority(priority).agentRuntimeId(ctx).build();
    }

    private static QueuedInput input(String text, QueuedInputPriority priority) {
        return input(text, priority, CTX_A);
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("rejects null repository")
        void rejectsNullRepository() {
            assertThatThrownBy(() -> new DefaultMessageQueueManager(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("enqueue")
    class EnqueueTests {

        @Test
        @DisplayName("delegates to repository and preserves FIFO")
        void delegatesToRepository() {
            QueuedInput a = input("a", QueuedInputPriority.NEXT);
            QueuedInput b = input("b", QueuedInputPriority.NEXT);

            manager.enqueue(a);
            manager.enqueue(b);

            assertThat(repository.size()).isEqualTo(2);
            assertThat(manager.snapshot()).containsExactly(a, b);
        }

        @Test
        @DisplayName("rejects null input")
        void rejectsNullInput() {
            assertThatThrownBy(() -> manager.enqueue(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("notifies listeners with ENQUEUED in insertion order")
        void notifiesListenersInOrder() {
            List<MessageQueueListener.Event> received = new CopyOnWriteArrayList<>();
            manager.addListener(received::add);

            QueuedInput a = input("a", QueuedInputPriority.NEXT);
            QueuedInput b = input("b", QueuedInputPriority.NOW);
            manager.enqueue(a);
            manager.enqueue(b);

            assertThat(received).hasSize(2);
            assertThat(received.get(0).getChangeType()).isEqualTo(MessageQueueListener.ChangeType.ENQUEUED);
            assertThat(received.get(0).getInput()).isEqualTo(a);
            assertThat(received.get(1).getChangeType()).isEqualTo(MessageQueueListener.ChangeType.ENQUEUED);
            assertThat(received.get(1).getInput()).isEqualTo(b);
        }
    }

    @Nested
    @DisplayName("drainForInjection")
    class DrainTests {

        @Test
        @DisplayName("returns entries ordered by priority then FIFO, honouring maxPriority cap")
        void ordersByPriorityThenFifo() {
            QueuedInput later = input("later", QueuedInputPriority.LATER);
            QueuedInput next1 = input("next1", QueuedInputPriority.NEXT);
            QueuedInput now1 = input("now1", QueuedInputPriority.NOW);
            QueuedInput next2 = input("next2", QueuedInputPriority.NEXT);

            manager.enqueue(later);
            manager.enqueue(next1);
            manager.enqueue(now1);
            manager.enqueue(next2);

            List<QueuedInput> drained = manager.drainForInjection(ANY, QueuedInputPriority.NEXT);

            assertThat(drained).containsExactly(now1, next1, next2);
            // LATER entry stays in the queue because it is below the maxPriority cap.
            assertThat(repository.size()).isEqualTo(1);
            assertThat(manager.snapshot()).containsExactly(later);
        }

        @Test
        @DisplayName("filters entries by predicate and removes only matches")
        void filtersByPredicate() {
            QueuedInput a1 = input("a1", QueuedInputPriority.NEXT, CTX_A);
            QueuedInput b1 = input("b1", QueuedInputPriority.NEXT, CTX_B);
            QueuedInput a2 = input("a2", QueuedInputPriority.NOW, CTX_A);

            manager.enqueue(a1);
            manager.enqueue(b1);
            manager.enqueue(a2);

            List<QueuedInput> drained = manager.drainForInjection(q -> q.getAgentRuntimeId().equals(CTX_A),
                    QueuedInputPriority.LATER);

            assertThat(drained).containsExactly(a2, a1);
            assertThat(repository.size()).isEqualTo(1);
            assertThat(manager.snapshot()).containsExactly(b1);
        }

        @Test
        @DisplayName("returns empty list when nothing matches")
        void emptyWhenNoMatch() {
            manager.enqueue(input("a", QueuedInputPriority.NEXT, CTX_A));

            List<QueuedInput> drained = manager.drainForInjection(q -> q.getAgentRuntimeId().equals(CTX_B),
                    QueuedInputPriority.LATER);

            assertThat(drained).isEmpty();
            assertThat(repository.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("notifies listeners with DRAINED event per removed entry, in the returned order")
        void notifiesListeners() {
            List<MessageQueueListener.Event> received = new CopyOnWriteArrayList<>();

            QueuedInput a = input("a", QueuedInputPriority.NEXT);
            QueuedInput b = input("b", QueuedInputPriority.NOW);
            manager.enqueue(a);
            manager.enqueue(b);

            // Only listen for drain events — add listener AFTER the initial enqueues.
            manager.addListener(received::add);

            List<QueuedInput> drained = manager.drainForInjection(ANY, QueuedInputPriority.LATER);

            assertThat(drained).containsExactly(b, a);
            assertThat(received).hasSize(2);
            assertThat(received.get(0).getChangeType()).isEqualTo(MessageQueueListener.ChangeType.DRAINED);
            assertThat(received.get(0).getInput()).isEqualTo(b);
            assertThat(received.get(1).getChangeType()).isEqualTo(MessageQueueListener.ChangeType.DRAINED);
            assertThat(received.get(1).getInput()).isEqualTo(a);
        }

        @Test
        @DisplayName("rejects null filter")
        void rejectsNullFilter() {
            assertThatThrownBy(() -> manager.drainForInjection(null, QueuedInputPriority.LATER))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null maxPriority")
        void rejectsNullMaxPriority() {
            assertThatThrownBy(() -> manager.drainForInjection(ANY, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("snapshot")
    class SnapshotTests {

        @Test
        @DisplayName("reflects current queue contents in priority-then-FIFO order")
        void reflectsCurrentContents() {
            QueuedInput next1 = input("next1", QueuedInputPriority.NEXT);
            QueuedInput now1 = input("now1", QueuedInputPriority.NOW);
            QueuedInput later1 = input("later1", QueuedInputPriority.LATER);

            manager.enqueue(next1);
            manager.enqueue(now1);
            manager.enqueue(later1);

            assertThat(manager.snapshot()).containsExactly(now1, next1, later1);
        }

        @Test
        @DisplayName("is unmodifiable")
        void isUnmodifiable() {
            manager.enqueue(input("a", QueuedInputPriority.NEXT));

            List<QueuedInput> snapshot = manager.snapshot();
            QueuedInput extra = input("b", QueuedInputPriority.NOW);

            assertThatThrownBy(() -> snapshot.add(extra)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("returns empty list when queue is empty")
        void emptyWhenQueueEmpty() {
            assertThat(manager.snapshot()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Listener management")
    class ListenerManagementTests {

        @Test
        @DisplayName("add then remove stops further notifications")
        void removeStopsNotifications() {
            AtomicInteger calls = new AtomicInteger();
            MessageQueueListener listener = event -> calls.incrementAndGet();

            manager.addListener(listener);
            manager.enqueue(input("a", QueuedInputPriority.NEXT));
            manager.removeListener(listener);
            manager.enqueue(input("b", QueuedInputPriority.NEXT));

            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("removeListener for unknown listener is a no-op")
        void removeUnknownIsNoOp() {
            MessageQueueListener listener = event -> {
            };
            // Never added, but removing still must not throw.
            manager.removeListener(listener);
            manager.enqueue(input("a", QueuedInputPriority.NEXT));
            assertThat(repository.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("addListener rejects null")
        void addListenerRejectsNull() {
            assertThatThrownBy(() -> manager.addListener(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("removeListener rejects null")
        void removeListenerRejectsNull() {
            assertThatThrownBy(() -> manager.removeListener(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the same listener added twice receives two notifications per event")
        void doubleAddReceivesTwice() {
            AtomicInteger calls = new AtomicInteger();
            MessageQueueListener listener = event -> calls.incrementAndGet();

            manager.addListener(listener);
            manager.addListener(listener);
            manager.enqueue(input("a", QueuedInputPriority.NEXT));

            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Exception isolation")
    class ExceptionIsolationTests {

        @Test
        @DisplayName("listener exception does not break producer or other listeners")
        void exceptionIsolated() {
            AtomicInteger goodCalls = new AtomicInteger();
            manager.addListener(event -> {
                throw new RuntimeException("boom-enqueue");
            });
            manager.addListener(event -> goodCalls.incrementAndGet());

            manager.enqueue(input("a", QueuedInputPriority.NEXT));
            manager.enqueue(input("b", QueuedInputPriority.NEXT));

            assertThat(goodCalls.get()).isEqualTo(2);
            assertThat(repository.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("exception during drain notification does not stop remaining notifications or affect the result")
        void exceptionDuringDrain() {
            manager.enqueue(input("a", QueuedInputPriority.NEXT));
            manager.enqueue(input("b", QueuedInputPriority.NOW));

            AtomicInteger goodCalls = new AtomicInteger();
            manager.addListener(event -> {
                if (event.getChangeType() == MessageQueueListener.ChangeType.DRAINED) {
                    throw new RuntimeException("boom-drain");
                }
            });
            manager.addListener(event -> {
                if (event.getChangeType() == MessageQueueListener.ChangeType.DRAINED) {
                    goodCalls.incrementAndGet();
                }
            });

            List<QueuedInput> drained = manager.drainForInjection(ANY, QueuedInputPriority.LATER);

            assertThat(drained).hasSize(2);
            assertThat(goodCalls.get()).isEqualTo(2);
            assertThat(repository.size()).isZero();
        }
    }

    @Nested
    @DisplayName("Event equality")
    class EventEqualityTests {

        @Test
        @DisplayName("equal Events compare equal and share hash")
        void eventEquality() {
            QueuedInput a = input("a", QueuedInputPriority.NEXT);
            MessageQueueListener.Event e1 = new MessageQueueListener.Event(a, MessageQueueListener.ChangeType.ENQUEUED);
            MessageQueueListener.Event e2 = new MessageQueueListener.Event(a, MessageQueueListener.ChangeType.ENQUEUED);
            MessageQueueListener.Event e3 = new MessageQueueListener.Event(a, MessageQueueListener.ChangeType.DRAINED);

            assertThat(e1).isEqualTo(e2).hasSameHashCodeAs(e2);
            assertThat(e1).isNotEqualTo(e3);
        }

        @Test
        @DisplayName("constructor rejects null args")
        void eventConstructorNulls() {
            QueuedInput a = input("a", QueuedInputPriority.NEXT);
            assertThatThrownBy(() -> new MessageQueueListener.Event(null, MessageQueueListener.ChangeType.ENQUEUED))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new MessageQueueListener.Event(a, null)).isInstanceOf(NullPointerException.class);
        }
    }
}
