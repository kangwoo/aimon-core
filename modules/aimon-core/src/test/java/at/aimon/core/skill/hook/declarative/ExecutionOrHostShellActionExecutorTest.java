package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.action.ShellAction;

class ExecutionOrHostShellActionExecutorTest {

    private static final ShellAction ACTION = new ShellAction("guard", Duration.ofSeconds(1));

    private final ShellActionExecutor environment = mock(ShellActionExecutor.class);
    private final ShellActionExecutor host = mock(ShellActionExecutor.class);
    private final ExecutionOrHostShellActionExecutor router = new ExecutionOrHostShellActionExecutor(environment, host);

    @Test
    void aFiringWithAnExecution_goesToTheEnvironment() {
        final HookContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry())
                .executionEnvironment(TestExecutionEnvironments.ofShell(mock(VirtualShell.class))).userMessage("hi")
                .build();
        when(environment.run(any(), any(), anyMap(), any())).thenReturn(ShellHookOutcome.of(0, "", ""));

        router.run(ACTION, context, Map.of(), null);

        verify(environment).run(ACTION, context, Map.of(), null);
        verify(host, never()).run(any(), any(), anyMap(), any());
    }

    @Test
    void anExecutionWhoseEnvironmentIsMissing_stillGoesToTheEnvironment() {
        // A wiring gap, not "no execution": quietly using the host here is the fallback the design rules out.
        final HookContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).userMessage("hi").build();

        router.run(ACTION, context, Map.of(), null);

        verify(environment).run(ACTION, context, Map.of(), null);
        verify(host, never()).run(any(), any(), anyMap(), any());
    }

    @Test
    void aFiringWithNoExecution_goesToTheHost() {
        final HookContext context = SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("worker").hookRegistry(new DefaultHookRegistry()).subagentName("worker").taskId("t")
                .goal("g").spawnedOutsideExecution(true).build();

        router.run(ACTION, context, Map.of(), null);

        verify(host).run(ACTION, context, Map.of(), null);
        verify(environment, never()).run(any(), any(), anyMap(), any());
    }

    @Test
    void canRunAnywhere() {
        when(environment.isShellSupported()).thenReturn(true);
        when(host.isShellSupported()).thenReturn(true);

        assertThat(router.isShellSupported()).isTrue();
        assertThat(router.requiresExecutionEnvironment()).isFalse();
        for (HookEventType<?> type : HookEventType.values()) {
            assertThat(router.canRunOn(type)).as(type.name()).isTrue();
        }
    }

    @Test
    void supportsShellsOnlyWhenBothDo() {
        when(environment.isShellSupported()).thenReturn(true);
        when(host.isShellSupported()).thenReturn(false);

        assertThat(router.isShellSupported()).isFalse();
    }
}
