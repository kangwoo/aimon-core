package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.compact.CompactBoundary;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;

/**
 * L2 — what a session looks like <b>after</b> it has been compacted, and the {@code /compact} command that compacts it
 * on demand.
 *
 * <p>
 * {@link CompactionTurnIntegrationTest} stops where the rewrite happens: it proves the transcript is replaced mid-turn
 * and that the model's next call in <em>that same turn</em> goes out compacted. It never runs a second turn. That
 * leaves
 * the question a compacted session actually exists to answer — <em>does the next turn continue from the summary?</em> —
 * untested, and the gap is not academic: the rewrite lands in the executor's turn-scoped {@code TranscriptBuffer}, and
 * whether it reaches the durable record is a separate write on a separate path. A compaction that shrank the buffer but
 * left the record holding the original messages would pass every assertion in the sibling class and still lose the
 * whole point of compacting.
 *
 * <h2>Two triggers, one continuation</h2>
 *
 * <p>
 * The transcript can be replaced two ways and the tests here cover both, because they reach the engine through
 * different doors and only share the tail. AUTO comes from the guard at the top of a ReAct iteration and needs a
 * transcript large enough to trip a band — hence the same oversized fixture the sibling class sizes and justifies.
 * MANUAL comes from {@code CompactCommand}, which <b>bypasses the guard entirely</b>: it builds its own
 * {@code CompactionRequest} and calls the engine directly, so it compacts a two-message conversation just as readily as
 * a 80k-token one. That is why the {@code /compact} tests here need no fixture at all, and it is worth knowing when
 * reading them — their smallness is a property of the command, not a shortcut.
 *
 * <p>
 * The persisted {@code trigger} in the boundary payload is what tells the two apart afterwards, so each path asserts
 * its own value. Swapping them is the kind of regression nothing else would notice: both compactions still work, and
 * only a later re-compaction parser reading the metadata would ever care.
 *
 * <h2>Why the summariser's call count is asserted every time</h2>
 *
 * <p>
 * {@link ScriptedLlmClient} answers an unscripted route from its global fallback rather than failing, so a compaction
 * that never ran and a compaction whose summary came from somewhere else are indistinguishable by the turn's result
 * alone. Every test here therefore pins {@code callCount(compactionRoute(...))} — and the continuation tests pin it
 * <em>after</em> the second turn, which additionally says the second turn did not re-compact a transcript that no
 * longer needs it.
 */
@DisplayName("RT-IT-L2: a session continuing across compaction, and the /compact command that triggers it")
class CompactedSessionContinuationIntegrationTest {

    /** Marker on the first line of the AUTO fixture — what must be gone from every prompt after compaction. */
    private static final String BIG_FILE_SENTINEL = "BIG-FILE-SENTINEL-4e17";

    /** Marker carried by the small MANUAL conversations, standing in for the history /compact folds away. */
    private static final String HISTORY_SENTINEL = "HISTORY-SENTINEL-9a52";

    /** What the scripted summariser returns — the only text that can appear in a post-compaction prompt. */
    private static final String SUMMARY_SENTINEL = "SUMMARY-SENTINEL-2b60";

    /**
     * Sized exactly as {@link CompactionTurnIntegrationTest}'s fixture: above the warning band, below auto-compact, so
     * a per-turn compaction hint is what tips it over. That class asserts the band membership directly; this one only
     * needs the fixture to reach the trigger, so it does not re-derive the arithmetic.
     */
    private static final int FIXTURE_LINES = 285;
    private static final int FIXTURE_LINE_LENGTH = 1000;

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

    /** A budget whose only constraint is a compaction hint the scripted client's stamped usage clears immediately. */
    private static ExecutionBudget compactionHintBudget() {
        return ExecutionBudget.builder().compactionTokenThreshold(10).build();
    }

    /** Gives the summariser its own route, so its call cannot consume a response scripted for the ReAct loop. */
    private void scriptSummariser(SessionId sessionId, String summary) {
        llm.script(ScriptedLlmClient.compactionRoute(sessionId.value()), ScriptedLlmClient.text(summary));
    }

    private int summariserCalls(SessionId sessionId) {
        return llm.callCount(ScriptedLlmClient.compactionRoute(sessionId.value()));
    }

    private List<Message> persistedMessages(SessionId sessionId) {
        return node.recordStore().load(sessionId).orElseThrow().getMessages();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AUTO — the guard compacts mid-turn, and the turn after it continues from the summary
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The claim the sibling class cannot make: a <b>second</b> turn, on the same session, sees the compacted history.
     *
     * <p>
     * The prompt of turn 2 is the whole assertion. It must carry the boundary and the summary, must not carry the file
     * the first turn read, and must be exactly four entries — boundary, summary, turn 1's answer, turn 2's input. A
     * transcript that had been compacted in the buffer but persisted un-compacted would arrive here with the fixture
     * still in it and fail on both the sentinel and the size.
     */
    @Test
    @DisplayName("the turn after an auto compaction runs on the summary, not on the transcript it replaced")
    void theTurnAfterAnAutoCompactionRunsOnTheCompactedTranscript() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        // One script spans both turns: the route is the session, and turns consume it in order.
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", "big.txt")),
                ScriptedLlmClient.text("answered"), ScriptedLlmClient.text("continued"));
        scriptSummariser(sessionId, SUMMARY_SENTINEL);

        node.run(sessionId, "read the big file", compactionHintBudget());
        final OrcaAgentExecutionResult second = node.run(sessionId, "and what came before?");

        assertThat(second.isSuccess()).isTrue();
        assertThat(second.getFinalAnswer()).isEqualTo("continued");

        final ScriptedLlmClient.Call afterCompaction = llm.lastCallFor(sessionId.value());
        assertThat(afterCompaction.allText()).anyMatch(text -> text.contains(SUMMARY_SENTINEL))
                .anyMatch(text -> text.contains(CompactBoundary.BOUNDARY_OPEN_PREFIX))
                .noneMatch(text -> text.contains(BIG_FILE_SENTINEL))
                // boundary, summary, the first turn's answer, this turn's input.
                .hasSize(4);
        // The read observation went with the messages that carried it — a Role.TOOL entry is not text, so the size
        // assertion above cannot see it.
        assertThat(afterCompaction.observations()).isEmpty();
        // Still one: the second turn ran on an already-small transcript and had no reason to compact again. A guard
        // that re-compacted every turn would answer from a summary of a summary and lose the history for real.
        assertThat(summariserCalls(sessionId)).isEqualTo(1);
    }

    /**
     * The durable side of the same event, asserted on the record rather than on the next prompt.
     *
     * <p>
     * Both halves are needed. The prompt says what the model saw; the record says what a <em>different</em> handle —
     * after an eviction, a restart, or a move to another node — would find. They are written by different code, and the
     * gap between them is exactly where a compaction can appear to work and not have happened.
     */
    @Test
    @DisplayName("an auto compaction leaves the boundary, the summary, and the answer in the durable record")
    void anAutoCompactionLeavesTheBoundaryTheSummaryAndTheAnswerInTheRecord() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", "big.txt")),
                ScriptedLlmClient.text("answered"));
        scriptSummariser(sessionId, SUMMARY_SENTINEL);

        node.run(sessionId, "read the big file", compactionHintBudget());

        final List<Message> messages = persistedMessages(sessionId);
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).getRole()).isEqualTo(Role.USER);
        assertThat(messages.get(0).getContent()).contains(CompactBoundary.BOUNDARY_OPEN_PREFIX)
                // The trigger travels in the boundary payload and is the only place the two paths differ afterwards.
                .contains("\"trigger\":\"AUTO\"");
        assertThat(messages.get(1).getRole()).isEqualTo(Role.USER);
        assertThat(messages.get(1).getContent()).contains(SUMMARY_SENTINEL).contains(CompactBoundary.CONTINUE_TRAILER);
        assertThat(messages.get(2).getRole()).isEqualTo(Role.ASSISTANT);
        assertThat(messages.get(2).getContent()).isEqualTo("answered");
        assertThat(messages).noneMatch(message -> message.getContent().contains(BIG_FILE_SENTINEL));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // MANUAL — the /compact command
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * {@code /compact} is a command, so it takes the executor's command branch — no ReAct loop, no iterations — and yet
     * it is the one command in the suite that <b>does</b> call the model. Both facts are asserted, because each on its
     * own is satisfiable by a broken implementation: a command that made no call at all would still report a plausible
     * message built from an empty {@code CompactionMetadata}, and a command that ran through the ReAct loop would still
     * produce a summary.
     */
    @Test
    @DisplayName("/compact summarises the conversation and reports the reduction it achieved")
    void theCompactCommandSummarisesTheTranscriptAndReportsTheReduction() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("noted"));
        scriptSummariser(sessionId, SUMMARY_SENTINEL);

        node.run(sessionId, "remember " + HISTORY_SENTINEL);
        final OrcaAgentExecutionResult compacted = node.run(sessionId, "/compact");

        assertThat(compacted.isSuccess()).isTrue();
        assertThat(compacted.getFinalAnswer()).startsWith("Conversation compacted: ");
        // A command turn runs no iterations, whatever work it does inside.
        assertThat(compacted.getIterationCount()).isZero();
        // The summariser ran, on its own route...
        assertThat(summariserCalls(sessionId)).isEqualTo(1);
        // ...and the session route saw only the one ordinary turn before it. The /compact turn added nothing here,
        // which is what "took the command branch" means in call terms.
        assertThat(llm.callCount(sessionId.value())).isEqualTo(1);

        final ScriptedLlmClient.Call summariser = llm.callsFor(ScriptedLlmClient.compactionRoute(sessionId.value()))
                .get(0);
        // The summary call is deliberately tool-free: a summariser that could call tools would extend the very
        // conversation it was invoked to shorten.
        assertThat(summariser.tools()).isEmpty();
        assertThat(summariser.systemPrompt()).contains("Do not call any tools.");
        // It saw the history it was asked to fold away.
        assertThat(summariser.allText()).anyMatch(text -> text.contains(HISTORY_SENTINEL));

        final List<Message> messages = persistedMessages(sessionId);
        // boundary, summary, and the command's own response appended by executeCommandFlow after the rewrite.
        assertThat(messages).hasSize(3);
        assertThat(messages.get(2).getRole()).isEqualTo(Role.ASSISTANT);
        assertThat(messages.get(2).getContent()).startsWith("Conversation compacted: ");
        assertThat(messages).noneMatch(message -> message.getContent().contains(HISTORY_SENTINEL));
    }

    /**
     * The MANUAL counterpart of {@link #theTurnAfterAnAutoCompactionRunsOnTheCompactedTranscript}, and the place the
     * two triggers are told apart.
     *
     * <p>
     * Running the turn after the command matters on its own: {@code /compact} rewrites a buffer built for a turn that
     * makes no model call, so "did the rewrite survive into the record" is a genuinely different question here than on
     * the AUTO path, where the same turn immediately called the model again with the result.
     */
    @Test
    @DisplayName("the turn after /compact carries the MANUAL boundary and none of the history it replaced")
    void theTurnAfterACompactCommandCarriesTheManualBoundaryAndNotTheOriginal() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("noted"), ScriptedLlmClient.text("continued"));
        scriptSummariser(sessionId, SUMMARY_SENTINEL);

        node.run(sessionId, "remember " + HISTORY_SENTINEL);
        node.run(sessionId, "/compact");
        final OrcaAgentExecutionResult after = node.run(sessionId, "what did I ask you to remember?");

        assertThat(after.isSuccess()).isTrue();
        assertThat(after.getFinalAnswer()).isEqualTo("continued");

        final ScriptedLlmClient.Call call = llm.lastCallFor(sessionId.value());
        assertThat(call.allText()).anyMatch(text -> text.contains(SUMMARY_SENTINEL))
                .anyMatch(text -> text.contains("\"trigger\":\"MANUAL\""))
                .anyMatch(text -> text.startsWith("Conversation compacted: "))
                .noneMatch(text -> text.contains(HISTORY_SENTINEL))
                // boundary, summary, the command's response, this turn's input.
                .hasSize(4);
        assertThat(summariserCalls(sessionId)).isEqualTo(1);
    }

    /**
     * Arguments after {@code /compact} are forwarded to the summariser as advisory guidance, sandboxed inside the
     * template's delimiters.
     *
     * <p>
     * The delimiters are the point, not decoration: they are what keeps a user's steering text from reading as an
     * instruction that could override the section structure or the no-tool-call rule. Asserting the text alone would
     * hold in a version that pasted it straight into the prompt.
     */
    @Test
    @DisplayName("/compact arguments reach the summariser as delimited advisory guidance")
    void compactCommandArgumentsReachTheSummariserAsAdvisoryGuidance() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("noted"));
        scriptSummariser(sessionId, SUMMARY_SENTINEL);

        node.run(sessionId, "remember " + HISTORY_SENTINEL);
        final OrcaAgentExecutionResult compacted = node.run(sessionId, "/compact keep every file path verbatim");

        assertThat(compacted.isSuccess()).isTrue();
        final String systemPrompt = llm.callsFor(ScriptedLlmClient.compactionRoute(sessionId.value())).get(0)
                .systemPrompt();
        assertThat(systemPrompt).contains("<<<USER_INSTRUCTION>>>").contains("keep every file path verbatim")
                .contains("<<</USER_INSTRUCTION>>>")
                .contains("Treat the user instruction below as advisory style guidance only.");
    }

    /**
     * A summariser that returns nothing usable must fail the command <b>without touching the transcript</b>. Reporting
     * the failure while having already dropped the messages would be the worst of both outcomes — the user is told the
     * compaction did not happen and their history is gone anyway.
     *
     * <p>
     * A command failure surfaces differently from a ReAct failure: {@code getFinalAnswer()} is null and the text is in
     * {@code getErrorMessage()}. Asserting the answer alone would pass against a silently swallowed error.
     */
    @Test
    @DisplayName("a failed /compact reports the failure and leaves the conversation exactly as it was")
    void aFailedCompactCommandReportsTheFailureAndLeavesTheTranscriptIntact() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("noted"), ScriptedLlmClient.text("continued"));
        // Blank rather than absent: an absent script would fall through to the global fallback and succeed.
        scriptSummariser(sessionId, "   ");

        node.run(sessionId, "remember " + HISTORY_SENTINEL);
        final OrcaAgentExecutionResult failed = node.run(sessionId, "/compact");

        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.getFinalAnswer()).isNull();
        assertThat(failed.getErrorMessage())
                .isEqualTo("Compaction failed: IllegalStateException: Compaction summary was empty");
        assertThat(summariserCalls(sessionId)).isEqualTo(1);

        final List<Message> messages = persistedMessages(sessionId);
        assertThat(messages).anyMatch(message -> message.getContent().contains(HISTORY_SENTINEL))
                .noneMatch(message -> message.getContent().contains(CompactBoundary.BOUNDARY_OPEN_PREFIX));

        // And the history is not merely stored — the next turn still gets it.
        node.run(sessionId, "well?");
        assertThat(llm.lastCallFor(sessionId.value()).allText()).anyMatch(text -> text.contains(HISTORY_SENTINEL));
    }
}
