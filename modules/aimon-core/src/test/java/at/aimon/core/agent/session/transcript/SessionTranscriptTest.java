package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.Message;

/** Contract tests for {@link SessionTranscript}. */
@DisplayName("SessionTranscript")
class SessionTranscriptTest {

    private static final Message HELLO = Message.user("hello");
    private static final Message HI = Message.assistant("hi");

    @Test
    @DisplayName("empty() has no prompt and no messages")
    void emptyIsBlank() {
        assertThat(SessionTranscript.empty().getSystemPrompt()).isNull();
        assertThat(SessionTranscript.empty().getMessages()).isEmpty();
        assertThat(SessionTranscript.empty().isEmpty()).isTrue();
        assertThat(SessionTranscript.empty().size()).isZero();
    }

    @Test
    @DisplayName("empty() tolerates contains(null) — it is not backed by List.of()")
    void emptyToleratesNullQueries() {
        // List.of() throws NullPointerException on contains(null); the transcript must not, because SessionRecord's
        // getMessages() has always been lenient here.
        assertThat(SessionTranscript.empty().getMessages().contains(null)).isFalse();
    }

    @Test
    @DisplayName("of() defensively copies the incoming list")
    void ofCopiesInput() {
        final List<Message> source = new ArrayList<>(List.of(HELLO));

        final SessionTranscript transcript = SessionTranscript.of("sys", source);
        source.add(HI);

        assertThat(transcript.getMessages()).containsExactly(HELLO);
        assertThat(transcript.getSystemPrompt()).isEqualTo("sys");
    }

    @Test
    @DisplayName("of() accepts null elements")
    void ofAcceptsNullElements() {
        final SessionTranscript transcript = SessionTranscript.of(null, Arrays.asList(HELLO, null));

        assertThat(transcript.getMessages()).containsExactly(HELLO, null);
    }

    @Test
    @DisplayName("of() rejects a null list")
    void ofRejectsNullList() {
        assertThatNullPointerException().isThrownBy(() -> SessionTranscript.of("sys", null))
                .withMessage("messages cannot be null");
    }

    @Test
    @DisplayName("getMessages() is unmodifiable")
    void messagesAreUnmodifiable() {
        final SessionTranscript transcript = SessionTranscript.of(null, List.of(HELLO));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> transcript.getMessages().add(HI));
    }

    @Test
    @DisplayName("append() returns a new transcript and leaves the original — and its exposed list — untouched")
    void appendDoesNotMutate() {
        final SessionTranscript original = SessionTranscript.of("sys", List.of(HELLO));
        final List<Message> exposed = original.getMessages();

        final SessionTranscript appended = original.append(HI);

        assertThat(original.getMessages()).containsExactly(HELLO);
        assertThat(exposed).containsExactly(HELLO);
        assertThat(appended.getMessages()).containsExactly(HELLO, HI);
        assertThat(appended.getSystemPrompt()).isEqualTo("sys");
    }

    @Test
    @DisplayName("append() rejects null")
    void appendRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> SessionTranscript.empty().append(null))
                .withMessage("message cannot be null");
    }

    @Test
    @DisplayName("withSystemPrompt() swaps the prompt, shares the history, and clears on null")
    void withSystemPromptSwapsPrompt() {
        final SessionTranscript original = SessionTranscript.of("old", List.of(HELLO));

        final SessionTranscript renamed = original.withSystemPrompt("new");
        assertThat(renamed.getSystemPrompt()).isEqualTo("new");
        assertThat(renamed.getMessages()).isEqualTo(original.getMessages());
        assertThat(original.getSystemPrompt()).isEqualTo("old");

        assertThat(original.withSystemPrompt(null).getSystemPrompt()).isNull();
    }

    @Test
    @DisplayName("withSystemPrompt() returns the same instance when the prompt is unchanged")
    void withSystemPromptIsIdentityOnNoOp() {
        final SessionTranscript original = SessionTranscript.of("sys", List.of(HELLO));

        assertThat(original.withSystemPrompt("sys")).isSameAs(original);
        assertThat(SessionTranscript.empty().withSystemPrompt(null)).isSameAs(SessionTranscript.empty());
    }

    @Test
    @DisplayName("equality is by value over both halves")
    void equalityIsByValue() {
        assertThat(SessionTranscript.of("sys", List.of(HELLO))).isEqualTo(SessionTranscript.of("sys", List.of(HELLO)))
                .hasSameHashCodeAs(SessionTranscript.of("sys", List.of(HELLO)))
                .isNotEqualTo(SessionTranscript.of("other", List.of(HELLO)))
                .isNotEqualTo(SessionTranscript.of("sys", List.of(HELLO, HI)));

        assertThat(SessionTranscript.of(null, List.of())).isEqualTo(SessionTranscript.empty());
    }

    @Test
    @DisplayName("toString() summarizes without leaking prompt or message content")
    void toStringDoesNotLeak() {
        final SessionTranscript transcript = SessionTranscript.of("secret-prompt", List.of(HELLO, HI));

        assertThat(transcript.toString()).contains("hasSystemPrompt=true").contains("messages=2")
                .doesNotContain("secret-prompt").doesNotContain("hello");
    }
}
