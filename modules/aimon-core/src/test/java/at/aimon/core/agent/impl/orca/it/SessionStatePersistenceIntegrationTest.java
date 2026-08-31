package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;

/**
 * L2 — the two fields a session keeps across the handles that serve it: {@code SessionTotals} and the runtime
 * {@code budgetOverride}.
 *
 * <p>
 * These are the only pieces of live-session state the scope model says must outlive the handle
 * ({@code docs/overview/scope-model.md} §3), and the reason is the 1 : 0..N shape — a {@code SessionRecord} may be
 * served
 * by many {@link DefaultLiveSession} handles over its life, as idle eviction, a restart or a node handoff each replace
 * the handle without ending the session. State kept only on the handle disappears at each of those points, silently and
 * with no failure anywhere.
 *
 * <h2>Every test here crosses a handle boundary, because that is where the bug lives</h2>
 *
 * <p>
 * Accumulation <em>within</em> one handle would keep working if both fields were plain instance state — the whole class
 * of defect this covers is invisible until a second handle opens on the same {@code SessionId}. So
 * {@link #totalsAccumulateAcrossTurnsOnOneHandle} exists only as the control that says the accumulator works at all,
 * and
 * every other test opens a second handle and asserts against that.
 *
 * <h2>A restored budget is asserted by what it does, not by what it reads back</h2>
 *
 * <p>
 * Reading the override back off the new handle's options proves it was stored and re-read. It does not prove the turn
 * runs under it — the request builder could still be reading the opener default.
 * {@link #aRestoredBudgetOverrideGovernsTheNextTurn} closes that by making the restored budget small enough to stop a
 * turn, so the override's effect is visible in the completion reason and in the model call that never happened.
 */
@DisplayName("RT-IT-L2: session totals and budget override surviving the handle that produced them")
class SessionStatePersistenceIntegrationTest {

    /** {@link ScriptedLlmClient} stamps this on every response, so a one-call turn costs exactly this many tokens. */
    private static final int TOKENS_PER_CALL = 15;

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
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    /** A session whose every turn is answered in one model call, so totals are arithmetic rather than guesswork. */
    private void scriptDirectAnswers(SessionId sessionId) {
        llm.globalFallback(ScriptedLlmClient.text("answered"));
        llm.script(sessionId.value(), ScriptedLlmClient.text("answered"));
        llm.fallback(sessionId.value(), ScriptedLlmClient.text("answered"));
    }

    private SessionTotals persistedTotals(SessionId sessionId) {
        return node.recordStore().load(sessionId).map(SessionRecordView::getSessionTotals).orElseThrow();
    }

    /**
     * Creates the record before a handle opens on it, which is what an opener does — {@code DefaultSessionRouter}
     * provisions before it routes the first turn.
     *
     * <p>
     * It is not optional for the budget tests, and the reason is a sharp edge worth stating: the durable write is
     * {@code SessionRecordStore.setTotalsAndBudgetOverride}, and the in-memory backend implements it with
     * {@code computeIfPresent} — an override set on a session that has no record yet is <em>silently dropped</em>. The
     * totals tests never notice because a turn provisions the record on its way through; the budget tests set an
     * override without running a turn first, so they have to stand where the opener stands.
     */
    private void provisionRecord(SessionId sessionId) {
        node.recordStore().provision(sessionId);
    }

    /**
     * The control: the accumulator adds turns up. Deliberately weak — it would still pass with both fields kept purely
     * in memory, which is exactly the failure the rest of the class is here to catch.
     */
    @Test
    @DisplayName("totals accumulate turn by turn while one handle serves the session")
    void totalsAccumulateAcrossTurnsOnOneHandle() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptDirectAnswers(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        session.submit("one", SubmitOptions.empty());
        assertThat(persistedTotals(sessionId).getTurnCount()).isEqualTo(1);
        assertThat(persistedTotals(sessionId).getIterations()).isEqualTo(1);
        assertThat(persistedTotals(sessionId).getTokenUsage().getTotalTokens()).isEqualTo(TOKENS_PER_CALL);

        session.submit("two", SubmitOptions.empty());
        assertThat(persistedTotals(sessionId).getTurnCount()).isEqualTo(2);
        assertThat(persistedTotals(sessionId).getIterations()).isEqualTo(2);
        assertThat(persistedTotals(sessionId).getTokenUsage().getTotalTokens()).isEqualTo(2 * TOKENS_PER_CALL);

        assertThat(session.status().getSessionTotals()).isEqualTo(persistedTotals(sessionId));
    }

    /**
     * The one that matters. A second handle on the same {@code SessionId} hydrates the totals the first one left behind
     * and keeps counting from there — so the user's running total does not silently reset when their session is evicted
     * and picked back up.
     */
    @Test
    @DisplayName("a second handle on the same session hydrates the totals and continues them")
    void totalsSurviveTheHandleAndAreContinuedByTheNext() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptDirectAnswers(sessionId);

        final DefaultLiveSession first = node.openLiveSession(sessionId);
        first.submit("one", SubmitOptions.empty());
        first.close();

        final DefaultLiveSession second = node.openLiveSession(sessionId);
        // Hydrated at construction, before a single turn has run on this handle.
        assertThat(second.status().getSessionTotals().getTurnCount()).isEqualTo(1);
        assertThat(second.status().getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(TOKENS_PER_CALL);

        second.submit("two", SubmitOptions.empty());

        // Continued, not restarted: a handle that dropped the hydrated value would report 1 here.
        assertThat(persistedTotals(sessionId).getTurnCount()).isEqualTo(2);
        assertThat(persistedTotals(sessionId).getTokenUsage().getTotalTokens()).isEqualTo(2 * TOKENS_PER_CALL);
    }

    /**
     * Totals are per session, not per agent. Two sessions on the same node run a turn each and neither sees the other's
     * — the assertion that fails if the accumulator is ever keyed by anything coarser than the {@code SessionId}.
     */
    @Test
    @DisplayName("totals are keyed by session, so a sibling session's turns do not count towards them")
    void totalsDoNotLeakBetweenSessionsOfTheSameAgent() {
        final SessionId first = OrcaRuntimeItSupport.session("totals-a");
        final SessionId second = OrcaRuntimeItSupport.session("totals-b");
        scriptDirectAnswers(first);
        scriptDirectAnswers(second);

        final DefaultLiveSession sessionA = node.openLiveSession(first);
        sessionA.submit("one", SubmitOptions.empty());
        sessionA.submit("two", SubmitOptions.empty());
        node.openLiveSession(second).submit("one", SubmitOptions.empty());

        assertThat(persistedTotals(first).getTurnCount()).isEqualTo(2);
        assertThat(persistedTotals(second).getTurnCount()).isEqualTo(1);
    }

    /**
     * A session nobody has run a turn on has empty totals — the baseline the accumulation assertions are read against.
     */
    @Test
    @DisplayName("a session that has run no turn has empty totals")
    void aFreshSessionHasEmptyTotals() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();

        final DefaultLiveSession session = node.openLiveSession(sessionId);

        assertThat(session.status().getSessionTotals()).isEqualTo(SessionTotals.empty());
        assertThat(node.recordStore().load(sessionId)).isEmpty();
    }

    /**
     * A budget set at runtime is written to the record and re-applied over the next handle's opener default — the
     * "override wins" rule, asserted across the boundary that makes it necessary.
     */
    @Test
    @DisplayName("a runtime budget override is persisted and beats the next handle's opener default")
    void aBudgetOverrideIsPersistedAndReappliedByTheNextHandle() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final ExecutionBudget override = ExecutionBudget.builder().maxTokens(1000).build();
        provisionRecord(sessionId);

        final DefaultLiveSession first = node.openLiveSession(sessionId);
        first.setOptions(LiveSessionOptions.builder().budget(override).build());
        first.close();

        assertThat(node.recordStore().load(sessionId).orElseThrow().getBudgetOverride()).contains(override);

        // Opened with the unbounded default, exactly as an opener that knows nothing about the override would.
        final DefaultLiveSession second = node.openLiveSession(sessionId, LiveSessionOptions.defaults());

        assertThat(second.getOptions().getBudget()).isEqualTo(override);
    }

    /**
     * Storage is not application. A restored override of ten tokens must actually stop the next handle's turn — which
     * is only visible in the completion reason and in the second model call that never went out.
     */
    @Test
    @DisplayName("a restored budget override governs the next handle's turn, not just its options")
    void aRestoredBudgetOverrideGovernsTheNextTurn() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        node.writeFile("note.txt", "seeded");
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", "note.txt")),
                ScriptedLlmClient.text("answered"));
        provisionRecord(sessionId);

        final DefaultLiveSession first = node.openLiveSession(sessionId);
        first.setOptions(LiveSessionOptions.builder().budget(ExecutionBudget.builder().maxTokens(10).build()).build());
        first.close();

        final DefaultLiveSession second = node.openLiveSession(sessionId, LiveSessionOptions.defaults());
        final AgentExecutionResult result = second.submit("read the note", SubmitOptions.empty());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        // The second script entry was never consumed: the restored override cost the model a call.
        assertThat(llm.callCount(sessionId.value())).isEqualTo(1);
    }

    /**
     * Clearing erases the stored override rather than merely masking it in memory, so the <em>next</em> handle reverts
     * to its opener default too. A clear that only touched the live options would leave the record still carrying the
     * old budget, and it would come back at the next open.
     */
    @Test
    @DisplayName("clearing the override erases it from the record so the next handle reverts to the opener default")
    void clearingTheOverrideErasesItForTheNextHandleToo() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptDirectAnswers(sessionId);
        provisionRecord(sessionId);

        final DefaultLiveSession first = node.openLiveSession(sessionId);
        first.setOptions(LiveSessionOptions.builder().budget(ExecutionBudget.builder().maxTokens(10).build()).build());
        assertThat(node.recordStore().load(sessionId).orElseThrow().getBudgetOverride()).isPresent();
        first.clearBudgetOverride();
        first.close();

        assertThat(node.recordStore().load(sessionId).orElseThrow().getBudgetOverride()).isEmpty();

        final DefaultLiveSession second = node.openLiveSession(sessionId, LiveSessionOptions.defaults());
        final AgentExecutionResult result = second.submit("go", SubmitOptions.empty());

        assertThat(second.getOptions().getBudget()).isEqualTo(LiveSessionOptions.defaults().getBudget());
        // And the effect: the ten-token ceiling is genuinely gone, so the turn completes.
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }
}
