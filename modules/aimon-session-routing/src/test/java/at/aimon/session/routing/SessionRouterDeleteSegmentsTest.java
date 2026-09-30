package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * A session delete removes the session's sealed segments after its record (session-log §6.2), through the fenced
 * view — the delete holds the lease, so the fence lets it through.
 */
@DisplayName("SessionRouter session delete — sealed segments")
class SessionRouterDeleteSegmentsTest {

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    private static SessionLogSegment segment(SessionId id) {
        return SessionLogSegment.builder().sessionId(id).id(SegmentId.generate()).fromSeq(0).toSeq(1).entryCount(1)
                .payload("p").createdAt(Instant.now()).build();
    }

    @Test
    @DisplayName("delete removes every segment of the session and none of another's")
    void deleteRemovesTheSessionsSegments() {
        final InMemorySessionLogSegmentStore segments = new InMemorySessionLogSegmentStore();
        harness = TestManagerHarness.builder().segmentStore(segments).build();
        final SessionId deleted = SessionId.of("seg-delete-1");
        final SessionId bystander = SessionId.of("seg-delete-2");
        harness.repository().provision(deleted, "alpha");
        segments.put(segment(deleted));
        segments.put(segment(deleted));
        segments.put(segment(bystander));

        harness.manager().deleteSession(deleted);

        assertThat(harness.repository().exists(deleted)).isFalse();
        assertThat(segments.list(deleted)).isEmpty();
        assertThat(segments.list(bystander)).hasSize(1);
    }

    @Test
    @DisplayName("without a segment store the delete behaves as before")
    void deleteWithoutASegmentStore() {
        harness = TestManagerHarness.builder().build();
        final SessionId id = SessionId.of("seg-delete-3");
        harness.repository().provision(id, "alpha");

        harness.manager().deleteSession(id);

        assertThat(harness.repository().exists(id)).isFalse();
    }
}
