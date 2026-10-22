package at.aimon.session.mongodb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import at.aimon.core.agent.session.wake.WakeLedger;
import at.aimon.session.testkit.AbstractWakeLedgerContractTest;

@DisplayName("MongoWakeLedger — the WakeLedger contract")
@Tag("docker")
class MongoWakeLedgerContractTest extends AbstractWakeLedgerContractTest {

    private WakeLedger ledger;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        ledger = new MongoWakeLedger(MongoTestSupport.sharedDatabase());
    }

    @Override
    protected WakeLedger ledger() {
        return ledger;
    }
}
