package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

@DisplayName("WorkflowPatterns / Verdict — quality-pattern helpers (Phase 4 §4.4)")
class WorkflowPatternsTest {

    private static final Map<String, Object> REFUTE_SCHEMA = Map.of("type", "object", "properties",
            Map.of("refuted", Map.of("type", "boolean")), "required", List.of("refuted"));

    private static final Map<String, Object> SCORE_SCHEMA = Map.of("type", "object", "properties",
            Map.of("score", Map.of("type", "number")), "required", List.of("score"));

    private static final Map<String, Object> VERIFY_SCHEMA = Map.of("type", "object", "properties",
            Map.of("verified", Map.of("type", "boolean")), "required", List.of("verified"));

    private SubagentExecutionManager manager;
    private SubagentExecutionEnvironment env;
    private Subagent sub;
    private WorkflowRunner runner;

    @BeforeEach
    void setUp() {
        manager = mock(SubagentExecutionManager.class);
        env = env();
        sub = subagent("worker");
        runner = WorkflowRunners.create(manager, env);
    }

    @AfterEach
    void tearDown() {
        runner.close();
    }

    @Test
    @DisplayName("Verdict.tally: majority-refuted kills, minority-refuted survives, below-quorum is inconclusive")
    void verdictTally() {
        assertThat(Verdict.tally(List.of(false, false, true), 3, 2).isSurvived()).isTrue();
        assertThat(Verdict.tally(List.of(true, true, false), 3, 2).isSurvived()).isFalse();
        assertThat(Verdict.tally(List.of(false), 3, 2).isInconclusive()).isTrue();
    }

    @Test
    @DisplayName("adversarialVerify: no skeptic refutes → survives with all valid votes")
    void adversarialSurvives() {
        stubJson("{\"refuted\": false}");
        final Verdict v = runner
                .run(ctx -> WorkflowPatterns.adversarialVerify(ctx, "a finding", sub, 3, 2, REFUTE_SCHEMA));

        assertThat(v.isSurvived()).isTrue();
        assertThat(v.getValidVotes()).isEqualTo(3);
        assertThat(v.getRefutations()).isZero();
    }

    @Test
    @DisplayName("adversarialVerify: a majority refutes → does not survive")
    void adversarialRefuted() {
        stubJson("{\"refuted\": true}");
        final Verdict v = runner
                .run(ctx -> WorkflowPatterns.adversarialVerify(ctx, "a finding", sub, 3, 2, REFUTE_SCHEMA));

        assertThat(v.isSurvived()).isFalse();
        assertThat(v.getRefutations()).isEqualTo(3);
    }

    @Test
    @DisplayName("adversarialVerify: unparseable skeptic output abstains → below quorum → inconclusive")
    void adversarialInconclusive() {
        stubJson("not json at all");
        final Verdict v = runner
                .run(ctx -> WorkflowPatterns.adversarialVerify(ctx, "a finding", sub, 3, 2, REFUTE_SCHEMA));

        assertThat(v.isInconclusive()).isTrue();
        assertThat(v.getValidVotes()).isZero();
    }

    @Test
    @DisplayName("loopUntilDry: stops after quietK consecutive empty rounds")
    void loopUntilDryStops() {
        stubEcho();
        final List<AgentStepResult> results = runner.run(
                ctx -> WorkflowPatterns.loopUntilDry(ctx, r -> AgentTask.of(sub, "round" + r), res -> true, 2, 10));

        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("adversarialVerify: quorum < 1 is rejected before any skeptic is dispatched")
    void adversarialQuorumValidation() {
        assertThatThrownBy(
                () -> runner.run(ctx -> WorkflowPatterns.adversarialVerify(ctx, "a finding", sub, 3, 0, REFUTE_SCHEMA)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quorum must be >= 1");
        verifyNoInteractions(manager);
    }

    @Test
    @DisplayName("adversarialVerify: null refuteSchema → every skeptic abstains → inconclusive")
    void adversarialAbstainsWithoutSchema() {
        stubJson("{\"refuted\": true}"); // would refute, but without a schema no vote is ever parsed
        final Verdict v = runner.run(ctx -> WorkflowPatterns.adversarialVerify(ctx, "a finding", sub, 3, 2, null));

        assertThat(v.isInconclusive()).isTrue();
        assertThat(v.isSurvived()).isFalse();
        assertThat(v.getValidVotes()).isZero();
        assertThat(v.getTotal()).isEqualTo(3);
    }

    @Test
    @DisplayName("judgePanel: picks the candidate with the highest mean score and synthesizes from it")
    void judgePanelPicksWinner() {
        stubByGoal(goal -> {
            if (goal.startsWith("Score this candidate")) {
                return success(goal.contains("candidate-B") ? "{\"score\": 9}" : "{\"score\": 3}");
            }
            if (goal.startsWith("Synthesize a final answer")) {
                return success("synthesized:" + (goal.contains("candidate-B") ? "B" : "?"));
            }
            return success(goal.contains("makeB") ? "candidate-B" : "candidate-A");
        });
        final List<AgentTask> attempts = List.of(AgentTask.of(sub, "makeA"), AgentTask.of(sub, "makeB"));
        final JudgedResult judged = runner.run(ctx -> WorkflowPatterns.judgePanel(ctx, attempts, subagent("judge"), 2,
                subagent("synth"), SCORE_SCHEMA));

        assertThat(judged.bestIndex()).isEqualTo(1);
        assertThat(judged.best()).isPresent();
        assertThat(judged.best().orElseThrow().text()).isEqualTo("candidate-B");
        assertThat(judged.scores()).containsExactly(3.0, 9.0);
        assertThat(judged.synthesis()).isPresent();
        assertThat(judged.synthesis().orElseThrow().text()).isEqualTo("synthesized:B");
    }

    @Test
    @DisplayName("judgePanel: null scoreSchema → every candidate scores NaN, none selected, no synthesis")
    void judgePanelNullSchemaSelectsNone() {
        stubByGoal(goal -> success(goal.startsWith("Score this candidate") ? "{\"score\": 10}" : "cand"));
        final JudgedResult judged = runner.run(ctx -> WorkflowPatterns.judgePanel(ctx,
                List.of(AgentTask.of(sub, "make")), subagent("judge"), 1, subagent("synth"), null));

        assertThat(judged.bestIndex()).isEqualTo(-1);
        assertThat(judged.best()).isEmpty();
        assertThat(judged.synthesis()).isEmpty();
        assertThat(judged.scores()).containsExactly(Double.NaN);
    }

    @Test
    @DisplayName("judgePanel: a failed candidate is skipped and scores stay attributed to the right candidates")
    void judgePanelSkipsFailedCandidate() {
        stubByGoal(goal -> {
            if (goal.startsWith("Score this candidate")) {
                return success(goal.contains("candidate-C") ? "{\"score\": 8}" : "{\"score\": 2}");
            }
            if (goal.contains("makeA")) {
                return failure("producer exploded");
            }
            return success(goal.contains("makeB") ? "candidate-B" : "candidate-C");
        });
        final List<AgentTask> attempts = List.of(AgentTask.of(sub, "makeA"), AgentTask.of(sub, "makeB"),
                AgentTask.of(sub, "makeC"));
        final JudgedResult judged = runner
                .run(ctx -> WorkflowPatterns.judgePanel(ctx, attempts, subagent("judge"), 1, null, SCORE_SCHEMA));

        // Candidate 0 failed → NaN; the two judge results must map back to candidates 1 and 2, not 0 and 1.
        assertThat(judged.scores()).containsExactly(Double.NaN, 2.0, 8.0);
        assertThat(judged.bestIndex()).isEqualTo(2);
        assertThat(judged.best().orElseThrow().text()).isEqualTo("candidate-C");
        assertThat(judged.synthesis()).isEmpty();
    }

    @Test
    @DisplayName("completenessCritic: maxRevisions == 0 returns the draft unchanged without running any agent")
    void criticZeroRevisions() {
        final AgentStepResult draft = draft("draft-v0");
        final AgentStepResult out = runner.run(
                ctx -> WorkflowPatterns.completenessCritic(ctx, draft, subagent("critic"), subagent("reviser"), 0));

        assertThat(out).isSameAs(draft);
        verifyNoInteractions(manager);
    }

    @Test
    @DisplayName("completenessCritic: one successful cycle revises the draft against the critique")
    void criticOneCycleRevises() {
        stubByGoal(goal -> {
            if (goal.startsWith("Critique this draft")) {
                return success("missing: X");
            }
            if (goal.startsWith("Revise the draft") && goal.contains("missing: X") && goal.contains("draft-v0")) {
                return success("draft-v1");
            }
            return failure("unexpected goal: " + goal);
        });
        final AgentStepResult out = runner.run(ctx -> WorkflowPatterns.completenessCritic(ctx, draft("draft-v0"),
                subagent("critic"), subagent("reviser"), 1));

        assertThat(out.text()).isEqualTo("draft-v1");
        assertThat(out.isSuccess()).isTrue();
        assertThat(out.isComplete()).isTrue();
    }

    @Test
    @DisplayName("completenessCritic: a failing reviser leaves the previous draft in place and stops the loop")
    void criticFailedReviserKeepsDraft() {
        stubByGoal(
                goal -> goal.startsWith("Critique this draft") ? success("missing: X") : failure("reviser exploded"));
        final AgentStepResult draft = draft("draft-v0");
        final AgentStepResult out = runner.run(
                ctx -> WorkflowPatterns.completenessCritic(ctx, draft, subagent("critic"), subagent("reviser"), 3));

        assertThat(out).isSameAs(draft);
        // One critique + one failed revision, then the loop breaks — the remaining budget is not burned.
        verify(manager, times(2)).execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString());
    }

    @Test
    @DisplayName("completenessCritic: a failing critique ends the loop before any revision")
    void criticFailedCritiqueKeepsDraft() {
        stubByGoal(goal -> goal.startsWith("Critique this draft")
                ? failure("critic exploded")
                : success("should-never-run"));
        final AgentStepResult draft = draft("draft-v0");
        final AgentStepResult out = runner.run(
                ctx -> WorkflowPatterns.completenessCritic(ctx, draft, subagent("critic"), subagent("reviser"), 3));

        assertThat(out).isSameAs(draft);
        verify(manager, times(1)).execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString());
    }

    @Test
    @DisplayName("completenessCritic: a budget-stopped (incomplete) revision is discarded, not accepted")
    void criticIncompleteRevisionDiscarded() {
        stubByGoal(goal -> goal.startsWith("Critique this draft")
                ? success("missing: X")
                : success("draft-v1-truncated", CompletionReason.MAX_ITERATIONS));
        final AgentStepResult draft = draft("draft-v0");
        final AgentStepResult out = runner.run(
                ctx -> WorkflowPatterns.completenessCritic(ctx, draft, subagent("critic"), subagent("reviser"), 3));

        assertThat(out).isSameAs(draft);
    }

    @Test
    @DisplayName("loopUntilDry: an incomplete (budget-stopped) round does not count toward consecutive-quiet")
    void loopUntilDryIncompleteRoundNotQuiet() {
        stubByGoal(
                goal -> goal.equals("round0") ? success("partial", CompletionReason.MAX_ITERATIONS) : success("done"));
        final List<AgentStepResult> results = runner.run(
                ctx -> WorkflowPatterns.loopUntilDry(ctx, r -> AgentTask.of(sub, "round" + r), res -> true, 2, 10));

        // round0 looks empty but was budget-stopped, so quiet counting only starts at round1: 3 rounds, not 2.
        assertThat(results).hasSize(3);
        assertThat(results.get(0).isComplete()).isFalse();
    }

    @Test
    @DisplayName("loopUntilDry: maxRounds caps the loop when no round is ever quiet")
    void loopUntilDryMaxRoundsCap() {
        stubEcho();
        final List<AgentStepResult> results = runner.run(
                ctx -> WorkflowPatterns.loopUntilDry(ctx, r -> AgentTask.of(sub, "round" + r), res -> false, 2, 3));

        assertThat(results).hasSize(3);
    }

    @Test
    @DisplayName("perspectiveDiverseVerify: one result per perspective, in order, with structured output")
    void perspectiveDiverseVerifyHappyPath() {
        stubJson("{\"verified\": true}");
        final List<Subagent> perspectives = List.of(subagent("p0"), subagent("p1"), subagent("p2"));
        final List<AgentStepResult> results = runner.run(
                ctx -> WorkflowPatterns.perspectiveDiverseVerify(ctx, "the sky is blue", perspectives, VERIFY_SCHEMA));

        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.structured()).contains(Map.of("verified", true));
            assertThat(r.getTask().getGoal()).contains("the sky is blue");
        });
        assertThat(results).extracting(r -> r.getTask().getSubagent().getName()).containsExactly("p0", "p1", "p2");
    }

    @Test
    @DisplayName("multiModalSweep: one result per mode task, in input order")
    void multiModalSweepHappyPath() {
        stubEcho();
        final List<AgentTask> modes = List.of(AgentTask.of(sub, "mode-a"), AgentTask.of(sub, "mode-b"),
                AgentTask.of(sub, "mode-c"));
        final List<AgentStepResult> results = runner.run(ctx -> WorkflowPatterns.multiModalSweep(ctx, modes));

        assertThat(results).extracting(AgentStepResult::text).containsExactly("ans:mode-a", "ans:mode-b", "ans:mode-c");
    }

    private void stubJson(String finalAnswer) {
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> success(finalAnswer));
    }

    private void stubEcho() {
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> success("ans:" + invocation.getArgument(2, String.class)));
    }

    /**
     * Stubs the manager to dispatch on the (possibly schema-augmented) goal string — lets one test drive
     * producer/judge/critic/reviser roles from a single mock.
     */
    private void stubByGoal(Function<String, SubagentExecutionResult> responder) {
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> responder.apply(invocation.getArgument(2, String.class)));
    }

    /** A pre-existing successful, normally-completed draft step result to feed into completenessCritic. */
    private AgentStepResult draft(String text) {
        return AgentStepResult.of(AgentTask.of(sub, "write the draft"), success(text));
    }

    private static Subagent subagent(String name) {
        return Subagent.builder().name(name).systemPrompt("(inline)").build();
    }

    private static SubagentExecutionResult success(String answer) {
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()),
                singleIterationMetadata());
    }

    /** A successful result that stopped for {@code completionReason} — "succeeded but incomplete" (budget stop). */
    private static SubagentExecutionResult success(String answer, CompletionReason completionReason) {
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()),
                singleIterationMetadata(), completionReason);
    }

    private static SubagentExecutionResult failure(String errorMessage) {
        return SubagentExecutionResult.failure(errorMessage, SessionSnapshot.of(SessionId.generate()),
                singleIterationMetadata());
    }

    private static ExecutionMetadata singleIterationMetadata() {
        final Instant now = Instant.now();
        return ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now)
                .build();
    }

    private static SubagentExecutionEnvironment env() {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }
}
