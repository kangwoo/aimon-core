package at.aimon.core.subagent.task.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionRewindPoint;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.llm.Message;

/**
 * Pins the two persisted formats: both are read, version 1 is written unless the write format or the snapshot asks for
 * version 2, and a state read from version 2 is never written back as version 1 (the sticky upgrade).
 */
class JsonSessionSnapshotCodecLogFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SessionId ID = SessionId.of("s-fmt");
    private static final Message ASK = Message.user("ask");
    private static final Message ANSWER = Message.assistant("answer");
    private static final Message REMINDER = Message.user("<system-reminder>ctx</system-reminder>");

    private final JsonSessionSnapshotCodec v1Codec = new JsonSessionSnapshotCodec();
    private final JsonSessionSnapshotCodec v2Codec = new JsonSessionSnapshotCodec(SessionLogFormat.V2);

    /** A state version 1 cannot express: a raised floor, a gap from a rewind, a synthetic entry, a seq rewind point. */
    private static SessionLogState richState(SessionLogFormat format) {
        return SessionLogState.builder()
                .entries(List.of(SessionLogEntry.of(4, REMINDER, LogOrigin.SYNTHETIC),
                        SessionLogEntry.of(5, ASK, LogOrigin.CONVERSATION),
                        SessionLogEntry.of(8, ANSWER, LogOrigin.CONVERSATION)))
                .floorSeq(3).nextSeq(9).rewindPoint(SessionRewindPoint.of(8, TextInput.of("retry me"))).format(format)
                .build();
    }

    private static JsonNode tree(String encoded) throws Exception {
        return MAPPER.readTree(encoded);
    }

    @Test
    void aVersionOneStateIsWrittenAsVersionOneByDefault() throws Exception {
        final String encoded = v1Codec.encode(SessionSnapshot.of(ID, "sys", List.of(ASK, ANSWER)));

        assertThat(tree(encoded).get("version").asInt()).isEqualTo(1);
        assertThat(tree(encoded).has("messages")).isTrue();
        assertThat(tree(encoded).has("entries")).isFalse();
    }

    @Test
    void aVersionOneDocumentReadsAsSeqsZeroToNMinusOneAllConversation() {
        final SessionSnapshot decoded = v1Codec.decode(v1Codec.encode(
                SessionSnapshot.of(ID, "sys", List.of(ASK, ANSWER), SessionRewindPoint.of(1, TextInput.of("again")))));

        final SessionLogState state = decoded.getLogState();
        assertThat(state.getFormat()).isEqualTo(SessionLogFormat.V1);
        assertThat(state.getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(0L, 1L);
        assertThat(state.getEntries()).extracting(SessionLogEntry::getOrigin).containsOnly(LogOrigin.CONVERSATION);
        assertThat(state.getRewindPoint().orElseThrow().getSeq()).isEqualTo(1);
    }

    @Test
    void theVersionTwoSwitchWritesTheWholeStateAndItRoundTrips() throws Exception {
        final SessionSnapshot snapshot = SessionSnapshot.fromLog(ID, "sys", richState(SessionLogFormat.V1));

        final String encoded = v2Codec.encode(snapshot);
        final SessionSnapshot decoded = v1Codec.decode(encoded);

        assertThat(tree(encoded).get("version").asInt()).isEqualTo(2);
        assertThat(decoded.getSystemPrompt()).isEqualTo("sys");
        assertThat(decoded.getLogState()).isEqualTo(richState(SessionLogFormat.V2));
    }

    /**
     * The sticky upgrade: a node still writing version 1 must not rewrite a version-2 record as version 1, which would
     * lose the seqs, origins and floor and restart seqs at 0 on the next read.
     */
    @Test
    void aStateReadFromVersionTwoIsWrittenAsVersionTwoByAVersionOneCodec() throws Exception {
        final String written = v2Codec.encode(SessionSnapshot.fromLog(ID, "sys", richState(SessionLogFormat.V1)));
        final SessionSnapshot read = v1Codec.decode(written);

        final String rewritten = v1Codec.encode(read);

        assertThat(tree(rewritten).get("version").asInt()).isEqualTo(2);
        assertThat(v1Codec.decode(rewritten).getLogState()).isEqualTo(read.getLogState());
    }

    /**
     * Writing version 1 converts at the boundary: version 1 reads positions back as seqs, so the rewind point is stored
     * as the number of carried entries before it.
     */
    @Test
    void writingVersionOneStoresTheRewindPointAsAPosition() throws Exception {
        final String encoded = v1Codec.encode(SessionSnapshot.fromLog(ID, "sys", richState(SessionLogFormat.V1)));

        assertThat(tree(encoded).get("rewindPoint").get("messageCount").asInt()).isEqualTo(2);
        final SessionSnapshot decoded = v1Codec.decode(encoded);
        assertThat(decoded.getConversationHistory()).containsExactly(REMINDER, ASK, ANSWER);
        assertThat(decoded.getLogState().rewind().getMessages()).containsExactly(REMINDER, ASK);
    }

    @Test
    void aVersionTwoDocumentWithSeqsOutOfOrderIsRefused() throws Exception {
        final ObjectNode root = (ObjectNode) tree(
                v2Codec.encode(SessionSnapshot.fromLog(ID, "sys", richState(SessionLogFormat.V2))));
        ((ObjectNode) root.get("entries").get(1)).put("seq", 4);

        assertThatThrownBy(() -> v1Codec.decode(root.toString())).isInstanceOf(SessionSnapshotCodecException.class)
                .hasMessageContaining("strictly increase");
    }

    @Test
    void aVersionTwoRewindPointOutsideTheLogIsRefused() throws Exception {
        final ObjectNode root = (ObjectNode) tree(
                v2Codec.encode(SessionSnapshot.fromLog(ID, "sys", richState(SessionLogFormat.V2))));
        ((ObjectNode) root.get("rewindPoint")).put("seq", 10);

        assertThatThrownBy(() -> v1Codec.decode(root.toString())).isInstanceOf(SessionSnapshotCodecException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void aVersionTwoDocumentWithAnUnknownOriginIsRefused() throws Exception {
        final ObjectNode root = (ObjectNode) tree(
                v2Codec.encode(SessionSnapshot.fromLog(ID, "sys", richState(SessionLogFormat.V2))));
        ((ObjectNode) root.get("entries").get(0)).put("origin", "TELEPATHY");

        assertThatThrownBy(() -> v1Codec.decode(root.toString())).isInstanceOf(SessionSnapshotCodecException.class)
                .hasMessageContaining("TELEPATHY");
    }

    @Test
    void aVersionTwoDocumentWithoutNextSeqIsRefused() throws Exception {
        final ObjectNode root = (ObjectNode) tree(
                v2Codec.encode(SessionSnapshot.fromLog(ID, "sys", richState(SessionLogFormat.V2))));
        root.remove("nextSeq");

        assertThatThrownBy(() -> v1Codec.decode(root.toString())).isInstanceOf(SessionSnapshotCodecException.class)
                .hasMessageContaining("nextSeq");
    }

    @Test
    void anUnknownVersionIsStillRefused() throws Exception {
        final ObjectNode root = (ObjectNode) tree(v1Codec.encode(SessionSnapshot.of(ID, "sys", List.of(ASK))));
        root.put("version", 3);

        assertThatThrownBy(() -> v1Codec.decode(root.toString())).isInstanceOf(SessionSnapshotCodecException.class)
                .hasMessageContaining("version: 3");
    }
}
