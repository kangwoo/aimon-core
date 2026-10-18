package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionRecordStoreException;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.session.testkit.AbstractSessionRecordStoreContractTest;

/**
 * Integration tests for {@link MongoSessionRecordStore} against a real MongoDB container.
 *
 * <p>
 * The SPI contract is the shared {@link AbstractSessionRecordStoreContractTest}. What this class supplies is the
 * backend: a second store over its own client for the node-handoff case — the reason this store exists at all, since
 * before it the lease, the inbox, the signal bus and the idempotency ledger were all distributed while the transcript
 * was not — and raw {@code $set}s that corrupt the {@code transcript} and {@code owner} fields. The one case of its
 * own is an owner of the wrong BSON type, which only a document store can hold.
 */
@DisplayName("MongoSessionRecordStore integration")
@Tag("docker")
class MongoSessionRecordStoreIntegrationTest extends AbstractSessionRecordStoreContractTest {

    private MongoSessionRecordStore store;
    private MongoClient peerClient;
    private MongoSessionRecordStore peer;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        store = new MongoSessionRecordStore(MongoTestSupport.sharedDatabase());
    }

    @AfterEach
    void tearDown() {
        if (peerClient != null) {
            peerClient.close();
        }
    }

    @Override
    protected SessionRecordStore store() {
        return store;
    }

    @Override
    protected SessionRecordStore peerStore() {
        // Its own client, sharing nothing with store() but the database, as after an eviction or a restart.
        if (peer == null) {
            peerClient = MongoTestSupport.newClient();
            peer = new MongoSessionRecordStore(peerClient.getDatabase(MongoTestSupport.DATABASE_NAME));
        }
        return peer;
    }

    @Override
    protected void corruptTranscript(SessionId id) {
        rawSet(id, "transcript", "not-a-transcript");
    }

    @Override
    protected void corruptOwner(SessionId id) {
        rawSet(id, "owner", new Document("displayName", "x"));
    }

    @Test
    @DisplayName("an owner that is not a subdocument at all fails loadOwner the way a malformed one does")
    void loadOwnerRefusesAnOwnerThatIsNotADocument() {
        // The cast fails before the codec runs, and must fail the same way.
        final SessionId id = SessionId.of("rec-load-owner-string");
        store.provision(id, "agent-A", Principal.user("u-1", "Alice"));
        rawSet(id, "owner", "u-1");

        assertThatThrownBy(() -> store.loadOwner(id)).isInstanceOf(SessionRecordStoreException.class)
                .hasMessageContaining("Failed to decode stored session owner for");
    }

    private static void rawSet(SessionId id, String field, Object value) {
        assertThat(MongoTestSupport.sharedDatabase().getCollection("session_records")
                .updateOne(new Document("_id", id.value()), new Document("$set", new Document(field, value)))
                .getMatchedCount()).isEqualTo(1);
    }
}
