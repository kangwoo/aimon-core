package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecord;
import at.aimon.core.llm.Message;

/**
 * Pins the buffer's half of the retry contract: which mutations move the rewind point, which do not, and that it
 * survives the round trip through a snapshot — the path every persisted session takes.
 */
class TranscriptBufferRewindTest {

    private static final SessionId SESSION = SessionId.of("retry-session");
    private static final String ASK_TEXT = "summarise the incident";
    private static final UserInput ASK_INPUT = TextInput.of(ASK_TEXT);
    private static final Message ASK = Message.user(ASK_TEXT);

    private static TranscriptBuffer withHistory() {
        return new TranscriptBuffer(SESSION, "prompt",
                List.of(Message.user("earlier"), Message.assistant("earlier answer")));
    }

    @Test
    void aBufferWithNoTurnMarkedHasNothingToRewind() {
        final TranscriptBuffer buffer = withHistory();

        assertThat(buffer.getRewindPoint()).isEmpty();
        assertThat(buffer.rewind()).isEmpty();
        assertThat(buffer.size()).isEqualTo(2);
    }

    @Test
    void rewindingDropsEverythingTheTurnAddedAndHandsBackWhatStartedIt() {
        final TranscriptBuffer buffer = withHistory();

        buffer.beginTurn(ASK_INPUT, SubmitOptions.empty());
        buffer.addMessage(Message.user("<system-reminder>injected context</system-reminder>"));
        buffer.addMessage(ASK);
        buffer.addMessage(Message.assistant("half an ans"));

        assertThat(buffer.rewind().orElseThrow().getUserInput()).isEqualTo(ASK_INPUT);
        assertThat(buffer.getMessages()).hasSize(2);
        assertThat(buffer.getMessages().get(1).getContent()).isEqualTo("earlier answer");
        assertThat(buffer.getRewindPoint()).isEmpty();
    }

    /**
     * The synthetic context block injected ahead of the user message belongs to the turn, so it has to go with it.
     * Leaving it behind would strand a {@code <system-reminder>} that introduces a request no longer in the history,
     * and — on a fresh session — let the retry inject a second copy.
     */
    @Test
    void theRewindReachesBackPastTheInjectedContextBlock() {
        final TranscriptBuffer buffer = new TranscriptBuffer(SESSION, "prompt");

        buffer.beginTurn(ASK_INPUT, SubmitOptions.empty());
        buffer.addMessage(Message.user("<system-reminder>injected context</system-reminder>"));
        buffer.addMessage(ASK);

        buffer.rewind();

        assertThat(buffer.getMessages()).isEmpty();
    }

    /** A turn that ended any way other than interrupted leaves nothing behind to retry. */
    @Test
    void endTurnClearsTheMark() {
        final TranscriptBuffer buffer = withHistory();

        buffer.beginTurn(ASK_INPUT, SubmitOptions.empty());
        buffer.addMessage(ASK);
        buffer.endTurn();

        assertThat(buffer.getRewindPoint()).isEmpty();
        assertThat(buffer.rewind()).isEmpty();
        assertThat(buffer.size()).as("clearing the mark must not touch the history").isEqualTo(3);
    }

    /** Only the most recent turn is ever retryable, so a second mark replaces the first rather than queueing. */
    @Test
    void beginningANewTurnReplacesTheEarlierMark() {
        final TranscriptBuffer buffer = new TranscriptBuffer(SESSION, "prompt");

        buffer.beginTurn(TextInput.of("first"), SubmitOptions.empty());
        buffer.addMessage(Message.user("first"));
        buffer.beginTurn(ASK_INPUT, SubmitOptions.empty());
        buffer.addMessage(ASK);

        assertThat(buffer.rewind().orElseThrow().getUserInput()).isEqualTo(ASK_INPUT);
        assertThat(buffer.getMessages()).hasSize(1);
    }

    /**
     * The round trip is the whole point of putting the mark in the transcript rather than beside it: a session that
     * was interrupted and then evicted, restarted or handed to another node must still be retryable when it comes
     * back, and a snapshot is how it comes back.
     */
    @Test
    void theMarkSurvivesTheSnapshotRoundTrip() {
        final TranscriptBuffer buffer = withHistory();
        buffer.beginTurn(ASK_INPUT, SubmitOptions.empty());
        buffer.addMessage(ASK);
        buffer.addMessage(Message.assistant("half an ans"));

        final TranscriptBuffer reopened = TranscriptBuffer.fromSnapshot(buffer.toSnapshot());

        assertThat(reopened.getRewindPoint()).isPresent();
        assertThat(reopened.rewind().orElseThrow().getUserInput()).isEqualTo(ASK_INPUT);
        assertThat(reopened.getMessages()).hasSize(2);
    }

    @Test
    void clearDropsTheMarkAlongWithTheHistory() {
        final TranscriptBuffer buffer = withHistory();
        buffer.beginTurn(ASK_INPUT, SubmitOptions.empty());
        buffer.addMessage(ASK);

        buffer.clear();

        assertThat(buffer.getRewindPoint()).isEmpty();
    }

    /**
     * Compaction rewrites the history out from under the mark, and the mark cannot survive that.
     *
     * <p>
     * {@code replaceWith} can leave far fewer messages than there were when the turn began, so the recorded count
     * stops being a position in the transcript. Keeping it would not merely rewind to the wrong place: the count is
     * validated where the transcript is rebuilt, so the end-of-turn persist would throw — into
     * {@code saveSilently}, which swallows it — and the whole turn's history would be dropped without a word.
     */
    @Test
    void compactingTheHistoryDropsTheMarkRatherThanStrandingIt() {
        final TranscriptBuffer buffer = withHistory();
        buffer.beginTurn(ASK_INPUT, SubmitOptions.empty());
        buffer.addMessage(ASK);
        buffer.addMessage(Message.assistant("half an ans"));

        buffer.replaceWith(List.of(Message.user("<summary of everything above>")));

        assertThat(buffer.getRewindPoint()).isEmpty();
        assertThat(buffer.rewind()).isEmpty();
        assertThat(SessionRecord.fromSnapshot(buffer.toSnapshot()).getMessages()).hasSize(1);
    }

    /**
     * Marking a turn says nothing about the LLM-visible history, so it must not bump the version — a mid-turn
     * checkpoint fired because a turn had started would be a checkpoint for nothing. The rewind itself is a genuine
     * mutation and does bump it.
     */
    @Test
    void markingIsBookkeepingButRewindingIsAMutation() {
        final TranscriptBuffer buffer = withHistory();
        final long atRest = buffer.getVersion();

        buffer.beginTurn(ASK_INPUT, SubmitOptions.empty());
        buffer.endTurn();
        assertThat(buffer.getVersion()).isEqualTo(atRest);

        buffer.beginTurn(ASK_INPUT, SubmitOptions.empty());
        buffer.addMessage(ASK);
        final long afterAppend = buffer.getVersion();
        buffer.rewind();

        assertThat(buffer.getVersion()).isGreaterThan(afterAppend);
    }
}
