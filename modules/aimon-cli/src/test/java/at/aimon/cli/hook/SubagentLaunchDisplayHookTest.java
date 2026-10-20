package at.aimon.cli.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.tools.ForkAccess;

@DisplayName("SubagentLaunchDisplayHook")
class SubagentLaunchDisplayHookTest {

    @Test
    @DisplayName("renders the subagent launch via OutputFormatter and returns a result")
    void rendersLaunch() {
        final OutputFormatter formatter = mock(OutputFormatter.class);
        final SubagentLaunchDisplayHook hook = new SubagentLaunchDisplayHook(formatter);
        final SubagentStartContext context = SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("workflow:perspective:risk").hookRegistry(new DefaultHookRegistry())
                .subagentName("workflow:perspective:risk").taskId("task-1").goal("assess the rollout risk").build();

        final HookResult result = hook.execute(context);

        verify(formatter).displaySubagentLaunch("workflow:perspective:risk", "assess the rollout risk");
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("labels the launch with the fork's own short task id when it carries one")
    void labelsLaunchWithTaskId() {
        final OutputFormatter formatter = mock(OutputFormatter.class);
        final SubagentLaunchDisplayHook hook = new SubagentLaunchDisplayHook(formatter);
        final SubagentStartContext context = SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("Explore").hookRegistry(new DefaultHookRegistry()).subagentName("Explore")
                .taskId("3fa2c1d0-aaaa").goal("find it")
                .executionAttributes(Map.of(ForkAccess.TASK_ID_ATTRIBUTE, "3fa2c1d0-aaaa")).build();

        hook.execute(context);

        verify(formatter).displaySubagentLaunch("Explore", "find it", "3fa2c1d0");
    }
}
