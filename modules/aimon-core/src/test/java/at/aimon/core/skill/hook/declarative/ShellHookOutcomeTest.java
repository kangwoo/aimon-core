package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ShellHookOutcomeTest {

    @Test
    void isDenied_trueOnlyForExitCodeTwo() {
        assertThat(ShellHookOutcome.of(2, "", "nope").isDenied()).isTrue();
        assertThat(ShellHookOutcome.of(0, "", "").isDenied()).isFalse();
        assertThat(ShellHookOutcome.of(1, "", "boom").isDenied()).isFalse();
        assertThat(ShellHookOutcome.of(127, "", "not found").isDenied()).isFalse();
    }

    @Test
    void notObserved_isNeverDenied() {
        ShellHookOutcome outcome = ShellHookOutcome.notObserved();

        assertThat(outcome.isObserved()).isFalse();
        assertThat(outcome.isDenied()).isFalse();
        assertThat(outcome.getStdout()).isEmpty();
        assertThat(outcome.getStderr()).isEmpty();
    }

    @Test
    void of_marksOutcomeObserved() {
        ShellHookOutcome outcome = ShellHookOutcome.of(2, "out", "err");

        assertThat(outcome.isObserved()).isTrue();
        assertThat(outcome.getExitCode()).isEqualTo(2);
        assertThat(outcome.getStdout()).isEqualTo("out");
        assertThat(outcome.getStderr()).isEqualTo("err");
    }

    @Test
    void of_nullStreams_throwsNpe() {
        assertThatThrownBy(() -> ShellHookOutcome.of(0, null, "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShellHookOutcome.of(0, "", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void denyReason_returnsTrimmedStderr() {
        ShellHookOutcome outcome = ShellHookOutcome.of(2, "", "  write access is not allowed here \n");

        assertThat(outcome.denyReason()).isEqualTo("write access is not allowed here");
    }

    @Test
    void denyReason_blankStderr_fallsBackToGenericMessage() {
        ShellHookOutcome outcome = ShellHookOutcome.of(2, "", "   \n\t ");

        assertThat(outcome.denyReason()).isEqualTo("Blocked by a shell hook (exit code 2, no stderr output)");
    }

    @Test
    void denyReason_stderrAtCap_isNotTruncated() {
        String exact = "x".repeat(ShellHookOutcome.MAX_DENY_REASON_LENGTH);

        String reason = ShellHookOutcome.of(2, "", exact).denyReason();

        assertThat(reason).isEqualTo(exact).doesNotContain("[truncated");
    }

    @Test
    void denyReason_oversizedStderr_isTruncatedWithMarker() {
        int total = ShellHookOutcome.MAX_DENY_REASON_LENGTH + 500;
        String huge = "y".repeat(total);

        String reason = ShellHookOutcome.of(2, "", huge).denyReason();

        assertThat(reason).startsWith("y".repeat(ShellHookOutcome.MAX_DENY_REASON_LENGTH))
                .endsWith("... [truncated, " + total + " chars total]");
        // Bounded: the cap plus a short, fixed-shape marker — never the whole blob.
        assertThat(reason.length()).isLessThan(ShellHookOutcome.MAX_DENY_REASON_LENGTH + 64);
    }
}
