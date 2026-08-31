package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.compact.CompactBoundary;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.ModelContextLimits;

/**
 * L2 — transcript compaction inside a running turn.
 *
 * <p>
 * Compaction is the one piece of session state the executor rewrites <b>in place, mid-turn</b>: the guard runs at the
 * top of an iteration, the engine replaces the whole transcript with a boundary marker plus a summary, and the next LLM
 * call goes out against the rewritten history. Nothing else in this suite can see that — every other turn ends with the
 * transcript it accumulated.
 *
 * <h2>How the trigger is reached without a real 95k-token conversation</h2>
 *
 * <p>
 * The thresholds come from {@code ModelContextLimits} and are <b>not overridable</b>: the factory keeps its token
 * estimator and context-window registry private with no setter. The only lever a test has is the per-turn
 * {@link ExecutionBudget} — a {@code compactionTokenThreshold} the scripted client's stamped usage clears after one
 * call makes {@code BudgetTracker.check()} return {@code SHOULD_COMPACT}, which routes the gate through
 * {@code forceCompact} and lowers the effective trigger from the auto-compact band to the <b>warning</b> band.
 *
 * <p>
 * So the fixture is sized deliberately <b>between the two bands</b>: above
 * {@link ModelContextLimits#getWarningThreshold
 * warning}, below {@link ModelContextLimits#getAutoCompactThreshold auto-compact}. That is what makes the pair of tests
 * here mutually falsifying — the same script must <em>not</em> compact without a budget
 * ({@link #withoutABudgetHintTheSameTranscriptIsLeftAlone}) and <em>must</em> compact with one
 * ({@link #aBudgetHintCompactsTheTranscriptMidTurn}). If the fixture ever drifts out of the band, one of the two fails
 * rather than both quietly passing, and {@link #theFixtureSitsBetweenTheWarningAndAutoCompactBands} names the drift
 * directly by asserting the engine's own pre-compaction count.
 */
@DisplayName("RT-IT-L2: transcript compaction through an assembled runtime")
class CompactionTurnIntegrationTest {

    /** Marker on the first line of the fixture — what must survive before compaction and vanish after. */
    private static final String BIG_FILE_SENTINEL = "BIG-FILE-SENTINEL-6c04";

    private static final String SUMMARY_SENTINEL = "SUMMARY-SENTINEL-1d7f";

    /**
     * 285 lines × 1000 chars. {@code ReadTool} prefixes each line with {@code %6d→} (9 bytes: 6 digits plus a 3-byte
     * arrow) and a newline, so the observation is ~285 × 1010 ≈ 288 KB ≈ 82,300 tokens at the heuristic estimator's
     * 3.5 bytes/token — roughly 7,000 tokens above the warning band and 12,000 below auto-compact. Neither
     * {@code ReadTool}'s 2000-line default limit nor its 2000-char line cap is approached, and the executor does not
     * truncate tool results, so the whole thing really does land in the transcript.
     */
    private static final int FIXTURE_LINES = 285;
    private static final int FIXTURE_LINE_LENGTH = 1000;

    /** The limits the assembled runtime resolves for an agent that left its model name defaulted. */
    private static final ModelContextLimits LIMITS = InMemoryModelContextWindowRegistry.withDefaults().resolve("");

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
        node.writeFile("big.txt", fixtureContent());
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    /** A file whose first line carries the sentinel, padded out to the size the band requires. */
    private static String fixtureContent() {
        final StringBuilder content = new StringBuilder(FIXTURE_LINES * (FIXTURE_LINE_LENGTH + 1));
        for (int line = 0; line < FIXTURE_LINES; line++) {
            final String head = line == 0 ? BIG_FILE_SENTINEL : "filler-" + line;
            content.append(head);
            content.append("x".repeat(FIXTURE_LINE_LENGTH - head.length()));
            content.append('\n');
        }
        return content.toString();
    }

    /**
     * Reads the fixture on iteration 1, then answers on iteration 2 — so the guard's second pass sees a transcript
     * carrying the whole file.
     */
    private void scriptReadThenAnswer(SessionId sessionId, LlmResponse answer) {
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", "big.txt")), answer);
    }

    /** A budget whose only constraint is a compaction hint the scripted client's stamped usage clears immediately. */
    private static ExecutionBudget compactionHintBudget() {
        return ExecutionBudget.builder().compactionTokenThreshold(10).build();
    }

    @Test
    @DisplayName("a budget compaction hint rewrites the transcript mid-turn, and the next call goes out compacted")
    void aBudgetHintCompactsTheTranscriptMidTurn() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId, ScriptedLlmClient.text("answered"));
        // The summariser's own call. It inherits the session's trace id, so without its own route it would consume the
        // response scripted for iteration 2 and the turn would end on the summary text instead.
        llm.script(ScriptedLlmClient.compactionRoute(sessionId.value()), ScriptedLlmClient.text(SUMMARY_SENTINEL));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the big file", compactionHintBudget());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("answered");
        assertThat(result.getCompactionEvents()).hasSize(1);
        // The summariser really ran — exactly once, on its own route.
        assertThat(llm.callCount(ScriptedLlmClient.compactionRoute(sessionId.value()))).isEqualTo(1);

        // What the model saw on the iteration after compaction. The gate runs at the *top* of an iteration, so the
        // model never sees the read observation at all here — the transcript is replaced before the call goes out.
        // Compaction that emitted an event but left the original messages in place would pass every assertion above.
        final ScriptedLlmClient.Call afterCompaction = llm.lastCallFor(sessionId.value());
        assertThat(afterCompaction.allText()).anyMatch(text -> text.contains(SUMMARY_SENTINEL))
                .anyMatch(text -> text.contains(CompactBoundary.BOUNDARY_OPEN_PREFIX))
                .noneMatch(text -> text.contains(BIG_FILE_SENTINEL))
                // Exactly the boundary and the summary: the replacement is the whole transcript, not a prepend. The
                // user's own input is gone too, which is why the summary has to stand in for it.
                .hasSize(2);
        // The tool result went with it — a Role.TOOL message is not text content, so hasSize(2) alone would not say so.
        assertThat(afterCompaction.observations()).isEmpty();
        // The sentinel's absence is a rewrite rather than an observation that never reached the model: the negative
        // control below runs this same script and fixture, and its last call does carry it.
    }

    /**
     * The negative control, and the reason the fixture is sized into a band rather than made as large as possible: with
     * no budget hint the gate stays on the auto-compact band, which this transcript does not reach.
     */
    @Test
    @DisplayName("without a budget hint the same transcript is below the auto-compact band and is left alone")
    void withoutABudgetHintTheSameTranscriptIsLeftAlone() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId, ScriptedLlmClient.text("answered"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the big file");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompactionEvents()).isEmpty();
        // No summariser call was made at all — the route is unscripted, so a stray call would also have consumed the
        // global fallback and gone unnoticed without this.
        assertThat(llm.callCount(ScriptedLlmClient.compactionRoute(sessionId.value()))).isZero();
        assertThat(llm.lastCallFor(sessionId.value()).allText()).anyMatch(text -> text.contains(BIG_FILE_SENTINEL));
    }

    /**
     * Pins the fixture into the band using the engine's own accounting rather than this test's arithmetic.
     *
     * <p>
     * Without it, a fixture that drifted above the auto-compact band would silently turn the negative control into a
     * second positive control, and a fixture that drifted below the warning band would make both tests pass while
     * exercising no compaction at all.
     */
    @Test
    @DisplayName("the fixture sits between the warning and auto-compact bands, as both tests above require")
    void theFixtureSitsBetweenTheWarningAndAutoCompactBands() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId, ScriptedLlmClient.text("answered"));
        llm.script(ScriptedLlmClient.compactionRoute(sessionId.value()), ScriptedLlmClient.text(SUMMARY_SENTINEL));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the big file", compactionHintBudget());

        final CompactionMetadata event = result.getCompactionEvents().get(0);
        assertThat(event.getPreCompactTokenCount()).isGreaterThanOrEqualTo(LIMITS.getWarningThreshold())
                .isLessThan(LIMITS.getAutoCompactThreshold());
        // Compaction is only worth doing if it shrinks the transcript; a summary larger than what it replaced would be
        // a regression the event count alone cannot see.
        assertThat(event.getPostCompactTokenCount()).isLessThan(event.getPreCompactTokenCount());
        assertThat(event.getMessagesSummarized()).isPositive();
    }

    /**
     * A summariser that fails must not take the turn down with it. The engine treats a blank summary as a failure, so
     * an empty scripted response is the cheapest way to reach that path without stubbing the engine.
     */
    @Test
    @DisplayName("a failed summarisation leaves the turn running on the uncompacted transcript")
    void aFailedSummarisationDoesNotAbortTheTurn() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptReadThenAnswer(sessionId, ScriptedLlmClient.text("answered anyway"));
        llm.script(ScriptedLlmClient.compactionRoute(sessionId.value()), ScriptedLlmClient.text(""));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the big file", compactionHintBudget());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("answered anyway");
        // The transcript was not rewritten, so the model still has everything it had before the attempt.
        assertThat(llm.lastCallFor(sessionId.value()).allText()).anyMatch(text -> text.contains(BIG_FILE_SENTINEL));
    }
}
