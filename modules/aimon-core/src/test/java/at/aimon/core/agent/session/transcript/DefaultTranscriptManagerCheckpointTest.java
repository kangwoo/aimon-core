package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;

/**
 * Verifies how {@link DefaultTranscriptManager} uses a {@link SessionCheckpointMailbox}: an initialized memory
 * checkpoints on mutation, and both end-of-turn write paths drain the mailbox first so the authoritative write is the
 * last one to reach the repository.
 */
class DefaultTranscriptManagerCheckpointTest {

    private SessionCheckpointMailbox mailbox;

    @AfterEach
    void tearDown() {
        if (mailbox != null) {
            mailbox.close();
        }
    }

    @Test
    @DisplayName("a mutation is persisted before the turn ends")
    void mutationIsPersistedMidTurn() {
        mailbox = SessionCheckpointMailbox.background();
        final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
        final DefaultTranscriptManager manager = new DefaultTranscriptManager(repository, mailbox);
        final SessionId id = SessionId.generate();

        final TranscriptBuffer memory = manager.initialize(id, "sys");
        memory.addUserMessage("hi");

        // No save() / saveSilently() — the mailbox alone must get this into the repository.
        await().atMost(Duration.ofSeconds(2)).until(() -> repository.exists(id));
        assertThat(repository.load(id)).isPresent();
    }

    @Test
    @DisplayName("saveSilently drains an in-flight checkpoint before its own write")
    void saveSilentlyDrainsBeforeTheAuthoritativeWrite() throws Exception {
        assertDrainsBeforeAuthoritativeWrite(DefaultTranscriptManager::saveSilently);
    }

    @Test
    @DisplayName("save drains an in-flight checkpoint before its own write")
    void saveDrainsBeforeTheAuthoritativeWrite() throws Exception {
        // The predecessor (ConversationFlusher) only drained on the saveSilently path, leaving save() racing a late
        // background write. Both paths drain now.
        assertDrainsBeforeAuthoritativeWrite(DefaultTranscriptManager::save);
    }

    @Test
    @DisplayName("without a mailbox the repository is written only at end of turn")
    void defaultConstructorPersistsOnlyAtEndOfTurn() {
        final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
        final DefaultTranscriptManager manager = new DefaultTranscriptManager(repository);
        final SessionId id = SessionId.generate();

        final TranscriptBuffer memory = manager.initialize(id, "sys");
        memory.addUserMessage("hi");
        assertThat(repository.exists(id)).isFalse();

        manager.saveSilently(memory);
        assertThat(repository.exists(id)).isTrue();
    }

    /**
     * Parks the mid-turn checkpoint inside the repository, then drives {@code endOfTurn} from another thread and
     * asserts it cannot reach the repository until the parked checkpoint has finished writing.
     */
    private void assertDrainsBeforeAuthoritativeWrite(EndOfTurnWrite endOfTurn) throws Exception {
        mailbox = SessionCheckpointMailbox.background();
        final GatedRepository repository = new GatedRepository();
        final DefaultTranscriptManager manager = new DefaultTranscriptManager(repository, mailbox);
        final SessionId id = SessionId.generate();

        final TranscriptBuffer memory = manager.initialize(id, "sys");
        memory.addUserMessage("hi");
        assertThat(repository.firstWriteEntered.await(2, TimeUnit.SECONDS)).isTrue();

        final Thread driver = new Thread(() -> endOfTurn.write(manager, memory), "end-of-turn-driver");
        driver.start();

        // The drain parks the driver on the mailbox's barrier latch (a timed await, hence TIMED_WAITING). Waiting for
        // that state is what makes "the authoritative write has not happened yet" an assertion rather than a guess.
        await().atMost(Duration.ofSeconds(2)).until(() -> driver.getState() == Thread.State.TIMED_WAITING);
        assertThat(repository.writes).as("the authoritative write must not race past the in-flight checkpoint")
                .isEmpty();

        repository.releaseFirstWrite();
        driver.join(2_000);

        assertThat(driver.isAlive()).isFalse();
        assertThat(repository.writes).containsExactly("checkpoint", "end-of-turn");
    }

    /**
     * Signature shared by {@link DefaultTranscriptManager#save} and {@link DefaultTranscriptManager#saveSilently},
     * so one assertion body can drive either.
     */
    private interface EndOfTurnWrite {
        void write(DefaultTranscriptManager manager, TranscriptBuffer memory);
    }

    /**
     * Records every write in order and parks the first one (the mid-turn checkpoint) until released, so the end-of-turn
     * write has something concrete to race against.
     */
    private static final class GatedRepository extends InMemorySessionRecordStore {
        final List<String> writes = new CopyOnWriteArrayList<>();
        final CountDownLatch firstWriteEntered = new CountDownLatch(1);
        private final CountDownLatch firstWriteMayProceed = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void mergeFromSnapshot(SessionSnapshot snapshot) {
            if (calls.incrementAndGet() == 1) {
                firstWriteEntered.countDown();
                try {
                    firstWriteMayProceed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                writes.add("checkpoint");
            } else {
                writes.add("end-of-turn");
            }
            super.mergeFromSnapshot(snapshot);
        }

        void releaseFirstWrite() {
            firstWriteMayProceed.countDown();
        }
    }
}
