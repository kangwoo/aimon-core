package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.llm.Message;

/**
 * Pins the rewind point itself and the two things {@link SessionTranscript} does with one: carry it across the copies
 * that immutability forces, and use it to take an interrupted turn back out.
 */
class SessionRewindPointTest {

    private static final String ASK_TEXT = "summarise the incident";
    private static final UserInput ASK_INPUT = TextInput.of(ASK_TEXT);
    private static final Message ASK = Message.user(ASK_TEXT);

    @Test
    void aPointCannotIndexBeforeTheStartOfTheTranscript() {
        assertThatIllegalArgumentException().isThrownBy(() -> SessionRewindPoint.of(-1, ASK_INPUT))
                .withMessageContaining("cannot be negative");
        assertThatNullPointerException().isThrownBy(() -> SessionRewindPoint.of(0, null));
    }

    /**
     * A point counting more messages than exist would rewind to a position that is not in the transcript, so the pair
     * is rejected where it is assembled rather than where it is used — by which time the caller has no way to tell a
     * corrupt record from an empty one.
     */
    @Test
    void aPointPastTheEndOfTheMessagesIsRefused() {
        final List<Message> messages = List.of(ASK, Message.assistant("working on it"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SessionTranscript.of("prompt", messages, SessionRewindPoint.of(3, ASK_INPUT)))
                .withMessageContaining("holds 2");
        assertThatIllegalArgumentException().isThrownBy(
                () -> SessionTranscript.of("prompt", messages).withRewindPoint(SessionRewindPoint.of(3, ASK_INPUT)))
                .withMessageContaining("holds 2");
    }

    /** A point exactly at the end is the ordinary case: a turn interrupted before it appended anything. */
    @Test
    void aPointAtTheEndIsAllowed() {
        final List<Message> messages = List.of(ASK);

        final SessionTranscript transcript = SessionTranscript.of("prompt", messages,
                SessionRewindPoint.of(1, ASK_INPUT));

        assertThat(transcript.getRewindPoint()).isPresent();
        assertThat(transcript.rewind().getMessages()).containsExactly(ASK);
    }

    /**
     * Every copy an immutable transcript makes has to bring the point along, or it would be dropped by whichever
     * ordinary operation happened to run between the interrupt and the retry — appending the next message being the
     * one that always does.
     */
    @Test
    void thePointSurvivesAppendAndSystemPromptChanges() {
        final SessionRewindPoint point = SessionRewindPoint.of(0, ASK_INPUT);
        final SessionTranscript transcript = SessionTranscript.of("prompt", List.of(ASK), point);

        assertThat(transcript.append(Message.assistant("partial")).getRewindPoint()).contains(point);
        assertThat(transcript.withSystemPrompt("another prompt").getRewindPoint()).contains(point);
    }

    @Test
    void rewindingDropsTheTurnsMessagesAndThePointWithThem() {
        final SessionTranscript before = SessionTranscript.of("prompt",
                List.of(Message.user("earlier"), Message.assistant("earlier answer")));
        final SessionTranscript interrupted = before.withRewindPoint(SessionRewindPoint.of(before.size(), ASK_INPUT))
                .append(ASK).append(Message.assistant("half an ans"));

        final SessionTranscript rewound = interrupted.rewind();

        assertThat(rewound.getMessages()).isEqualTo(before.getMessages());
        assertThat(rewound.getSystemPrompt()).isEqualTo("prompt");
        assertThat(rewound.getRewindPoint()).as("a rewound transcript has nothing left to rewind").isEmpty();
    }

    /**
     * Dropping the point in the same step is what makes retrying safe to repeat. Were it kept, a second rewind would
     * cut at the same index into a shorter transcript and take the previous turn with it.
     */
    @Test
    void rewindingTwiceIsTheSameAsRewindingOnce() {
        final SessionTranscript before = SessionTranscript.of("prompt", List.of(Message.user("earlier")));
        final SessionTranscript interrupted = before.withRewindPoint(SessionRewindPoint.of(1, ASK_INPUT)).append(ASK);

        assertThat(interrupted.rewind().rewind().getMessages()).isEqualTo(before.getMessages());
    }

    @Test
    void aTranscriptWithNothingToRewindIsReturnedUnchanged() {
        final SessionTranscript transcript = SessionTranscript.of("prompt", List.of(ASK));

        assertThat(transcript.rewind()).isSameAs(transcript);
    }

    /** Two transcripts that differ only in whether a turn can be retried are not the same transcript. */
    @Test
    void thePointParticipatesInEquality() {
        final SessionTranscript plain = SessionTranscript.of("prompt", List.of(ASK));
        final SessionTranscript retryable = plain.withRewindPoint(SessionRewindPoint.of(0, ASK_INPUT));

        assertThat(retryable).isNotEqualTo(plain);
        assertThat(retryable.withRewindPoint(null)).isEqualTo(plain);
    }
}
