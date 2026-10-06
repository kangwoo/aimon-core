package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentLaunchContext;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.SubagentExecutor;
import at.aimon.core.workflow.AgentStepResult;
import at.aimon.core.workflow.WorkflowBudget;
import at.aimon.core.workflow.WorkflowConcurrencyConfig;
import at.aimon.core.workflow.WorkflowEventSink;

/**
 * End-to-end integration through the <b>real</b> {@link DefaultSubagentExecutionManager} (not a mock) driven by a
 * {@link DefaultWorkflowRunner}. Subagents are implemented as Java {@code SubagentBehavior}s, so the whole stack —
 * workflow primitives → the Phase 0 inline execute → the real manager's dispatch/behavior/result-wrapping — runs
 * with <b>no LLM</b>. The ReAct/LLM executor is verified to never be touched.
 *
 * <p>
 * This exercises the wiring a bootstrap performs (build a base {@link SubagentLaunchContext}, hand it plus the
 * manager to the runner) without needing an API key.
 */
@DisplayName("Workflow ↔ real DefaultSubagentExecutionManager (code behaviors, no LLM)")
class WorkflowRealManagerIntegrationTest {

    private SubagentExecutor reactExecutor;
    private ExecutorService bgPool;
    private DefaultSubagentExecutionManager manager;

    @BeforeEach
    void setUp() {
        // The ReAct/LLM executor is required by the manager but must never run: every subagent below has a code
        // behavior, which replaces the ReAct loop on the inline execute path.
        reactExecutor = mock(SubagentExecutor.class);
        bgPool = Executors.newSingleThreadExecutor();

        final InMemorySubagentBehaviorRegistry behaviors = new InMemorySubagentBehaviorRegistry();
        behaviors.register("upper", (ctx, req, support) -> support.success(req.getGoal().toUpperCase(Locale.ROOT)));
        behaviors.register("reverse",
                (ctx, req, support) -> support.success(new StringBuilder(req.getGoal()).reverse().toString()));
        behaviors.register("tag", (ctx, req, support) -> support.success("[" + req.getGoal() + "]"));

        manager = new DefaultSubagentExecutionManager(reactExecutor, bgPool, null, behaviors);
    }

    @AfterEach
    void tearDown() {
        bgPool.shutdownNow();
    }

    @Test
    @DisplayName("a parallel -> pipeline script runs real code-behavior subagents end-to-end, never hitting the LLM "
            + "path")
    void parallelThenPipelineOverRealBehaviors() {
        final DefaultWorkflowRunner runner = new DefaultWorkflowRunner(manager, env(),
                WorkflowConcurrencyConfig.enabled(4), WorkflowEventSink.NO_OP, WorkflowBudget.defaults());

        // Inline subagents — the behavior is keyed by name; no registry data entry is required on the inline path.
        final Subagent upper = subagent("upper");
        final Subagent reverse = subagent("reverse");
        final Subagent tag = subagent("tag");

        final List<String> out = runner.run(ctx -> {
            // parallel: uppercase "hello" and reverse "world" concurrently, in input order.
            final List<AgentStepResult> found = ctx
                    .parallel(List.of(() -> ctx.agent(upper, "hello"), () -> ctx.agent(reverse, "world")));
            // pipeline: feed each result's text through a second real subagent that tags it.
            return ctx.pipeline(found, AgentStepResult::text, (text, step) -> ctx.agent(tag, text).text());
        });

        // upper("hello")="HELLO"; reverse("world")="dlrow" (reverse only, no case change); each then tagged.
        assertThat(out).containsExactly("[HELLO]", "[dlrow]");
        // The real manager ran the behaviors; the ReAct/LLM executor was never invoked.
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("a step an onStart hook blocks is a failed, incomplete step whose reason is BLOCKED (EE-75)")
    void aStepAnOnStartHookBlocksEndsBlocked() {
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.block("STEP-REFUSED-8f02"));
        final InMemorySubagentBehaviorRegistry behaviors = new InMemorySubagentBehaviorRegistry();
        behaviors.register("upper", (ctx, req, support) -> support.success(req.getGoal().toUpperCase(Locale.ROOT)));
        final DefaultSubagentExecutionManager hooked = new DefaultSubagentExecutionManager(reactExecutor, bgPool,
                new DefaultHookExecutionManager(), behaviors);
        final SubagentLaunchContext env = SubagentLaunchContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:workflow-test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(hooks).defaultModel(LlmModel.builder().name("gpt-4").build()).build();
        final DefaultWorkflowRunner runner = new DefaultWorkflowRunner(hooked, env,
                WorkflowConcurrencyConfig.enabled(4), WorkflowEventSink.NO_OP, WorkflowBudget.defaults());

        final AgentStepResult step = runner.run(ctx -> ctx.agent(subagent("upper"), "hello"));

        assertThat(step.isSuccess()).isFalse();
        assertThat(step.isComplete()).isFalse();
        assertThat(step.completionReason()).isEqualTo(CompletionReason.BLOCKED);
        assertThat(step.text()).contains("OnStart", "STEP-REFUSED-8f02");
    }

    private static Subagent subagent(String name) {
        return Subagent.builder().name(name).systemPrompt("(code behavior)").build();
    }

    private static SubagentLaunchContext env() {
        return SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:workflow-test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }
}
