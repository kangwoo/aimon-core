package at.aimon.session.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import at.aimon.core.agent.session.wake.WakeLedger;
import at.aimon.session.testkit.AbstractWakeLedgerContractTest;

@DisplayName("PostgresWakeLedger — the WakeLedger contract")
@Tag("docker")
class PostgresWakeLedgerContractTest extends AbstractWakeLedgerContractTest {

    private WakeLedger ledger;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        ledger = new PostgresWakeLedger(PostgresTestSupport.dataSource());
    }

    @Override
    protected WakeLedger ledger() {
        return ledger;
    }

    /** Its own instance; every update takes its own connection from the pool, as another node's would. */
    @Override
    protected WakeLedger otherNodeLedger() {
        return new PostgresWakeLedger(PostgresTestSupport.dataSource());
    }
}
