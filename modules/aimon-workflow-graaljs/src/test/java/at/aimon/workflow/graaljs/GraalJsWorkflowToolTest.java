package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.workflow.WorkflowBackgroundConfig;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowRunnerOptions;
import at.aimon.core.workflow.WorkflowRunners;

/**
 * Consumer-wiring tests for {@link GraalJsWorkflowTool}: foreground/background dispatch, environment
 * building, budget wiring, and never-throw error translation. Mocked manager, no LLM.
 */
@DisplayName("GraalJsWorkflowTool — consumer wiring")
class GraalJsWorkflowToolTest extends AbstractGraalJsRunTest {

    private GraalJsWorkflowTool tool(WorkflowRunner backgroundRunner) {
        return tool(backgroundRunner, null);
    }

    private GraalJsWorkflowTool tool(WorkflowRunner backgroundRunner, JsSandboxConfig sandbox) {
        final GraalJsWorkflowTool.Builder builder = GraalJsWorkflowTool.builder()
                .defaultModel(LlmModel.builder().name("gpt-4").build()).subagentRegistry(new InMemorySubagentRegistry())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .environment(Environment.createDefault()).subagentExecutionManager(manager).engines(engines)
                .backgroundRunner(backgroundRunner);
        if (sandbox != null) {
            builder.sandbox(sandbox);
        }
        return builder.build();
    }

    private static ToolContext contextWithId() {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test")).build();
    }

    @Test
    @DisplayName("foreground runs the JS script and returns its result")
    void foregroundRunsScript() {
        final ToolResult result = tool(null).execute(
                ToolInput.of(Map.of("script", "return agent({ agentType: 'a', goal: 'g' }).text;")), contextWithId());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("ans:g");
    }

    @Test
    @DisplayName("args are passed through to the script")
    void argsPassedThrough() {
        final ToolResult result = tool(null).execute(
                ToolInput.of(Map.of("script", "return 'hi ' + args.who;", "args", Map.of("who", "there"))),
                contextWithId());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("hi there");
    }

    @Test
    @DisplayName("a blank script is rejected")
    void blankScriptRejected() {
        final ToolResult result = tool(null).execute(ToolInput.of(Map.of("script", "   ")), contextWithId());
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("script cannot be blank");
    }

    @Test
    @DisplayName("foreground without an agent runtime id is an error")
    void missingRuntimeIdError() {
        final ToolResult result = tool(null).execute(ToolInput.of(Map.of("script", "return 1;")), ToolContext.empty());
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Agent runtime ID not found");
    }

    @Test
    @DisplayName("a guest throw is translated to a ToolResult.error, never propagated")
    void guestThrowNeverThrows() {
        final ToolResult result = tool(null).execute(ToolInput.of(Map.of("script", "throw new Error('boom');")),
                contextWithId());
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("JS workflow failed");
    }

    @Test
    @DisplayName("max_agents enforces a budget ceiling, surfaced as an error (never-throw)")
    void maxAgentsBudgetSurfacedAsError() {
        final ToolResult result = tool(null).execute(ToolInput.of(Map.of("script",
                "agent({ agentType: 'a', goal: '1' }); return agent({ agentType: 'a', goal: '2' }).text;", "max_agents",
                1)), contextWithId());
        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("background mode without a runner is an error")
    void backgroundWithoutRunnerError() {
        final ToolResult result = tool(null).execute(ToolInput.of(Map.of("script", "return 1;", "mode", "background")),
                contextWithId());
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Background mode is not available");
    }

    @Test
    @DisplayName("background mode with a runner returns a trackable run id")
    void backgroundReturnsRunId() {
        final WorkflowRunnerOptions options = WorkflowRunnerOptions.builder()
                .backgroundConfig(WorkflowBackgroundConfig.of(1)).build();
        try (WorkflowRunner backgroundRunner = WorkflowRunners.create(manager, env(), options)) {
            final ToolResult result = tool(backgroundRunner).execute(ToolInput
                    .of(Map.of("script", "return agent({ agentType: 'a', goal: 'g' }).text;", "mode", "background")),
                    contextWithId());
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("/runs").contains("graaljs");
        }
    }

    @Test
    @DisplayName("the tool exposes the WorkflowJs name and a script parameter")
    void definitionExposesName() {
        assertThat(tool(null).getDefinition().getName()).isEqualTo("WorkflowJs");
        assertThat(List.of("WorkflowJs")).containsExactly(GraalJsWorkflowTool.TOOL_NAME);
    }

    @Test
    @DisplayName("a cyclic script result becomes ToolResult.error, never an escaping Error")
    void cyclicResultNeverThrows() {
        final ToolResult result = tool(null)
                .execute(ToolInput.of(Map.of("script", "const o = {}; o.self = o; return o;")), contextWithId());
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("nesting depth");
    }

    @Test
    @Timeout(30)
    @DisplayName("a foreground wall-clock timeout surfaces as ToolResult.error (never-throw)")
    void wallClockTimeoutSurfacedAsError() {
        final JsSandboxConfig config = JsSandboxConfig.builder().maxStatements(Long.MAX_VALUE)
                .wallClockTimeout(Duration.ofMillis(300)).build();
        final ToolResult result = tool(null, config).execute(ToolInput.of(Map.of("script", "while (true) { }")),
                contextWithId());
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("JS workflow failed");
    }

    @Test
    @DisplayName("declares COOPERATIVE interrupt behavior (signal-driven cancellation, never-throw result)")
    void declaresCooperativeInterruptBehavior() {
        assertThat(tool(null).getInterruptBehavior()).isEqualTo(InterruptBehavior.COOPERATIVE);
    }
}
