package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionRewindPoint;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.llm.Message;

/**
 * Pins that the session log crosses every hop of the load and save chains whole (session-log §7.2): backend string →
 * {@link SessionRecordCodec} → {@link StoredSessionRecord} → {@link SessionRecordView} → {@link SessionSnapshot} →
 * {@link TranscriptBuffer}, and back through {@link SessionRecord} to the codec. A value picked apart at any one hop
 * would come out of the far end without its seqs, origins, floor or format.
 */
class SessionLogStateChainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SessionId ID = SessionId.of("s-chain");
    private static final Message REMINDER = Message.user("<system-reminder>ctx</system-reminder>");
    private static final Message ASK = Message.user("ask");
    private static final Message ANSWER = Message.assistant("answer");

    private static SessionLogState v2State() {
        return SessionLogState.builder()
                .entries(List.of(SessionLogEntry.of(2, REMINDER, LogOrigin.SYNTHETIC),
                        SessionLogEntry.of(3, ASK, LogOrigin.CONVERSATION)))
                .floorSeq(2).nextSeq(5).rewindPoint(SessionRewindPoint.of(3, TextInput.of("ask")))
                .format(SessionLogFormat.V2).build();
    }

    private static int version(String encoded) throws Exception {
        return MAPPER.readTree(encoded).get("version").asInt();
    }

    @Test
    void aVersionTwoRecordSurvivesTheFullLoadAndSaveChainAndIsWrittenBackAsVersionTwo() throws Exception {
        final String stored = SessionRecordCodec.encodeTranscript(SessionSnapshot.fromLog(ID, "sys", v2State()));

        // Load chain, the way a distributed backend runs it.
        final SessionRecordView view = StoredSessionRecord.builder(ID)
                .transcript(SessionRecordCodec.decodeTranscript(ID, stored)).build();
        final TranscriptBuffer buffer = TranscriptBuffer.fromSnapshot(SessionSnapshot.from(view));
        buffer.addMessage(ANSWER);

        // Save chain.
        final SessionRecord record = SessionRecord.fromSnapshot(buffer.toSnapshot());
        final String rewritten = SessionRecordCodec.encodeTranscript(record);

        assertThat(version(rewritten)).isEqualTo(2);
        final SessionLogState reread = SessionRecordCodec.decodeTranscript(ID, rewritten).getLogState();
        assertThat(reread.getFloorSeq()).isEqualTo(2);
        assertThat(reread.getNextSeq()).isEqualTo(6);
        assertThat(reread.getEntries()).containsExactly(SessionLogEntry.of(2, REMINDER, LogOrigin.SYNTHETIC),
                SessionLogEntry.of(3, ASK, LogOrigin.CONVERSATION),
                SessionLogEntry.of(5, ANSWER, LogOrigin.CONVERSATION));
        assertThat(reread.getRewindPoint().orElseThrow().getSeq()).isEqualTo(3);
    }

    @Test
    void copyingAViewKeepsTheWholeLog() {
        final SessionRecordView view = StoredSessionRecord.builder(ID)
                .transcript(SessionSnapshot.fromLog(ID, "sys", v2State())).build();

        assertThat(SessionRecord.copyOf(view).getLogState()).isEqualTo(v2State());
    }

    @Test
    void theInMemoryStoreKeepsTheWholeLog() {
        final InMemorySessionRecordStore store = new InMemorySessionRecordStore();

        store.mergeFromSnapshot(SessionSnapshot.fromLog(ID, "sys", v2State()));

        assertThat(store.load(ID).orElseThrow().getLogState()).isEqualTo(v2State());
    }

    @Test
    void aViewThatOnlyKnowsMessagesMigratesThemAsVersionOne() {
        final SessionRecordView legacy = new SessionRecordView() {
            @Override
            public SessionId getId() {
                return ID;
            }

            @Override
            public String getSystemPrompt() {
                return "sys";
            }

            @Override
            public List<Message> getMessages() {
                return List.of(ASK, ANSWER);
            }

            @Override
            public Optional<String> getAgentRef() {
                return Optional.empty();
            }

            @Override
            public int getCompactionFailureCount() {
                return 0;
            }
        };

        assertThat(SessionSnapshot.from(legacy).getLogState())
                .isEqualTo(SessionLogState.ofMessages(List.of(ASK, ANSWER)));
    }

    @Test
    void theTranscriptManagerWritesVersionOneByDefault() throws Exception {
        final InMemorySessionRecordStore store = new InMemorySessionRecordStore();
        final DefaultTranscriptManager manager = new DefaultTranscriptManager(store);

        final TranscriptBuffer buffer = manager.initialize(ID, "sys");
        buffer.addMessage(ASK);
        manager.save(buffer);

        assertThat(manager.getWriteFormat()).isEqualTo(SessionLogFormat.V1);
        assertThat(version(SessionRecordCodec.encodeTranscript(store.load(ID).orElseThrow()))).isEqualTo(1);
    }

    @Test
    void theTranscriptManagerWriteSwitchUpgradesNewAndLoadedRecords() throws Exception {
        final InMemorySessionRecordStore store = new InMemorySessionRecordStore();
        store.mergeFromSnapshot(SessionSnapshot.of(ID, "sys", List.of(ASK)));
        final DefaultTranscriptManager manager = new DefaultTranscriptManager(store,
                SessionCheckpointMailbox.disabled(), SessionLogFormat.V2);

        final TranscriptBuffer buffer = manager.initialize(ID, "sys");
        buffer.addMessage(ANSWER);
        manager.save(buffer);

        assertThat(version(SessionRecordCodec.encodeTranscript(store.load(ID).orElseThrow()))).isEqualTo(2);
        final TranscriptBuffer fresh = manager.initialize(SessionId.of("s-new"), "sys");
        assertThat(fresh.getFormat()).isEqualTo(SessionLogFormat.V2);
    }

    /** The sticky upgrade on the manager side: a node set to version 1 keeps a version-2 record at version 2. */
    @Test
    void aVersionOneManagerKeepsAVersionTwoRecordAtVersionTwo() {
        final InMemorySessionRecordStore store = new InMemorySessionRecordStore();
        store.mergeFromSnapshot(SessionSnapshot.fromLog(ID, "sys", v2State()));
        final DefaultTranscriptManager manager = new DefaultTranscriptManager(store);

        final TranscriptBuffer buffer = manager.initialize(ID, "sys");
        manager.save(buffer);

        assertThat(store.load(ID).orElseThrow().getLogState().getFormat()).isEqualTo(SessionLogFormat.V2);
    }
}
