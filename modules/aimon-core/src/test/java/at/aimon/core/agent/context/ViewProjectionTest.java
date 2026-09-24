package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.compact.CompactBoundary;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.subagent.task.codec.JsonSessionSnapshotCodec;

/**
 * {@link ViewProjection}: the view is the log with its view state applied, the same every time it is computed.
 */
class ViewProjectionTest {

    private static final Message Q1 = Message.user("q1");
    private static final Message CALL = Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of())));
    private static final Message RESULT = Message
            .toolUseResults(List.of(ToolUseResult.error("t1", "a very long tool result body")));
    private static final Message A1 = Message.assistant("a1");
    private static final Message Q2 = Message.user("q2");

    private static SessionLogState log() {
        return SessionLogState.ofMessages(List.of(Q1, CALL, RESULT, A1, Q2)).withFormatAtLeast(SessionLogFormat.V2);
    }

    private static SummarySpan span(long from, long to) {
        return SummarySpan.builder().fromSeq(from).toSeq(to).summaryText("the summary").boundaryId("b-1")
                .trigger("AUTO").preTokenCount(42).messagesSummarized(4).discoveredToolNames(List.of("Read")).build();
    }

    @Test
    void anEmptyViewStateShowsTheLogAsItIsWithTheSameInstances() {
        final ViewProjection view = ViewProjection.of(log());

        assertThat(view.getMessages()).containsExactly(Q1, CALL, RESULT, A1, Q2);
        assertThat(view.getMessages().get(1)).isSameAs(CALL);
        assertThat(view.sourceSeq(4)).isEqualTo(4);
        assertThat(view.isVerbatim(4)).isTrue();
    }

    @Test
    void theSpanIsReplacedByTheMarkerPairBuiltFromItsStoredMetadata() {
        final ViewProjection view = ViewProjection.of(log().summarize(span(0, 4)));

        assertThat(view.size()).isEqualTo(3);
        assertThat(view.getMessages().get(0))
                .isEqualTo(CompactBoundary.boundaryMessage("b-1", CompactionTrigger.AUTO, 42, 4, List.of("Read")));
        assertThat(view.getMessages().get(1)).isEqualTo(CompactBoundary.summaryMessage("b-1", "the summary"));
        assertThat(view.getMessages().get(2)).isSameAs(Q2);
        assertThat(view.sourceSeq(0)).isEqualTo(ViewProjection.MADE_BY_VIEW);
        assertThat(view.isVerbatim(1)).isFalse();
        assertThat(view.sourceSeq(2)).isEqualTo(4);
    }

    @Test
    void aSpanWhoseEntriesAreAllGoneStillShowsItsMarkersAtTheEnd() {
        final SessionLogState rewound = log().summarize(span(4, 5)).truncateFrom(5);
        final SessionLogState spanPastTheEntries = rewound.toBuilder().entries(rewound.getEntries().subList(0, 4))
                .build();

        final ViewProjection view = ViewProjection.of(spanPastTheEntries);

        assertThat(view.getMessages()).hasSize(6);
        assertThat(view.getMessages().get(4).getContent()).startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
    }

    @Test
    void droppedEntriesAreLeftOut() {
        final ViewProjection view = ViewProjection.of(log().drop(0, 1).drop(3, 4));

        assertThat(view.getMessages()).containsExactly(CALL, RESULT, Q2);
        assertThat(view.sourceSeq(2)).isEqualTo(4);
    }

    @Test
    void anElidedResultKeepsItsIdAndErrorFlagAndShowsThePlaceholder() {
        final ViewProjection view = ViewProjection.of(log().elide(2, "[tool result elided: seq=2]"));

        final Message shown = view.getMessages().get(2);
        assertThat(view.isVerbatim(2)).isFalse();
        assertThat(view.sourceSeq(2)).isEqualTo(2);
        assertThat(shown.getToolUseResults()).singleElement().satisfies(result -> {
            assertThat(result.getToolUseId()).isEqualTo("t1");
            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("[tool result elided: seq=2]");
        });
    }

    @Test
    void theSameRecordProjectsTheSameViewOnAnotherNode() {
        final SessionLogState state = log().summarize(span(0, 4)).elide(2, "x");
        final JsonSessionSnapshotCodec codec = new JsonSessionSnapshotCodec();
        final SessionLogState elsewhere = codec
                .decode(codec.encode(SessionSnapshot.fromLog(SessionId.of("s"), "sys", state))).getLogState();

        assertThat(ViewProjection.of(elsewhere).getMessages()).isEqualTo(ViewProjection.of(state).getMessages());
    }
}
