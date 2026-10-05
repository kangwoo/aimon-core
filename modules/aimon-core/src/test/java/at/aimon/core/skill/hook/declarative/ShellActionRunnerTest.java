package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import at.aimon.core.shell.impl.local.LocalShell;
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
                // A shell failure's message routinely quotes the command; only its type reaches the reason.
                Arguments.of(new ShellExecutionException("cannot fork: guard.sh"),
                        ShellHookOutcome.Unrun.EXECUTION_FAILED, "ShellExecutionException"),
                Arguments.of(new IllegalStateException("Shell is closed"), ShellHookOutcome.Unrun.EXECUTION_FAILED,
                        "IllegalStateException"),
                // Both roads an interrupt takes read the same (EE-80): the command's cancellation signal, and a
                // shell that answers a thread interrupt (LocalShell wraps the InterruptedException).
                Arguments.of(new at.aimon.core.shell.exception.ShellCancelledException("Process cancelled: guard.sh"),
                        ShellHookOutcome.Unrun.CANCELLED, ""),
                Arguments.of(new ShellExecutionException("Interrupted: guard.sh", new InterruptedException()),
                        ShellHookOutcome.Unrun.CANCELLED, ""));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("failures")
    void run_commandWithoutExitStatus_reportsItsCause(Exception failure, ShellHookOutcome.Unrun cause, String detail)
            throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenThrow(failure);

        ShellHookOutcome outcome = ShellActionRunner.run(shell, ACTION, Map.of(), null, java.util.Optional.empty());

        assertThat(outcome.isObserved()).isFalse();
        assertThat(outcome.getUnrunCause()).contains(cause);
        assertThat(outcome.unrunReason())
                .isEqualTo(detail.isEmpty() ? cause.description() : cause.description() + ": " + detail);
    }

    @Test
    void run_realShellThatCannotStart_keepsTheCommandOutOfTheReason(@TempDir Path tmp) {
        // A real LocalShell start failure: its exception message is "Failed to start process: <command>".
        VirtualShell shell = new LocalShell(tmp.resolve("does-not-exist"));
        ShellAction action = new ShellAction("guard.sh --token s3cret", Duration.ofSeconds(5));

        ShellHookOutcome outcome = ShellActionRunner.run(shell, action, Map.of(), null, java.util.Optional.empty());

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.EXECUTION_FAILED);
        assertThat(outcome.unrunReason()).doesNotContain("guard.sh").doesNotContain("s3cret")
                .isEqualTo(ShellHookOutcome.Unrun.EXECUTION_FAILED.description() + ": ShellExecutionException");
    }

    @Test
    void run_commandThatExits_isObservedWhateverTheCode() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(127, "", "guard.sh: not found", Duration.ofMillis(3)));

        ShellHookOutcome outcome = ShellActionRunner.run(shell, ACTION, Map.of(), null, java.util.Optional.empty());

        // The runner reports the exit code as it came: it serves advisory events too, and there 127 is only logged.
        assertThat(outcome.isObserved()).isTrue();
        assertThat(outcome.getExitCode()).isEqualTo(127);
        assertThat(outcome.getUnrunCause()).isEmpty();
        // Reading "command not found" as "could not run" is the guard events' decision (EE-66), and it never
        // carries the shell's stderr, which quotes the command.
        assertThat(outcome.asGuardAnswer().getUnrunCause()).contains(ShellHookOutcome.Unrun.COMMAND_NOT_FOUND);
        assertThat(outcome.asGuardAnswer().unrunReason()).isEqualTo("command not found: exit code 127");
    }
}
