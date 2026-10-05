package at.aimon.cli.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentMetadata;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.workflow.graaljs.GraalJsEngineHolder;
import at.aimon.workflow.graaljs.GraalJsWorkflowTool;

/**
 * Unit tests for {@link GraalJsWorkflowToolProvider}, the CLI-side provider that registers the Phase 5
 * {@code WorkflowJs} tool.
 *
 * <p>
 * The {@link GraalJsEngineHolder} is mocked (Mockito 5 mocks the final holder
 * without spinning up a real GraalVM engine): the provider only stores the references and hands them to the tool
 * builder, so no engine method runs during registration.
 */
class GraalJsWorkflowToolProviderTest {

    private static OrcaToolProviderContext newContext() {
        final Agent agent = mock(Agent.class);
        final AgentMetadata metadata = mock(AgentMetadata.class);
        when(agent.getMetadata()).thenReturn(metadata);
        when(metadata.getModel()).thenReturn(mock(LlmModel.class));

        final OrcaToolProviderContext context = mock(OrcaToolProviderContext.class);
        when(context.getAgent()).thenReturn(agent);
        when(context.getSubagentRegistry()).thenReturn(mock(SubagentRegistry.class));
        when(context.getToolRegistry()).thenReturn(mock(ToolRegistry.class));
        when(context.getHookRegistry()).thenReturn(mock(HookRegistry.class));
        when(context.getSubagentExecutionManager()).thenReturn(mock(SubagentExecutionManager.class));
        when(context.getToolContextEnrichers()).thenReturn(List.of());
        // getWorkflowRunner() is left at the mock default (null): background mode disabled, which the tool tolerates.
        return context;
    }

    @Test
    void registerTools_registersWorkflowJsTool() {
        // No worktree factory any more: an isolated step derives its branch from the execution environment.
        final GraalJsWorkflowToolProvider provider = new GraalJsWorkflowToolProvider(mock(GraalJsEngineHolder.class));
        final ToolRegistry registry = new DefaultToolRegistry();

        provider.registerTools(registry, newContext());

        assertThat(registry.findByName(GraalJsWorkflowTool.TOOL_NAME)).isPresent();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void constructor_rejectsNullEngines() {
        assertThatNullPointerException().isThrownBy(() -> new GraalJsWorkflowToolProvider(null));
    }

    @Test
    void registerTools_rejectsNullArguments() {
        final GraalJsWorkflowToolProvider provider = new GraalJsWorkflowToolProvider(mock(GraalJsEngineHolder.class));

        // registry is null-checked first, before the context is ever read, so a bare context mock suffices here.
        assertThatNullPointerException()
                .isThrownBy(() -> provider.registerTools(null, mock(OrcaToolProviderContext.class)));
        assertThatNullPointerException().isThrownBy(() -> provider.registerTools(new DefaultToolRegistry(), null));
    }
}
