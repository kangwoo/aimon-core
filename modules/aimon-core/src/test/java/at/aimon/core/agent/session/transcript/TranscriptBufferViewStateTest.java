package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.Message;

/**
 * The view state as a {@link TranscriptBuffer} carries it: changed only through the three operations, persisted like
 * any mutation, carried through snapshots, and cleaned up by the paths that cut the log.
 */
class TranscriptBufferViewStateTest {

    private TranscriptBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new TranscriptBuffer(SessionId.of("s-v"), "sys");
        buffer.requireFormat(SessionLogFormat.V2);
        buffer.addUserMessage("q1");
        buffer.addAssistantMessage("a1");
        buffer.addUserMessage("q2");
    }

    private static SummarySpan span(long from, long to) {
        return SummarySpan.builder().fromSeq(from).toSeq(to).summaryText("s").boundaryId("b").trigger("AUTO").build();
    }

    @Test
    void aViewStateChangeIsAMutationTheCheckpointSees() {
        final List<Long> notified = new ArrayList<>();
        buffer.setDirtyListener(memory -> notified.add(memory.getVersion()));

        buffer.dropFromView(0, 1);
        buffer.summarizeView(span(0, 2));

        assertThat(notified).hasSize(2);
        assertThat(buffer.getMessages()).hasSize(3);
        assertThat(buffer.getLogState().getViewState()).isEqualTo(buffer.getViewState());
    }

    @Test
    void aVersionOneBufferRefusesViewStateOperations() {
        final TranscriptBuffer v1 = new TranscriptBuffer(SessionId.of("s-1"), "sys");
        v1.addUserMessage("q");

        assertThatThrownBy(() -> v1.dropFromView(0, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(v1.getVersion()).isEqualTo(1);
    }

    @Test
    void theViewStateTravelsThroughASnapshot() {
        buffer.summarizeView(span(0, 2));

        final TranscriptBuffer restored = TranscriptBuffer.fromSnapshot(buffer.toSnapshot());

        assertThat(restored.getViewState()).isEqualTo(buffer.getViewState());
    }

    @Test
    void aRewindCutsTheViewStateWithTheLog() {
        buffer.beginTurn(TextInput.of("q3"), SubmitOptions.empty());
        buffer.addUserMessage("q3");
        buffer.addAssistantMessage("a3");
        buffer.summarizeView(span(0, 5));

        buffer.rewind();

        assertThat(buffer.getViewState().getSummarySpan())
                .hasValueSatisfying(held -> assertThat(held.getRange()).isEqualTo(SeqRange.of(0, 3)));
    }

    @Test
    void clearAndAnInPlaceRewriteEmptyTheViewState() {
        buffer.summarizeView(span(0, 2));
        buffer.clear();
        assertThat(buffer.getViewState().isEmpty()).isTrue();

        buffer.addUserMessage("q");
        buffer.dropFromView(3, 4);
        @SuppressWarnings("deprecation")
        final Runnable rewrite = () -> buffer.replaceWith(List.of(Message.user("rewritten")));
        rewrite.run();
        assertThat(buffer.getViewState().isEmpty()).isTrue();
    }

    @Test
    void conversationMessagesLeaveOutSyntheticEntries() {
        buffer.addMessage(Message.user("reminder"), LogOrigin.SYNTHETIC);

        assertThat(buffer.getConversationMessages()).extracting(Message::getContent).containsExactly("q1", "a1", "q2");
    }
}
