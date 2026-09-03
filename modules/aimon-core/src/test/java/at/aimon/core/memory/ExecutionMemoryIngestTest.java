package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;

/**
 * The ingest delta, and what the sink does with it.
 *
 * <p>
 * The delta is a message <em>count</em> because {@code Message} carries no stable id, and that makes it exactly as
 * fragile as the rewind point the tree already had: {@code replaceWith} rewrites the history under it. The tests below
 * are mostly about that one fact — the mark is dropped rather than re-based, and the execution whose history was
 * rewritten sends nothing rather than sending the compaction summary or the whole transcript again.
 */
@DisplayName("Execution memory ingest")
class ExecutionMemoryIngestTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final SessionId SESSION = SessionId.of("s-1");

    @Nested
    @DisplayName("TranscriptBuffer ingest mark")
    class Mark {

        private TranscriptBuffer buffer() {
            return new TranscriptBuffer(SESSION, "system");
        }

        @Test
        @DisplayName("with no mark there is no delta, so a buffer nobody marks feeds nothing")
        void noMarkNoDelta() {
            final TranscriptBuffer buffer = buffer();
            buffer.addUserMessage("hello");

            assertThat(buffer.messagesSinceIngestMark()).isEmpty();
        }

        @Test
        @DisplayName("the delta is what was added after the mark, and nothing that was there before")
        void deltaIsWhatWasAdded() {
            final TranscriptBuffer buffer = buffer();
            buffer.addUserMessage("earlier turn");
            buffer.addAssistantMessage("earlier answer");

            buffer.markIngestPoint();
            buffer.addUserMessage("this turn");
            buffer.addAssistantMessage("this answer");

            assertThat(buffer.messagesSinceIngestMark()).extracting(Message::getContent).containsExactly("this turn",
                    "this answer");
        }

        @Test
        @DisplayName("marking again moves the mark, so consecutive executions do not re-send each other's messages")
        void consecutiveExecutionsDoNotOverlap() {
            final TranscriptBuffer buffer = buffer();
            buffer.markIngestPoint();
            buffer.addUserMessage("first");
            assertThat(buffer.messagesSinceIngestMark()).hasSize(1);

            buffer.markIngestPoint();
            buffer.addUserMessage("second");

            assertThat(buffer.messagesSinceIngestMark()).extracting(Message::getContent).containsExactly("second");
        }

        @Test
        @DisplayName("an execution that added nothing has an empty delta rather than a stale one")
        void emptyExecutionHasEmptyDelta() {
            final TranscriptBuffer buffer = buffer();
            buffer.addUserMessage("earlier");
            buffer.markIngestPoint();

            assertThat(buffer.messagesSinceIngestMark()).isEmpty();
        }

        @Test
        @DisplayName("replaceWith drops the mark — compaction leaves nothing the count could still point at")
        void compactionDropsTheMark() {
            final TranscriptBuffer buffer = buffer();
            buffer.addUserMessage("one");
            buffer.addUserMessage("two");
            buffer.markIngestPoint();
            buffer.addUserMessage("three");

            buffer.replaceWith(List.of(Message.user("[summary of the conversation so far]")));

            // Not "the summary", and not "everything": both would put messages the backend has already paid to
            // extract from back through the same extraction.
            assertThat(buffer.messagesSinceIngestMark()).isEmpty();
        }

        @Test
        @DisplayName("the next execution after a compaction marks afresh and the stream resumes")
        void streamResumesAfterCompaction() {
            final TranscriptBuffer buffer = buffer();
            buffer.markIngestPoint();
            buffer.addUserMessage("one");
            buffer.replaceWith(List.of(Message.user("[summary]")));

            buffer.markIngestPoint();
            buffer.addUserMessage("after the compaction");

            assertThat(buffer.messagesSinceIngestMark()).extracting(Message::getContent)
                    .containsExactly("after the compaction");
        }

        @Test
        @DisplayName("clear drops the mark too")
        void clearDropsTheMark() {
            final TranscriptBuffer buffer = buffer();
            buffer.markIngestPoint();
            buffer.addUserMessage("one");

            buffer.clear();
            buffer.addUserMessage("after the clear");

            assertThat(buffer.messagesSinceIngestMark()).isEmpty();
        }
    }

    @Nested
    @DisplayName("IngestingExecutionMemorySink")
    class Sink {

        private final List<MemoryIngestRequest> ingested = new ArrayList<>();

        private ExecutionMemorySink sink(MemoryPeerResolver resolver) {
            final MemoryIngestor ingestor = request -> {
                ingested.add(request);
                return MemoryIngestReceipt.builder().accepted(request.getMessages().size()).build();
            };
            return new IngestingExecutionMemorySink(ingestor, WS, resolver);
        }

        private ExecutionMemoryUpdate update(SessionId sessionId, Principal principal, String... contents) {
            final List<Message> messages = new ArrayList<>();
            for (String content : contents) {
                messages.add(Message.user(content));
            }
            return ExecutionMemoryUpdate.builder().sessionId(sessionId).principal(principal).messages(messages).build();
        }

        @Test
        @DisplayName("a fixed peer's execution is ingested under that peer, in that session")
        void fixedPeerIngests() {
            sink(MemoryPeerResolver.fixed(Principal.user("alice", "Alice")))
                    .afterExecution(update(SESSION, null, "hello", "hi"));

            assertThat(ingested).hasSize(1);
            assertThat(ingested.get(0).getSessionId()).isEqualTo("s-1");
            assertThat(ingested.get(0).getObserver().getPrincipal().getId()).isEqualTo("alice");
            assertThat(ingested.get(0).getMessages()).hasSize(2);
        }

        @Test
        @DisplayName("per-caller with no principal ingests nothing — the seam carries no identity to attribute to")
        void perCallerWithoutPrincipalIngestsNothing() {
            sink(MemoryPeerResolver.caller()).afterExecution(update(SESSION, null, "hello"));

            assertThat(ingested).isEmpty();
        }

        @Test
        @DisplayName("a session-less execution ingests nothing — a fork has no session to file messages under")
        void sessionLessExecutionIngestsNothing() {
            sink(MemoryPeerResolver.fixed(Principal.user("alice", "Alice")))
                    .afterExecution(update(null, null, "hello"));

            assertThat(ingested).isEmpty();
        }

        @Test
        @DisplayName("an empty delta ingests nothing, so a rewritten history costs a call rather than a wrong one")
        void emptyDeltaIngestsNothing() {
            sink(MemoryPeerResolver.fixed(Principal.user("alice", "Alice"))).afterExecution(update(SESSION, null));

            assertThat(ingested).isEmpty();
        }

        @Test
        @DisplayName("a failing backend does not propagate — the execution's answer is not worth losing to it")
        void ingestFailureIsSwallowed() {
            final ExecutionMemorySink failing = new IngestingExecutionMemorySink(request -> {
                throw new IllegalStateException("memory backend unreachable");
            }, WS, MemoryPeerResolver.fixed(Principal.user("alice", "Alice")));

            failing.afterExecution(update(SESSION, null, "hello"));
        }
    }
}
