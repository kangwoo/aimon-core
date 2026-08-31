package at.aimon.cli.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.execution.HookResult;

@DisplayName("SubagentLaunchDisplayHook")
class SubagentLaunchDisplayHookTest {

    @Test
    @DisplayName("renders the subagent launch via OutputFormatter and returns a result")
    void rendersLaunch() {
        final OutputFormatter formatter = mock(OutputFormatter.class);
        final SubagentLaunchDisplayHook hook = new SubagentLaunchDisplayHook(formatter);
        final SubagentStartContext context = SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                .invokerName("workflow:perspective:risk").hookRegistry(new DefaultHookRegistry())
                .environment(Environment.createDefault()).subagentName("workflow:perspective:risk").taskId("task-1")
                .goal("assess the rollout risk").build();

        final HookResult result = hook.execute(context);

        verify(formatter).displaySubagentLaunch("workflow:perspective:risk", "assess the rollout risk");
        assertThat(result).isNotNull();
    }
}
