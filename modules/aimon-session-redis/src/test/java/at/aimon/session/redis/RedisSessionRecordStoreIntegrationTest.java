package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.TokenUsage;
import at.aimon.session.testkit.AbstractSessionRecordStoreContractTest;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Integration tests for {@link RedisSessionRecordStore} against a real Redis container.
 *
 * <p>
 * The SPI contract is the shared {@link AbstractSessionRecordStoreContractTest}. What this class supplies is the
 * backend: a second store over its own connection for the node-handoff case — the reason this store exists at all,
 * since before it the lease, the inbox, the signal bus and the idempotency ledger were all distributed while the
 * transcript was not — and raw {@code HSET}s that corrupt the {@code transcript} and {@code owner} fields. The cases of
 * its own are what the SPI cannot see: which hash fields the scripts write, the {@code ""} sentinel they reserve, and
 * the keyspace this store shares with the other Redis backends.
 */
@DisplayName("RedisSessionRecordStore integration")
@Tag("docker")
class RedisSessionRecordStoreIntegrationTest extends AbstractSessionRecordStoreContractTest {

    private StatefulRedisConnection<String, String> connection;
    private RedisSessionRecordStore store;
    private StatefulRedisConnection<String, String> peerConnection;
    private RedisSessionRecordStore peer;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        connection = RedisTestSupport.connect();
        store = new RedisSessionRecordStore(connection);
    }

    @AfterEach
    void tearDown() {
        if (peerConnection != null) {
            peerConnection.close();
        }
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    protected SessionRecordStore store() {
        return store;
    }

    @Override
    protected SessionRecordStore peerStore() {
        // Its own connection, sharing nothing with store() but Redis, as after an eviction or a restart.
        if (peer == null) {
            peerConnection = RedisTestSupport.connect();
            peer = new RedisSessionRecordStore(peerConnection);
        }
        return peer;
    }

    @Override
    protected void corruptTranscript(SessionId id) {
        rawHset(id, "transcript", "not-a-transcript");
    }

    @Override
    protected void corruptOwner(SessionId id) {
        rawHset(id, "owner", "{\"displayName\":\"x\"}");
    }

    private void rawHset(SessionId id, String field, String value) {
        // HSET on a missing key would create a one-field hash; insist on a provisioned record, as the Postgres and
        // Mongo hooks do with their row counts.
        assertThat(connection.sync().exists(key(id))).isEqualTo(1L);
        connection.sync().hset(key(id), field, value);
    }

    @Test
    @DisplayName("provision without a binding still writes a field — a field-less hash does not exist in Redis")
    void provisionWithoutBindingWritesTheSentinelField() {
        final SessionId id = SessionId.of("rec-unbound");

        store.provision(id);

        // The sentinel field earns its keep exactly here. Redis has no empty hash, so provisioning without a binding
        // would otherwise write nothing at all and leave the session reading as absent -- it would be re-provisioned,
        // and the binding a later claim writes would look like a first binding rather than a conflict.
        assertThat(connection.sync().hget(key(id), "sessionId")).isEqualTo("rec-unbound");
        assertThat(connection.sync().hexists(key(id), "agentRef")).isFalse();
    }

    @Test
    @DisplayName("an owner-less provision writes no owner field")
    void ownerlessProvisionWritesNoOwnerField() {
        final SessionId id = SessionId.of("rec-owner-late");

        store.provision(id, "agent-A");

        assertThat(connection.sync().hexists(key(id), "owner")).isFalse();
    }

    @Test
    @DisplayName("each optional provision argument is skipped on its own: no agent with an owner, an agent without one")
    void eachOptionalArgumentIsIndependent() {
        // The script reads both optional arguments positionally with "" as the absent sentinel; this pins that
        // a missing first one does not shift the second into its place, and a missing second one writes nothing.
        // The suite's ownerWithoutBinding and bindingWithoutOwner check what the SPI reads back; this checks the hash.
        final SessionId ownerOnly = SessionId.of("rec-owner-only");
        store.provision(ownerOnly, null, Principal.user("u-1", "alice"));
        assertThat(connection.sync().hexists(key(ownerOnly), "agentRef")).isFalse();

        final SessionId agentOnly = SessionId.of("rec-agent-only");
        store.provision(agentOnly, "agent-A", null);
        assertThat(connection.sync().hexists(key(agentOnly), "owner")).isFalse();
    }

    @Test
    @DisplayName("an empty agentRef is refused, since the script reserves \"\" to mean no agent")
    void emptyAgentRefIsRefused() {
        final SessionId id = SessionId.of("rec-empty-agent");

        assertThatThrownBy(() -> store.provision(id, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.provision(id, "", Principal.user("u-1", "alice")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.exists(id)).isFalse();
    }

    @Test
    @DisplayName("a null budget override is an HDEL, not a write")
    void nullOverrideIsAnHdel() {
        final SessionId id = SessionId.of("rec-totals");
        store.provision(id, "agent-A");
        store.setTotalsAndBudgetOverride(id, SessionTotals.of(1, 3, TokenUsage.of(5, 6, 11)),
                ExecutionBudget.builder().maxTokens(1_000).build());

        // Redis needs an HDEL rather than a write to express "cleared", which is why the script branches.
        store.setTotalsAndBudgetOverride(id, SessionTotals.of(2, 7, TokenUsage.of(9, 9, 18)), null);

        assertThat(connection.sync().hexists(key(id), "budgetOverride")).isFalse();
    }

    @Test
    @DisplayName("writes against a session that was never provisioned leave no key behind")
    void sideFieldWritesLeaveNoKey() {
        final SessionId id = SessionId.of("rec-absent");

        store.setTotalsAndBudgetOverride(id, SessionTotals.of(1, 1, TokenUsage.of(1, 1, 2)), null);
        store.resetCompactionFailureCount(id);
        store.incrementCompactionFailureCount(id);

        // The EXISTS guard in front of each script is what makes this pass, and it is not defensive coding: HSET and
        // HINCRBY both create the hash they write to, so without the guard each of these three calls would conjure a
        // record holding one field and no transcript, and the next claim would find the session already provisioned.
        assertThat(connection.sync().exists(key(id))).isZero();
    }

    @Test
    @DisplayName("only this store's keys are enumerated and cleared — the other backends share the keyspace")
    void enumerationIgnoresForeignKeys() {
        // The lease, inbox, idempotency and signal keys live in the same Redis. clear() is a SCAN over this store's
        // own prefix precisely so that wiping session records does not take the fleet's locks with it.
        store.provision(SessionId.of("rec-a"), "agent-A");
        connection.sync().set("aimon:conv:lock:rec-a", "node-A");
        connection.sync().set("aimon:inbox:rec-a", "queued");

        assertThat(store.listSessionIds()).containsExactly(SessionId.of("rec-a"));

        store.clear();

        assertThat(store.listSessionIds()).isEmpty();
        assertThat(connection.sync().exists("aimon:conv:lock:rec-a", "aimon:inbox:rec-a")).isEqualTo(2L);
    }

    @Test
    @DisplayName("an owner field holding the \"\" sentinel reads as no owner, as it does through load")
    void loadOwnerTreatsTheEmptySentinelAsNoOwner() {
        final SessionId id = SessionId.of("rec-load-owner-blank");
        store.provision(id, "agent-A");
        // PROVISION_SCRIPT never writes this; hand-written or older data might.
        connection.sync().hset(key(id), "owner", "");

        assertThat(store.load(id).orElseThrow().getOwner()).isEmpty();
        assertThat(store.loadOwner(id)).isEmpty();
    }

    private static String key(SessionId id) {
        return "aimon:session:record:" + id.value();
    }
}
