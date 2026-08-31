package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;

/**
 * Verifies the semantics {@link SessionCheckpointMailbox} promises in place of the per-entry locks and version
 * bookkeeping its predecessor needed: one pending checkpoint per session, the newest state at write time, a
 * {@code flush} that drains before an authoritative write, a writer thread that survives a failing store, and a
 * {@code close} that neither drops what it could not drain nor leaves later callers waiting on a barrier nobody lifts.
 */
class SessionCheckpointMailboxTest {

    private SessionCheckpointMailbox mailbox;

    @AfterEach
    void tearDown() {
        if (mailbox != null) {
            mailbox.close();
        }
    }

    @Test
    @DisplayName("a checkpoint is written without any end-of-turn save")
    void checkpointIsWrittenByTheWriterThread() {
        mailbox = SessionCheckpointMailbox.background();
        final SessionId id = SessionId.generate();
        final TranscriptBuffer memory = new TranscriptBuffer(id, "sys");
        final List<SessionSnapshot> writes = new CopyOnWriteArrayList<>();

        memory.addUserMessage("hi");
        mailbox.checkpoint(memory, writes::add);

        await().atMost(Duration.ofSeconds(2)).until(() -> writes.size() == 1);
        assertThat(writes.get(0).getSessionId()).isEqualTo(id);
        assertThat(writes.get(0).getConversationHistory()).hasSize(1);
    }

    @Test
    @DisplayName("the write carries the state at write time, not the state at checkpoint time")
    void writeCarriesTheNewestState() throws Exception {
        mailbox = SessionCheckpointMailbox.background();
        final SessionId id = SessionId.generate();
        final TranscriptBuffer memory = new TranscriptBuffer(id, "sys");
        final Gate gate = new Gate();
        final List<SessionSnapshot> writes = new CopyOnWriteArrayList<>();
        final Consumer<SessionSnapshot> write = gate.firstCallBlocks(writes);

        memory.addUserMessage("m1");
        mailbox.checkpoint(memory, write);
        assertThat(gate.entered.await(2, TimeUnit.SECONDS)).isTrue();

        // Both mutations land while the first write is parked, so they share one pending checkpoint.
        memory.addUserMessage("m2");
        memory.addUserMessage("m3");
        mailbox.checkpoint(memory, write);
        mailbox.checkpoint(memory, write);

        gate.release();
        await().atMost(Duration.ofSeconds(2)).until(() -> writes.size() == 2);
        assertThat(writes.get(0).getConversationHistory()).hasSize(1);
        assertThat(writes.get(1).getConversationHistory()).hasSize(3);
    }

    @Test
    @DisplayName("mutations that arrive during a write collapse into one further write")
    void mutationsCoalesceIntoOnePendingCheckpoint() throws Exception {
        mailbox = SessionCheckpointMailbox.background();
        final SessionId id = SessionId.generate();
        final TranscriptBuffer memory = new TranscriptBuffer(id, "sys");
        final Gate gate = new Gate();
        final List<SessionSnapshot> writes = new CopyOnWriteArrayList<>();
        final Consumer<SessionSnapshot> write = gate.firstCallBlocks(writes);

        memory.addUserMessage("m0");
        mailbox.checkpoint(memory, write);
        assertThat(gate.entered.await(2, TimeUnit.SECONDS)).isTrue();

        for (int i = 1; i <= 50; i++) {
            memory.addUserMessage("m" + i);
            mailbox.checkpoint(memory, write);
        }

        gate.release();
        mailbox.flush(id);

        assertThat(writes).as("50 mutations behind one in-flight write must not produce 50 writes").hasSize(2);
        assertThat(writes.get(1).getConversationHistory()).hasSize(51);
    }

    @Test
    @DisplayName("flush returns only after the pending checkpoint has been written")
    void flushDrainsThePendingCheckpoint() {
        mailbox = SessionCheckpointMailbox.background();
        final SessionId id = SessionId.generate();
        final TranscriptBuffer memory = new TranscriptBuffer(id, "sys");
        final List<SessionSnapshot> writes = new CopyOnWriteArrayList<>();

        memory.addUserMessage("hi");
        mailbox.checkpoint(memory, writes::add);
        mailbox.flush(id);

        assertThat(writes).hasSize(1);
    }

    @Test
    @DisplayName("flush is a no-op for a conversation the mailbox never saw")
    void flushIsNoOpForUnknownId() {
        mailbox = SessionCheckpointMailbox.background();

        mailbox.flush(SessionId.generate());
    }

    @Test
    @DisplayName("a checkpoint left by a previous turn's memory does not overwrite the current one")
    void supersededMemoryIsNotWritten() throws Exception {
        mailbox = SessionCheckpointMailbox.background();
        final SessionId id = SessionId.generate();
        final Gate gate = new Gate();
        final List<SessionSnapshot> writes = new CopyOnWriteArrayList<>();
        final Consumer<SessionSnapshot> write = gate.firstCallBlocks(writes);

        final TranscriptBuffer previousTurn = new TranscriptBuffer(id, "sys");
        previousTurn.addUserMessage("stale-1");
        mailbox.checkpoint(previousTurn, write);
        assertThat(gate.entered.await(2, TimeUnit.SECONDS)).isTrue();

        // Queue a second checkpoint for the old memory, then rebind the id to a freshly loaded memory. The queued task
        // must find itself superseded rather than write the old object's state over the new one's.
        previousTurn.addUserMessage("stale-2");
        mailbox.checkpoint(previousTurn, write);
        final TranscriptBuffer currentTurn = new TranscriptBuffer(id, "sys");
        currentTurn.addUserMessage("fresh-1");
        mailbox.checkpoint(currentTurn, write);

        gate.release();
        mailbox.flush(id);

        assertThat(writes).hasSize(2);
        assertThat(writes.get(1).getConversationHistory()).hasSize(1);
        assertThat(writes.get(1).getConversationHistory().get(0).getContent()).isEqualTo("fresh-1");
    }

    @Test
    @DisplayName("a store that throws does not end checkpointing for every later conversation")
    void writeFailureDoesNotKillTheWriterThread() {
        mailbox = SessionCheckpointMailbox.background();
        final SessionId id = SessionId.generate();
        final TranscriptBuffer memory = new TranscriptBuffer(id, "sys");
        final AtomicInteger attempts = new AtomicInteger();
        final Consumer<SessionSnapshot> failingWrite = snapshot -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("store is down");
            }
        };

        memory.addUserMessage("m1");
        mailbox.checkpoint(memory, failingWrite);
        mailbox.flush(id);

        memory.addUserMessage("m2");
        mailbox.checkpoint(memory, failingWrite);
        mailbox.flush(id);

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("an Error from the store does not end checkpointing either")
    void writeErrorDoesNotKillTheWriterThread() {
        mailbox = SessionCheckpointMailbox.background();
        final SessionId id = SessionId.generate();
        final TranscriptBuffer memory = new TranscriptBuffer(id, "sys");
        final AtomicInteger attempts = new AtomicInteger();
        final Consumer<SessionSnapshot> failingWrite = snapshot -> {
            if (attempts.incrementAndGet() == 1) {
                // An Error, not an Exception: a driver's NoClassDefFoundError, an assertion inside the store, an OOM
                // while snapshotting. The writer thread must survive it exactly as it survives a failed write —
                // otherwise every later flush() waits out the drain timeout on a barrier nobody is left to lift.
                throw new AssertionError("store blew up");
            }
        };

        memory.addUserMessage("m1");
        mailbox.checkpoint(memory, failingWrite);
        mailbox.flush(id);

        memory.addUserMessage("m2");
        mailbox.checkpoint(memory, failingWrite);
        mailbox.flush(id);

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("close writes what is still pending")
    void closeWritesPendingCheckpoints() {
        mailbox = SessionCheckpointMailbox.background();
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), "sys");
        final List<SessionSnapshot> writes = new CopyOnWriteArrayList<>();

        memory.addUserMessage("hi");
        mailbox.checkpoint(memory, writes::add);
        mailbox.close();

        assertThat(writes).hasSize(1);
    }

    @Test
    @DisplayName("checkpoint after close is ignored, and close is idempotent")
    void checkpointAfterCloseIsIgnored() {
        mailbox = SessionCheckpointMailbox.background();
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), "sys");
        final List<SessionSnapshot> writes = new CopyOnWriteArrayList<>();

        mailbox.close();
        mailbox.close();
        memory.addUserMessage("hi");
        mailbox.checkpoint(memory, writes::add);

        assertThat(writes).isEmpty();
        assertThat(mailbox.pendingCheckpointSessionIds())
                .as("a checkpoint raised after close must be rejected outright, not queued for a writer that is gone")
                .isEmpty();
    }

    @Test
    @DisplayName("a close that abandons a stuck writer keeps the checkpoints it never reached")
    void closeKeepsWhatTheAbandonedDrainNeverReached() throws Exception {
        mailbox = SessionCheckpointMailbox.background();
        final SessionId id = SessionId.generate();
        final TranscriptBuffer memory = new TranscriptBuffer(id, "sys");
        final Gate gate = new Gate();
        final List<SessionSnapshot> writes = new CopyOnWriteArrayList<>();
        final Consumer<SessionSnapshot> write = gate.firstCallBlocks(writes);

        memory.addUserMessage("m1");
        mailbox.checkpoint(memory, write);
        assertThat(gate.entered.await(2, TimeUnit.SECONDS)).isTrue();

        // A second checkpoint is queued behind the parked write, so close() cannot drain it. This test therefore pays
        // the full drain timeout inside close() — that timeout is exactly what it is here to pin down.
        memory.addUserMessage("m2");
        mailbox.checkpoint(memory, write);
        mailbox.close();

        assertThat(mailbox.pendingCheckpointSessionIds())
                .as("work the abandoned drain never reached must stay visible instead of being dropped")
                .containsExactly(id);

        // Flushing after close must not queue a barrier behind the stuck writer and wait it out.
        final long startNanos = System.nanoTime();
        mailbox.flush(id);
        assertThat(Duration.ofNanos(System.nanoTime() - startNanos)).isLessThan(Duration.ofSeconds(2));

        // And once the store call returns, the abandoned writer still writes the checkpoint it never reached.
        gate.release();
        await().atMost(Duration.ofSeconds(2)).until(() -> writes.size() == 2);
        assertThat(writes.get(1).getConversationHistory()).hasSize(2);
        // Awaited rather than asserted outright: the snapshot lands in `writes` inside the callback, and the session
        // stops counting as in flight one statement later, when that callback returns.
        await().atMost(Duration.ofSeconds(2)).until(() -> mailbox.pendingCheckpointSessionIds().isEmpty());
    }

    @Test
    @DisplayName("a close that abandons a stuck writer names the session it is stuck inside")
    void closeNamesTheSessionWhoseWriteIsInFlight() throws Exception {
        mailbox = SessionCheckpointMailbox.background();
        final SessionId id = SessionId.generate();
        final TranscriptBuffer memory = new TranscriptBuffer(id, "sys");
        final Gate gate = new Gate();
        final List<SessionSnapshot> writes = new CopyOnWriteArrayList<>();

        memory.addUserMessage("m1");
        mailbox.checkpoint(memory, gate.firstCallBlocks(writes));
        assertThat(gate.entered.await(2, TimeUnit.SECONDS)).isTrue();

        // Nothing is queued behind the parked write — deliberately, and this is the whole difference from the test
        // above. The single checkpoint that exists is the one being written, and writeSlot cleared its queued mark
        // before entering the store, so a set built from queued slots alone reports no losses for a writer that is
        // demonstrably stuck holding one. This close() pays the full drain timeout.
        mailbox.close();

        assertThat(mailbox.pendingCheckpointSessionIds())
                .as("the write the abandoned thread is parked inside is the one loss we already know about")
                .containsExactly(id);

        gate.release();
    }

    @Test
    @DisplayName("a disabled mailbox never writes")
    void disabledMailboxNeverWrites() {
        mailbox = SessionCheckpointMailbox.disabled();
        final SessionId id = SessionId.generate();
        final TranscriptBuffer memory = new TranscriptBuffer(id, "sys");
        final List<SessionSnapshot> writes = new CopyOnWriteArrayList<>();

        memory.addUserMessage("hi");
        mailbox.checkpoint(memory, writes::add);
        mailbox.flush(id);
        mailbox.close();

        assertThat(writes).isEmpty();
        assertThat(mailbox.pendingCheckpointSessionIds()).isEmpty();
    }

    @Test
    void nullArgumentsAreRejected() {
        mailbox = SessionCheckpointMailbox.disabled();
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate(), "sys");

        assertThatThrownBy(() -> mailbox.checkpoint(null, snapshot -> {
        })).isInstanceOf(NullPointerException.class).hasMessageContaining("memory");
        assertThatThrownBy(() -> mailbox.checkpoint(memory, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("write");
        assertThatThrownBy(() -> mailbox.flush(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
    }

    /**
     * Parks the writer thread inside its first write so a test can arrange mutations that are provably concurrent with
     * a write in flight.
     */
    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch mayProceed = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        Consumer<SessionSnapshot> firstCallBlocks(List<SessionSnapshot> writes) {
            return snapshot -> {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        mayProceed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                writes.add(snapshot);
            };
        }

        void release() {
            mayProceed.countDown();
        }
    }
}
