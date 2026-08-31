package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Integration tests for {@link RedisSessionRecordStore} against a real Redis container.
 *
 * <p>
 * Two things are being checked here, and only one of them is the SPI contract. The other is the reason this store
 * exists at all: before it, the lease, the inbox, the signal bus and the idempotency ledger were all distributed while
 * the transcript was not, so a session could be handed from one node to another and arrive with its history gone. The
 * handoff test below is that scenario written down — two store instances over two separate connections.
 */
@DisplayName("RedisSessionRecordStore integration")
@Tag("docker")
class RedisSessionRecordStoreIntegrationTest {

    private StatefulRedisConnection<String, String> connection;
    private RedisSessionRecordStore store;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        connection = RedisTestSupport.connect();
        store = new RedisSessionRecordStore(connection);
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("provision creates the hash, and a second provision never re-binds it")
    void provisionBindsOnce() {
        final SessionId id = SessionId.of("rec-provision");

        final SessionRecordView first = store.provision(id, "agent-A");
        assertThat(first.getAgentRef()).contains("agent-A");
        assertThat(store.exists(id)).isTrue();

        // HSETNX on agentRef, and this is what it buys: the second caller is told who actually owns the session rather
        // than having already taken it. Both nodes then agree, and the loser can reject the request instead of serving
        // a session under an agent it was never bound to.
        final SessionRecordView second = store.provision(id, "agent-B");
        assertThat(second.getAgentRef()).contains("agent-A");
        assertThat(store.load(id).orElseThrow().getAgentRef()).contains("agent-A");
    }

    @Test
    @DisplayName("provision without a binding still materializes a hash — a field-less hash does not exist in Redis")
    void provisionWithoutBinding() {
        final SessionId id = SessionId.of("rec-unbound");

        final SessionRecordView view = store.provision(id);

        assertThat(view.getAgentRef()).isEmpty();
        assertThat(view.getMessages()).isEmpty();
        assertThat(view.getCompactionFailureCount()).isZero();
        assertThat(view.getSessionTotals()).isEqualTo(SessionTotals.empty());
        assertThat(view.getBudgetOverride()).isEmpty();
        // The sentinel field earns its keep exactly here. Redis has no empty hash, so provisioning without a binding
        // would otherwise write nothing at all and leave the session reading as absent -- it would be re-provisioned,
        // and the binding a later claim writes would look like a first binding rather than a conflict.
        assertThat(store.exists(id)).isTrue();
        assertThat(connection.sync().hget("aimon:session:record:rec-unbound", "sessionId")).isEqualTo("rec-unbound");
        assertThat(connection.sync().hexists("aimon:session:record:rec-unbound", "agentRef")).isFalse();
    }

    @Test
    @DisplayName("a transcript survives the round trip and does not disturb the side fields")
    void transcriptRoundTripLeavesSideFieldsAlone() {
        final SessionId id = SessionId.of("rec-transcript");
        store.provision(id, "agent-A");
        store.setTotalsAndBudgetOverride(id, SessionTotals.of(2, 5, TokenUsage.of(10, 20, 30)),
                ExecutionBudget.builder().maxIterations(9).build());
        store.incrementCompactionFailureCount(id);

        store.mergeFromSnapshot(
                SessionSnapshot.of(id, "You are a helper.", List.of(Message.user("hi"), Message.assistant("hello"))));

        final SessionRecordView view = store.load(id).orElseThrow();
        assertThat(view.getSystemPrompt()).isEqualTo("You are a helper.");
        assertThat(view.getMessages()).hasSize(2);
        // The merge names the transcript field and nothing else, so the four fields it cannot carry survive by not
        // being written. A load-mutate-store implementation would pass this only until two writers overlapped.
        assertThat(view.getAgentRef()).contains("agent-A");
        assertThat(view.getCompactionFailureCount()).isEqualTo(1);
        assertThat(view.getSessionTotals().getTurnCount()).isEqualTo(2);
        assertThat(view.getBudgetOverride().orElseThrow().getMaxIterations()).contains(9);
    }

    @Test
    @DisplayName("a tool-call input with arbitrary keys survives the encoding")
    void arbitraryToolInputKeysSurvive() {
        // A tool_use input is a map the model wrote, so its keys are outside anyone's control. Redis would not object
        // to any of these, but the encoding is shared with the Mongo and Postgres stores and this pins that the three
        // agree on what comes back -- a session must read the same whichever backend a deployment picked.
        final SessionId id = SessionId.of("rec-keys");
        final Map<String, Object> input = new LinkedHashMap<>();
        input.put("filters.status", "open");
        input.put("$where", "1 == 1");
        input.put("nested", Map.of("a.b", 1));
        store.provision(id, "agent-A");

        store.mergeFromSnapshot(SessionSnapshot.of(id, null,
                List.of(Message.assistant("calling", List.of(ToolUse.of("tu-1", "Search", input))))));

        final List<ToolUse> toolUses = store.load(id).orElseThrow().getMessages().get(0).getToolUses();
        assertThat(toolUses).hasSize(1);
        assertThat(toolUses.get(0).getInput()).containsEntry("filters.status", "open").containsEntry("$where", "1 == 1")
                .containsEntry("nested", Map.of("a.b", 1));
    }

    @Test
    @DisplayName("totals and budget override move as a pair, and a null override clears one that was set")
    void totalsAndOverrideMoveTogether() {
        final SessionId id = SessionId.of("rec-totals");
        store.provision(id, "agent-A");

        store.setTotalsAndBudgetOverride(id, SessionTotals.of(1, 3, TokenUsage.of(5, 6, 11)),
                ExecutionBudget.builder().maxTokens(1_000).build());
        assertThat(store.load(id).orElseThrow().getBudgetOverride()).isPresent();

        // Null is not "leave it alone". A session whose /budget override was lifted must fall back to the opener's
        // default on the next open -- keeping the stale override would silently re-impose a limit the user removed.
        // Redis needs an HDEL rather than a write to express that, which is why the script branches.
        store.setTotalsAndBudgetOverride(id, SessionTotals.of(2, 7, TokenUsage.of(9, 9, 18)), null);

        final SessionRecordView view = store.load(id).orElseThrow();
        assertThat(view.getSessionTotals().getTurnCount()).isEqualTo(2);
        assertThat(view.getSessionTotals().getIterations()).isEqualTo(7);
        assertThat(view.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(18);
        assertThat(view.getBudgetOverride()).isEmpty();
        assertThat(connection.sync().hexists("aimon:session:record:rec-totals", "budgetOverride")).isFalse();
    }

    @Test
    @DisplayName("writes against a session that was never provisioned leave no key behind")
    void sideFieldWritesDoNotCreateRecords() {
        final SessionId id = SessionId.of("rec-absent");

        store.setTotalsAndBudgetOverride(id, SessionTotals.of(1, 1, TokenUsage.of(1, 1, 2)), null);
        store.resetCompactionFailureCount(id);
        final int count = store.incrementCompactionFailureCount(id);

        // The EXISTS guard in front of each script is what makes this pass, and it is not defensive coding: HSET and
        // HINCRBY both create the hash they write to, so without the guard each of these three calls would conjure a
        // record holding one field and no transcript, and the next claim would find the session already provisioned.
        assertThat(count).isZero();
        assertThat(store.exists(id)).isFalse();
        assertThat(store.load(id)).isEmpty();
        assertThat(connection.sync().exists("aimon:session:record:rec-absent")).isZero();
    }

    @Test
    @DisplayName("each concurrent increment gets its own number, and reset returns to zero")
    void concurrentIncrementsAreDistinct() throws Exception {
        final SessionId id = SessionId.of("rec-counter");
        store.provision(id, "agent-A");
        final int writers = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            final List<Future<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final Callable<Integer> attempt = () -> {
                    start.await();
                    return store.incrementCompactionFailureCount(id);
                };
                attempts.add(pool.submit(attempt));
            }
            start.countDown();

            final List<Integer> observed = new ArrayList<>();
            for (Future<Integer> attempt : attempts) {
                observed.add(attempt.get(30, TimeUnit.SECONDS));
            }
            // HINCRBY returns the value its own increment produced. A read-after-write would let another node's
            // increment land in between and hand two callers the same number, which is the one miscount a compaction
            // circuit breaker must not make: it would trip late, or never.
            assertThat(observed).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
            assertThat(store.load(id).orElseThrow().getCompactionFailureCount()).isEqualTo(writers);

            store.resetCompactionFailureCount(id);
            assertThat(store.load(id).orElseThrow().getCompactionFailureCount()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a session handed to another node arrives with its history — the gap this store closes")
    void nodeHandoffCarriesTheTranscript() {
        final SessionId id = SessionId.of("rec-handoff");

        // Node A: claims the session, runs a turn, records what the turn cost.
        store.provision(id, "agent-A");
        store.mergeFromSnapshot(SessionSnapshot.of(id, "You are a helper.",
                List.of(Message.user("deploy the thing"), Message.assistant("deployed"))));
        store.setTotalsAndBudgetOverride(id, SessionTotals.of(1, 4, TokenUsage.of(120, 60, 180)),
                ExecutionBudget.builder().maxIterations(20).build());

        // Node B: its own connection and its own store, sharing nothing but Redis, as after an eviction or a restart.
        try (StatefulRedisConnection<String, String> nodeB = RedisTestSupport.connect()) {
            final SessionRecordView resumed = new RedisSessionRecordStore(nodeB).load(id).orElseThrow();

            assertThat(resumed.getSystemPrompt()).isEqualTo("You are a helper.");
            assertThat(resumed.getMessages()).extracting(Message::getContent).containsExactly("deploy the thing",
                    "deployed");
            assertThat(resumed.getAgentRef()).contains("agent-A");
            // Not just the messages: totals restore so status reporting resumes from where it was rather than from
            // zero, and the override wins over node B's opener default exactly as it did on node A.
            assertThat(resumed.getSessionTotals().getTurnCount()).isEqualTo(1);
            assertThat(resumed.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(180);
            assertThat(resumed.getBudgetOverride().orElseThrow().getMaxIterations()).contains(20);
        }
    }

    @Test
    @DisplayName("load, delete, listSessionIds and clear agree on what exists")
    void enumerationAndRemoval() {
        assertThat(store.load(SessionId.of("nobody"))).isEmpty();
        assertThat(store.exists(SessionId.of("nobody"))).isFalse();

        store.provision(SessionId.of("rec-a"), "agent-A");
        store.provision(SessionId.of("rec-b"), "agent-A");
        assertThat(store.listSessionIds()).containsExactlyInAnyOrder(SessionId.of("rec-a"), SessionId.of("rec-b"));

        store.delete(SessionId.of("rec-a"));
        assertThat(store.listSessionIds()).containsExactly(SessionId.of("rec-b"));
        // Deleting what is not there is a no-op, not an error: teardown paths run without first checking.
        store.delete(SessionId.of("rec-a"));

        store.clear();
        assertThat(store.listSessionIds()).isEmpty();
    }

    @Test
    @DisplayName("enumeration reads its ids off the keys, and a ':' inside one is not a delimiter")
    void sessionIdsContainingColons() {
        // The SCAN pattern is prefix + ":*" and the id is the whole remainder, taken by prefix length. Splitting on
        // ':' instead would truncate any id that carries one -- and ids are caller-supplied, so some deployments' are
        // structured strings. A truncated id here means listSessionIds reports sessions that cannot then be loaded.
        final SessionId id = SessionId.of("tenant:acme:sess:1");
        store.provision(id, "agent-A");

        assertThat(store.listSessionIds()).containsExactly(id);
        assertThat(store.load(id)).isPresent();
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
    @DisplayName("a hash whose transcript was never written reads as an empty record, not a missing one")
    void provisionedButNeverWritten() {
        // Every session's first turn hits this: the claim path provisions before anything has a transcript to store,
        // and the load that follows must report empty history rather than an absent session. The field is genuinely
        // absent at that point, and absent is not a state of its own here.
        final SessionId id = SessionId.of("rec-fresh");
        store.provision(id, "agent-A");

        final Optional<SessionRecordView> view = store.load(id);

        assertThat(view).isPresent();
        assertThat(view.orElseThrow().getSystemPrompt()).isNull();
        assertThat(view.orElseThrow().getMessages()).isEmpty();
    }
}
