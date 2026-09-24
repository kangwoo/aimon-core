package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.testkit.AbstractSessionLogSegmentStoreContractTest;

@DisplayName("MongoSessionLogSegmentStore integration")
@Tag("docker")
class MongoSessionLogSegmentStoreIntegrationTest extends AbstractSessionLogSegmentStoreContractTest {

    private SessionLogSegmentStore store;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        store = new MongoSessionLogSegmentStore(MongoTestSupport.sharedDatabase());
    }

    @Override
    protected SessionLogSegmentStore store() {
        return store;
    }

    @Test
    @DisplayName("_id is the session-scoped pair, sessionId first")
    void idIsSessionScoped() {
        // The stored shape, not just the behaviour: a later "simplification" back to the segment id alone would still
        // pass most of the contract until two sessions shared an id.
        final SegmentId id = SegmentId.generate();
        store.put(SessionLogSegment.builder().sessionId(SessionId.of("seg-shape")).id(id).fromSeq(1).toSeq(2)
                .entryCount(1).payload("p").createdAt(Instant.parse("2026-09-24T10:15:30.123Z")).build());

        final Document raw = MongoTestSupport.sharedDatabase().getCollection(DocumentKeys.COLL_SESSION_LOG_SEGMENTS)
                .find().first();
        assertThat(raw).isNotNull();
        final Document rawId = raw.get(DocumentKeys.F_ID, Document.class);
        assertThat(rawId.keySet()).containsExactly("sessionId", "segmentId");
        assertThat(rawId.getString("sessionId")).isEqualTo("seg-shape");
        assertThat(rawId.getString("segmentId")).isEqualTo(id.value());
        assertThat(raw.getString("sessionId")).isEqualTo("seg-shape");
    }
}
