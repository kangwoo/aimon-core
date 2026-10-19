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
import at.aimon.core.shell.exception.RunDirectoryIntegrityException;
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

        ShellHookOutcome outcome = ShellActionRunner.run(shell, ACTION, Map.of(), null, java.util.Optional.empty(),
                false);

        assertThat(outcome.isObserved()).isFalse();
        assertThat(outcome.getUnrunCause()).contains(cause);
        assertThat(outcome.unrunReason())
                .isEqualTo(detail.isEmpty() ? cause.description() : cause.description() + ": " + detail);
    }

    @Test
    void run_tamperedStdinWithNoRunDirectory_isTamperingAndBlocksWithFailOpen() throws Exception {
        // #283 F2: a shell that uploads the stdin payload checks it too, and reports a mismatch with the same
        // exception,
        // for a hook that has no run directory at all.
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new RunDirectoryIntegrityException("the hook's input does not match"));

        ShellHookOutcome outcome = ShellActionRunner.run(shell, ACTION, Map.of(), "{\"tool\":\"Bash\"}",
                java.util.Optional.empty(), true, null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.HOOK_FILES_TAMPERED);
        assertThat(outcome.unrunReason()).isEqualTo("the hook's files or input changed before the run");
        assertThat(ShellHookVerdicts.guard(outcome, true, "audit", "preTool"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("files or input changed before the run"));
    }

    @Test
    void run_realShellThatCannotStart_keepsTheCommandOutOfTheReason(@TempDir Path tmp) {
        // A real LocalShell start failure: its exception message is "Failed to start process: <command>".
        VirtualShell shell = new LocalShell(tmp.resolve("does-not-exist"));
        ShellAction action = new ShellAction("guard.sh --token s3cret", Duration.ofSeconds(5));

        ShellHookOutcome outcome = ShellActionRunner.run(shell, action, Map.of(), null, java.util.Optional.empty(),
                false);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.EXECUTION_FAILED);
        assertThat(outcome.unrunReason()).doesNotContain("guard.sh").doesNotContain("s3cret")
                .isEqualTo(ShellHookOutcome.Unrun.EXECUTION_FAILED.description() + ": ShellExecutionException");
    }

    @Test
    void run_commandThatExits_isObservedWhateverTheCode() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(127, "", "guard.sh: not found", Duration.ofMillis(3)));

        ShellHookOutcome outcome = ShellActionRunner.run(shell, ACTION, Map.of(), null, java.util.Optional.empty(),
                false);

        // The runner reports the exit code as it came: it serves advisory events too, and there 127 is only logged.
        assertThat(outcome.isObserved()).isTrue();
        assertThat(outcome.getExitCode()).isEqualTo(127);
        assertThat(outcome.getUnrunCause()).isEmpty();
        // Reading "command not found" as "could not run" is the guard events' decision (EE-66), and it never
        // carries the shell's stderr, which quotes the command.
        assertThat(outcome.asGuardAnswer().getUnrunCause()).contains(ShellHookOutcome.Unrun.COMMAND_NOT_FOUND);
        assertThat(outcome.asGuardAnswer().unrunReason()).isEqualTo("command not found: exit code 127");
    }

    @Test
    void run_commandWithEnvironmentNotices_keepsThemOutOfTheHookOutcome() throws Exception {
        // A hook result is never where an environment notice is delivered (#254): the notice is about the
        // environment, and the model hears the environment's own queue on the tool result instead.
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenReturn(new ShellCommandResult(0,
                "ok", "", Duration.ofMillis(3), false, java.util.List.of("shell session was reopened")));

        ShellHookOutcome outcome = ShellActionRunner.run(shell, ACTION, Map.of(), null, java.util.Optional.empty(),
                false);

        assertThat(outcome.getStdout()).isEqualTo("ok");
        assertThat(outcome.getStderr()).isEmpty();
        assertThat(outcome.toString()).doesNotContain("shell session was reopened");
    }
}
