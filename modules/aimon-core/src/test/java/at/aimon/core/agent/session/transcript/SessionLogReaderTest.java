package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * {@link SessionLogReader}: the manifest decides what is read, missing segments are gaps, and pages end at legal cuts.
 */
class SessionLogReaderTest {

    private static final SessionId SESSION = SessionId.of("reader-session");

    private final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
    private final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();

    /** seq 0..5: q1, call t1, result t1, a1, q2, a2 — with [0, 4) summarized and sealed. */
    private TranscriptBuffer sealedBuffer() {
        final TranscriptBuffer buffer = TranscriptBuffer.fromSnapshot(SessionSnapshot.fromLog(SESSION, "system",
                SessionLogState
                        .ofMessages(List.of(Message.user("q1"),
                                Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))),
                                Message.toolUseResults(List.of(ToolUseResult.success("t1", "body"))),
                                Message.assistant("a1"), Message.user("q2"), Message.assistant("a2")))
                        .withFormatAtLeast(SessionLogFormat.V2)));
        buffer.summarizeView(
                SummarySpan.builder().fromSeq(0).toSeq(4).summaryText("s").boundaryId("b").trigger("AUTO").build());
        new SessionLogSealer(SessionLogStorage.builder(segments).minSealTokens(0).build()).seal(buffer);
        records.mergeFromSnapshot(buffer.toSnapshot());
        return buffer;
    }

    private SessionLogReader reader(int maxReadTokens) {
        return new SessionLogReader(records, segments, maxReadTokens, new HeuristicTokenEstimator());
    }

    private static List<String> contents(List<SessionLogEntry> entries) {
        final List<String> contents = new ArrayList<>();
        for (SessionLogEntry entry : entries) {
            contents.add(entry.getMessage().getRole() == Role.TOOL ? "<tool>" : entry.getMessage().getContent());
        }
        return contents;
    }

    @Test
    void readsSealedAndCarriedEntriesInSeqOrder() {
        sealedBuffer();

        final SessionLogPage page = reader(0).read(SESSION, 0, Long.MAX_VALUE);

        assertThat(page.getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
        assertThat(page.getGaps()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void honoursTheRequestedWindow() {
        sealedBuffer();

        assertThat(reader(0).read(SESSION, 2, 5).getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(2L,
                3L, 4L);
    }

    @Test
    void pagesEndOnlyAtLegalCuts() {
        sealedBuffer();

        // A budget of one token ends a page after every entry — except where the next entry would orphan a result.
        final List<List<Long>> pages = new ArrayList<>();
        long from = 0;
        while (true) {
            final SessionLogPage page = reader(1).read(SESSION, from, Long.MAX_VALUE);
            pages.add(page.getEntries().stream().map(SessionLogEntry::getSeq).toList());
            if (!page.hasMore()) {
                break;
            }
            from = page.getNextFromSeq().getAsLong();
        }

        assertThat(pages).containsExactly(List.of(0L), List.of(1L, 2L), List.of(3L), List.of(4L), List.of(5L));
    }

    @Test
    void aMissingSegmentIsAGapNotAFailure() {
        final TranscriptBuffer buffer = sealedBuffer();
        segments.delete(SESSION, buffer.getManifest().get(0).getSegmentId());

        final SessionLogPage page = reader(0).read(SESSION, 0, Long.MAX_VALUE);

        assertThat(page.getGaps()).containsExactly(SeqRange.of(0, 4));
        assertThat(contents(page.getEntries())).containsExactly("[history unavailable: seq 0..3]", "q2", "a2");
        assertThat(page.getEntries().get(0).getOrigin()).isEqualTo(LogOrigin.SYNTHETIC);
    }

    @Test
    void aSegmentThatDoesNotMatchItsHashIsAGap() {
        final TranscriptBuffer buffer = sealedBuffer();
        final SessionLogManifestEntry line = buffer.getManifest().get(0);
        final SessionLogSegment genuine = segments.get(SESSION, line.getSegmentId()).orElseThrow();
        segments.delete(SESSION, line.getSegmentId());
        segments.put(SessionLogSegment.builder().sessionId(SESSION).id(line.getSegmentId()).fromSeq(0).toSeq(4)
                .entryCount(4).payload(genuine.getPayload() + " ").createdAt(Instant.now()).build());

        assertThat(reader(0).read(SESSION, 0, Long.MAX_VALUE).getGaps()).containsExactly(SeqRange.of(0, 4));
    }

    @Test
    void aSegmentNoManifestNamesIsNeverRead() {
        final TranscriptBuffer buffer = sealedBuffer();
        buffer.clear();
        records.mergeFromSnapshot(buffer.toSnapshot());
        segments.put(SessionLogSegment.builder().sessionId(SESSION).id(SegmentId.generate()).fromSeq(0).toSeq(1)
                .entryCount(1).payload("unrelated").createdAt(Instant.now()).build());

        assertThat(reader(0).read(SESSION, 0, Long.MAX_VALUE).getEntries()).isEmpty();
    }

    @Test
    void aSessionWithNoRecordReadsEmpty() {
        assertThat(reader(0).read(SessionId.of("absent"), 0, Long.MAX_VALUE).getEntries()).isEmpty();
    }
}
