package at.aimon.core.agent.impl.orca.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;

class OrcaCommandProviderContextTest {

    @Test
    void shouldRequireDependencies() {
        assertThatNullPointerException().isThrownBy(() -> OrcaCommandProviderContext.builder()
                .commandRegistry(mock(CommandRegistry.class)).version("1.0.0").build())
                .withMessageContaining("dependencies");
    }

    @Test
    void shouldBuildWithMinimumFields() {
        OrcaProviderDependencies deps = OrcaProviderDependencies.builder().build();

        OrcaCommandProviderContext context = OrcaCommandProviderContext.builder().dependencies(deps).build();

        assertThat(context.getDependencies()).isSameAs(deps);
        assertThat(context.getCommandRegistry()).isNull();
        assertThat(context.getVersion()).isNull();
    }

    @Test
    void shouldDelegateAllGettersToDependencies() {
        SubagentRegistry subagentRegistry = mock(SubagentRegistry.class);
        SubagentExecutionManager subagentExec = mock(SubagentExecutionManager.class);
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        HookRegistry hookRegistry = mock(HookRegistry.class);
        HookExecutionManager hookExec = mock(HookExecutionManager.class);
        ScheduledTaskManager scheduledTaskManager = mock(ScheduledTaskManager.class);
        CredentialStore credentialStore = mock(CredentialStore.class);
        Environment environment = mock(Environment.class);
        CompactionEngine compactionEngine = mock(CompactionEngine.class);
        CompactionGuard compactionGuard = mock(CompactionGuard.class);
        PendingTurnRegistry pendingTurnRegistry = mock(PendingTurnRegistry.class);
        AgentApprovalStore agentApprovalStore = mock(AgentApprovalStore.class);

        OrcaProviderDependencies deps = OrcaProviderDependencies.builder().subagentRegistry(subagentRegistry)
                .subagentExecutionManager(subagentExec).skillRegistry(skillRegistry).toolRegistry(toolRegistry)
                .hookRegistry(hookRegistry).hookExecutionManager(hookExec).scheduledTaskManager(scheduledTaskManager)
                .credentialStore(credentialStore).environment(environment).compactionEngine(compactionEngine)
                .compactionGuard(compactionGuard).pendingTurnRegistry(pendingTurnRegistry)
                .agentApprovalStore(agentApprovalStore).build();

        CommandRegistry commandRegistry = mock(CommandRegistry.class);
        OrcaCommandProviderContext context = OrcaCommandProviderContext.builder().commandRegistry(commandRegistry)
                .version("9.9.9").dependencies(deps).build();

        assertThat(context.getCommandRegistry()).isSameAs(commandRegistry);
        assertThat(context.getVersion()).isEqualTo("9.9.9");
        assertThat(context.getSubagentRegistry()).isSameAs(subagentRegistry);
        assertThat(context.getSubagentExecutionManager()).isSameAs(subagentExec);
        assertThat(context.getSkillRegistry()).isSameAs(skillRegistry);
        assertThat(context.getToolRegistry()).isSameAs(toolRegistry);
        assertThat(context.getHookRegistry()).isSameAs(hookRegistry);
        assertThat(context.getHookExecutionManager()).isSameAs(hookExec);
        assertThat(context.getScheduledTaskManager()).isSameAs(scheduledTaskManager);
        assertThat(context.getCredentialStore()).isSameAs(credentialStore);
        assertThat(context.getEnvironment()).isSameAs(environment);
        assertThat(context.getCompactionEngine()).isSameAs(compactionEngine);
        assertThat(context.getCompactionGuard()).isSameAs(compactionGuard);
        assertThat(context.getPendingTurnRegistry()).isSameAs(pendingTurnRegistry);
        assertThat(context.getAgentApprovalStore()).isSameAs(agentApprovalStore);
    }

    @Test
    void shouldReturnNullForUnsetOptionalDependencies() {
        OrcaProviderDependencies deps = OrcaProviderDependencies.builder().build();
        OrcaCommandProviderContext context = OrcaCommandProviderContext.builder().dependencies(deps).build();

        assertThat(context.getSubagentRegistry()).isNull();
        assertThat(context.getSubagentExecutionManager()).isNull();
        assertThat(context.getSkillRegistry()).isNull();
        assertThat(context.getToolRegistry()).isNull();
        assertThat(context.getHookRegistry()).isNull();
        assertThat(context.getHookExecutionManager()).isNull();
        assertThat(context.getScheduledTaskManager()).isNull();
        assertThat(context.getCredentialStore()).isNull();
        assertThat(context.getEnvironment()).isNull();
        assertThat(context.getCompactionEngine()).isNull();
        assertThat(context.getCompactionGuard()).isNull();
        assertThat(context.getPendingTurnRegistry()).isNull();
        assertThat(context.getAgentApprovalStore()).isNull();
    }
}
