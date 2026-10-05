package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.skill.hook.SkillHookSet;
import at.aimon.core.skill.hook.SkillScopedHookRegistry;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentRegistry;
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
        return tool(backgroundRunner, sandbox, new InMemorySubagentRegistry());
    }

    private GraalJsWorkflowTool tool(WorkflowRunner backgroundRunner, JsSandboxConfig sandbox,
            SubagentRegistry registry) {
        final GraalJsWorkflowTool.Builder builder = GraalJsWorkflowTool.builder()
                .defaultModel(LlmModel.builder().name("gpt-4").build()).subagentRegistry(registry)
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .subagentExecutionManager(manager).engines(engines).backgroundRunner(backgroundRunner);
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
    @DisplayName("by default a step's agentType is looked up in the tool's subagent registry for attributes (EE-42)")
    void defaultResolverUsesToolRegistry() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name("builder").systemPrompt("unused")
                .attributes(Map.of("sandbox.slot", "build")).build());
        behavior = (subagent, goal) -> subagent.getName() + " " + subagent.getMetadata().getAttributes();

        final ToolResult result = tool(null, null, registry).execute(
                ToolInput.of(Map.of("script", "return agent({ agentType: 'builder', goal: 'g' }).text;")),
                contextWithId());

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        assertThat(result.getContent()).isEqualTo("graaljs:builder {sandbox.slot=build}");
    }

    @Test
    @DisplayName("EE-45: by default a script that gives a step attributes fails, naming the key and the remedy")
    void scriptAttributesAreRefusedByDefault() {
        final java.util.concurrent.atomic.AtomicInteger steps = new java.util.concurrent.atomic.AtomicInteger();
        behavior = (subagent, goal) -> {
            steps.incrementAndGet();
            return "ran";
        };

        final ToolResult result = tool(null).execute(ToolInput.of(Map.of("script",
                "return agent({ goal: 'g', systemPrompt: 'p', attributes: { sandbox: { slot: 'privileged' } } }).text;")),
                contextWithId());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("'sandbox.slot'", "allowed: none", "scriptAttributeKeys");
        assertThat(steps).as("no step runs with attributes the operator did not allow").hasValue(0);
    }

    @Test
    @DisplayName("EE-45: scriptAttributeKeys admits exactly the listed keys")
    void scriptAttributeKeysAdmitTheListedKeys() {
        behavior = (subagent, goal) -> subagent.getMetadata().getAttributes().toString();
        final GraalJsWorkflowTool allowing = GraalJsWorkflowTool.builder()
                .defaultModel(LlmModel.builder().name("gpt-4").build()).subagentRegistry(new InMemorySubagentRegistry())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .subagentExecutionManager(manager).engines(engines).scriptAttributeKeys(List.of("sandbox.profile"))
                .build();

        final ToolResult allowed = allowing.execute(ToolInput.of(Map.of("script",
                "return agent({ agentType: 'a', goal: 'g', attributes: { sandbox: { profile: 'ro' } } }).text;")),
                contextWithId());
        final ToolResult refused = allowing.execute(ToolInput.of(Map.of("script",
                "return agent({ agentType: 'a', goal: 'g', attributes: { sandbox: { slot: 'privileged' } } }).text;")),
                contextWithId());

        assertThat(allowed.isSuccess()).as(allowed.getContent()).isTrue();
        assertThat(allowed.getContent()).isEqualTo("{sandbox.profile=ro}");
        assertThat(refused.isError()).isTrue();
        assertThat(refused.getContent()).contains("'sandbox.slot'", "allowed: sandbox.profile");
    }

    @Test
    @DisplayName("EE-45: scriptAttributeKeys with a custom resolver is refused at build, and so is a blank key")
    void scriptAttributeKeysConfigureOnlyTheDefaultResolver() {
        final GraalJsWorkflowTool.Builder builder = GraalJsWorkflowTool.builder()
                .defaultModel(LlmModel.builder().name("gpt-4").build()).subagentRegistry(new InMemorySubagentRegistry())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .subagentExecutionManager(manager).engines(engines).subagentResolver(SubagentResolver.inline())
                .scriptAttributeKeys(List.of("gpu"));

        org.assertj.core.api.Assertions.assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scriptAttributeKeys");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> GraalJsWorkflowTool.builder().scriptAttributeKeys(List.of("")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("EE-45: the tool's schema does not advertise 'attributes' to the model")
    void theSchemaDoesNotAdvertiseAttributes() {
        assertThat(tool(null).getDefinition().getInputSchema().toString()).doesNotContain("attributes");
        assertThat(tool(null).getDefinition().getDescription()).doesNotContain("attributes");
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

    // --- skill hooks follow a foreground run and refuse a background one (EE-49) -----------------------------------

    private static final PreToolHook GUARD = ctx -> HookResult.success();
    private static final PostToolHook AUDIT = ctx -> HookResult.success();

    private static ToolContext insideSkill(HookRegistry registry) {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test"))
                .put(ToolContextKeys.HOOK_REGISTRY, registry).build();
    }

    @Test
    @DisplayName("a foreground run's subagents dispatch against the caller's hook registry, not the tool's own")
    void foregroundUsesTheCallersHookRegistry() {
        final HookRegistry fromContext = new DefaultHookRegistry();

        final ToolResult result = tool(null).execute(
                ToolInput.of(Map.of("script", "return agent({ agentType: 'a', goal: 'g' }).text;")),
                insideSkill(fromContext));

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        final ArgumentCaptor<SubagentExecutionEnvironment> env = ArgumentCaptor
                .forClass(SubagentExecutionEnvironment.class);
        verify(manager).execute(env.capture(), any(Subagent.class), anyString());
        assertThat(env.getValue().getHookRegistry()).isSameAs(fromContext);
    }

    @Test
    @DisplayName("an active skill guard refuses background mode, and the runner is never reached")
    void activeSkillGuardRefusesBackgroundMode() {
        final WorkflowRunner backgroundRunner = mock(WorkflowRunner.class);
        final SkillScopedHookRegistry outer = new SkillScopedHookRegistry(new DefaultHookRegistry(), "deploy",
                SkillHookSet.builder().addPreTool(GUARD).build());
        final SkillScopedHookRegistry inner = new SkillScopedHookRegistry(outer, "audit",
                SkillHookSet.builder().addPostTool(AUDIT).build());

        final ToolResult result = tool(backgroundRunner)
                .execute(ToolInput.of(Map.of("script", "return 1;", "mode", "background")), insideSkill(inner));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("skill 'deploy'").contains("guard hooks").contains("foreground");
        verifyNoInteractions(backgroundRunner);
    }

    @Test
    @DisplayName("a skill whose only hook is on onStart refuses background mode too (EE-70)")
    void onStartOnlySkillRefusesBackgroundMode() {
        final WorkflowRunner backgroundRunner = mock(WorkflowRunner.class);
        final SkillScopedHookRegistry view = new SkillScopedHookRegistry(new DefaultHookRegistry(), "gate",
                SkillHookSet.builder().addOnStart(ctx -> HookResult.success()).build());

        final ToolResult result = tool(backgroundRunner)
                .execute(ToolInput.of(Map.of("script", "return 1;", "mode", "background")), insideSkill(view));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("skill 'gate'").contains("guard hooks").contains("foreground");
        verifyNoInteractions(backgroundRunner);
    }

    @Test
    @DisplayName("observation-only skill hooks, a closed skill scope and a plain registry do not refuse background mode")
    void backgroundModeAllowedWithoutAnActiveGuard() {
        final SkillScopedHookRegistry observing = new SkillScopedHookRegistry(new DefaultHookRegistry(), "audit",
                SkillHookSet.builder().addPostTool(AUDIT).build());
        final SkillScopedHookRegistry closed = new SkillScopedHookRegistry(new DefaultHookRegistry(), "deploy",
                SkillHookSet.builder().addPreTool(GUARD).build());
        closed.deactivate();

        for (HookRegistry registry : List.of(observing, closed, new DefaultHookRegistry())) {
            final WorkflowRunner backgroundRunner = mock(WorkflowRunner.class);

            final ToolResult result = tool(backgroundRunner)
                    .execute(ToolInput.of(Map.of("script", "return 1;", "mode", "background")), insideSkill(registry));

            assertThat(result.isSuccess()).as(result.getContent()).isTrue();
            verify(backgroundRunner).runInBackground(any(), any());
        }
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
