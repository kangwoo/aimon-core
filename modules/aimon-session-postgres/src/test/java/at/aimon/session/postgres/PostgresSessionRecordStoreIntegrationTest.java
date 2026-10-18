package at.aimon.session.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import com.zaxxer.hikari.HikariDataSource;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.session.testkit.AbstractSessionRecordStoreContractTest;

/**
 * Integration tests for {@link PostgresSessionRecordStore} against a real Postgres container.
 *
 * <p>
 * The SPI contract is the shared {@link AbstractSessionRecordStoreContractTest}; Postgres adds no case of its own (the
 * migration tests are separate classes). What this class supplies is the backend: a second store over its own pool
 * for the node-handoff case — the reason this store exists at all, since before it the lease, the inbox, the signal
 * bus and the idempotency ledger were all distributed while the transcript was not — and raw {@code UPDATE}s that
 * corrupt the {@code transcript} and {@code owner} columns for the two {@code loadOwner} cases.
 */
@DisplayName("PostgresSessionRecordStore integration")
@Tag("docker")
class PostgresSessionRecordStoreIntegrationTest extends AbstractSessionRecordStoreContractTest {

    private PostgresSessionRecordStore store;
    private HikariDataSource peerPool;
    private PostgresSessionRecordStore peer;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        store = new PostgresSessionRecordStore(PostgresTestSupport.dataSource());
    }

    @AfterEach
    void tearDown() {
        if (peerPool != null) {
            peerPool.close();
        }
    }

    @Override
    protected SessionRecordStore store() {
        return store;
    }

    @Override
    protected SessionRecordStore peerStore() {
        // Its own pool, sharing nothing with store() but the database, as after an eviction or a restart.
        if (peer == null) {
            peerPool = PostgresTestSupport.isolatedDataSource(2);
            peer = new PostgresSessionRecordStore(peerPool);
        }
        return peer;
    }

    @Override
    protected void corruptTranscript(SessionId id) {
        rawUpdate("UPDATE session_record SET transcript = ? WHERE session_id = ?", "not-a-transcript", id);
    }

    @Override
    protected void corruptOwner(SessionId id) {
        rawUpdate("UPDATE session_record SET owner = ?::jsonb WHERE session_id = ?", "{\"displayName\":\"x\"}", id);
    }

    private static void rawUpdate(String sql, String value, SessionId id) {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setString(2, id.value());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
