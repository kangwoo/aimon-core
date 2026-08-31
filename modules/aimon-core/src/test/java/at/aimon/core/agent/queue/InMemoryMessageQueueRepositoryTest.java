package at.aimon.core.agent.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeIds;

@DisplayName("InMemoryMessageQueueRepository Tests")
class InMemoryMessageQueueRepositoryTest {

    private static final AgentRuntimeId CTX_A = AgentRuntimeIds.testCtx("ctx-A");
    private static final AgentRuntimeId CTX_B = AgentRuntimeIds.testCtx("ctx-B");
    private static final Predicate<QueuedInput> ANY = q -> true;

    private static QueuedInput input(String text, QueuedInputPriority priority, AgentRuntimeId ctx) {
        return QueuedInput.builder().inputText(text).priority(priority).agentRuntimeId(ctx).build();
    }

    private static QueuedInput input(String text, QueuedInputPriority priority) {
        return input(text, priority, CTX_A);
    }

    @Nested
    @DisplayName("Priority ordering")
    class PriorityOrdering {

        @Test
        @DisplayName("dequeue returns NOW, then NEXT, then LATER regardless of insertion order")
        void priorityOrderingAcrossTiers() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();

            QueuedInput later = input("later", QueuedInputPriority.LATER);
            QueuedInput now = input("now", QueuedInputPriority.NOW);
            QueuedInput next = input("next", QueuedInputPriority.NEXT);

            repo.enqueue(later);
            repo.enqueue(now);
            repo.enqueue(next);

            assertThat(repo.dequeue(ANY)).contains(now);
            assertThat(repo.dequeue(ANY)).contains(next);
            assertThat(repo.dequeue(ANY)).contains(later);
            assertThat(repo.dequeue(ANY)).isEmpty();
        }

        @Test
        @DisplayName("FIFO preserved within a priority tier")
        void fifoWithinPriority() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();

            QueuedInput first = input("first", QueuedInputPriority.NEXT);
            QueuedInput second = input("second", QueuedInputPriority.NEXT);
            QueuedInput third = input("third", QueuedInputPriority.NEXT);

            repo.enqueue(first);
            repo.enqueue(second);
            repo.enqueue(third);

            assertThat(repo.dequeue(ANY)).contains(first);
            assertThat(repo.dequeue(ANY)).contains(second);
            assertThat(repo.dequeue(ANY)).contains(third);
        }

        @Test
        @DisplayName("peek returns same order as dequeue without removing")
        void peekDoesNotRemove() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();

            QueuedInput later = input("later", QueuedInputPriority.LATER);
            QueuedInput now = input("now", QueuedInputPriority.NOW);
            repo.enqueue(later);
            repo.enqueue(now);

            assertThat(repo.peek(ANY)).contains(now);
            assertThat(repo.peek(ANY)).contains(now);
            assertThat(repo.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Predicate filtering")
    class FilteringTests {

        @Test
        @DisplayName("dequeue skips entries that don't match the predicate")
        void dequeueFilteredByContext() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();

            QueuedInput a1 = input("a1", QueuedInputPriority.NEXT, CTX_A);
            QueuedInput b1 = input("b1", QueuedInputPriority.NEXT, CTX_B);
            QueuedInput a2 = input("a2", QueuedInputPriority.NEXT, CTX_A);

            repo.enqueue(a1);
            repo.enqueue(b1);
            repo.enqueue(a2);

            Predicate<QueuedInput> onlyA = q -> q.getAgentRuntimeId().equals(CTX_A);

            assertThat(repo.dequeue(onlyA)).contains(a1);
            assertThat(repo.dequeue(onlyA)).contains(a2);
            assertThat(repo.dequeue(onlyA)).isEmpty();
            assertThat(repo.size()).isEqualTo(1);
            assertThat(repo.peek(ANY)).contains(b1);
        }

        @Test
        @DisplayName("peek applies predicate across tiers (NOW for B beats NEXT for A)")
        void peekFilteredByContext() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();

            QueuedInput aNext = input("a-next", QueuedInputPriority.NEXT, CTX_A);
            QueuedInput bNow = input("b-now", QueuedInputPriority.NOW, CTX_B);
            repo.enqueue(aNext);
            repo.enqueue(bNow);

            assertThat(repo.peek(q -> q.getAgentRuntimeId().equals(CTX_A))).contains(aNext);
            assertThat(repo.peek(q -> q.getAgentRuntimeId().equals(CTX_B))).contains(bNow);
        }
    }

    @Nested
    @DisplayName("listByMaxPriority")
    class ListByMaxPriorityTests {

        @Test
        @DisplayName("listByMaxPriority(NEXT, any) returns NOW + NEXT, ordered priority-then-FIFO")
        void listByMaxPriorityNext() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();

            QueuedInput later = input("later", QueuedInputPriority.LATER);
            QueuedInput next1 = input("next1", QueuedInputPriority.NEXT);
            QueuedInput now1 = input("now1", QueuedInputPriority.NOW);
            QueuedInput next2 = input("next2", QueuedInputPriority.NEXT);
            QueuedInput now2 = input("now2", QueuedInputPriority.NOW);

            repo.enqueue(later);
            repo.enqueue(next1);
            repo.enqueue(now1);
            repo.enqueue(next2);
            repo.enqueue(now2);

            List<QueuedInput> result = repo.listByMaxPriority(QueuedInputPriority.NEXT, ANY);

            assertThat(result).containsExactly(now1, now2, next1, next2);
        }

        @Test
        @DisplayName("listByMaxPriority(NOW, any) returns only NOW tier")
        void listByMaxPriorityNow() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();

            QueuedInput now1 = input("now1", QueuedInputPriority.NOW);
            QueuedInput next1 = input("next1", QueuedInputPriority.NEXT);
            QueuedInput later1 = input("later1", QueuedInputPriority.LATER);

            repo.enqueue(now1);
            repo.enqueue(next1);
            repo.enqueue(later1);

            List<QueuedInput> result = repo.listByMaxPriority(QueuedInputPriority.NOW, ANY);
            assertThat(result).containsExactly(now1);
        }

        @Test
        @DisplayName("listByMaxPriority(LATER, any) returns all entries priority-then-FIFO")
        void listByMaxPriorityLater() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();

            QueuedInput later1 = input("later1", QueuedInputPriority.LATER);
            QueuedInput now1 = input("now1", QueuedInputPriority.NOW);
            QueuedInput next1 = input("next1", QueuedInputPriority.NEXT);

            repo.enqueue(later1);
            repo.enqueue(now1);
            repo.enqueue(next1);

            assertThat(repo.listByMaxPriority(QueuedInputPriority.LATER, ANY)).containsExactly(now1, next1, later1);
        }

        @Test
        @DisplayName("snapshot is a defensive copy — mutating it does not affect repo state")
        void snapshotIsDefensiveCopy() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            QueuedInput now = input("now", QueuedInputPriority.NOW);
            repo.enqueue(now);

            List<QueuedInput> snapshot = repo.listByMaxPriority(QueuedInputPriority.LATER, ANY);
            snapshot.clear();

            assertThat(repo.size()).isEqualTo(1);
            assertThat(repo.peek(ANY)).contains(now);
        }

        @Test
        @DisplayName("predicate filters entries in the snapshot")
        void snapshotFiltered() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();

            QueuedInput a = input("a", QueuedInputPriority.NOW, CTX_A);
            QueuedInput b = input("b", QueuedInputPriority.NEXT, CTX_B);
            repo.enqueue(a);
            repo.enqueue(b);

            List<QueuedInput> snapshot = repo.listByMaxPriority(QueuedInputPriority.LATER,
                    q -> q.getAgentRuntimeId().equals(CTX_A));
            assertThat(snapshot).containsExactly(a);
        }
    }

    @Nested
    @DisplayName("remove(uuid)")
    class RemoveTests {

        @Test
        @DisplayName("remove returns true and deletes a present entry")
        void removePresent() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            QueuedInput entry = input("a", QueuedInputPriority.NEXT);
            repo.enqueue(entry);

            assertThat(repo.remove(entry.getUuid())).isTrue();
            assertThat(repo.size()).isZero();
            assertThat(repo.peek(ANY)).isEmpty();
        }

        @Test
        @DisplayName("remove returns false for unknown uuid")
        void removeMissing() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            repo.enqueue(input("a", QueuedInputPriority.NEXT));

            assertThat(repo.remove(UUID.randomUUID())).isFalse();
            assertThat(repo.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("remove is idempotent — second remove returns false")
        void removeIdempotent() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            QueuedInput entry = input("a", QueuedInputPriority.NEXT);
            repo.enqueue(entry);

            assertThat(repo.remove(entry.getUuid())).isTrue();
            assertThat(repo.remove(entry.getUuid())).isFalse();
        }

        @Test
        @DisplayName("remove keeps other entries intact")
        void removeKeepsOthers() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            QueuedInput a = input("a", QueuedInputPriority.NEXT);
            QueuedInput b = input("b", QueuedInputPriority.NEXT);
            repo.enqueue(a);
            repo.enqueue(b);

            assertThat(repo.remove(a.getUuid())).isTrue();
            assertThat(repo.peek(ANY)).contains(b);
            assertThat(repo.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Listeners")
    class ListenerTests {

        @Test
        @DisplayName("subscribed listener receives onEnqueued for each enqueue")
        void listenerReceivesEvents() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            List<QueuedInput> received = new CopyOnWriteArrayList<>();
            repo.subscribe(received::add);

            QueuedInput a = input("a", QueuedInputPriority.NEXT);
            QueuedInput b = input("b", QueuedInputPriority.NOW);
            repo.enqueue(a);
            repo.enqueue(b);

            assertThat(received).containsExactly(a, b);
        }

        @Test
        @DisplayName("closing a registration unsubscribes the listener")
        void unsubscribeViaRegistration() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            List<QueuedInput> received = new CopyOnWriteArrayList<>();

            MessageQueueRepository.Listener.Registration reg = repo.subscribe(received::add);
            repo.enqueue(input("first", QueuedInputPriority.NEXT));
            reg.close();
            repo.enqueue(input("second", QueuedInputPriority.NEXT));

            assertThat(received).hasSize(1);
        }

        @Test
        @DisplayName("closing an already-closed registration is a no-op")
        void doubleCloseIsNoop() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            MessageQueueRepository.Listener.Registration reg = repo.subscribe(q -> {
            });
            reg.close();
            reg.close();

            // Enqueuing still works; this just asserts no exception escaped.
            repo.enqueue(input("x", QueuedInputPriority.NEXT));
            assertThat(repo.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("exception in one listener does not affect other listeners or the producer")
        void exceptionIsolation() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            AtomicInteger goodListenerCalls = new AtomicInteger();

            repo.subscribe(q -> {
                throw new RuntimeException("boom");
            });
            repo.subscribe(q -> goodListenerCalls.incrementAndGet());

            repo.enqueue(input("a", QueuedInputPriority.NEXT));
            repo.enqueue(input("b", QueuedInputPriority.NEXT));

            assertThat(goodListenerCalls.get()).isEqualTo(2);
            assertThat(repo.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Null safety")
    class NullSafetyTests {

        @Test
        @DisplayName("enqueue rejects null")
        void enqueueRejectsNull() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            assertThatThrownBy(() -> repo.enqueue(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("dequeue rejects null filter")
        void dequeueRejectsNull() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            assertThatThrownBy(() -> repo.dequeue(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("peek rejects null filter")
        void peekRejectsNull() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            assertThatThrownBy(() -> repo.peek(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("listByMaxPriority rejects null args")
        void listByMaxPriorityRejectsNull() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            assertThatThrownBy(() -> repo.listByMaxPriority(null, ANY)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> repo.listByMaxPriority(QueuedInputPriority.LATER, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("remove rejects null uuid")
        void removeRejectsNull() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            assertThatThrownBy(() -> repo.remove(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("subscribe rejects null listener")
        void subscribeRejectsNull() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            assertThatThrownBy(() -> repo.subscribe(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("size()")
    class SizeTests {

        @Test
        @DisplayName("size tracks enqueue, dequeue and remove correctly")
        void sizeTracksMutations() {
            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
            assertThat(repo.size()).isZero();

            QueuedInput a = input("a", QueuedInputPriority.NEXT);
            QueuedInput b = input("b", QueuedInputPriority.NEXT);
            repo.enqueue(a);
            repo.enqueue(b);
            assertThat(repo.size()).isEqualTo(2);

            repo.dequeue(ANY);
            assertThat(repo.size()).isEqualTo(1);

            repo.remove(b.getUuid());
            assertThat(repo.size()).isZero();
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("4 producers × 250 items + 4 consumers: no duplicates, no lost uuids")
        void concurrentProducersConsumers() throws Exception {
            final int producers = 4;
            final int consumers = 4;
            final int perProducer = 250;
            final int total = producers * perProducer;

            InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();

            final List<UUID> produced = Collections.synchronizedList(new ArrayList<>(total));
            final Set<UUID> consumed = ConcurrentHashMap.newKeySet();
            final AtomicInteger duplicateCount = new AtomicInteger();
            final AtomicInteger consumedCount = new AtomicInteger();

            ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);
            try {
                final CountDownLatch startGate = new CountDownLatch(1);
                final CountDownLatch producersDone = new CountDownLatch(producers);

                for (int p = 0; p < producers; p++) {
                    final int producerIndex = p;
                    pool.submit(() -> {
                        try {
                            startGate.await();
                            for (int i = 0; i < perProducer; i++) {
                                QueuedInputPriority priority = QueuedInputPriority.values()[i % 3];
                                QueuedInput qi = input("p" + producerIndex + "-i" + i, priority);
                                produced.add(qi.getUuid());
                                repo.enqueue(qi);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            producersDone.countDown();
                        }
                        return null;
                    });
                }

                final CountDownLatch consumersDone = new CountDownLatch(consumers);
                for (int c = 0; c < consumers; c++) {
                    pool.submit(() -> {
                        try {
                            startGate.await();
                            while (true) {
                                Optional<QueuedInput> taken = repo.dequeue(ANY);
                                if (taken.isPresent()) {
                                    UUID uuid = taken.get().getUuid();
                                    if (!consumed.add(uuid)) {
                                        duplicateCount.incrementAndGet();
                                    }
                                    consumedCount.incrementAndGet();
                                } else if (producersDone.getCount() == 0 && repo.size() == 0) {
                                    break;
                                } else {
                                    Thread.yield();
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            consumersDone.countDown();
                        }
                        return null;
                    });
                }

                startGate.countDown();
                assertThat(producersDone.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(consumersDone.await(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(duplicateCount.get()).as("no duplicate consumptions").isZero();
            assertThat(consumedCount.get()).as("every enqueued entry consumed exactly once").isEqualTo(total);
            assertThat(consumed).as("consumed uuids exactly match produced uuids").hasSize(total)
                    .containsExactlyInAnyOrderElementsOf(produced);
            assertThat(repo.size()).isZero();
        }
    }
}
