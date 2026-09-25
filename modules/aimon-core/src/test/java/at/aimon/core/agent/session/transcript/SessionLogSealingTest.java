package at.aimon.core.agent.session.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLogSegmentStoreException;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SegmentScanPage;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentCodec;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Sealing (session-log §5): the manifest on {@link SessionLogState}, the buffer's in-memory retention, and which runs
 * {@link SessionLogSealer} picks.
 */
class SessionLogSealingTest {

    private static final SessionId SESSION = SessionId.of("seal-session");

    /** seq 0 user, 1 assistant(tool_use), 2 tool result, 3 assistant, 4 user, 5 assistant. */
    private static SessionLogState log() {
        return SessionLogState
                .ofMessages(
                        List.of(Message.user("q1"), Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))),
                                Message.toolUseResults(List.of(ToolUseResult.success("t1", "file body"))),
                                Message.assistant("a1"), Message.user("q2"), Message.assistant("a2")))
                .withFormatAtLeast(SessionLogFormat.V2);
    }

    private static SummarySpan span(long from, long to) {
        return SummarySpan.builder().fromSeq(from).toSeq(to).summaryText("summary").boundaryId("b-1").trigger("AUTO")
                .preTokenCount(10).messagesSummarized(3).build();
    }

    private static SessionLogManifestEntry line(long from, long to, int count) {
        return SessionLogManifestEntry.builder().fromSeq(from).toSeq(to).segmentId(SegmentId.generate())
                .contentHash("sha256:x").entryCount(count).build();
    }

    private static SessionLogStorage storage(SessionLogSegmentStore store) {
        return SessionLogStorage.builder(store).minSealTokens(0).build();
    }

    @Nested
    class ManifestOnTheState {

        @Test
        void sealingMovesTheRangeOutOfTheCarriedEntries() {
            final SessionLogState summarized = log().summarize(span(0, 4));

            final SessionLogState sealed = summarized.seal(line(0, 4, 4));

            assertThat(sealed.getEntries()).extracting(SessionLogEntry::getSeq).containsExactly(4L, 5L);
            assertThat(sealed.getManifest()).hasSize(1);
            assertThat(sealed.liveEntryCount()).as("sealed entries are still part of the log").isEqualTo(6);
            assertThat(sealed.getNextSeq()).isEqualTo(6);
        }

        @Test
        void onlyAHiddenRangeWithTheRightCountAndLegalCutsCanBeSealed() {
            final SessionLogState summarized = log().summarize(span(0, 4));

            assertThatThrownBy(() -> summarized.seal(line(0, 5, 5))).as("seq 4 is visible")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> summarized.seal(line(0, 4, 3))).as("wrong entry count")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> summarized.seal(line(0, 2, 2))).as("splits the tool pair")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SessionLogState.ofMessages(List.of(Message.user("q"))).seal(line(0, 1, 1)))
                    .as("version 1").isInstanceOf(IllegalStateException.class);
        }

        @Test
        void sealedRangesDoNotOverlapAndNoRewindPointLiesInside() {
            final SessionLogState sealed = log().summarize(span(0, 4)).seal(line(0, 4, 4));

            assertThatThrownBy(() -> sealed.seal(line(0, 4, 4))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> sealed.withRewindPoint(SessionRewindPoint.of(2, TextInput.of("q"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(sealed.isLegalCut(2)).as("nothing cuts inside a sealed range").isFalse();
            assertThat(sealed.isLegalCut(4)).isTrue();
        }

        @Test
        void aRewindDropsWholeLinesAtOrAfterTheCutAndRefusesToSplitOne() {
            final SessionLogState sealed = log().summarize(span(0, 4)).seal(line(0, 4, 4));

            assertThat(sealed.truncateFrom(4).getManifest()).hasSize(1);
            assertThat(sealed.truncateFrom(0).getManifest()).isEmpty();
            assertThatThrownBy(() -> sealed.truncateFrom(2)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void clearEmptiesTheManifest() {
            final SessionLogState cleared = log().summarize(span(0, 4)).seal(line(0, 4, 4)).clear();

            assertThat(cleared.getManifest()).isEmpty();
            assertThat(cleared.liveEntryCount()).isZero();
            assertThat(cleared.getFloorSeq()).isEqualTo(6);
        }

        @Test
        void aSealedRangeCountsAsConversation() {
            final SessionLogState onlySealed = SessionLogState
                    .ofMessages(List.of(Message.user("q"), Message.assistant("a")))
                    .withFormatAtLeast(SessionLogFormat.V2).summarize(span(0, 2)).seal(line(0, 2, 2));

            assertThat(onlySealed.getEntries()).isEmpty();
            assertThat(onlySealed.hasConversation()).isTrue();
        }
    }

    @Nested
    class SealableRuns {

        private final SessionLogSealer sealer = new SessionLogSealer(storage(new InMemorySessionLogSegmentStore()));

        @Test
        void nothingIsSealableWithoutAViewStateOrOnVersion1() {
            assertThat(sealer.sealableRuns(log())).isEmpty();
            assertThat(sealer.sealableRuns(SessionLogState.ofMessages(List.of(Message.user("q"))))).isEmpty();
        }

        @Test
        void aVisibleEntrySplitsRuns() {
            final SessionLogState state = log().drop(0, 1).drop(3, 4);

            assertThat(sealer.sealableRuns(state)).extracting(run -> run.get(0).getSeq()).containsExactly(0L, 3L);
        }

        @Test
        void aRunIsCutAtTheRewindPoint() {
            final SessionLogState state = log().summarize(span(0, 6))
                    .withRewindPoint(SessionRewindPoint.of(4, TextInput.of("q2")));

            final List<List<SessionLogEntry>> runs = sealer.sealableRuns(state);

            assertThat(runs).extracting(List::size).containsExactly(4, 2);
            assertThat(runs.get(1).get(0).getSeq()).isEqualTo(4);
        }

        @Test
        void aRunBelowTheMinimumIsLeftInTheRecord() {
            final SessionLogSealer strict = new SessionLogSealer(
                    SessionLogStorage.builder(new InMemorySessionLogSegmentStore()).minSealTokens(1_000_000).build());

            assertThat(strict.sealableRuns(log().summarize(span(0, 4)))).isEmpty();
        }
    }

    @Nested
    class SealingABuffer {

        private TranscriptBuffer v2Buffer() {
            final TranscriptBuffer buffer = TranscriptBuffer
                    .fromSnapshot(SessionSnapshot.fromLog(SESSION, "system", log()));
            buffer.markIngestPoint();
            buffer.addMessage(Message.user("q3"));
            buffer.addMessage(Message.assistant("a3"));
            buffer.summarizeView(span(0, 7));
            return buffer;
        }

        @Test
        void theSegmentIsWrittenAndTheManifestNamesIt() {
            final InMemorySessionLogSegmentStore store = new InMemorySessionLogSegmentStore();
            final TranscriptBuffer buffer = v2Buffer();
            final long versionBefore = buffer.getVersion();

            assertThat(new SessionLogSealer(storage(store)).seal(buffer)).isEqualTo(1);

            final SessionLogManifestEntry line = buffer.getManifest().get(0);
            assertThat(line.getRange()).isEqualTo(SeqRange.of(0, 7));
            assertThat(buffer.getMessages()).extracting(Message::getContent).containsExactly("a3");
            assertThat(buffer.getVersion()).as("a persisted change").isGreaterThan(versionBefore);
            final SessionLogSegment segment = store.get(SESSION, line.getSegmentId()).orElseThrow();
            assertThat(SessionLogSegmentCodec.contentHash(segment.getPayload())).isEqualTo(line.getContentHash());
            assertThat(SessionLogSegmentCodec.decode(segment.getPayload())).hasSize(7);
            assertThat(buffer.toSnapshot().getLogState().getEntries()).hasSize(1);
        }

        @Test
        void sealedEntriesStayInMemoryForTheExecutionEndIngest() {
            final TranscriptBuffer buffer = v2Buffer();

            new SessionLogSealer(storage(new InMemorySessionLogSegmentStore())).seal(buffer);

            assertThat(buffer.messagesSinceIngestMark()).extracting(Message::getContent).containsExactly("q3", "a3");
        }

        @Test
        void aFailedWriteLeavesTheRangeInTheRecord() {
            final SessionLogSegmentStore backing = new InMemorySessionLogSegmentStore();
            final SessionLogSegmentStore broken = new SessionLogSegmentStore() {
                @Override
                public void put(SessionLogSegment segment) {
                    throw new SessionLogSegmentStoreException("down");
                }

                @Override
                public Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id) {
                    return backing.get(sessionId, id);
                }

                @Override
                public List<SegmentInfo> list(SessionId sessionId) {
                    return backing.list(sessionId);
                }

                @Override

                public SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit) {

                    return backing.scanSessions(createdBefore, cursor, limit);

                }

                @Override
                public void delete(SessionId sessionId, SegmentId id) {
                }

                @Override
                public void deleteAll(SessionId sessionId) {
                }
            };
            final TranscriptBuffer buffer = v2Buffer();

            assertThat(new SessionLogSealer(storage(broken)).seal(buffer)).isZero();

            assertThat(buffer.getManifest()).isEmpty();
            assertThat(buffer.getMessages()).hasSize(8);
        }

        @Test
        void clearQueuesTheSegmentsForDeletionAndRewindDropsLaterLines() {
            final TranscriptBuffer buffer = v2Buffer();
            new SessionLogSealer(storage(new InMemorySessionLogSegmentStore())).seal(buffer);
            final SegmentId sealed = buffer.getManifest().get(0).getSegmentId();

            buffer.clear();

            assertThat(buffer.getManifest()).isEmpty();
            assertThat(buffer.pendingSegmentDeletions()).containsExactly(sealed);
            buffer.forgetSegmentDeletions(List.of(sealed));
            assertThat(buffer.pendingSegmentDeletions()).isEmpty();
        }

        @Test
        void aBufferRebuiltFromTheSnapshotCarriesTheManifest() {
            final TranscriptBuffer buffer = v2Buffer();
            new SessionLogSealer(storage(new InMemorySessionLogSegmentStore())).seal(buffer);

            final TranscriptBuffer next = TranscriptBuffer.fromSnapshot(buffer.toSnapshot());

            assertThat(next.getManifest()).isEqualTo(buffer.getManifest());
            assertThat(next.liveEntryCount()).isEqualTo(8);
            assertThat(next.hasConversation()).isTrue();
        }
    }
}
