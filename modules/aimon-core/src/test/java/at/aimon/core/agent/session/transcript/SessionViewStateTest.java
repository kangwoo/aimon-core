package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * The view state operations on {@link SessionLogState}: what they accept, what they refuse, and how the two paths
 * that cut the log clean up after them.
 */
class SessionViewStateTest {

    /** seq 0 user, 1 assistant(tool_use), 2 tool result, 3 assistant, 4 user, 5 assistant. */
    private static SessionLogState log(SessionLogFormat format) {
        return SessionLogState
                .ofMessages(
                        List.of(Message.user("q1"), Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))),
                                Message.toolUseResults(List.of(ToolUseResult.success("t1", "file body"))),
                                Message.assistant("a1"), Message.user("q2"), Message.assistant("a2")))
                .withFormatAtLeast(format);
    }

    private static SummarySpan span(long from, long to) {
        return SummarySpan.builder().fromSeq(from).toSeq(to).summaryText("summary").boundaryId("b-1").trigger("AUTO")
                .preTokenCount(10).messagesSummarized(3).build();
    }

    @Nested
    class LegalCutsOfTheLog {

        @Test
        void aCutBetweenAToolUseAndItsResultIsNotLegal() {
            final SessionLogState state = log(SessionLogFormat.V2);

            assertThat(state.isLegalCut(0)).isTrue();
            assertThat(state.isLegalCut(1)).isTrue();
            assertThat(state.isLegalCut(2)).as("the result would start the second part").isFalse();
            assertThat(state.isLegalCut(3)).isTrue();
            assertThat(state.isLegalCut(6)).isTrue();
            assertThat(state.isLegalCut(7)).as("outside the log").isFalse();
        }

        @Test
        void theEndOfALogWithAnUnansweredCallIsNotLegal() {
            final SessionLogState state = SessionLogState
                    .ofMessages(List.of(Message.user("q"),
                            Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of())))))
                    .withFormatAtLeast(SessionLogFormat.V2);

            assertThat(state.isLegalCut(2)).isFalse();
        }
    }

    @Nested
    class Operations {

        @Test
        void summarizeRecordsTheSpanAndLeavesTheLogAlone() {
            final SessionLogState state = log(SessionLogFormat.V2);

            final SessionLogState summarized = state.summarize(span(0, 4));

            assertThat(summarized.getEntries()).isEqualTo(state.getEntries());
            assertThat(summarized.getViewState().getSummarySpan()).contains(span(0, 4));
            assertThat(summarized.getViewState().hidesOriginal(3)).isTrue();
            assertThat(summarized.getViewState().hidesOriginal(4)).isFalse();
        }

        @Test
        void aSpanOnlyWidensAndAbsorbsWhatItCovers() {
            final SessionLogState state = log(SessionLogFormat.V2).drop(0, 1).elide(2, "[elided]")
                    .summarize(span(0, 3));

            assertThat(state.getViewState().getDroppedRanges()).as("the span hides it anyway").isEmpty();
            assertThat(state.getViewState().getElisions()).isEmpty();
            assertThatThrownBy(() -> state.summarize(span(1, 6))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only widens");
            assertThat(state.summarize(span(0, 6)).getViewState().getSummarySpan()).contains(span(0, 6));
        }

        @Test
        void summarizeAndDropRefuseACutThatSplitsAToolPair() {
            final SessionLogState state = log(SessionLogFormat.V2);

            assertThatThrownBy(() -> state.summarize(span(0, 2))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("legal cuts");
            assertThatThrownBy(() -> state.drop(2, 3)).isInstanceOf(IllegalArgumentException.class);
            assertThat(state.drop(1, 3).getViewState().getDroppedRanges()).containsExactly(SeqRange.of(1, 3));
        }

        @Test
        void droppedRangesAreSortedAndMerged() {
            final SessionLogState state = log(SessionLogFormat.V2).drop(4, 5).drop(0, 1).drop(1, 3);

            assertThat(state.getViewState().getDroppedRanges()).containsExactly(SeqRange.of(0, 3), SeqRange.of(4, 5));
            assertThat(state.getViewState().isDropped(2)).isTrue();
            assertThat(state.getViewState().isDropped(3)).isFalse();
        }

        @Test
        void elideTakesOnlyAToolResultTheLogCarries() {
            final SessionLogState state = log(SessionLogFormat.V2);

            assertThat(state.elide(2, "[elided]").getViewState().getElisions()).containsEntry(2L, "[elided]");
            assertThatThrownBy(() -> state.elide(0, "x")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tool result");
            assertThatThrownBy(() -> state.elide(9, "x")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aVersionOneLogKeepsNoViewState() {
            final SessionLogState state = log(SessionLogFormat.V1);

            assertThatThrownBy(() -> state.summarize(span(0, 4))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> state.drop(0, 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> state.elide(2, "x")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> state.toBuilder()
                    .viewState(SessionViewState.of(null, List.of(SeqRange.of(0, 1)), Map.of())).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version-2");
        }

        @Test
        void aViewStatePointingOutsideTheLogIsRefused() {
            final SessionLogState state = log(SessionLogFormat.V2);

            assertThatThrownBy(() -> state.toBuilder()
                    .viewState(SessionViewState.of(null, List.of(SeqRange.of(5, 9)), Map.of())).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("points outside");
        }
    }

    @Nested
    class CuttingTheLog {

        @Test
        void aRewindCutsRangesAndASpanThatReachPastIt() {
            final SessionLogState state = log(SessionLogFormat.V2).summarize(span(0, 4)).drop(4, 6);

            final SessionLogState cut = state.truncateFrom(5);

            assertThat(cut.getViewState().getSummarySpan()).contains(span(0, 4));
            assertThat(cut.getViewState().getDroppedRanges()).containsExactly(SeqRange.of(4, 5));
        }

        @Test
        void aRewindThroughTheSpanKeepsItsSummaryAndEndsItAtTheCut() {
            final SessionLogState cut = log(SessionLogFormat.V2).summarize(span(0, 6)).truncateFrom(4);

            assertThat(cut.getViewState().getSummarySpan()).hasValueSatisfying(held -> {
                assertThat(held.getRange()).isEqualTo(SeqRange.of(0, 4));
                assertThat(held.getSummaryText()).isEqualTo("summary");
            });
        }

        @Test
        void aRewindBeforeTheSpanDropsIt() {
            final SessionLogState cut = log(SessionLogFormat.V2).drop(0, 1).summarize(span(4, 6)).elide(2, "x")
                    .truncateFrom(3);

            assertThat(cut.getViewState().getSummarySpan()).isEmpty();
            assertThat(cut.getViewState().getElisions()).containsOnlyKeys(2L);
            assertThat(cut.getViewState().getDroppedRanges()).containsExactly(SeqRange.of(0, 1));
        }

        @Test
        void clearEmptiesTheViewState() {
            final SessionLogState cleared = log(SessionLogFormat.V2).summarize(span(0, 4)).clear();

            assertThat(cleared.getViewState().isEmpty()).isTrue();
            assertThat(cleared.getFloorSeq()).isEqualTo(6);
        }
    }

    @Test
    void conversationMessagesLeaveOutWhatTheRuntimeInjected() {
        final SessionLogState state = SessionLogState.empty().append(Message.user("block"), LogOrigin.SYNTHETIC)
                .append(Message.user("q"), LogOrigin.CONVERSATION);

        assertThat(state.getConversationMessages()).extracting(Message::getContent).containsExactly("q");
    }
}
