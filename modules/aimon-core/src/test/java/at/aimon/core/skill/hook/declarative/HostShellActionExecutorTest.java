package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.base.UserLocale;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.skill.hook.action.ShellAction;

class HostShellActionExecutorTest {

    @Test
    void constructor_nullShell_throws() {
        assertThatThrownBy(() -> new HostShellActionExecutor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void reportsShellSupportWithoutNeedingAnExecutionEnvironment() {
        HostShellActionExecutor executor = new HostShellActionExecutor(mock(VirtualShell.class));

        assertThat(executor.isShellSupported()).isTrue();
        assertThat(executor.requiresExecutionEnvironment()).isFalse();
        for (HookEventType<?> type : HookEventType.values()) {
            assertThat(executor.canRunOn(type)).as(type.name()).isTrue();
        }
    }

    @Test
    void run_goesToTheFixedShell_evenWhenTheContextCarriesAnEnvironment() throws Exception {
        VirtualShell fixed = mock(VirtualShell.class);
        VirtualShell environmentShell = mock(VirtualShell.class);
        when(fixed.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "ok", "", Duration.ofMillis(1)));
        HookContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).userLocale(UserLocale.createDefault())
                .executionEnvironment(TestExecutionEnvironments.ofShell(environmentShell)).userMessage("hi").build();

        ShellHookOutcome outcome = new HostShellActionExecutor(fixed).run(
                new ShellAction("echo hi", Duration.ofSeconds(3)), context, Map.of("AIMON_HOOK_EVENT", "onStart"),
                "{}");

        ArgumentCaptor<ShellCommand> command = ArgumentCaptor.forClass(ShellCommand.class);
        ArgumentCaptor<ExecutionOptions> options = ArgumentCaptor.forClass(ExecutionOptions.class);
        verify(fixed).execute(command.capture(), options.capture());
        verifyNoInteractions(environmentShell);
        assertThat(command.getValue().asString()).isEqualTo("echo hi");
        assertThat(options.getValue().getTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(options.getValue().getEnvironment()).containsEntry("AIMON_HOOK_EVENT", "onStart");
        assertThat(options.getValue().getStdin()).isEqualTo("{}");
        assertThat(outcome.getExitCode()).isZero();
    }

    @Test
    void run_outOfExecutionContext_stillRuns() throws Exception {
        VirtualShell fixed = mock(VirtualShell.class);
        when(fixed.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "", "", Duration.ofMillis(1)));
        HookContext context = OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).userLocale(UserLocale.createDefault()).build();

        ShellHookOutcome outcome = new HostShellActionExecutor(fixed)
                .run(new ShellAction("true", Duration.ofSeconds(1)), context, Map.of(), null);

        verify(fixed).execute(any(ShellCommand.class), any(ExecutionOptions.class));
        assertThat(outcome.isObserved()).isTrue();
    }

    @Test
    void run_shellFailure_isSwallowed() throws Exception {
        VirtualShell fixed = mock(VirtualShell.class);
        when(fixed.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new ShellTimeoutException("timeout", Duration.ofSeconds(1), "", ""));
        HookContext context = OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).userLocale(UserLocale.createDefault()).build();

        ShellHookOutcome outcome = new HostShellActionExecutor(fixed)
                .run(new ShellAction("sleep 9", Duration.ofSeconds(1)), context, Map.of(), null);

        // Swallowed, but not silently: the hook that reads this decides whether the missing answer blocks.
        assertThat(outcome.isObserved()).isFalse();
        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.TIMEOUT);
    }
}
