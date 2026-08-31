package at.aimon.core.agent.impl.orca.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.skill.fork.NoOpSkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkExecutor;
import at.aimon.core.skill.fork.SubagentBackedSkillForkExecutor;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;

@DisplayName("OrcaSkillForkExecutorResolver")
class OrcaSkillForkExecutorResolverTest {

    private final Agent agent = DefaultAgent.builder().name("test").systemPrompt("p").build();
    private final SubagentRegistry subagentRegistry = mock(SubagentRegistry.class);
    private final ToolRegistry toolRegistry = new DefaultToolRegistry();
    private final HookRegistry hookRegistry = mock(HookRegistry.class);
    private final Environment environment = mock(Environment.class);
    private final SubagentExecutionManager subagentExecutionManager = mock(SubagentExecutionManager.class);

    @Test
    @DisplayName("returns SubagentBackedSkillForkExecutor when all six dependencies are present")
    void resolvesSubagentBackedWhenComplete() {
        SkillForkExecutor resolved = OrcaSkillForkExecutorResolver.resolve(agent, subagentRegistry, toolRegistry,
                hookRegistry, environment, subagentExecutionManager);

        assertThat(resolved).isInstanceOf(SubagentBackedSkillForkExecutor.class);
    }

    @Test
    @DisplayName("falls back to NoOpSkillForkExecutor when agent is null")
    void fallsBackToNoOpWhenAgentMissing() {
        SkillForkExecutor resolved = OrcaSkillForkExecutorResolver.resolve(null, subagentRegistry, toolRegistry,
                hookRegistry, environment, subagentExecutionManager);

        assertThat(resolved).isInstanceOf(NoOpSkillForkExecutor.class);
    }

    @Test
    @DisplayName("falls back to NoOpSkillForkExecutor when subagentRegistry is null")
    void fallsBackToNoOpWhenSubagentRegistryMissing() {
        SkillForkExecutor resolved = OrcaSkillForkExecutorResolver.resolve(agent, null, toolRegistry, hookRegistry,
                environment, subagentExecutionManager);

        assertThat(resolved).isInstanceOf(NoOpSkillForkExecutor.class);
    }

    @Test
    @DisplayName("falls back to NoOpSkillForkExecutor when toolRegistry is null")
    void fallsBackToNoOpWhenToolRegistryMissing() {
        SkillForkExecutor resolved = OrcaSkillForkExecutorResolver.resolve(agent, subagentRegistry, null, hookRegistry,
                environment, subagentExecutionManager);

        assertThat(resolved).isInstanceOf(NoOpSkillForkExecutor.class);
    }

    @Test
    @DisplayName("falls back to NoOpSkillForkExecutor when hookRegistry is null")
    void fallsBackToNoOpWhenHookRegistryMissing() {
        SkillForkExecutor resolved = OrcaSkillForkExecutorResolver.resolve(agent, subagentRegistry, toolRegistry, null,
                environment, subagentExecutionManager);

        assertThat(resolved).isInstanceOf(NoOpSkillForkExecutor.class);
    }

    @Test
    @DisplayName("falls back to NoOpSkillForkExecutor when environment is null")
    void fallsBackToNoOpWhenEnvironmentMissing() {
        SkillForkExecutor resolved = OrcaSkillForkExecutorResolver.resolve(agent, subagentRegistry, toolRegistry,
                hookRegistry, null, subagentExecutionManager);

        assertThat(resolved).isInstanceOf(NoOpSkillForkExecutor.class);
    }

    @Test
    @DisplayName("falls back to NoOpSkillForkExecutor when subagentExecutionManager is null")
    void fallsBackToNoOpWhenSubagentExecutionManagerMissing() {
        SkillForkExecutor resolved = OrcaSkillForkExecutorResolver.resolve(agent, subagentRegistry, toolRegistry,
                hookRegistry, environment, null);

        assertThat(resolved).isInstanceOf(NoOpSkillForkExecutor.class);
    }
}
