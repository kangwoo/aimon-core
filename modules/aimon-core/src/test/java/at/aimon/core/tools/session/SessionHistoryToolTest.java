package at.aimon.core.tools.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.context.RollingContextEngine;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogReader;
import at.aimon.core.agent.session.transcript.SessionLogSealer;
import at.aimon.core.agent.session.transcript.SessionLogSource;
import at.aimon.core.agent.session.transcript.SessionLogStorage;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * {@link SessionHistoryTool}: reads back conversation entries of the running session by seq or by search, sealed
 * ranges included, and nothing else.
 */
class SessionHistoryToolTest {

    private static final SessionId SESSION = SessionId.of("history-session");

    private final SessionHistoryTool tool = new SessionHistoryTool();

    /** seq 0..6: q1, call t1, result t1 (long), a1, reminder (synthetic), q2 "deploy the canary", a2. */
    private TranscriptBuffer buffer() {
        final TranscriptBuffer buffer = new TranscriptBuffer(SESSION);
        buffer.requireFormat(SessionLogFormat.V2);
        buffer.addUserMessage("q1 read the config");
        buffer.addMessage(Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of("file_path", "/app.yml")))));
        buffer.addMessage(
                Message.toolUseResults(List.of(ToolUseResult.success("t1", "port: 8080\n" + "x".repeat(50)))));
        buffer.addAssistantMessage("a1 the port is 8080");
        buffer.addMessage(Message.user("<system-reminder>deploy is frozen</system-reminder>"), LogOrigin.SYNTHETIC);
        buffer.addUserMessage("q2 deploy the canary");
        buffer.addAssistantMessage("a2 canary deployed");
        return buffer;
    }

    private ToolContext context(SessionLogSource source) {
        return ToolContext.builder().put(SessionHistoryTool.LOG_SOURCE_KEY, source).build();
    }

    @Nested
    class BySeq {

        @Test
        void returnsTheOriginalOfAnElidedResultWithItsNeighbours() {
            final TranscriptBuffer buffer = buffer();
            buffer.elideInView(2, RollingContextEngine.placeholder(2));

            final ToolResult result = tool.execute(ToolInput.of("seq", 2), context(SessionLogSource.of(buffer, null)));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains(">> [seq 2] tool: port: 8080").contains("[seq 0] user")
                    .contains("[seq 1] assistant").contains("[seq 3] assistant")
                    .as("synthetic entries are not conversation").doesNotContain("deploy is frozen");
        }

        @Test
        void aSyntheticEntryIsNotReadBack() {
            final ToolResult result = tool.execute(ToolInput.of("seq", 4),
                    context(SessionLogSource.of(buffer(), null)));

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("not a conversation message");
        }

        @Test
        void aSeqOutsideTheLogIsAnError() {
            final TranscriptBuffer buffer = buffer();
            buffer.clear();

            final ToolResult result = tool.execute(ToolInput.of("seq", 2), context(SessionLogSource.of(buffer, null)));

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("not in this session's history");
        }

        @Test
        void longMessagesAreCut() {
            final SessionHistoryTool narrow = new SessionHistoryTool(1_000, 10, new HeuristicTokenEstimator());

            final ToolResult result = narrow.execute(ToolInput.of("seq", 2),
                    context(SessionLogSource.of(buffer(), null)));

            assertThat(result.getContent())
                    .contains("[seq 2] tool: port: 8080 … [51 more characters; read on with seq=2, offset=10]");
        }

        @Test
        void aLongMessageCanBeReadInFullByOffset() {
            final SessionHistoryTool narrow = new SessionHistoryTool(1_000, 10, new HeuristicTokenEstimator());
            final ToolContext context = context(SessionLogSource.of(buffer(), null));
            final Pattern next = Pattern.compile("read on with seq=2, offset=(\\d+)]$");
            final Pattern part = Pattern.compile(">> \\[seq 2\\] tool: (?:\\[characters \\d+-\\d+ of 61\\] )?(.*?)"
                    + "(?: … \\[\\d+ more characters; read on with seq=2, offset=\\d+\\])?$", Pattern.DOTALL);

            final StringBuilder read = new StringBuilder();
            Integer offset = null;
            int calls = 0;
            do {
                final ToolResult result = narrow.execute(
                        offset == null ? ToolInput.of("seq", 2) : ToolInput.of("seq", 2, "offset", offset), context);
                assertThat(result.isSuccess()).isTrue();
                final String content = result.getContent();
                final String line = offset == null
                        ? content.lines().filter(l -> l.startsWith(">> ")).findFirst().orElseThrow()
                        : content;
                final Matcher matcher = part.matcher(line);
                assertThat(matcher.matches()).as(line).isTrue();
                read.append(matcher.group(1));
                final Matcher more = next.matcher(line);
                offset = more.find() ? Integer.valueOf(more.group(1)) : null;
                calls++;
            } while (offset != null && calls < 20);

            assertThat(read.toString()).isEqualTo("port: 8080\n" + "x".repeat(50));
            assertThat(calls).isEqualTo(7);
        }

        @Test
        void aLaterPartStandsAloneAndSaysWhereItStarts() {
            final SessionHistoryTool narrow = new SessionHistoryTool(1_000, 10, new HeuristicTokenEstimator());

            final ToolResult result = narrow.execute(ToolInput.of("seq", 2, "offset", 55),
                    context(SessionLogSource.of(buffer(), null)));

            assertThat(result.getContent()).isEqualTo(">> [seq 2] tool: [characters 55-61 of 61] xxxxxx");
        }

        @Test
        void aCutNeverSplitsASurrogatePair() {
            final TranscriptBuffer buffer = new TranscriptBuffer(SESSION);
            buffer.requireFormat(SessionLogFormat.V2);
            buffer.addUserMessage("abc\uD83D\uDE00def"); // an emoji at chars 3-4
            final SessionHistoryTool narrow = new SessionHistoryTool(1_000, 4, new HeuristicTokenEstimator());
            final ToolContext context = context(SessionLogSource.of(buffer, null));

            final String first = narrow.execute(ToolInput.of("seq", 0), context).getContent();
            final String second = narrow.execute(ToolInput.of("seq", 0, "offset", 3), context).getContent();

            assertThat(first).contains("user: abc … [5 more characters; read on with seq=0, offset=3]");
            assertThat(second).contains("[characters 3-7 of 8] \uD83D\uDE00de");
        }

        @Test
        void anOffsetPastTheEndOrNegativeIsAnError() {
            final ToolContext context = context(SessionLogSource.of(buffer(), null));

            assertThat(tool.execute(ToolInput.of("seq", 2, "offset", 61), context).getContent())
                    .contains("past the end");
            assertThat(tool.execute(ToolInput.of("seq", 2, "offset", -1), context).isError()).isTrue();
            assertThat(tool.execute(ToolInput.of("query", "x", "offset", 3), context).getContent())
                    .contains("applies to seq only");
        }
    }

    @Nested
    class ByQuery {

        @Test
        void findsMatchesCaseInsensitivelyNewestFirst() {
            final ToolResult result = tool.execute(ToolInput.of("query", "CANARY"),
                    context(SessionLogSource.of(buffer(), null)));

            assertThat(result.isSuccess()).isTrue();
            final String content = result.getContent();
            assertThat(content).contains("=== match at seq 6 ===").contains("=== match at seq 5 ===");
            assertThat(content.indexOf("seq 6 ===")).as("newest first").isLessThan(content.indexOf("seq 5 ==="));
        }

        @Test
        void theLimitBoundsTheMatches() {
            final ToolResult result = tool.execute(ToolInput.of("query", "canary", "limit", 1),
                    context(SessionLogSource.of(buffer(), null)));

            assertThat(result.getContent()).contains("match at seq 6").doesNotContain("match at seq 5");
        }

        @Test
        void searchesToolInputsAndResultsButNotSyntheticEntries() {
            final SessionLogSource source = SessionLogSource.of(buffer(), null);

            assertThat(tool.execute(ToolInput.of("query", "/app.yml"), context(source)).getContent())
                    .contains("match at seq 1");
            assertThat(tool.execute(ToolInput.of("query", "frozen"), context(source)).getContent())
                    .contains("No message in this session's history matches 'frozen'");
        }

        @Test
        void aScanLimitSaysOlderHistoryWasNotSearched() {
            final SessionHistoryTool shortScan = new SessionHistoryTool(20, 2_000, new HeuristicTokenEstimator());

            final ToolResult result = shortScan.execute(ToolInput.of("query", "q1"),
                    context(SessionLogSource.of(buffer(), null)));

            assertThat(result.getContent()).contains("No message").contains("Older history was not searched");
        }

        @Test
        void readsSealedRangesThroughTheReaderAndReportsWhatCannotBeRead() {
            final TranscriptBuffer buffer = buffer();
            buffer.summarizeView(
                    SummarySpan.builder().fromSeq(0).toSeq(4).summaryText("s").boundaryId("b").trigger("AUTO").build());
            final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();
            new SessionLogSealer(SessionLogStorage.builder(segments).minSealTokens(0).build()).seal(buffer);
            assertThat(buffer.getManifest()).hasSize(1);
            final SessionLogReader reader = new SessionLogReader(new InMemorySessionRecordStore(), segments, 0,
                    new HeuristicTokenEstimator());
            final SessionLogSource source = SessionLogSource.of(buffer, reader);

            assertThat(tool.execute(ToolInput.of("query", "8080"), context(source)).getContent())
                    .as("a sealed result is still found").contains("match at seq 2");

            segments.deleteAll(SESSION);

            assertThat(tool.execute(ToolInput.of("seq", 2), context(source)).getContent()).contains("unavailable");
            assertThat(tool.execute(ToolInput.of("query", "canary"), context(source)).getContent())
                    .contains("match at seq 6").contains("could not be read: [history unavailable: seq 0..3]");
        }
    }

    @Nested
    class LongSealedHistory {

        /** seq 0..199 alternating user / assistant, "needle" only in the last user message; [0, 150) sealed. */
        private TranscriptBuffer longSealedBuffer(InMemorySessionLogSegmentStore segments) {
            final TranscriptBuffer buffer = new TranscriptBuffer(SESSION);
            buffer.requireFormat(SessionLogFormat.V2);
            for (int i = 0; i < 200; i++) {
                if (i % 2 == 0) {
                    buffer.addUserMessage(i == 198 ? "needle " + i : "question " + i);
                } else {
                    buffer.addAssistantMessage("answer " + i);
                }
            }
            buffer.summarizeView(SummarySpan.builder().fromSeq(0).toSeq(150).summaryText("s").boundaryId("b")
                    .trigger("AUTO").build());
            new SessionLogSealer(SessionLogStorage.builder(segments).minSealTokens(0).build()).seal(buffer);
            assertThat(buffer.getManifest()).hasSize(1);
            return buffer;
        }

        @Test
        void anUnreadableRangeOverManyWindowsIsReportedOnce() {
            final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();
            final TranscriptBuffer buffer = longSealedBuffer(segments);
            segments.deleteAll(SESSION);
            final SessionLogReader reader = new SessionLogReader(new InMemorySessionRecordStore(), segments, 0,
                    new HeuristicTokenEstimator());

            for (SessionLogSource source : List.of(SessionLogSource.of(buffer, reader),
                    SessionLogSource.of(buffer, null))) {
                final String content = tool.execute(ToolInput.of("query", "needle"), context(source)).getContent();

                assertThat(content).contains("match at seq 198");
                assertThat(content.split("could not be read", -1)).as(content).hasSize(2);
                assertThat(content).contains("could not be read: [history unavailable: seq 0..149]");
            }
        }

        @Test
        void oneSearchLoadsTheSealedSegmentOnce() {
            final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();
            final TranscriptBuffer buffer = longSealedBuffer(segments);
            final int[] gets = new int[1];
            final SessionLogSegmentStore counting = new SessionLogSegmentStore() {
                @Override
                public void put(SessionLogSegment segment) {
                    segments.put(segment);
                }

                @Override
                public Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id) {
                    gets[0]++;
                    return segments.get(sessionId, id);
                }

                @Override
                public List<SegmentInfo> list(SessionId sessionId) {
                    return segments.list(sessionId);
                }

                @Override
                public void delete(SessionId sessionId, SegmentId id) {
                    segments.delete(sessionId, id);
                }

                @Override
                public void deleteAll(SessionId sessionId) {
                    segments.deleteAll(sessionId);
                }
            };
            final SessionLogReader reader = new SessionLogReader(new InMemorySessionRecordStore(), counting, 0,
                    new HeuristicTokenEstimator());

            final String content = tool
                    .execute(ToolInput.of("query", "question 2"), context(SessionLogSource.of(buffer, reader)))
                    .getContent();

            assertThat(content).contains("match at seq 28").doesNotContain("could not be read");
            assertThat(gets[0]).as("150 sealed seqs span four 64-seq windows; the segment is loaded once").isEqualTo(1);
        }
    }

    @Nested
    class Input {

        @Test
        void outsideASessionThereIsNothingToRead() {
            final ToolResult result = tool.execute(ToolInput.of("seq", 1), ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("only available inside a session");
        }

        @Test
        void exactlyOneOfSeqAndQuery() {
            final ToolContext context = context(SessionLogSource.of(buffer(), null));

            assertThat(tool.execute(ToolInput.of(), context).isError()).isTrue();
            assertThat(tool.execute(ToolInput.of("seq", 1, "query", "x"), context).getContent())
                    .contains("exactly one");
        }

        @Test
        void aBlankQueryOrAZeroLimitIsAnError() {
            final ToolContext context = context(SessionLogSource.of(buffer(), null));

            assertThat(tool.execute(ToolInput.of("query", "  "), context).isError()).isTrue();
            assertThat(tool.execute(ToolInput.of("query", "q", "limit", 0), context).isError()).isTrue();
        }

        @Test
        void theSchemaIsClosed() {
            assertThat(tool.getDefinition().getInputSchema()).containsEntry("additionalProperties", false);
            assertThat(tool.getDefinition().getInputSchema().get("properties")).asInstanceOf(MAP).containsKeys("seq",
                    "query", "limit", "offset");
            assertThat(tool.getDefinition().getName()).isEqualTo(SessionHistoryTool.TOOL_NAME);
        }
    }
}
