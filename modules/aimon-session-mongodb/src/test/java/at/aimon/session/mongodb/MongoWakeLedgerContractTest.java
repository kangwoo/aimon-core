package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.wake.WakeLedger;
import at.aimon.core.agent.session.wake.WakeState;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.testkit.AbstractWakeLedgerContractTest;

@DisplayName("MongoWakeLedger — the WakeLedger contract")
@Tag("docker")
class MongoWakeLedgerContractTest extends AbstractWakeLedgerContractTest {

    private WakeLedger ledger;
    private MongoClient otherClient;
    private WakeLedger otherNode;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        ledger = new MongoWakeLedger(MongoTestSupport.sharedDatabase());
        otherClient = MongoTestSupport.newClient();
        otherNode = new MongoWakeLedger(otherClient.getDatabase(MongoTestSupport.DATABASE_NAME));
    }

    @AfterEach
    void tearDown() {
        otherClient.close();
    }

    @Override
    protected WakeLedger ledger() {
        return ledger;
    }

    @Override
    protected WakeLedger otherNodeLedger() {
        return otherNode;
    }

    @Test
    @DisplayName("a document expires a day after its last write")
    void documentCarriesTheExpiry() {
        final Instant now = Instant.parse("2026-10-22T10:00:00Z");
        new MongoWakeLedger(MongoTestSupport.sharedDatabase(), MongoWakeLedger.DEFAULT_TTL,
                Clock.fixed(now, ZoneOffset.UTC))
                .update(SessionId.of("wake-ttl"), current -> WakeState.of(List.of(), 1, null, 0));

        final Document doc = MongoTestSupport.sharedDatabase().getCollection(DocumentKeys.COLL_WAKE_LEDGER)
                .find(Filters.eq("_id", "wake-ttl")).first();
        assertThat(doc.getDate("expiresAt")).isEqualTo(Date.from(now.plus(MongoWakeLedger.DEFAULT_TTL)));
    }

    @Test
    @DisplayName("a document without a version is updated, not stuck")
    void documentWithoutVersionIsUpdated() {
        MongoTestSupport.sharedDatabase().getCollection(DocumentKeys.COLL_WAKE_LEDGER)
                .insertOne(new Document("_id", "wake-unversioned").append("recentWakes", List.of()));

        ledger.update(SessionId.of("wake-unversioned"),
                current -> WakeState.of(List.of(), current.getConsecutive() + 1, null, current.getOutstanding()));

        assertThat(ledger.get(SessionId.of("wake-unversioned")).getConsecutive()).isEqualTo(1);
    }
}
