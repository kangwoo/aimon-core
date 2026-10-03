package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.base.UserLocale;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.ShellActionExecutor;
import at.aimon.core.skill.hook.declarative.ShellHookOutcome;

/**
 * EE-48 on the CLI assembly: {@code hooks.json} shell actions run on the CLI's host shell, not in an execution
 * environment. The file is operator configuration and declares session- and config-lifecycle events that fire outside
 * any execution, so an environment-bound executor here would silently skip them.
 */
@DisplayName("AgentSetupFactory hooks.json executor: host shell")
class AgentSetupFactoryHookConfigShellTest {

    private static ShellCommandResult ok() {
        return new ShellCommandResult(0, "", "", Duration.ofMillis(1));
    }

    @Test
    @DisplayName("needs no execution environment and accepts every event, lifecycle ones included")
    void needsNoExecutionEnvironment() {
        final ShellActionExecutor executor = AgentSetupFactory.createHookConfigShellExecutor(mock(VirtualShell.class));

        assertThat(executor.isShellSupported()).isTrue();
        assertThat(executor.requiresExecutionEnvironment()).isFalse();
        assertThat(executor.canRunOn(HookEventType.ON_SESSION_START)).isTrue();
        assertThat(executor.canRunOn(HookEventType.ON_CONFIG_RELOAD)).isTrue();
    }

    @Test
    @DisplayName("runs a session-lifecycle action on the host shell")
    void runsOutOfExecutionActionsOnTheHostShell() throws Exception {
        final VirtualShell host = mock(VirtualShell.class);
        when(host.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenReturn(ok());
        final ShellActionExecutor executor = AgentSetupFactory.createHookConfigShellExecutor(host);

        final ShellHookOutcome outcome = executor.run(new ShellAction("echo hi", Duration.ofSeconds(1)),
                OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                        .hookRegistry(new DefaultHookRegistry()).userLocale(UserLocale.createDefault()).build(),
                Map.of(), null);

        verify(host).execute(any(ShellCommand.class), any(ExecutionOptions.class));
        assertThat(outcome.isObserved()).isTrue();
    }

    @Test
    @DisplayName("stays on the host shell even when the hook context carries an execution environment")
    void staysOnTheHostShellInsideAnExecution() throws Exception {
        final VirtualShell host = mock(VirtualShell.class);
        final VirtualShell environmentShell = mock(VirtualShell.class);
        final ExecutionEnvironment environment = mock(ExecutionEnvironment.class);
        when(environment.shell()).thenReturn(environmentShell);
        when(host.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenReturn(ok());
        final ShellActionExecutor executor = AgentSetupFactory.createHookConfigShellExecutor(host);

        executor.run(new ShellAction("echo hi", Duration.ofSeconds(1)),
                OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                        .hookRegistry(new DefaultHookRegistry()).userLocale(UserLocale.createDefault())
                        .executionEnvironment(environment).userMessage("hi").build(),
                Map.of(), null);

        verify(host).execute(any(ShellCommand.class), any(ExecutionOptions.class));
        verifyNoInteractions(environmentShell);
    }
}
