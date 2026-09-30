package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.RewoundTurn;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentCodec;
import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogManifestEntry;
import at.aimon.core.agent.session.transcript.SessionLogPage;
import at.aimon.core.agent.session.transcript.SessionLogReader;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionRewindPoint;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * Rewinding an interrupted turn whose record is a sealed version-2 log, end to end through
 * {@link DefaultLiveSession#rewindLastTurn()} (session-log §6.1).
 *
 * <p>
 * The interrupted turn started at seq 4 and a compaction inside it summarized seqs 1–4, so the stored record has
 *
 * <pre>
 * seq:        0      1   2   3    4      5         6        7
 *             user | earlier turn | go | tool_use | result | partial
 * span:              [1 ................. 5)          — straddles the rewind point
 * manifest:          [1 ........ 4) [4 . 5)           — one line ends at it, one starts at it
 * rewind point:                      4
 * </pre>
 *
 * <p>
 * The rewind must keep the line that ends at the point, drop the one that starts at it, cut the span back to the point,
 * and leave a log that a reader, the next turn's view and the next save all accept.
 */
@DisplayName("RT-IT-L3: rewinding an interrupted turn on a sealed version-2 record")
class SealedLogRewindIntegrationTest {

    private static final String SYSTEM_PROMPT = "You are a test agent.";
    private static final String SUMMARY = "Summary: the user asked earlier question two and said go.";

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;
    private InMemorySessionLogSegmentStore segments;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        node = support.newNode("agent-a", llm);
        segments = new InMemorySessionLogSegmentStore();
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    @Test
    @DisplayName("the rewind keeps the line before the point, drops the one at it, and cuts the span back to it")
    void rewindCutsASealedLogAtTheTurnStart() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final SessionLogManifestEntry[] lines = plantInterruptedSealedRecord(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        final RewoundTurn rewound = session.rewindLastTurn().orElseThrow();

        assertThat(rewound.getUserInput()).isEqualTo(TextInput.of("go"));
        final SessionLogState stored = node.recordStore().load(sessionId).orElseThrow().getLogState();
        assertThat(stored.getFormat()).isEqualTo(SessionLogFormat.V2);
        assertThat(stored.getRewindPoint()).isEmpty();
        assertThat(stored.getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(0L);
        assertThat(stored.getManifest()).containsExactly(lines[0]);
        final SummarySpan span = stored.getViewState().getSummarySpan().orElseThrow();
        assertThat(span.getFromSeq()).isEqualTo(1L);
        assertThat(span.getToSeq()).as("cut back to the rewind point").isEqualTo(4L);
        assertThat(span.getSummaryText()).as("the summary is kept as it was — a known inaccuracy, §6.1")
                .isEqualTo(SUMMARY);
        assertThat(stored.getNextSeq()).as("seqs the rewind cut are not handed out again").isEqualTo(8L);

        // The whole log still reads: the kept line from its segment, nothing of the rewound turn.
        final SessionLogPage page = new SessionLogReader(node.recordStore(), segments, 0, new HeuristicTokenEstimator())
                .read(sessionId, 0, Long.MAX_VALUE);
        assertThat(page.getGaps()).isEmpty();
        assertThat(page.getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(0L, 1L, 2L, 3L);
    }

    @Test
    @DisplayName("the retried turn sees the summary and none of the stopped attempt, and saves a valid record")
    void aRetryAfterTheRewindRunsOnTheCutView() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final SessionLogManifestEntry[] lines = plantInterruptedSealedRecord(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);
        llm.script(sessionId.value(), ScriptedLlmClient.text("done"));

        assertThat(session.retryLastTurn().orElseThrow().getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);

        final List<String> sent = llm.lastCallFor(sessionId.value()).messageContents();
        assertThat(sent).contains("what is this session for", "go");
        assertThat(String.join("\n", sent)).contains(SUMMARY).doesNotContain("halfway there")
                .doesNotContain("earlier answer one");
        assertThat(llm.lastCallFor(sessionId.value()).observations()).doesNotContain("partial output");

        final SessionLogState stored = node.recordStore().load(sessionId).orElseThrow().getLogState();
        assertThat(stored.getManifest()).containsExactly(lines[0]);
        assertThat(stored.getEntries()).extracting(SessionLogEntry::getSeq).first().isEqualTo(0L);
        assertThat(stored.getEntries()).filteredOn(entry -> entry.getSeq() > 0)
                .allSatisfy(entry -> assertThat(entry.getSeq()).isGreaterThanOrEqualTo(8L));
        assertThat(stored.getRewindPoint()).isEmpty();
    }

    /**
     * Stores the record drawn in the class comment, with both sealed ranges written to {@link #segments}.
     *
     * @return the two manifest lines, in seq order
     */
    private SessionLogManifestEntry[] plantInterruptedSealedRecord(SessionId sessionId) {
        final SessionLogState log = SessionLogState
                .ofMessages(List.of(Message.user("what is this session for"), Message.assistant("earlier answer one"),
                        Message.user("earlier question two"), Message.assistant("earlier answer two"),
                        Message.user("go"), Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))),
                        Message.toolUseResults(List.of(ToolUseResult.success("t1", "partial output"))),
                        Message.assistant("halfway there")))
                .withFormatAtLeast(SessionLogFormat.V2).withRewindPoint(SessionRewindPoint.of(4, TextInput.of("go")))
                .summarize(SummarySpan.builder().fromSeq(1).toSeq(5).summaryText(SUMMARY).boundaryId("b-1")
                        .trigger("AUTO").preTokenCount(100).messagesSummarized(4).build());
        final SessionLogManifestEntry before = seal(sessionId, log, 1, 4);
        final SessionLogManifestEntry at = seal(sessionId, log, 4, 5);
        final SessionLogState sealed = log.seal(before).seal(at);

        node.recordStore().provision(sessionId);
        node.recordStore().mergeFromSnapshot(SessionSnapshot.fromLog(sessionId, SYSTEM_PROMPT, sealed));
        return new SessionLogManifestEntry[]{before, at};
    }

    private SessionLogManifestEntry seal(SessionId sessionId, SessionLogState log, long from, long to) {
        final List<SessionLogEntry> entries = log.entriesIn(from, to);
        final String payload = SessionLogSegmentCodec.encode(entries);
        final SegmentId id = SegmentId.generate();
        segments.put(SessionLogSegment.builder().sessionId(sessionId).id(id).fromSeq(from).toSeq(to)
                .entryCount(entries.size()).payload(payload).createdAt(Instant.now()).build());
        return SessionLogManifestEntry.builder().fromSeq(from).toSeq(to).segmentId(id)
                .contentHash(SessionLogSegmentCodec.contentHash(payload)).entryCount(entries.size()).build();
    }
}
