package at.aimon.core.agent.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ToolUseResult;

@DisplayName("StalledIterationGuard - the one stall definition a turn, a fork and a skill's loop share")
class StalledIterationGuardTest {

    private static final List<ToolUseResult> FAILED = List.of(ToolUseResult.error("t1", "boom"),
            ToolUseResult.error("t2", "boom"));
    private static final List<ToolUseResult> PROGRESS = List.of(ToolUseResult.success("t1", "ok"),
            ToolUseResult.error("t2", "boom"));

    /** The text OrcaAgentExecutor built before the guard was shared, byte for byte. */
    private static final String PLAIN_MESSAGE = "Execution aborted: 3 consecutive tool-only iterations made no progress "
            + "(all tool calls failed)";

    @Nested
    @DisplayName("isStalled")
    class IsStalled {

        @Test
        @DisplayName("an iteration with no results is never stalled")
        void emptyIsNotStalled() {
            assertThat(StalledIterationGuard.isStalled(List.of())).isFalse();
        }

        @Test
        @DisplayName("an iteration whose every result is an error is stalled")
        void allErrorsIsStalled() {
            assertThat(StalledIterationGuard.isStalled(FAILED)).isTrue();
        }

        @Test
        @DisplayName("one success among errors is progress")
        void oneSuccessIsNotStalled() {
            assertThat(StalledIterationGuard.isStalled(PROGRESS)).isFalse();
        }

        @Test
        @DisplayName("null results are rejected")
        void nullIsRejected() {
            assertThatNullPointerException().isThrownBy(() -> StalledIterationGuard.isStalled(null));
        }
    }

    @Test
    @DisplayName("the third consecutive stalled iteration trips, and not before")
    void theThirdConsecutiveStalledIterationTrips() {
        final StalledIterationGuard guard = new StalledIterationGuard();

        assertThat(guard.recordToolIteration(FAILED, false)).isFalse();
        assertThat(guard.recordToolIteration(FAILED, false)).isFalse();
        assertThat(guard.recordToolIteration(FAILED, false)).isTrue();
        assertThat(guard.getConsecutiveStalledIterations())
                .isEqualTo(StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS);
    }

    @Test
    @DisplayName("an iteration that made progress resets the streak")
    void progressResetsTheStreak() {
        final StalledIterationGuard guard = new StalledIterationGuard();

        assertThat(guard.recordToolIteration(FAILED, false)).isFalse();
        assertThat(guard.recordToolIteration(FAILED, false)).isFalse();
        assertThat(guard.recordToolIteration(PROGRESS, false)).isFalse();
        assertThat(guard.getConsecutiveStalledIterations()).isZero();
        assertThat(guard.recordToolIteration(FAILED, false)).isFalse();
        assertThat(guard.recordToolIteration(FAILED, false)).isFalse();
        assertThat(guard.recordToolIteration(FAILED, false)).isTrue();
    }

    @Test
    @DisplayName("the plain stop message is the turn's text, byte for byte")
    void thePlainMessageIsTheTurnsText() {
        final StalledIterationGuard guard = new StalledIterationGuard();
        for (int i = 0; i < StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS; i++) {
            guard.recordToolIteration(FAILED, false);
        }

        assertThat(guard.stopMessage()).isEqualTo(PLAIN_MESSAGE);
    }

    @Test
    @DisplayName("a streak made only of refused cut responses names max_tokens")
    void aStreakOfRefusalsNamesMaxTokens() {
        final StalledIterationGuard guard = new StalledIterationGuard();
        for (int i = 0; i < StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS; i++) {
            guard.recordToolIteration(FAILED, true);
        }

        assertThat(guard.stopMessage()).isEqualTo(PLAIN_MESSAGE
                + " — each of those responses was cut off at max_tokens, and its tool calls were refused");
    }

    @Test
    @DisplayName("a mixed streak gets the plain message")
    void aMixedStreakGetsThePlainMessage() {
        final StalledIterationGuard guard = new StalledIterationGuard();

        guard.recordToolIteration(FAILED, true);
        guard.recordToolIteration(FAILED, false);
        assertThat(guard.recordToolIteration(FAILED, true)).isTrue();

        assertThat(guard.stopMessage()).isEqualTo(PLAIN_MESSAGE).doesNotContain("max_tokens");
    }

    @Test
    @DisplayName("after a reset the refused flag starts fresh, in both directions")
    void afterAResetTheRefusedFlagStartsFresh() {
        final StalledIterationGuard plainThenRefused = new StalledIterationGuard();
        plainThenRefused.recordToolIteration(FAILED, false);
        plainThenRefused.recordToolIteration(PROGRESS, false);
        for (int i = 0; i < StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS; i++) {
            plainThenRefused.recordToolIteration(FAILED, true);
        }
        assertThat(plainThenRefused.stopMessage()).contains("max_tokens");

        final StalledIterationGuard refusedThenPlain = new StalledIterationGuard();
        refusedThenPlain.recordToolIteration(FAILED, true);
        refusedThenPlain.reset();
        for (int i = 0; i < StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS; i++) {
            refusedThenPlain.recordToolIteration(FAILED, false);
        }
        assertThat(refusedThenPlain.stopMessage()).isEqualTo(PLAIN_MESSAGE);
    }
}
