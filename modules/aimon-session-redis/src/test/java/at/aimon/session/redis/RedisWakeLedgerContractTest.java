package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.wake.WakeLedger;
import at.aimon.core.agent.session.wake.WakeState;
import at.aimon.session.testkit.AbstractWakeLedgerContractTest;
import io.lettuce.core.api.StatefulRedisConnection;

@DisplayName("RedisWakeLedger — the WakeLedger contract")
@Tag("docker")
class RedisWakeLedgerContractTest extends AbstractWakeLedgerContractTest {

    private StatefulRedisConnection<String, String> connection;
    private WakeLedger ledger;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        connection = RedisTestSupport.connect();
        ledger = new RedisWakeLedger(connection);
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    @Override
    protected WakeLedger ledger() {
        return ledger;
    }

    @Test
    @DisplayName("an entry expires a day after its last write")
    void entryCarriesTheExpiry() {
        ledger.update(SessionId.of("wake-ttl"), current -> WakeState.of(List.of(), 1, null, false));

        assertThat(connection.sync().pttl(RedisWakeLedger.DEFAULT_KEY_PREFIX + ":wake-ttl"))
                .isBetween(RedisWakeLedger.DEFAULT_TTL.toMillis() - 60_000, RedisWakeLedger.DEFAULT_TTL.toMillis());
    }
}
