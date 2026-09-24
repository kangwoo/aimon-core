package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.llm.Message;

/**
 * The storage half of sealing as {@link DefaultTranscriptManager} runs it: seal before the turn-end save, delete what
 * {@code /clear} cut loose after it, collect orphans past the grace period (session-log §5.3, §5.4, §6.2).
 */
class DefaultTranscriptManagerSealingTest {

    private static final SessionId SESSION = SessionId.of("manager-seal");
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    private final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
    private final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private DefaultTranscriptManager manager() {
        return new DefaultTranscriptManager(records, SessionCheckpointMailbox.disabled(), SessionLogFormat.V2,
                SessionLogStorage.builder(segments).minSealTokens(0).clock(clock).build());
    }

    /** A turn that compacted everything but its last answer. */
    private static TranscriptBuffer compactedTurn(DefaultTranscriptManager manager) {
        final TranscriptBuffer buffer = manager.initialize(SESSION, "system");
        buffer.addUserMessage("q1");
        buffer.addAssistantMessage("a1");
        buffer.addUserMessage("q2");
        buffer.addAssistantMessage("a2");
        buffer.summarizeView(
                SummarySpan.builder().fromSeq(0).toSeq(3).summaryText("s").boundaryId("b").trigger("AUTO").build());
        return buffer;
    }

    @Test
    void theTurnEndSaveSealsBeforeItWrites() {
        final DefaultTranscriptManager manager = manager();

        manager.saveSilently(compactedTurn(manager));

        final SessionLogState stored = records.load(SESSION).orElseThrow().getLogState();
        assertThat(stored.getEntries()).extracting(entry -> entry.getMessage().getContent()).containsExactly("a2");
        assertThat(stored.getManifest()).hasSize(1);
        assertThat(segments.list(SESSION)).hasSize(1);
        assertThat(manager.initialize(SESSION, "system").liveEntryCount()).isEqualTo(4);
    }

    @Test
    void theLogReaderSeesTheWholeLog() {
        final DefaultTranscriptManager manager = manager();
        manager.saveSilently(compactedTurn(manager));

        final SessionLogReader reader = manager.getLogReader().orElseThrow();

        assertThat(reader.read(SESSION, 0, Long.MAX_VALUE).getEntries()).hasSize(4);
    }

    @Test
    void clearDeletesTheSealedSegmentsAfterTheClearedRecordIsSaved() {
        final DefaultTranscriptManager manager = manager();
        manager.saveSilently(compactedTurn(manager));

        final TranscriptBuffer next = manager.initialize(SESSION, "system");
        next.clear();
        manager.saveSilently(next);

        assertThat(records.load(SESSION).orElseThrow().getLogState().getManifest()).isEmpty();
        assertThat(segments.list(SESSION)).isEmpty();
    }

    @Test
    void clearKeepsTheSegmentsWhenTheSaveFails() {
        final DefaultTranscriptManager manager = manager();
        manager.saveSilently(compactedTurn(manager));
        final InMemorySessionRecordStore failing = new InMemorySessionRecordStore() {
            @Override
            public void mergeFromSnapshot(SessionSnapshot snapshot) {
                throw new IllegalStateException("down");
            }
        };
        final DefaultTranscriptManager broken = new DefaultTranscriptManager(failing,
                SessionCheckpointMailbox.disabled(), SessionLogFormat.V2,
                SessionLogStorage.builder(segments).minSealTokens(0).clock(clock).build());
        final TranscriptBuffer next = manager.initialize(SESSION, "system");
        next.clear();

        broken.saveSilently(next);

        assertThat(segments.list(SESSION)).as("the stored record still names it").hasSize(1);
        assertThat(next.pendingSegmentDeletions()).hasSize(1);
    }

    @Test
    void orphansAreCollectedOnlyPastTheGracePeriod() {
        final DefaultTranscriptManager manager = manager();
        manager.saveSilently(compactedTurn(manager));
        final SegmentId orphan = SegmentId.generate();
        segments.put(SessionLogSegment.builder().sessionId(SESSION).id(orphan).fromSeq(0).toSeq(1).entryCount(1)
                .payload("late write").createdAt(NOW).build());

        manager.saveSilently(manager.initialize(SESSION, "system"));
        assertThat(segments.list(SESSION)).extracting(SegmentInfo::getId).contains(orphan);

        now.set(NOW.plus(Duration.ofHours(2)));
        manager.saveSilently(manager.initialize(SESSION, "system"));
        assertThat(segments.list(SESSION)).extracting(SegmentInfo::getId).doesNotContain(orphan).hasSize(1);
    }

    @Test
    void theExecutorSealPointSealsMidTurn() {
        final DefaultTranscriptManager manager = manager();
        final TranscriptBuffer buffer = compactedTurn(manager);

        manager.seal(buffer);

        assertThat(buffer.getManifest()).hasSize(1);
        assertThat(records.load(SESSION)).as("sealing does not write the record").isEmpty();
    }

    @Test
    void withoutStorageNothingIsSealed() {
        final DefaultTranscriptManager manager = new DefaultTranscriptManager(records,
                SessionCheckpointMailbox.disabled(), SessionLogFormat.V2);

        manager.saveSilently(compactedTurn(manager));

        assertThat(records.load(SESSION).orElseThrow().getLogState().getEntries()).hasSize(4);
        assertThat(manager.getLogReader()).isEmpty();
    }

    @Test
    void aZeroGracePeriodIsRefused() {
        assertThatThrownBy(() -> SessionLogStorage.builder(segments).segmentGcGrace(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SessionLogGarbageCollector(SessionLogStorage.builder(segments).build()).collect(SESSION,
                SessionLogState.empty())).isZero();
    }

    @Test
    void messagesSealedMidTurnReachTheIngestDelta() {
        final DefaultTranscriptManager manager = manager();
        final TranscriptBuffer buffer = manager.initialize(SESSION, "system");
        buffer.markIngestPoint();
        buffer.addMessage(Message.user("q1"));
        buffer.addMessage(Message.assistant("a1"));
        buffer.addMessage(Message.user("q2"));
        buffer.summarizeView(
                SummarySpan.builder().fromSeq(0).toSeq(2).summaryText("s").boundaryId("b").trigger("AUTO").build());

        manager.seal(buffer);

        assertThat(buffer.getMessages()).hasSize(1);
        assertThat(buffer.messagesSinceIngestMark()).extracting(Message::getContent).containsExactly("q1", "a1", "q2");
    }
}
