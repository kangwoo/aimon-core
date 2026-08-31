package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.skill.hook.action.ShellAction;

class DefaultShellActionExecutorTest {

    @Test
    void isShellSupported_returnsTrue() {
        VirtualShell shell = mock(VirtualShell.class);

        assertThat(new DefaultShellActionExecutor(shell).isShellSupported()).isTrue();
    }

    @Test
    void constructor_nullShell_throws() {
        assertThatThrownBy(() -> new DefaultShellActionExecutor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void run_passesCommandAndEnvAndTimeoutToShell() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "ok", "", Duration.ofMillis(10)));

        Map<String, String> env = Map.of("AIMON_HOOK_EVENT", "preTool", "AIMON_TOOL_NAME", "Bash");
        ShellAction action = new ShellAction("echo hi", Duration.ofSeconds(7));

        new DefaultShellActionExecutor(shell).run(action, env);

        ArgumentCaptor<ShellCommand> cmdCaptor = ArgumentCaptor.forClass(ShellCommand.class);
        ArgumentCaptor<ExecutionOptions> optsCaptor = ArgumentCaptor.forClass(ExecutionOptions.class);
        verify(shell).execute(cmdCaptor.capture(), optsCaptor.capture());

        assertThat(cmdCaptor.getValue().asString()).isEqualTo("echo hi");
        assertThat(optsCaptor.getValue().getTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(optsCaptor.getValue().getEnvironment()).containsAllEntriesOf(env);
    }

    @Test
    void run_copiesEnvDefensively_callerMutationsDoNotLeak() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "", "", Duration.ofMillis(1)));

        Map<String, String> env = new HashMap<>();
        env.put("AIMON_HOOK_EVENT", "preTool");
        ShellAction action = new ShellAction("true", Duration.ofSeconds(1));

        new DefaultShellActionExecutor(shell).run(action, env);

        // Mutating the original after the call must not affect what the shell already saw.
        env.put("AIMON_HOOK_EVENT", "postTool");

        ArgumentCaptor<ExecutionOptions> optsCaptor = ArgumentCaptor.forClass(ExecutionOptions.class);
        verify(shell).execute(any(ShellCommand.class), optsCaptor.capture());
        assertThat(optsCaptor.getValue().getEnvironment()).containsEntry("AIMON_HOOK_EVENT", "preTool");
    }

    @Test
    void run_nonZeroExit_doesNotThrow() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(7, "", "boom", Duration.ofMillis(5)));

        new DefaultShellActionExecutor(shell).run(new ShellAction("false", Duration.ofSeconds(1)), Map.of());
        // No exception — non-zero exit is logged but never propagated.
    }

    @Test
    void run_timeoutException_doesNotThrow() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new ShellTimeoutException("timeout", Duration.ofSeconds(1), "", ""));

        new DefaultShellActionExecutor(shell).run(new ShellAction("sleep 999", Duration.ofSeconds(1)), Map.of());
    }

    @Test
    void run_executionException_doesNotThrow() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new ShellExecutionException("io fail"));

        new DefaultShellActionExecutor(shell).run(new ShellAction("nope", Duration.ofSeconds(1)), Map.of());
    }

    @Test
    void run_runtimeException_doesNotThrow() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new RuntimeException("unexpected"));

        new DefaultShellActionExecutor(shell).run(new ShellAction("x", Duration.ofSeconds(1)), Map.of());
    }

    @Test
    void run_nullAction_throwsNpe() {
        VirtualShell shell = mock(VirtualShell.class);

        assertThatThrownBy(() -> new DefaultShellActionExecutor(shell).run(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void run_nullEnv_throwsNpe() {
        VirtualShell shell = mock(VirtualShell.class);
        ShellAction action = new ShellAction("echo", Duration.ofSeconds(1));

        assertThatThrownBy(() -> new DefaultShellActionExecutor(shell).run(action, null))
                .isInstanceOf(NullPointerException.class);
    }
}
