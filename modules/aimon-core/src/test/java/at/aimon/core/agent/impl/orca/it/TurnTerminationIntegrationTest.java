package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.cost.CostEstimator;
import at.aimon.core.llm.cost.Money;

/**
 * L3 — how a turn ends when it does <b>not</b> end by answering.
 *
 * <p>
 * Every other class in this suite drives turns that succeed, so all of them would stay green with the budget tracker
 * and
 * the iteration ceiling deleted outright. This one covers the abnormal exits: the four {@link ExecutionBudget}
 * dimensions, and the agent's own iteration ceiling.
 *
 * <h2>The assertion that carries the weight is the call count</h2>
 *
 * <p>
 * A completion reason is cheap to produce and easy to produce for the wrong reason — a turn that crashed, or one whose
 * script simply ran out, could report the same thing. What actually distinguishes "the budget stopped this turn" from
 * "the turn stopped anyway" is <b>the model call that did not happen</b>. Each test below therefore pins how many calls
 * reached {@link ScriptedLlmClient}, and {@link #theSameScriptWithoutABudgetRunsToCompletion} is the shared positive
 * control: the identical script, unbudgeted, makes both calls and succeeds. Without that control every test here would
 * also pass against a script that was simply one response short.
 *
 * <h2>Two paths reach MAX_ITERATIONS, and only the message tells them apart</h2>
 *
 * <p>
 * The budget's {@code maxIterations} and the agent metadata's ceiling are separate mechanisms that produce the same
 * {@link CompletionReason#MAX_ITERATIONS}: the first stops at the <em>top</em> of the iteration that would exceed it
 * ({@code handleBudgetStop}), the second falls out of the {@code while} condition ({@code handleMaxIterations}). They
 * are distinguishable only by the stop message, so {@link #anIterationBudgetStopsTheTurnFromTheBudgetPath} and
 * {@link #theAgentIterationCeilingStopsTheTurnFromTheCeilingPath} pin both literals. Collapsing one path into the other
 * would leave the reason assertions green.
 */
@DisplayName("RT-IT-L3: how a turn ends when the budget or the iteration ceiling stops it")
class TurnTerminationIntegrationTest {

    /** Seeded so the scripted tool calls have something real to do; its content is never asserted on. */
    private static final String NOTE = "note.txt";

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        node = support.newNode("agent-a", llm);
        node.writeFile(NOTE, "seeded");
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    /** One tool call, then an answer — two model calls when nothing stops the turn. */
    private void scriptReadThenAnswer(SessionId sessionId) {
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", NOTE)),
                ScriptedLlmClient.text("answered"));
    }

    /**
     * The control every budget test here leans on: the same two-entry script, no budget, both calls made.
     *
     * <p>
     * Each test below asserts that <em>fewer</em> calls were made than this. That comparison is the whole
     * falsifiability
     * argument — a stopped turn and a turn whose script was one entry short are indistinguishable without it.
     */
    @Test
    @DisplayName("the same script without a budget makes both model calls and answers")
    void theSameScriptWithoutABudgetRunsToCompletion() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId);

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the note");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("answered");
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(llm.callCount(sessionId.value())).isEqualTo(2);
    }

    /**
     * The scripted client stamps 15 tokens on every response, so a ceiling of 10 is crossed by the first call and the
     * check at the top of iteration 2 stops the turn before the second one goes out.
     */
    @Test
    @DisplayName("a token budget ends the turn before the model call that would have overspent it")
    void aTokenBudgetStopsTheTurnBeforeTheNextModelCall() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId);

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the note",
                ExecutionBudget.builder().maxTokens(10).build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        assertThat(result.getErrorMessage()).isEqualTo("Execution stopped by budget: TOKEN_BUDGET_EXCEEDED");
        // The second entry of the script was never consumed: the budget cost the model a call.
        assertThat(llm.callCount(sessionId.value())).isEqualTo(1);
        assertThat(result.getIterationCount()).isEqualTo(1);
    }

    /**
     * The budget path to {@code MAX_ITERATIONS}. The agent keeps the harness ceiling of
     * {@link OrcaRuntimeItSupport#MAX_ITERATIONS}, so the budget is the only thing that can stop this turn — and it
     * stops it at the top of the iteration that would have been the third.
     */
    @Test
    @DisplayName("an iteration budget ends the turn as MAX_ITERATIONS, reported as a budget stop")
    void anIterationBudgetStopsTheTurnFromTheBudgetPath() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", NOTE)),
                ScriptedLlmClient.callTool("Read", Map.of("file_path", NOTE)), ScriptedLlmClient.text("never reached"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the note twice",
                ExecutionBudget.builder().maxIterations(2).build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        // handleBudgetStop's wording. The ceiling path below produces the same reason with a different message, which
        // is the only way to tell which mechanism actually fired.
        assertThat(result.getErrorMessage()).isEqualTo("Execution stopped by budget: MAX_ITERATIONS");
        assertThat(llm.callCount(sessionId.value())).isEqualTo(2);
    }

    /**
     * The ceiling path to the same reason. The turn carries no budget at all, so nothing but the agent's own
     * {@code maxIterations} can end it — and it ends by falling out of the loop rather than by a pre-iteration check.
     */
    @Test
    @DisplayName("the agent's own iteration ceiling ends the turn as MAX_ITERATIONS, with the ceiling's own message")
    void theAgentIterationCeilingStopsTheTurnFromTheCeilingPath() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final OrcaRuntimeItSupport.Node capped = support.newNode("agent-capped", llm,
                OrcaRuntimeItSupport.options().maxIterations(2));
        capped.writeFile(NOTE, "seeded");
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", NOTE)),
                ScriptedLlmClient.callTool("Read", Map.of("file_path", NOTE)));

        final OrcaAgentExecutionResult result = capped.run(sessionId, "read the note twice");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertThat(result.getErrorMessage()).isEqualTo("Maximum iterations exceeded: 2");
        assertThat(llm.callCount(sessionId.value())).isEqualTo(2);
        assertThat(result.getIterationCount()).isEqualTo(2);
    }

    /**
     * Wall clock is the one dimension that cannot be crossed by counting, so the first response spends the budget in
     * real time. The margin is deliberately wide — a 500 ms sleep against a 200 ms ceiling — because the tracker starts
     * before the turn's prompt assembly, and a narrow margin would make the *first* check the one that trips and turn
     * the call-count assertion into a coin flip.
     */
    @Test
    @DisplayName("a wall-clock budget ends the turn after the call that overran it")
    void aWallClockBudgetStopsTheTurnAfterTheCallThatOverranIt() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.scriptDynamic(sessionId.value(), call -> {
            sleep(500);
            return ScriptedLlmClient.callTool("Read", Map.of("file_path", NOTE));
        }, call -> ScriptedLlmClient.text("never reached"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "take your time",
                ExecutionBudget.builder().maxWallClockDuration(Duration.ofMillis(200)).build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.WALL_CLOCK_EXCEEDED);
        assertThat(result.getErrorMessage()).isEqualTo("Execution stopped by budget: WALL_CLOCK_EXCEEDED");
        assertThat(llm.callCount(sessionId.value())).isEqualTo(1);
    }

    /**
     * Cost accounting is off in the assembled runtime, and this pins that rather than working around it.
     *
     * <p>
     * The executor defaults to {@code CostEstimator.NOOP}, so every call is priced at zero;
     * {@code ExecutionBudget.Builder#maxCostUsd} rejects a zero ceiling; and a zero total can never reach a positive
     * one. {@code COST_BUDGET_EXCEEDED} is therefore <b>unreachable by construction</b> here — a cost budget is inert,
     * and {@link OrcaAgentExecutionResult#getCostSummary()} stays empty. If a default estimator is ever wired into the
     * executor this test fails, which is the notification worth having: a previously inert budget dimension went live.
     */
    @Test
    @DisplayName("a cost budget is inert because the assembled executor prices every call at zero")
    void aCostBudgetIsInertWithoutAnEstimator() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId);

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the note",
                ExecutionBudget.builder().maxCostUsd(Money.usd(0.01)).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("answered");
        assertThat(llm.callCount(sessionId.value())).isEqualTo(2);
        assertThat(result.getCostSummary().isEmpty()).isTrue();
        assertThat(result.getCostSummary().getTotalCost().isZero()).isTrue();
    }

    /**
     * The counterpart to the test above: with an estimator wired, the same dimension does stop a turn.
     *
     * <p>
     * The pair is what makes either half meaningful. On its own, "a cost budget did not stop the turn" is equally
     * consistent with a working ceiling that was never reached and with a cost axis that is not plumbed through at all;
     * only a run that actually reaches {@code COST_BUDGET_EXCEEDED} distinguishes them. The estimator prices every call
     * at one cent flat, so the first response spends the whole ceiling and the check at the top of iteration 2 stops
     * the
     * turn.
     */
    @Test
    @DisplayName("with a cost estimator wired, a cost budget ends the turn before the next model call")
    void aCostBudgetStopsTheTurnOnceCallsArePriced() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final CostEstimator onePerCall = (model, usage) -> Money.usd(0.01);
        final OrcaRuntimeItSupport.Node priced = support.newNode("agent-priced", llm,
                OrcaRuntimeItSupport.options().costEstimator(onePerCall));
        priced.writeFile(NOTE, "seeded");
        scriptReadThenAnswer(sessionId);

        final OrcaAgentExecutionResult result = priced.run(sessionId, "read the note",
                ExecutionBudget.builder().maxCostUsd(Money.usd(0.01)).build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COST_BUDGET_EXCEEDED);
        assertThat(result.getErrorMessage()).isEqualTo("Execution stopped by budget: COST_BUDGET_EXCEEDED");
        assertThat(llm.callCount(sessionId.value())).isEqualTo(1);
        // The cost really was accounted, not merely compared: the summary is no longer empty.
        assertThat(result.getCostSummary().isEmpty()).isFalse();
    }

    /**
     * When several dimensions are exhausted at once the reported reason is the first in the documented priority order
     * (iterations → tokens → cost → wall clock), not whichever happens to be checked last.
     *
     * <p>
     * After one iteration this turn has crossed both its 1-iteration and its 10-token ceiling, so both would stop it.
     * Reordering {@code BudgetTracker.getStopReason} would be invisible to every other test in this class — each of
     * those exhausts exactly one dimension, so any order reports the same thing.
     */
    @Test
    @DisplayName("when two budget dimensions are exhausted at once, the earlier one in the priority order is reported")
    void theStopReasonFollowsTheDocumentedDimensionPriority() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId);

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the note",
                ExecutionBudget.builder().maxIterations(1).maxTokens(10).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertThat(result.getErrorMessage()).isEqualTo("Execution stopped by budget: MAX_ITERATIONS");
        assertThat(llm.callCount(sessionId.value())).isEqualTo(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while spending the wall-clock budget", e);
        }
    }
}
