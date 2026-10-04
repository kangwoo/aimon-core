package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * The ladder both shell executors share: every way a command can end without an exit status is reported with its own
 * cause, never thrown and never left cause-less (EE-51).
 */
class ShellActionRunnerTest {

    private static final ShellAction ACTION = new ShellAction("guard.sh", Duration.ofSeconds(30));

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new ShellTimeoutException("timeout", Duration.ofSeconds(30), "", ""),
                        ShellHookOutcome.Unrun.TIMEOUT, "no exit status within 30000ms"),
                Arguments.of(new ExecutionEnvironmentUnavailableException("sandbox is down", null),
                        ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE, "sandbox is down"),
                Arguments.of(new ShellExecutionException("cannot fork"), ShellHookOutcome.Unrun.EXECUTION_FAILED,
                        "cannot fork"),
                Arguments.of(new IllegalStateException("Shell is closed"), ShellHookOutcome.Unrun.EXECUTION_FAILED,
                        "Shell is closed"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("failures")
    void run_commandWithoutExitStatus_reportsItsCause(Exception failure, ShellHookOutcome.Unrun cause, String detail)
            throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenThrow(failure);

        ShellHookOutcome outcome = ShellActionRunner.run(shell, ACTION, Map.of(), null);

        assertThat(outcome.isObserved()).isFalse();
        assertThat(outcome.getUnrunCause()).contains(cause);
        assertThat(outcome.unrunReason()).isEqualTo(cause.description() + ": " + detail);
    }

    @Test
    void run_commandThatExits_isObservedWhateverTheCode() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(127, "", "guard.sh: not found", Duration.ofMillis(3)));

        ShellHookOutcome outcome = ShellActionRunner.run(shell, ACTION, Map.of(), null);

        // An exit code — even "command not found" — is an answer; only its absence is "could not run".
        assertThat(outcome.isObserved()).isTrue();
        assertThat(outcome.getExitCode()).isEqualTo(127);
        assertThat(outcome.getUnrunCause()).isEmpty();
    }
}
