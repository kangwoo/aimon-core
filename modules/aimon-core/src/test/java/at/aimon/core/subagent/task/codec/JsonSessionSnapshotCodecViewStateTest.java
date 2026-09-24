package at.aimon.core.subagent.task.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SeqRange;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * The view state in the version-2 document: written whole when it leaves anything out, read back equal, absent means
 * empty, and a view state that does not fit its log is refused.
 */
class JsonSessionSnapshotCodecViewStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SessionId ID = SessionId.of("s-view");

    private final JsonSessionSnapshotCodec codec = new JsonSessionSnapshotCodec();

    private static SessionLogState state() {
        return SessionLogState
                .ofMessages(
                        List.of(Message.user("q1"), Message.assistant("", List.of(ToolUse.of("t", "Read", Map.of()))),
                                Message.toolUseResults(List.of(ToolUseResult.success("t", "body"))), Message.user("q2"),
                                Message.assistant("a2")))
                .withFormatAtLeast(SessionLogFormat.V2)
                .summarize(SummarySpan.builder().fromSeq(0).toSeq(1).summaryText("the summary").boundaryId("b-7")
                        .trigger("MANUAL").preTokenCount(12).messagesSummarized(1)
                        .discoveredToolNames(List.of("Read", "Bash")).build())
                .drop(3, 4).elide(2, "[tool result elided: seq=2]");
    }

    @Test
    void aViewStateRoundTripsWhole() throws Exception {
        final SessionLogState original = state();

        final String encoded = codec.encode(SessionSnapshot.fromLog(ID, "sys", original));
        final SessionLogState decoded = codec.decode(encoded).getLogState();

        assertThat(MAPPER.readTree(encoded).get("version").asInt()).isEqualTo(2);
        assertThat(decoded.getViewState()).isEqualTo(original.getViewState());
        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.getViewState().getDroppedRanges()).containsExactly(SeqRange.of(3, 4));
    }

    @Test
    void anEmptyViewStateIsNotWrittenAndReadsBackEmpty() throws Exception {
        final SessionLogState plain = SessionLogState.ofMessages(List.of(Message.user("q")))
                .withFormatAtLeast(SessionLogFormat.V2);

        final String encoded = codec.encode(SessionSnapshot.fromLog(ID, "sys", plain));

        assertThat(MAPPER.readTree(encoded).has("viewState")).isFalse();
        assertThat(codec.decode(encoded).getLogState().getViewState().isEmpty()).isTrue();
    }

    @Test
    void aViewStateThatDoesNotFitItsLogIsRefused() throws Exception {
        final ObjectNode root = (ObjectNode) MAPPER.readTree(codec.encode(SessionSnapshot.fromLog(ID, "sys", state())));
        ((ObjectNode) root.get("viewState").get("droppedRanges").get(0)).put("toSeq", 99);

        assertThatThrownBy(() -> codec.decode(MAPPER.writeValueAsString(root)))
                .isInstanceOf(SessionSnapshotCodecException.class).hasMessageContaining("Inconsistent session log");
    }
}
