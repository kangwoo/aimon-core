package at.aimon.core.subagent.task.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogManifestEntry;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.llm.Message;

/**
 * The manifest in the version-2 document, and the segment payload encoding.
 */
class JsonSessionSnapshotCodecManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SessionId ID = SessionId.of("s-manifest");

    private final JsonSessionSnapshotCodec codec = new JsonSessionSnapshotCodec();

    private static SessionLogState sealed() {
        return SessionLogState
                .ofMessages(List
                        .of(Message.user("q1"), Message.assistant("a1"), Message.user("q2"), Message.assistant("a2")))
                .withFormatAtLeast(SessionLogFormat.V2)
                .summarize(SummarySpan.builder().fromSeq(0).toSeq(2).summaryText("s").boundaryId("b").trigger("AUTO")
                        .build())
                .seal(SessionLogManifestEntry.builder().fromSeq(0).toSeq(2).segmentId(SegmentId.of("seg-1"))
                        .contentHash("sha256:abc").entryCount(2).build());
    }

    @Test
    void aManifestRoundTripsWhole() throws Exception {
        final SessionLogState original = sealed();

        final String encoded = codec.encode(SessionSnapshot.fromLog(ID, "sys", original));

        final ObjectNode root = (ObjectNode) MAPPER.readTree(encoded);
        assertThat(root.get("manifest").get(0).get("segmentId").asText()).isEqualTo("seg-1");
        assertThat(root.get("entries")).hasSize(2);
        assertThat(codec.decode(encoded).getLogState()).isEqualTo(original);
    }

    @Test
    void aLogThatNeverSealedWritesNoManifest() throws Exception {
        final String encoded = codec.encode(SessionSnapshot.fromLog(ID, "sys",
                SessionLogState.ofMessages(List.of(Message.user("q"))).withFormatAtLeast(SessionLogFormat.V2)));

        assertThat(MAPPER.readTree(encoded).has("manifest")).isFalse();
    }

    @Test
    void aManifestLineThatIsNotHiddenByTheViewIsRefused() throws Exception {
        final ObjectNode root = (ObjectNode) MAPPER
                .readTree(codec.encode(SessionSnapshot.fromLog(ID, "sys", sealed())));
        final ArrayNode manifest = (ArrayNode) root.get("manifest");
        ((ObjectNode) manifest.get(0)).put("toSeq", 3);
        ((ObjectNode) manifest.get(0)).put("entryCount", 3);
        root.remove("entries");
        root.putArray("entries");

        assertThatThrownBy(() -> codec.decode(MAPPER.writeValueAsString(root)))
                .isInstanceOf(SessionSnapshotCodecException.class).hasMessageContaining("Inconsistent session log");
    }

    @Test
    void entriesRoundTripAsASegmentPayload() {
        final List<SessionLogEntry> entries = List.of(SessionLogEntry.of(7, Message.user("q"), LogOrigin.SYNTHETIC),
                SessionLogEntry.of(9, Message.assistant("a"), LogOrigin.CONVERSATION));

        assertThat(codec.decodeEntries(codec.encodeEntries(entries))).isEqualTo(entries);
        assertThatThrownBy(() -> codec.decodeEntries("{\"version\":1}"))
                .isInstanceOf(SessionSnapshotCodecException.class);
    }
}
