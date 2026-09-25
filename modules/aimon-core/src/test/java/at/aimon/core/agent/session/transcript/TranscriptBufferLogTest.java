package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.Message;

/**
 * Pins the buffer as a seq-addressed log: origins, seqs that survive a rewind and {@code /clear}, the seq-based rewind
 * point and ingest mark, and the log travelling whole through {@link TranscriptBuffer#toSnapshot()} and
 * {@link TranscriptBuffer#fromSnapshot(SessionSnapshot)}.
 */
class TranscriptBufferLogTest {

    private static final SessionId ID = SessionId.of("s-log");
    private static final Message REMINDER = Message.user("<system-reminder>ctx</system-reminder>");
    private static final Message ASK = Message.user("ask");
    private static final Message ANSWER = Message.assistant("answer");

    @Test
    void plainAppendsAreConversationAndTheOriginOverloadIsHonoured() {
        final TranscriptBuffer buffer = new TranscriptBuffer(ID, "sys");
        buffer.addMessage(REMINDER, LogOrigin.SYNTHETIC);
        buffer.addMessage(ASK);
        buffer.addAssistantMessage("answer");

        assertThat(buffer.getLogState().getEntries()).extracting(SessionLogEntry::getOrigin)
                .containsExactly(LogOrigin.SYNTHETIC, LogOrigin.CONVERSATION, LogOrigin.CONVERSATION);
        assertThat(buffer.getLogState().getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(0L, 1L, 2L);
        assertThat(buffer.getNextSeq()).isEqualTo(3);
    }

    @Test
    void hasConversationIgnoresSyntheticUserMessages() {
        final TranscriptBuffer buffer = new TranscriptBuffer(ID, "sys");
        buffer.addMessage(REMINDER, LogOrigin.SYNTHETIC);

        assertThat(buffer.countUserMessages()).isEqualTo(1);
        assertThat(buffer.hasConversation()).isFalse();

        buffer.addMessage(ASK);
        assertThat(buffer.hasConversation()).isTrue();
    }

    @Test
    void theRewindPointIsTheNextSeqAndRewindingCutsFromIt() {
        final TranscriptBuffer buffer = new TranscriptBuffer(ID, "sys", List.of(ASK, ANSWER));
        buffer.beginTurn(TextInput.of("again"), SubmitOptions.empty());
        buffer.addMessage(REMINDER, LogOrigin.SYNTHETIC);
        buffer.addMessage(ASK);

        assertThat(buffer.getRewindPoint().orElseThrow().getSeq()).isEqualTo(2);
        assertThat(buffer.rewind()).isPresent();
        assertThat(buffer.getMessages()).containsExactly(ASK, ANSWER);
        assertThat(buffer.getNextSeq()).isEqualTo(4);

        buffer.addMessage(ASK);
        assertThat(buffer.getLogState().getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(0L, 1L, 4L);
    }

    @Test
    void clearingRaisesTheFloorAndKeepsCountingSeqs() {
        final TranscriptBuffer buffer = new TranscriptBuffer(ID, "sys", List.of(ASK, ANSWER));
        buffer.clear();
        buffer.addMessage(ASK);

        final SessionLogState state = buffer.getLogState();
        assertThat(state.getFloorSeq()).isEqualTo(2);
        assertThat(state.getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(2L);
        assertThat(buffer.liveEntryCount()).isEqualTo(1);
    }

    /**
     * The in-place (version-1) rewrite: fresh seqs from {@code nextSeq}, the floor raised past everything replaced,
     * and origins kept only for entries that are carried over as the same instance.
     */
    @Test
    void replaceWithRenumbersFromNextSeqAndKeepsTheOriginOfCarriedOverInstances() {
        final TranscriptBuffer buffer = new TranscriptBuffer(ID, "sys");
        buffer.addMessage(REMINDER, LogOrigin.SYNTHETIC);
        buffer.addMessage(ASK);
        buffer.addMessage(ANSWER);
        final Message summary = Message.user("summary");

        buffer.replaceWith(List.of(summary, REMINDER, ANSWER));

        final SessionLogState state = buffer.getLogState();
        assertThat(state.getFloorSeq()).isEqualTo(3);
        assertThat(state.getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(3L, 4L, 5L);
        assertThat(state.getEntries()).extracting(SessionLogEntry::getOrigin).containsExactly(LogOrigin.CONVERSATION,
                LogOrigin.SYNTHETIC, LogOrigin.CONVERSATION);
    }

    @Test
    void replaceMessageAtKeepsTheEntrysSeqAndOrigin() {
        final TranscriptBuffer buffer = new TranscriptBuffer(ID, "sys");
        buffer.addMessage(ASK);
        buffer.addMessage(REMINDER, LogOrigin.SYNTHETIC);

        buffer.replaceMessageAt(1, Message.user("scrubbed"));

        assertThat(buffer.getLogState().getEntries().get(1))
                .isEqualTo(SessionLogEntry.of(1, Message.user("scrubbed"), LogOrigin.SYNTHETIC));
    }

    @Test
    void theIngestMarkIsASeqAndClearingDropsIt() {
        final TranscriptBuffer buffer = new TranscriptBuffer(ID, "sys", List.of(ASK, ANSWER));
        buffer.markIngestPoint();
        buffer.addMessage(ASK);
        buffer.addMessage(ANSWER);

        assertThat(buffer.messagesSinceIngestMark()).containsExactly(ASK, ANSWER);

        buffer.clear();
        assertThat(buffer.messagesSinceIngestMark()).isEmpty();
    }

    @Test
    void theLogTravelsWholeThroughASnapshot() {
        final TranscriptBuffer buffer = new TranscriptBuffer(ID, "sys", List.of(ASK));
        buffer.clear();
        buffer.addMessage(REMINDER, LogOrigin.SYNTHETIC);
        buffer.beginTurn(TextInput.of("q"), SubmitOptions.empty());
        buffer.addMessage(ASK);
        buffer.requireFormat(SessionLogFormat.V2);

        final SessionSnapshot snapshot = buffer.toSnapshot();
        final TranscriptBuffer restored = TranscriptBuffer.fromSnapshot(snapshot);

        assertThat(restored.getLogState()).isEqualTo(buffer.getLogState());
        assertThat(restored.getFormat()).isEqualTo(SessionLogFormat.V2);
        assertThat(restored.getNextSeq()).isEqualTo(3);
        assertThat(restored.getRewindPoint().orElseThrow().getSeq()).isEqualTo(2);
    }

    @Test
    void requireFormatNeverLowersAndDoesNotMarkTheBufferDirty() {
        final TranscriptBuffer buffer = new TranscriptBuffer(ID, "sys");
        final long version = buffer.getVersion();

        buffer.requireFormat(SessionLogFormat.V2);
        buffer.requireFormat(SessionLogFormat.V1);

        assertThat(buffer.getFormat()).isEqualTo(SessionLogFormat.V2);
        assertThat(buffer.getVersion()).isEqualTo(version);
    }
}
