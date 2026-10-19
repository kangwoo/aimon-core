package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.compact.CompactBoundary;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SeqRange;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogManifestEntry;
import at.aimon.core.agent.session.transcript.SessionLogPage;
import at.aimon.core.agent.session.transcript.SessionLogReader;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionLogStorage;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.subagent.task.codec.JsonSessionSnapshotCodec;

/**
 * L2 — compaction as an application gets it without a setting: a version-2 session log, the default context engine in
 * view mode, and — as {@code AimonStackBuilder} supplies one beside an in-memory record store — a segment store the
 * compacted range is sealed into.
 *
 * <p>
 * {@link CompactionTurnIntegrationTest} and {@link CompactedSessionContinuationIntegrationTest} pin version 1, because
 * what they assert is the in-place rewrite. Version 2 is what an application now gets without a setting, and it
 * compacts differently enough that none of their assertions carry over: the log stays append-only, the model is sent
 * {@code [boundary, summary]} followed by the part it has not answered yet <em>verbatim</em>, and the summarized range
 * leaves the record for a segment. {@code OrcaAgentExecutorViewModeTest} proves those claims over a hand-built
 * executor and runtime with a tiny window; this class proves them over the runtime the real factory assembles, its
 * real tools reading a real file into the transcript — the LLM is the only thing scripted.
 *
 * <h2>Why compaction lands on the second turn</h2>
 *
 * <p>
 * In view mode the part the model has not answered yet is not summarized short of the blocking limit — everything
 * after the view's last assistant message, together with the call that message made (context-engine §13.10). A big
 * tool result read in the current iteration is exactly that part, so it cannot be folded away until the model has
 * answered it. The oversized read therefore happens on turn 1, which answers it, and the compaction hint is given to
 * turn 2. The fixture is sized as in {@link CompactionTurnIntegrationTest} — above the warning band, below
 * auto-compact — so the hint is what tips it over and turn 1, which runs without one, must not compact.
 *
 * <p>
 * Turn 2 reads a small note before answering. That gives it a second iteration — {@code BudgetTracker.check()} weighs
 * the hint against the tokens this execution has spent, which are zero until the scripted client stamps usage on the
 * first call, so {@code SHOULD_COMPACT} and the forced compaction come at the top of iteration 2 — and puts an open
 * tool call at the end of the view when it does. "Unanswered" is measured from the last assistant message, not from
 * the turn's start: turn 2's own input precedes that call and is summarized with turn 1, while the call and the note
 * it returned stay verbatim. The post-compaction prompt therefore carries a tool result that <em>must</em> survive
 * next to one that must not, and the turn's input is not among what survives.
 *
 * <h2>Sealing</h2>
 *
 * <p>
 * The storage seals at any size ({@code minSealTokens(0)}) rather than at the production default of 32,000 tokens, so
 * what is sealed depends only on where the summary span ends and not on how big the fixture happens to be — and the
 * small {@code /compact} conversation is sealed too. Reads keep the production page bound ({@code maxReadTokens}),
 * which the big file alone exceeds, so reading the log back exercises paging.
 */
@DisplayName("RT-IT-L2: compaction on the default version-2 log, through the assembled runtime")
class ViewModeCompactionIntegrationTest {

    /** Marker on the first line of the big file — summarized away from the view, but never from the log. */
    private static final String BIG_FILE_SENTINEL = "BIG-FILE-SENTINEL-7c31";

    /** Content of the note turn 2 reads — the unanswered tool result, so it must reach the model after compaction. */
    private static final String NOTE_SENTINEL = "NOTE-SENTINEL-58ae";

    /** Marker carried by the small conversations the /compact tests fold away. */
    private static final String HISTORY_SENTINEL = "HISTORY-SENTINEL-d240";

    private static final String SUMMARY_SENTINEL = "SUMMARY-SENTINEL-0f9b";

    private static final String TURN_1_INPUT = "read the big file";
    private static final String TURN_2_INPUT = "now read the note";

    /** See {@link CompactionTurnIntegrationTest}'s fixture for the arithmetic; the same size reaches the same band. */
    private static final int FIXTURE_LINES = 285;
    private static final int FIXTURE_LINE_LENGTH = 1000;

    /** Far more pages than these logs take — a cap so a read that stops advancing fails instead of hanging. */
    private static final int MAX_LOG_PAGES = 50;

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private InMemorySessionLogSegmentStore segments;
    private SessionLogStorage storage;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        segments = new InMemorySessionLogSegmentStore();
        storage = SessionLogStorage.builder(segments).minSealTokens(0).build();
        // No sessionLogWriteFormat: version 2 is the harness default, as it is the production one.
        node = support.newNode("agent-a", llm, OrcaRuntimeItSupport.options().sessionLogStorage(storage));
        node.writeFile("big.txt", fixtureContent());
        node.writeFile("note.txt", NOTE_SENTINEL + "\n");
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

    private void scriptSummariser(SessionId sessionId, String summary) {
        llm.script(ScriptedLlmClient.compactionRoute(sessionId.value()), ScriptedLlmClient.text(summary));
    }

    private int summariserCalls(SessionId sessionId) {
        return llm.callCount(ScriptedLlmClient.compactionRoute(sessionId.value()));
    }

    /**
     * Turn 1 reads the big file and answers; turn 2, under a compaction hint, reads the note and answers. The script
     * also carries {@code extra} responses for the turns a test runs after these two.
     */
    private SessionId compactAcrossTwoTurns(LlmResponse... extra) {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final List<LlmResponse> responses = Stream
                .concat(Stream.of(ScriptedLlmClient.callTool("Read", Map.of("file_path", "big.txt")),
                        ScriptedLlmClient.text("read it"),
                        ScriptedLlmClient.callTool("tu-note", "Read", Map.of("file_path", "note.txt")),
                        ScriptedLlmClient.text("noted")), Stream.of(extra))
                .toList();
        llm.script(sessionId.value(), responses.toArray(LlmResponse[]::new));
        scriptSummariser(sessionId, SUMMARY_SENTINEL);

        final OrcaAgentExecutionResult first = node.run(sessionId, TURN_1_INPUT);
        assertThat(first.isSuccess()).isTrue();
        // The negative control: the same transcript, without the hint, sits below auto-compact.
        assertThat(first.getCompactionEvents()).as("turn 1 runs without a hint and must not compact").isEmpty();

        final OrcaAgentExecutionResult second = node.run(sessionId, TURN_2_INPUT, compactionHintBudget());
        assertThat(second.isSuccess()).isTrue();
        assertThat(second.getFinalAnswer()).isEqualTo("noted");
        assertThat(second.getCompactionEvents()).hasSize(1);
        assertThat(summariserCalls(sessionId)).isEqualTo(1);
        return sessionId;
    }

    private SessionLogState logState(InMemorySessionRecordStore store, SessionId sessionId) {
        return store.load(sessionId).orElseThrow().getLogState();
    }

    /**
     * Every conversation message of the log, sealed ranges included — text and tool results alike. Paged, because one
     * read is bounded by {@code maxReadTokens} and the big file alone is over it.
     */
    private static List<String> wholeLogText(OrcaRuntimeItSupport.Node node, SessionId sessionId) {
        return logPages(node, sessionId).stream().flatMap(page -> page.getEntries().stream())
                .filter(entry -> entry.getOrigin() == LogOrigin.CONVERSATION)
                .flatMap(entry -> textOf(entry.getMessage())).toList();
    }

    /** The log read from its start to its end, one page per bounded read. */
    private static List<SessionLogPage> logPages(OrcaRuntimeItSupport.Node node, SessionId sessionId) {
        final SessionLogReader reader = node.transcriptManager().getLogReader().orElseThrow();
        final List<SessionLogPage> pages = new ArrayList<>();
        long from = 0;
        while (pages.size() < MAX_LOG_PAGES) {
            final SessionLogPage page = reader.read(sessionId, from, Long.MAX_VALUE);
            assertThat(page.getGaps()).as("no sealed range is unreadable").isEmpty();
            pages.add(page);
            if (!page.hasMore()) {
                return pages;
            }
            final long next = page.getNextFromSeq().orElseThrow();
            assertThat(next).as("a page must advance the read").isGreaterThan(from);
            from = next;
        }
        throw new AssertionError("the log did not end within " + MAX_LOG_PAGES + " pages");
    }

    private static Stream<String> textOf(Message message) {
        return Stream.concat(Stream.ofNullable(message.getContent()),
                message.getToolUseResults().stream().map(ToolUseResult::getContent));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AUTO — what the model is sent, and what the log keeps
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The view half. After compaction the model sees the marker pair, then the open tool call and the note it returned,
     * verbatim — and nothing else. Turn 1 and turn 2's input went into the summary. In version 1 the note would have
     * been summarized too; that the result the model has not read yet reaches it word for word is the version-2 claim.
     */
    @Test
    @DisplayName("an auto compaction sends the marker pair and the unanswered tool result, and nothing it summarized")
    void anAutoCompactionSendsTheMarkerPairAndTheUnansweredToolResult() {
        final SessionId sessionId = compactAcrossTwoTurns();

        final ScriptedLlmClient.Call afterCompaction = llm.lastCallFor(sessionId.value());
        final List<String> sent = afterCompaction.messageContents();
        assertThat(sent.get(0)).startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX).contains("\"trigger\":\"AUTO\"")
                // turn 1's four messages and turn 2's input
                .contains("\"messagesSummarized\":5");
        assertThat(sent.get(1)).startsWith(CompactBoundary.SUMMARY_OPEN_PREFIX).contains(SUMMARY_SENTINEL);
        // boundary, summary, the assistant message calling Read on the note, and the note's result.
        assertThat(afterCompaction.messages()).extracting(Message::getRole).containsExactly(Role.USER, Role.USER,
                Role.ASSISTANT, Role.TOOL);
        assertThat(afterCompaction.messages().get(2).getToolUses()).singleElement()
                .satisfies(use -> assertThat(use.getInput()).containsEntry("file_path", "note.txt"));
        assertThat(sent).doesNotContain(TURN_1_INPUT, "read it", TURN_2_INPUT);
        assertThat(afterCompaction.observations()).as("the tool result the model has not read yet").singleElement()
                .satisfies(text -> assertThat(text).contains(NOTE_SENTINEL));

        // The summariser was given everything before the open call — the big file and turn 2's input included — and
        // not the note, which it must leave to the model.
        final ScriptedLlmClient.Call summariser = llm.callsFor(ScriptedLlmClient.compactionRoute(sessionId.value()))
                .get(0);
        assertThat(summariser.allText()).anyMatch(text -> text.contains(BIG_FILE_SENTINEL))
                .anyMatch(text -> text.contains(TURN_2_INPUT)).noneMatch(text -> text.contains(NOTE_SENTINEL));
    }

    /**
     * The durable half. The record is version 2 and the summarized range went to the segment store; reading the log
     * back through it yields every word of both turns, and none of the summary — the summary lives in the view state,
     * not in the log.
     */
    @Test
    @DisplayName("an auto compaction seals the summarized range and the log still holds every word")
    void anAutoCompactionSealsTheSummarizedRangeAndKeepsTheWholeLog() {
        final SessionId sessionId = compactAcrossTwoTurns();

        final SessionLogState stored = logState(node.recordStore(), sessionId);
        assertThat(stored.getFormat()).isEqualTo(SessionLogFormat.V2);
        assertThat(stored.getViewState().getSummarySpan())
                .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 5)));
        // The span covers seqs 0-4: turn 1's four messages and turn 2's input. Sealing never straddles a turn's rewind
        // point, so the span is sealed as two runs that meet at turn 2's start (seq 4) rather than as one.
        assertThat(stored.getManifest()).extracting(SessionLogManifestEntry::getRange)
                .containsExactly(SeqRange.of(0, 4), SeqRange.of(4, 5));
        assertThat(segments.list(sessionId)).hasSize(2);
        assertThat(
                stored.getConversationMessages().stream().flatMap(ViewModeCompactionIntegrationTest::textOf).toList())
                .as("only what the view shows stays in the record").contains("noted")
                .anyMatch(text -> text.contains(NOTE_SENTINEL)).noneMatch(text -> text.contains(BIG_FILE_SENTINEL))
                .doesNotContain(TURN_1_INPUT, "read it", TURN_2_INPUT);

        assertThat(wholeLogText(node, sessionId)).as("the log keeps every word, sealed range included")
                .contains(TURN_1_INPUT, "read it", TURN_2_INPUT, "noted")
                .anyMatch(text -> text.contains(BIG_FILE_SENTINEL)).anyMatch(text -> text.contains(NOTE_SENTINEL))
                .noneMatch(text -> text.contains(SUMMARY_SENTINEL));
        assertThat(logPages(node, sessionId)).as("the big file alone is over one read's bound").hasSizeGreaterThan(1);
    }

    /**
     * Turn 3 re-reads the note and answers, under the same compaction hint as turn 2 — a two-iteration turn on which
     * the guard is forced at the top of iteration 2.
     */
    private static LlmResponse[] hintedThirdTurn(String answer) {
        return new LlmResponse[]{ScriptedLlmClient.callTool("tu-note-again", "Read", Map.of("file_path", "note.txt")),
                ScriptedLlmClient.text(answer)};
    }

    /**
     * The next turn's buffer is rebuilt from the record, so this is where a span that was written but not projected
     * would show: turn 3 must go out on the same summary, and must not summarize again.
     *
     * <p>
     * Turn 3 runs under the compaction hint, so "not again" can fail: the forced pass compacts whatever is over the
     * warning band, which a view that lost its span — the big file back in it — is, and a view that kept it is not.
     */
    @Test
    @DisplayName("the turn after an auto compaction runs on the same summary without compacting again")
    void theTurnAfterAnAutoCompactionRunsOnTheSameSummary() {
        final SessionId sessionId = compactAcrossTwoTurns(hintedThirdTurn("continued"));

        final OrcaAgentExecutionResult third = node.run(sessionId, "and then?", compactionHintBudget());

        assertThat(third.isSuccess()).isTrue();
        assertThat(third.getFinalAnswer()).isEqualTo("continued");
        assertThat(third.getCompactionEvents()).isEmpty();
        final ScriptedLlmClient.Call call = llm.lastCallFor(sessionId.value());
        assertThat(call.messageContents().get(1)).contains(SUMMARY_SENTINEL);
        assertThat(call.allText()).contains("noted", "and then?").anyMatch(text -> text.contains(NOTE_SENTINEL))
                .noneMatch(text -> text.contains(BIG_FILE_SENTINEL)).doesNotContain(TURN_1_INPUT, TURN_2_INPUT);
        assertThat(summariserCalls(sessionId)).isEqualTo(1);
    }

    /**
     * What a restart, or another node over the same backends, finds: the record through the version-2 codec into a
     * fresh store, the segment store shared. The continued turn must run on the summary exactly as it would have on
     * the node that compacted, and the sealed range must still be readable from the new node.
     */
    @Test
    @DisplayName("a node over the same backends, the record reloaded through the codec, continues from the summary")
    void aReloadedRecordContinuesFromTheSummary() {
        final SessionId sessionId = compactAcrossTwoTurns(hintedThirdTurn("continued elsewhere"));

        final SessionSnapshot stored = SessionSnapshot.from(node.recordStore().load(sessionId).orElseThrow());
        final JsonSessionSnapshotCodec codec = new JsonSessionSnapshotCodec(SessionLogFormat.V2);
        final SessionSnapshot decoded = codec.decode(codec.encode(stored));
        assertThat(decoded.getLogState()).as("the version-2 codec round trip is lossless")
                .isEqualTo(stored.getLogState());
        final InMemorySessionRecordStore reloaded = new InMemorySessionRecordStore();
        reloaded.mergeFromSnapshot(decoded);
        // The same agent name, so the second node works over the same workspace root. The first node stays up: this
        // is another node over the same backends rather than a restart, and nothing here depends on the difference.
        final OrcaRuntimeItSupport.Node restarted = support.newNode("agent-a", llm,
                OrcaRuntimeItSupport.options().sessionLogStorage(storage).recordStore(reloaded));

        final OrcaAgentExecutionResult continued = restarted.run(sessionId, "and then?", compactionHintBudget());

        assertThat(continued.isSuccess()).isTrue();
        assertThat(continued.getFinalAnswer()).isEqualTo("continued elsewhere");
        assertThat(continued.getCompactionEvents()).isEmpty();
        final ScriptedLlmClient.Call call = llm.lastCallFor(sessionId.value());
        assertThat(call.messageContents().get(1)).contains(SUMMARY_SENTINEL);
        assertThat(call.allText()).contains("noted", "and then?").anyMatch(text -> text.contains(NOTE_SENTINEL))
                .noneMatch(text -> text.contains(BIG_FILE_SENTINEL)).doesNotContain(TURN_1_INPUT, TURN_2_INPUT);
        assertThat(summariserCalls(sessionId)).isEqualTo(1);
        assertThat(wholeLogText(restarted, sessionId)).as("the sealed range is readable from the new node")
                .contains(TURN_1_INPUT, TURN_2_INPUT).anyMatch(text -> text.contains(BIG_FILE_SENTINEL));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // MANUAL — the /compact command
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * {@code /compact} reaches the engine through the command, not the guard, so it is a separate door into view mode.
     * The turn after it must carry the MANUAL boundary and none of the history, while the log keeps that history.
     */
    @Test
    @DisplayName("the turn after /compact runs on a MANUAL summary, and the log keeps what it summarized")
    void theTurnAfterACompactCommandRunsOnTheSummaryAndTheLogKeepsTheHistory() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("remembered"), ScriptedLlmClient.text("continued"));
        scriptSummariser(sessionId, SUMMARY_SENTINEL);

        assertThat(node.run(sessionId, "remember " + HISTORY_SENTINEL).isSuccess()).isTrue();
        final OrcaAgentExecutionResult compacted = node.run(sessionId, "/compact");
        final OrcaAgentExecutionResult after = node.run(sessionId, "what did I ask you to remember?");

        assertThat(compacted.isSuccess()).isTrue();
        assertThat(compacted.getFinalAnswer()).startsWith("Conversation compacted: ");
        assertThat(after.isSuccess()).isTrue();
        assertThat(after.getFinalAnswer()).isEqualTo("continued");
        assertThat(summariserCalls(sessionId)).isEqualTo(1);

        final ScriptedLlmClient.Call call = llm.lastCallFor(sessionId.value());
        assertThat(call.messageContents().get(0)).startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX)
                .contains("\"trigger\":\"MANUAL\"");
        assertThat(call.messageContents().get(1)).contains(SUMMARY_SENTINEL);
        assertThat(call.allText()).contains("what did I ask you to remember?")
                .noneMatch(text -> text.contains(HISTORY_SENTINEL));

        assertThat(logState(node.recordStore(), sessionId).getFormat()).isEqualTo(SessionLogFormat.V2);
        // The history left the record for a segment — so finding it in the log below is a read through the seal, not
        // the record still holding it.
        final SessionLogState stored = logState(node.recordStore(), sessionId);
        assertThat(stored.getManifest()).extracting(SessionLogManifestEntry::getRange)
                .containsExactly(SeqRange.of(0, 2));
        assertThat(
                stored.getConversationMessages().stream().flatMap(ViewModeCompactionIntegrationTest::textOf).toList())
                .noneMatch(text -> text.contains(HISTORY_SENTINEL));
        assertThat(wholeLogText(node, sessionId)).as("/compact shrinks the view, not the log")
                .anyMatch(text -> text.contains(HISTORY_SENTINEL));
    }
}
