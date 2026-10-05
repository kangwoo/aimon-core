package at.aimon.core.agent.impl.orca.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentMetadata;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory;
import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.search.KeywordToolSearchStrategy;
import at.aimon.core.agent.tool.search.ToolSearchCatalog;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.tools.bash.BackgroundBashManager;
import at.aimon.core.tools.bash.BashOutputTool;
import at.aimon.core.tools.bash.BashTool;
import at.aimon.core.tools.bash.KillShellTool;
import at.aimon.core.tools.knowledge.KnowledgeSearchTool;
import at.aimon.core.tools.scheduling.CancelScheduledTaskTool;
import at.aimon.core.tools.scheduling.ListScheduledTasksTool;
import at.aimon.core.tools.scheduling.ScheduleTaskTool;
import at.aimon.core.tools.skill.SkillTool;
import at.aimon.core.tools.task.AgentOutputTool;
import at.aimon.core.tools.task.TaskTool;
import at.aimon.core.tools.todo.TodoWriteTool;
import at.aimon.core.tools.workflow.WorkflowTool;

class OrcaToolProvidersTest {

    private OrcaToolProviderContext context(OrcaProviderDependencies deps) {
        return OrcaToolProviderContext.builder().dependencies(deps).build();
    }

    private OrcaToolProviderContext context(OrcaProviderDependencies deps, Agent agent) {
        return OrcaToolProviderContext.builder().agent(agent).dependencies(deps).build();
    }

    // ---- OrcaBashToolProvider ---------------------------------------------------------------------

    @Test
    void bashProviderRegistersBashAndBashOutputToolsWithoutAnyShell() {
        OrcaBashToolProvider provider = new OrcaBashToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();

        // No shell exists at registration time any more: Bash runs in the shell of each execution's environment
        // (execution-environment design §6). So the tools are always registered, and an execution whose environment
        // has no usable shell gets an error from the call instead of a missing tool.
        provider.registerTools(registry, context(OrcaProviderDependencies.builder().build()));

        assertThat(registry.findByName(BashTool.TOOL_NAME)).isPresent();
        assertThat(registry.findByName(BashOutputTool.TOOL_NAME)).isPresent();
        assertThat(registry.findByName(KillShellTool.TOOL_NAME)).isPresent();
    }

    @Test
    void bashProviderOverASharedManagerGivesEveryRegistryTheSameTaskList() {
        try (BackgroundBashManager shared = new BackgroundBashManager()) {
            OrcaBashToolProvider provider = new OrcaBashToolProvider(shared);
            ToolRegistry evicted = new DefaultToolRegistry();
            ToolRegistry rebuilt = new DefaultToolRegistry();
            provider.registerTools(evicted, context(OrcaProviderDependencies.builder().build()));
            provider.registerTools(rebuilt, context(OrcaProviderDependencies.builder().build()));
            shared.registerTask("bash_shared01", "sleep 1", CompletableFuture
                    .completedFuture(new ShellCommandResult(0, "from the shared list", "", Duration.ofMillis(1))));

            // A task the first registry's tools know is one the second registry's tools find: the list is the
            // provider's manager, not the registry's.
            for (ToolRegistry registry : List.of(evicted, rebuilt)) {
                ToolResult output = registry.findByName(BashOutputTool.TOOL_NAME).orElseThrow()
                        .execute(ToolInput.of(Map.of("taskId", "bash_shared01", "block", false)), ToolContext.empty());
                assertThat(output.isSuccess()).as(output.getContent()).isTrue();
                ToolResult kill = registry.findByName(KillShellTool.TOOL_NAME).orElseThrow()
                        .execute(ToolInput.of(Map.of("taskId", "bash_shared01")), ToolContext.empty());
                assertThat(kill.getContent()).contains("is not running");
            }
        }
    }

    @Test
    void bashProviderWithoutAManagerGivesEachRegistryItsOwnTaskList() {
        OrcaBashToolProvider provider = new OrcaBashToolProvider();
        ToolRegistry first = new DefaultToolRegistry();
        ToolRegistry second = new DefaultToolRegistry();
        provider.registerTools(first, context(OrcaProviderDependencies.builder().build()));
        provider.registerTools(second, context(OrcaProviderDependencies.builder().build()));

        assertThat(first.findByName(BashOutputTool.TOOL_NAME).orElseThrow())
                .isNotSameAs(second.findByName(BashOutputTool.TOOL_NAME).orElseThrow());
        assertThatNullPointerException().isThrownBy(() -> new OrcaBashToolProvider(null));
    }

    @Test
    void bashProviderRejectsNullArguments() {
        OrcaBashToolProvider provider = new OrcaBashToolProvider();
        OrcaToolProviderContext ctx = context(OrcaProviderDependencies.builder().build());
        ToolRegistry registry = new DefaultToolRegistry();

        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(null, ctx));
        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(registry, null));
    }

    // ---- OrcaTodoToolProvider ---------------------------------------------------------------------

    @Test
    void todoProviderRegistersTodoWriteTool() {
        OrcaTodoToolProvider provider = new OrcaTodoToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, context(OrcaProviderDependencies.builder().build()));

        assertThat(registry.findByName(TodoWriteTool.TOOL_NAME)).isPresent();
    }

    @Test
    void todoProviderRejectsNullArguments() {
        OrcaTodoToolProvider provider = new OrcaTodoToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext ctx = context(OrcaProviderDependencies.builder().build());

        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(null, ctx));
        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(registry, null));
    }

    // ---- OrcaKnowledgeToolProvider ----------------------------------------------------------------

    @Test
    void knowledgeProviderRegistersWithDefaultRegistry() {
        OrcaKnowledgeToolProvider provider = new OrcaKnowledgeToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, context(OrcaProviderDependencies.builder().build()));

        assertThat(registry.findByName(KnowledgeSearchTool.TOOL_NAME)).isPresent();
    }

    @Test
    void knowledgeProviderRegistersAsEagerInToolSearchCatalog() {
        OrcaKnowledgeToolProvider provider = new OrcaKnowledgeToolProvider();
        ToolSearchCatalog catalog = new ToolSearchCatalog(new KeywordToolSearchStrategy());

        provider.registerTools(catalog, context(OrcaProviderDependencies.builder().build()));

        assertThat(catalog.findByName(KnowledgeSearchTool.TOOL_NAME)).isPresent();
        assertThat(catalog.getEagerTools()).extracting(t -> t.getDefinition().getName())
                .contains(KnowledgeSearchTool.TOOL_NAME);
    }

    @Test
    void knowledgeProviderRejectsNullArguments() {
        OrcaKnowledgeToolProvider provider = new OrcaKnowledgeToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext ctx = context(OrcaProviderDependencies.builder().build());

        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(null, ctx));
        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(registry, null));
    }

    // ---- OrcaSchedulingToolProvider ---------------------------------------------------------------

    @Test
    void schedulingProviderRegistersAllSchedulingToolsWhenManagerPresent() {
        OrcaSchedulingToolProvider provider = new OrcaSchedulingToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaProviderDependencies deps = OrcaProviderDependencies.builder()
                .scheduledTaskManager(mock(ScheduledTaskManager.class)).build();

        provider.registerTools(registry, context(deps));

        assertThat(registry.findByName(ScheduleTaskTool.TOOL_NAME)).isPresent();
        assertThat(registry.findByName(ListScheduledTasksTool.TOOL_NAME)).isPresent();
        assertThat(registry.findByName(CancelScheduledTaskTool.TOOL_NAME)).isPresent();
    }

    @Test
    void schedulingProviderSkipsRegistrationWhenManagerMissing() {
        OrcaSchedulingToolProvider provider = new OrcaSchedulingToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, context(OrcaProviderDependencies.builder().build()));

        assertThat(registry.findAll()).isEmpty();
    }

    @Test
    void schedulingProviderRejectsNullArguments() {
        OrcaSchedulingToolProvider provider = new OrcaSchedulingToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext ctx = context(OrcaProviderDependencies.builder().build());

        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(null, ctx));
        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(registry, null));
    }

    // ---- OrcaSkillToolProvider --------------------------------------------------------------------

    @Test
    void skillProviderRegistersSkillTool() {
        OrcaSkillToolProvider provider = new OrcaSkillToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaProviderDependencies deps = OrcaProviderDependencies.builder().skillRegistry(mock(SkillRegistry.class))
                .build();

        provider.registerTools(registry, context(deps));

        assertThat(registry.findByName(SkillTool.TOOL_NAME)).isPresent();
    }

    @Test
    void skillProviderRequiresSkillRegistryInContext() {
        OrcaSkillToolProvider provider = new OrcaSkillToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();
        OrcaToolProviderContext ctx = context(OrcaProviderDependencies.builder().build());

        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(registry, ctx))
                .withMessageContaining("skillRegistry");
    }

    @Test
    void skillProviderRejectsNullArguments() {
        OrcaSkillToolProvider provider = new OrcaSkillToolProvider();
        OrcaProviderDependencies deps = OrcaProviderDependencies.builder().skillRegistry(mock(SkillRegistry.class))
                .build();
        OrcaToolProviderContext ctx = context(deps);
        ToolRegistry registry = new DefaultToolRegistry();

        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(null, ctx));
        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(registry, null));
    }

    // ---- OrcaSubagentToolProvider -----------------------------------------------------------------

    @Test
    void subagentProviderRegistersTaskAndAgentOutputTools() {
        Agent agent = stubAgent();
        SubagentExecutionManager execMgr = mock(SubagentExecutionManager.class);

        OrcaProviderDependencies deps = OrcaProviderDependencies.builder()
                .subagentRegistry(mock(SubagentRegistry.class)).toolRegistry(mock(ToolRegistry.class))
                .hookRegistry(mock(HookRegistry.class)).subagentExecutionManager(execMgr).build();

        OrcaSubagentToolProvider provider = new OrcaSubagentToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, context(deps, agent));

        assertThat(registry.findByName(TaskTool.TOOL_NAME)).isPresent();
        assertThat(registry.findByName(AgentOutputTool.TOOL_NAME)).isPresent();
        assertThat(registry.findByName(at.aimon.core.tools.task.TaskListTool.TOOL_NAME)).isPresent();
        assertThat(registry.findByName(at.aimon.core.tools.task.TaskStopTool.TOOL_NAME)).isPresent();
        // Workflow is opt-in — not registered by the default (no-arg) provider.
        assertThat(registry.findByName(WorkflowTool.TOOL_NAME)).isNotPresent();
    }

    @Test
    void subagentProviderRegistersWorkflowWhenEnabled() {
        Agent agent = stubAgent();
        SubagentExecutionManager execMgr = mock(SubagentExecutionManager.class);

        OrcaProviderDependencies deps = OrcaProviderDependencies.builder()
                .subagentRegistry(mock(SubagentRegistry.class)).toolRegistry(mock(ToolRegistry.class))
                .hookRegistry(mock(HookRegistry.class)).subagentExecutionManager(execMgr).build();

        ToolRegistry registry = new DefaultToolRegistry();

        new OrcaSubagentToolProvider(true).registerTools(registry, context(deps, agent));

        assertThat(registry.findByName(WorkflowTool.TOOL_NAME)).isPresent();
        assertThat(registry.findByName(TaskTool.TOOL_NAME)).isPresent();
    }

    @Test
    void defaultToolProvidersWiresWorkflowFlagIntoSubagentProvider() {
        // Exercises the actual wiring seam: defaultToolProviders(boolean) must construct the subagent provider with the
        // flag, so the enabled default set registers Workflow and the default (disabled) set does not.
        Agent agent = stubAgent();
        SubagentExecutionManager execMgr = mock(SubagentExecutionManager.class);
        OrcaProviderDependencies deps = OrcaProviderDependencies.builder()
                .subagentRegistry(mock(SubagentRegistry.class)).toolRegistry(mock(ToolRegistry.class))
                .hookRegistry(mock(HookRegistry.class)).subagentExecutionManager(execMgr).build();
        OrcaToolProviderContext ctx = context(deps, agent);

        ToolRegistry enabled = new DefaultToolRegistry();
        subagentProviderFrom(OrcaAgentRuntimeFactory.defaultToolProviders(true)).registerTools(enabled, ctx);
        assertThat(enabled.findByName(WorkflowTool.TOOL_NAME)).isPresent();

        ToolRegistry disabled = new DefaultToolRegistry();
        subagentProviderFrom(OrcaAgentRuntimeFactory.defaultToolProviders(false)).registerTools(disabled, ctx);
        assertThat(disabled.findByName(WorkflowTool.TOOL_NAME)).isNotPresent();
    }

    private static OrcaSubagentToolProvider subagentProviderFrom(List<OrcaToolProvider> providers) {
        return providers.stream().filter(OrcaSubagentToolProvider.class::isInstance)
                .map(OrcaSubagentToolProvider.class::cast).findFirst().orElseThrow();
    }

    @Test
    void subagentProviderRequiresAgentInContext() {
        OrcaProviderDependencies deps = OrcaProviderDependencies.builder()
                .subagentRegistry(mock(SubagentRegistry.class)).toolRegistry(mock(ToolRegistry.class))
                .hookRegistry(mock(HookRegistry.class)).subagentExecutionManager(mock(SubagentExecutionManager.class))
                .build();

        OrcaSubagentToolProvider provider = new OrcaSubagentToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();

        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(registry, context(deps)))
                .withMessageContaining("agent");
    }

    @Test
    void subagentProviderRequiresSubagentExecutionManagerInContext() {
        Agent agent = stubAgent();
        OrcaProviderDependencies deps = OrcaProviderDependencies.builder()
                .subagentRegistry(mock(SubagentRegistry.class)).toolRegistry(mock(ToolRegistry.class))
                .hookRegistry(mock(HookRegistry.class)).build();

        OrcaSubagentToolProvider provider = new OrcaSubagentToolProvider();
        ToolRegistry registry = new DefaultToolRegistry();

        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(registry, context(deps, agent)))
                .withMessageContaining("subagentExecutionManager");
    }

    @Test
    void subagentProviderRejectsNullArguments() {
        OrcaSubagentToolProvider provider = new OrcaSubagentToolProvider();
        OrcaToolProviderContext ctx = context(OrcaProviderDependencies.builder().build());
        ToolRegistry registry = new DefaultToolRegistry();

        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(null, ctx));
        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(registry, null));
    }

    private Agent stubAgent() {
        Agent agent = mock(Agent.class);
        AgentMetadata metadata = mock(AgentMetadata.class);
        LlmModel model = mock(LlmModel.class);
        when(agent.getMetadata()).thenReturn(metadata);
        when(metadata.getModel()).thenReturn(model);
        return agent;
    }
}
