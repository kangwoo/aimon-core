package at.aimon.core.tools.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.SubagentExecutor;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Drives {@link WorkflowTool} end-to-end through the <b>real</b> {@link DefaultSubagentExecutionManager} using
 * code
 * behaviors for the perspective/synthesizer sub-agents — so the whole path (tool → WorkflowRunner → real manager)
 * runs with no LLM. The ReAct/LLM executor is verified never to be touched.
 */
@DisplayName("WorkflowTool — multi-perspective workflow over the real execution manager (no LLM)")
class WorkflowToolTest {

    private SubagentExecutor reactExecutor;
    private ExecutorService bgPool;
    private InMemorySubagentBehaviorRegistry behaviors;
    private final AtomicInteger synthesizerCalls = new AtomicInteger();
    private WorkflowTool tool;

    @BeforeEach
    void setUp() {
        reactExecutor = mock(SubagentExecutor.class);
        bgPool = Executors.newSingleThreadExecutor();
        behaviors = new InMemorySubagentBehaviorRegistry();
        // Default perspectives + synthesizer, all as deterministic Java behaviors.
        behaviors.register("workflow:perspective:technical", (c, r, s) -> s.success("technical-says:" + r.getGoal()));
        behaviors.register("workflow:perspective:risk", (c, r, s) -> s.success("risk-says:" + r.getGoal()));
        behaviors.register("workflow:perspective:user_impact", (c, r, s) -> s.success("ux-says:" + r.getGoal()));
        behaviors.register("workflow:synthesizer", (c, r, s) -> {
            synthesizerCalls.incrementAndGet();
            return s.success(r.getGoal()); // echo the combined document so the test can inspect what it received
        });
        // Phase 4 judge-panel: candidates emit identifiable answers; the judge scores the "risk" candidate highest.
        behaviors.register("workflow:candidate:technical", (c, r, s) -> s.success("candidate-technical answer"));
        behaviors.register("workflow:candidate:risk", (c, r, s) -> s.success("candidate-risk answer"));
        behaviors.register("workflow:candidate:user_impact", (c, r, s) -> s.success("candidate-user_impact answer"));
        behaviors.register("workflow:judge",
                (c, r, s) -> s.success("{\"score\": " + (r.getGoal().contains("candidate-risk") ? 9 : 5) + "}"));
        // Phase 4 adversarial-verify: skeptics do not refute by default (claim survives).
        behaviors.register("workflow:skeptic", (c, r, s) -> s.success("{\"refuted\": false}"));

        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(reactExecutor, bgPool, null,
                behaviors);
        tool = new WorkflowTool(LlmModel.builder().name("gpt-4").build(), new InMemorySubagentRegistry(),
                new DefaultToolRegistry(), new DefaultHookRegistry(), Environment.createDefault(), manager, List.of());
    }

    @AfterEach
    void tearDown() {
        bgPool.shutdownNow();
    }

    @Test
    @DisplayName("synthesize=true (default) fans out the default perspectives in parallel and synthesizes them")
    void synthesizesDefaultPerspectives() {
        final ToolResult result = tool.execute(ToolInput.of(Map.of("prompt", "should we ship X?")), context());

        assertThat(result.isSuccess()).isTrue();
        // The synthesizer echoes the combined document, so the result contains every perspective's labeled analysis.
        assertThat(result.getContent()).contains("## Perspective: technical", "technical-says:should we ship X?",
                "## Perspective: risk", "risk-says:should we ship X?", "## Perspective: user_impact",
                "ux-says:should we ship X?");
        assertThat(synthesizerCalls.get()).isEqualTo(1);
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("synthesize=false returns the labeled per-perspective analyses without calling the synthesizer")
    void skipsSynthesisWhenDisabled() {
        final ToolResult result = tool.execute(ToolInput.of(Map.of("prompt", "ship X?", "synthesize", Boolean.FALSE)),
                context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("## Perspective: technical", "## Perspective: risk",
                "## Perspective: user_impact");
        assertThat(synthesizerCalls.get()).isZero();
    }

    @Test
    @DisplayName("a custom comma-separated perspectives list overrides the defaults")
    void customPerspectives() {
        behaviors.register("workflow:perspective:cost", (c, r, s) -> s.success("cost-says:" + r.getGoal()));
        behaviors.register("workflow:perspective:security", (c, r, s) -> s.success("sec-says:" + r.getGoal()));

        final ToolResult result = tool.execute(
                ToolInput.of(
                        Map.of("prompt", "adopt Y?", "perspectives", "cost, security", "synthesize", Boolean.FALSE)),
                context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("## Perspective: cost", "cost-says:adopt Y?",
                "## Perspective: security", "sec-says:adopt Y?");
        assertThat(result.getContent()).doesNotContain("## Perspective: technical");
    }

    @Test
    @DisplayName("returns an error (never throws) when the agent runtime id is missing")
    void errorsWhenNoAgentRuntimeId() {
        final ToolResult result = tool.execute(ToolInput.of(Map.of("prompt", "hi")), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Agent runtime ID not found");
    }

    @Test
    @DisplayName("returns an error for a missing / blank prompt")
    void errorsOnMissingOrBlankPrompt() {
        assertThat(tool.execute(ToolInput.of(Map.of()), context()).isError()).isTrue();
        assertThat(tool.execute(ToolInput.of(Map.of("prompt", "   ")), context()).isError()).isTrue();
    }

    @Test
    @DisplayName("strategy=judge_panel scores the candidates, synthesizes the best, and reports the scores")
    void judgePanelSelectsBestAndSynthesizes() {
        final ToolResult result = tool.execute(
                ToolInput.of(Map.of("prompt", "how should we build X?", "strategy", "judge_panel")), context());

        assertThat(result.isSuccess()).isTrue();
        // The synthesizer echoes its goal, which is grounded in the highest-scored (risk) candidate.
        assertThat(result.getContent()).contains("candidate-risk answer");
        // The score line reports every candidate and marks the winner.
        assertThat(result.getContent()).contains("Candidate scores:", "risk=9.0", "(best: risk)");
        assertThat(synthesizerCalls.get()).isEqualTo(1);
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("strategy=adversarial_verify with no refutation returns a SURVIVED verdict")
    void adversarialVerifySurvives() {
        final ToolResult result = tool.execute(
                ToolInput.of(Map.of("prompt", "the sky is blue", "strategy", "adversarial_verify")), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("SURVIVED", "3 skeptics", "0 refuted, 3 validated");
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("strategy=adversarial_verify with a refuting majority returns a REFUTED verdict")
    void adversarialVerifyRefuted() {
        behaviors.register("workflow:skeptic", (c, r, s) -> s.success("{\"refuted\": true}"));

        final ToolResult result = tool.execute(
                ToolInput.of(Map.of("prompt", "the earth is flat", "strategy", "adversarial_verify")), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("REFUTED", "3 refuted");
    }

    @Test
    @DisplayName("an unknown strategy is rejected before any sub-agent runs")
    void rejectsUnknownStrategy() {
        final ToolResult result = tool.execute(ToolInput.of(Map.of("prompt", "x", "strategy", "nonsense")), context());

        assertThat(result.isError()).isTrue();
        // Rejected by binding now, from the same allowed set the model was shown, so the sentence is the shared one
        // from ViolationMessages rather than a hand-written "Invalid strategy" the tool used to compose itself.
        assertThat(result.getContent())
                .contains("Parameter 'strategy' must be one of [perspectives, judge_panel, adversarial_verify], "
                        + "but was 'nonsense'. The tool was not executed.");
        verify(reactExecutor, never()).execute(any(), any());
    }

    private static ToolContext context() {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test")).build();
    }
}
