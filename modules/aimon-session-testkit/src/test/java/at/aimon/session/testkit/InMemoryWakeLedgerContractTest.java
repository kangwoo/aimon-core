package at.aimon.session.testkit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import at.aimon.core.agent.session.wake.InMemoryWakeLedger;
import at.aimon.core.agent.session.wake.WakeLedger;

@DisplayName("InMemoryWakeLedger — the WakeLedger contract")
class InMemoryWakeLedgerContractTest extends AbstractWakeLedgerContractTest {

    private WakeLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryWakeLedger();
    }

    @Override
    protected WakeLedger ledger() {
        return ledger;
    }
}
