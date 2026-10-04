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
    void notRun_isNotObservedAndNotDenied_andCarriesItsCause() {
        ShellHookOutcome outcome = ShellHookOutcome.notRun(ShellHookOutcome.Unrun.TIMEOUT, " no exit status in 30s ");

        assertThat(outcome.isObserved()).isFalse();
        // "Denied" is what an exit code says. Whether silence blocks is the reading hook's decision.
        assertThat(outcome.isDenied()).isFalse();
        assertThat(outcome.getStdout()).isEmpty();
        assertThat(outcome.getStderr()).isEmpty();
        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.TIMEOUT);
        assertThat(outcome.unrunReason()).isEqualTo("timed out: no exit status in 30s");
        assertThat(outcome).hasToString("ShellHookOutcome{notRun=TIMEOUT}");
    }

    @Test
    void notRun_withoutDetail_reasonIsTheCauseAlone() {
        assertThat(ShellHookOutcome.notRun(ShellHookOutcome.Unrun.NO_ENVIRONMENT, null).unrunReason())
                .isEqualTo("no execution environment");
        assertThat(ShellHookOutcome.notRun(ShellHookOutcome.Unrun.SHELL_UNSUPPORTED, "  ").unrunReason())
                .isEqualTo("shell actions not supported");
    }

    @Test
    void notRun_longDetail_isCappedLikeADenyReason() {
        String reason = ShellHookOutcome.notRun(ShellHookOutcome.Unrun.EXECUTION_FAILED, "x".repeat(10_000))
                .unrunReason();

        assertThat(reason).startsWith("shell execution failed: xxx").contains("... [truncated, ");
        assertThat(reason.length()).isLessThan(ShellHookOutcome.MAX_DENY_REASON_LENGTH + 100);
    }

    @Test
    void notRun_nullCause_throwsNpe() {
        assertThatThrownBy(() -> ShellHookOutcome.notRun(null, "x")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void observedOutcome_hasNoUnrunCause() {
        assertThat(ShellHookOutcome.of(0, "", "").getUnrunCause()).isEmpty();
        assertThat(ShellHookOutcome.of(2, "", "no").unrunReason()).isEmpty();
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
