package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogReader;
import at.aimon.core.agent.session.transcript.SessionLogSealer;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionLogStorage;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * The CLI's session-end ingest reads the whole log — sealed ranges included — through the reader, and keeps only the
 * conversation (context-engine §7, L5).
 */
class AgentSetupFactorySessionEndReadTest {

    @Test
    void sealedConversationReachesTheSessionEndIngestAndSyntheticEntriesDoNot() {
        final SessionId id = SessionId.of("cli-session-end");
        final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
        final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();
        final TranscriptBuffer buffer = TranscriptBuffer.fromSnapshot(SessionSnapshot.fromLog(id, "system",
                SessionLogState.ofMessages(List.of(Message.user("q1"), Message.assistant("a1")))
                        .withFormatAtLeast(SessionLogFormat.V2)));
        buffer.addMessage(Message.user("<system-reminder>ctx</system-reminder>"), LogOrigin.SYNTHETIC);
        buffer.addMessage(Message.user("q2"));
        buffer.summarizeView(
                SummarySpan.builder().fromSeq(0).toSeq(2).summaryText("s").boundaryId("b").trigger("AUTO").build());
        new SessionLogSealer(SessionLogStorage.builder(segments).minSealTokens(0).build()).seal(buffer);
        records.mergeFromSnapshot(buffer.toSnapshot());

        final List<List<Message>> pages = AgentSetupFactory
                .readConversationPages(new SessionLogReader(records, segments, 1, new HeuristicTokenEstimator()), id);

        assertThat(pages.stream().flatMap(List::stream).map(Message::getContent).toList()).containsExactly("q1", "a1",
                "q2");
        assertThat(pages).as("one page per entry at a one-token budget, the synthetic page dropped").hasSize(3);
    }
}
