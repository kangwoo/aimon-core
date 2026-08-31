package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecord;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.llm.Message;

/**
 * Verifies that {@link DefaultTranscriptManager#save(TranscriptBuffer)} preserves the persisted
 * {@code compactionFailureCount} on the existing repository record. The in-memory {@link TranscriptBuffer} does not
 * track the counter itself, so a naïve save would silently zero it out and clobber what the compaction guard's failure
 * store recorded.
 *
 * <p>
 * The single mechanism that keeps it alive is {@link SessionRecordStore#mergeFromSnapshot} preserving it from the
 * record — never the snapshot carrying it. The snapshot never could: {@link TranscriptBuffer#toSnapshot()} has no
 * counter to put there.
 */
class DefaultTranscriptManagerCompactionCounterTest {

    @Test
    void savePreservesPersistedCompactionFailureCounter() {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final SessionId id = SessionId.generate();
        repo.save(new SessionRecord(id, "sys", List.of(Message.user("seed")), 5));

        final DefaultTranscriptManager manager = new DefaultTranscriptManager(repo);
        final TranscriptBuffer memory = manager.initialize(id, "sys");
        memory.addUserMessage("follow-up");

        manager.save(memory);

        final SessionRecordView reloaded = repo.load(id).orElseThrow();
        assertThat(reloaded.getCompactionFailureCount()).isEqualTo(5);
        assertThat(reloaded.getMessages()).extracting(Message::getContent).contains("seed", "follow-up");
    }

    @Test
    void saveOnNewConversationKeepsCounterAtZero() {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final DefaultTranscriptManager manager = new DefaultTranscriptManager(repo);
        final SessionId id = SessionId.generate();

        final TranscriptBuffer memory = manager.initialize(id, "sys");
        memory.addUserMessage("hi");
        manager.save(memory);

        assertThat(repo.load(id).orElseThrow().getCompactionFailureCount()).isZero();
    }
}
