package at.aimon.session.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionRecordStoreException;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;

/**
 * The contract every {@link SessionRecordStore} must honour, written once and run against each shipped store and each
 * decorating one.
 *
 * <p>
 * What matters most is that every write is partial: provision is first-wins for the binding and for the owner, each
 * independently, and each side-field write leaves the fields it does not name exactly as they were. A backend that
 * implemented any of these as load-mutate-store would pass a single-threaded test of it, which is why the concurrency
 * and handoff cases are here too. The rest is round-tripping — a value goes in and comes back verbatim, whatever
 * character a backend happens to treat specially ({@code $} in Mongo, {@code :} in a Redis key, NUL in jsonb) — and
 * the narrow owner read, which must give {@code load}'s answer without depending on the transcript.
 *
 * <p>
 * Subclasses return a store over an emptied backend from {@link #store()}; each test uses its own session ids. The
 * cases that need more than the SPI go through the {@code protected} hooks, whose defaults suit a store that keeps
 * objects rather than an encoded form. A backend subclass overrides {@link #peerStore()},
 * {@link #corruptTranscript(SessionId)} and {@link #corruptOwner(SessionId)}; otherwise the corruption cases are
 * reported as skipped, not passed.
 */
public abstract class AbstractSessionRecordStoreContractTest {

    /**
     * @return the store under test, over an emptied backend
     */
    protected abstract SessionRecordStore store();

    /**
     * A second instance over the same backend, sharing nothing in-process with {@link #store()} — another pool,
     * connection or client — as a different node would. A subclass that opens a resource here closes it in its own
     * {@code @AfterEach}.
     *
     * @return {@link #store()} by default: an in-memory store has no other instance
     */
    protected SessionRecordStore peerStore() {
        return store();
    }

    /**
     * Overwrites the stored transcript of an existing record with bytes {@code load} cannot decode. The default aborts
     * the calling test, reporting it as skipped: a store with no encoded form cannot be corrupted.
     *
     * @param sessionId
     *            a provisioned record
     */
    protected void corruptTranscript(SessionId sessionId) {
        abort("store has no encoded transcript to corrupt");
    }

    /**
     * Overwrites the stored owner of an existing record with a value of the right storage type that the owner codec
     * rejects. The default aborts the calling test, as {@link #corruptTranscript(SessionId)} does.
     *
     * @param sessionId
     *            a record provisioned with an owner
     */
    protected void corruptOwner(SessionId sessionId) {
        abort("store has no encoded owner to corrupt");
    }

    /**
     * @return whether {@link SessionRecordStore#clear()} is supported — {@code true} by default; the fenced record
     *         views
     *         refuse it by design, since holdership cannot be proven for every session at once
     */
    protected boolean supportsClear() {
        return true;
    }

    @Test
    @DisplayName("provision creates the record, and a second provision never re-binds it")
    void provisionBindsOnce() {
        final SessionId id = SessionId.of("rec-provision");

        final SessionRecordView first = store().provision(id, "agent-A");
        assertThat(first.getAgentRef()).contains("agent-A");
        assertThat(store().exists(id)).isTrue();

        // The binding rule the claim path leans on: the second caller is told who actually owns the session rather
        // than having already taken it. Both nodes then agree, and the loser can reject the request instead of
        // serving a session under an agent it was never bound to.
        final SessionRecordView second = store().provision(id, "agent-B");
        assertThat(second.getAgentRef()).contains("agent-A");
        assertThat(store().load(id).orElseThrow().getAgentRef()).contains("agent-A");
    }

    @Test
    @DisplayName("provision records the owner, and a second provision with another owner keeps the first")
    void provisionRecordsOwnerOnce() {
        final SessionId id = SessionId.of("rec-owner");
        final Principal alice = Principal.user("u-alice", "Alice");

        final SessionRecordView first = store().provision(id, "agent-A", alice);
        assertThat(first.getAgentRef()).contains("agent-A");
        assertThat(first.getOwner()).contains(alice);
        assertThat(first.getOwner().orElseThrow().getDisplayName()).isEqualTo("Alice");

        // First-wins, like the binding: the second caller reads the recorded owner back instead of replacing it.
        final SessionRecordView second = store().provision(id, "agent-A", Principal.user("u-bob", "Bob"));
        assertThat(second.getOwner()).contains(alice);
        final Principal loaded = store().load(id).orElseThrow().getOwner().orElseThrow();
        assertThat(loaded).isEqualTo(alice);
        assertThat(loaded.getDisplayName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("a record provisioned without an owner gets one from the first provision that names one")
    void ownerFillsWhenEmpty() {
        final SessionId id = SessionId.of("rec-owner-late");
        final Principal billing = Principal.service("svc-1", "Billing");

        assertThat(store().provision(id, "agent-A").getOwner()).isEmpty();
        assertThat(store().load(id).orElseThrow().getOwner()).isEmpty();
        assertThat(store().provision(id, "agent-A", null).getOwner()).isEmpty();

        final SessionRecordView filled = store().provision(id, "agent-B", billing);
        assertThat(filled.getOwner()).contains(billing);
        // The binding still follows its own rule in the same operation.
        assertThat(filled.getAgentRef()).contains("agent-A");
        // And a later owner-less provision does not clear it: an absent owner loses to the stored one.
        assertThat(store().provision(id, "agent-A").getOwner()).contains(billing);
        assertThat(store().load(id).orElseThrow().getOwner()).contains(billing);
    }

    @Test
    @DisplayName("provision without a binding can still record an owner")
    void ownerWithoutBinding() {
        final SessionId id = SessionId.of("rec-owner-unbound");
        final Principal service = Principal.builder().type(Principal.Type.SERVICE).id("svc-1").displayName("dispatcher")
                .build();

        final SessionRecordView view = store().provision(id, null, service);

        assertThat(view.getAgentRef()).isEmpty();
        assertThat(view.getOwner()).contains(service);
        // The two first-wins rules are independent: binding later leaves the owner alone, and the owner did not bind.
        final SessionRecordView bound = store().provision(id, "agent-A");
        assertThat(bound.getAgentRef()).contains("agent-A");
        assertThat(bound.getOwner()).contains(service);
        assertThat(store().load(id).orElseThrow().getOwner()).contains(service);
    }

    @Test
    @DisplayName("provision can bind an agent without recording an owner")
    void bindingWithoutOwner() {
        final SessionId id = SessionId.of("rec-agent-only");

        final SessionRecordView view = store().provision(id, "agent-A", null);

        assertThat(view.getAgentRef()).contains("agent-A");
        assertThat(view.getOwner()).isEmpty();
        assertThat(store().load(id).orElseThrow().getOwner()).isEmpty();
    }

    @Test
    @DisplayName("the owner survives transcript merges and totals writes, which do not name it")
    void ownerSurvivesOtherWriters() {
        final SessionId id = SessionId.of("rec-owner-preserved");
        final Principal alice = Principal.user("u-alice", "Alice");
        store().provision(id, "agent-A", alice);

        store().mergeFromSnapshot(SessionSnapshot.of(id, null, List.of(Message.user("hi"))));
        final Principal afterMerge = store().load(id).orElseThrow().getOwner().orElseThrow();
        assertThat(afterMerge).isEqualTo(alice);
        assertThat(afterMerge.getDisplayName()).isEqualTo("Alice");

        store().setTotalsAndBudgetOverride(id, SessionTotals.of(1, 2, TokenUsage.of(3, 4, 7)),
                ExecutionBudget.builder().maxIterations(5).build());
        store().setTotalsAndBudgetOverride(id, SessionTotals.of(2, 3, TokenUsage.of(4, 5, 9)), null);
        store().incrementCompactionFailureCount(id);
        store().resetCompactionFailureCount(id);

        final SessionRecordView view = store().load(id).orElseThrow();
        assertThat(view.getOwner()).contains(alice);
        assertThat(view.getOwner().orElseThrow().getDisplayName()).isEqualTo("Alice");
        assertThat(view.getMessages()).hasSize(1);
    }

    @Test
    @DisplayName("provision without a binding materializes an unbound record")
    void provisionWithoutBinding() {
        final SessionId id = SessionId.of("rec-unbound");

        final SessionRecordView view = store().provision(id);

        assertThat(view.getAgentRef()).isEmpty();
        assertThat(view.getMessages()).isEmpty();
        assertThat(view.getCompactionFailureCount()).isZero();
        assertThat(view.getSessionTotals()).isEqualTo(SessionTotals.empty());
        assertThat(view.getBudgetOverride()).isEmpty();
        assertThat(view.getOwner()).isEmpty();
        // Existence is the whole point of this call: a record that reads as absent would be re-provisioned, and the
        // binding a later claim writes would look like a first binding rather than a conflict.
        assertThat(store().exists(id)).isTrue();
    }

    @Test
    @DisplayName("a transcript survives the round trip and does not disturb the side fields")
    void transcriptRoundTripLeavesSideFieldsAlone() {
        final SessionId id = SessionId.of("rec-transcript");
        store().provision(id, "agent-A");
        store().setTotalsAndBudgetOverride(id, SessionTotals.of(2, 5, TokenUsage.of(10, 20, 30, 7)),
                ExecutionBudget.builder().maxIterations(9).build());
        store().incrementCompactionFailureCount(id);

        // The assistant message carries a reasoning trace: an opaque provider payload that lands in the persisted
        // transcript. A backend stores the transcript as an opaque string and cannot see the field, which is the
        // argument -- this test is what turns that argument into an observation on a real database.
        final ReasoningTrace trace = ReasoningTrace.builder().providerName("OpenAI")
                .payload("{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"gAAAAA==\"}")
                .toolUseId("call_1").build();
        store().mergeFromSnapshot(SessionSnapshot.of(id, "You are a helper.",
                List.of(Message.user("hi"), Message.assistant("hello").withReasoningTraces(List.of(trace)))));

        final SessionRecordView view = store().load(id).orElseThrow();
        assertThat(view.getSystemPrompt()).isEqualTo("You are a helper.");
        assertThat(view.getMessages()).hasSize(2);
        assertThat(view.getMessages().get(1).getReasoningTraces()).containsExactly(trace);
        // The merge names the transcript and nothing else, so the side fields it cannot carry survive by not being
        // written. A load-mutate-store implementation would pass this only until two writers overlapped.
        assertThat(view.getAgentRef()).contains("agent-A");
        assertThat(view.getCompactionFailureCount()).isEqualTo(1);
        assertThat(view.getSessionTotals().getTurnCount()).isEqualTo(2);
        assertThat(view.getSessionTotals().getTokenUsage().getReasoningTokens()).isEqualTo(7);
        assertThat(view.getBudgetOverride().orElseThrow().getMaxIterations()).contains(9);
    }

    @Test
    @DisplayName("a NUL byte in a tool-call input and in the system prompt survives")
    void nulBytesSurvive() {
        // A tool_use input is an arbitrary map the model wrote, so a U+0000 can turn up inside a string value. Postgres
        // keeps the transcript in text rather than jsonb for exactly this (jsonb rejects the character outright); a
        // store that dropped or refused it would lose the turn on whatever tool call happened to carry one.
        final SessionId id = SessionId.of("rec-nul");
        store().provision(id, "agent-A");
        // Built rather than written as a literal: a raw NUL in a source file is invisible in every diff and
        // every editor, and this test is worthless if a later edit silently drops it.
        final String withNul = "a" + (char) 0 + "b";
        final String sysPrompt = "sys" + (char) 0 + "prompt";
        final Map<String, Object> input = Map.of("pattern", withNul);

        store().mergeFromSnapshot(SessionSnapshot.of(id, sysPrompt,
                List.of(Message.assistant("calling", List.of(ToolUse.of("tu-1", "Search", input))))));

        final SessionRecordView view = store().load(id).orElseThrow();
        assertThat(view.getSystemPrompt()).isEqualTo(sysPrompt);
        assertThat(view.getMessages().get(0).getToolUses().get(0).getInput()).containsEntry("pattern", withNul);
    }

    @Test
    @DisplayName("tool-call input keys come back verbatim, dots and leading $ included")
    void toolInputKeysSurviveVerbatim() {
        // A tool_use input is a map the model wrote, so its keys are outside anyone's control. BSON field names may not
        // contain '.' or start with '$', which is why Mongo keeps the transcript as one encoded string; the encoding is
        // shared by every backend, and this pins that they agree on what comes back -- a session must read the same
        // whichever backend a deployment picked.
        final SessionId id = SessionId.of("rec-keys");
        final Map<String, Object> input = new LinkedHashMap<>();
        input.put("filters.status", "open");
        input.put("$where", "1 == 1");
        input.put("nested", Map.of("a.b", 1));
        store().provision(id, "agent-A");

        store().mergeFromSnapshot(SessionSnapshot.of(id, null,
                List.of(Message.assistant("calling", List.of(ToolUse.of("tu-1", "Search", input))))));

        final List<ToolUse> toolUses = store().load(id).orElseThrow().getMessages().get(0).getToolUses();
        assertThat(toolUses).hasSize(1);
        assertThat(toolUses.get(0).getInput()).containsEntry("filters.status", "open").containsEntry("$where", "1 == 1")
                .containsEntry("nested", Map.of("a.b", 1));
    }

    @Test
    @DisplayName("an agentRef and an owner beginning with $ are stored as data, not evaluated")
    void dollarPrefixedValuesAreData() {
        // Mongo's provision is an update pipeline, where a string beginning with '$' is a field path: "$_id" read that
        // way would bind the session to its own id instead of the agent, and an owner subdocument inside $ifNull is an
        // expression. The property is the same for every store -- the value goes in and comes back as written.
        final SessionId bound = SessionId.of("rec-provision-dollar");
        assertThat(store().provision(bound, "$_id").getAgentRef()).contains("$_id");
        assertThat(store().load(bound).orElseThrow().getAgentRef()).contains("$_id");

        final SessionId owned = SessionId.of("rec-owner-dollar");
        final Principal owner = Principal.user("$where", "$set");
        assertThat(store().provision(owned, "agent-A", owner).getOwner().orElseThrow().getId()).isEqualTo("$where");
        final Principal loaded = store().load(owned).orElseThrow().getOwner().orElseThrow();
        assertThat(loaded.getId()).isEqualTo("$where");
        assertThat(loaded.getDisplayName()).isEqualTo("$set");
    }

    @Test
    @DisplayName("totals and budget override move as a pair, and a null override clears one that was set")
    void totalsAndOverrideMoveTogether() {
        final SessionId id = SessionId.of("rec-totals");
        store().provision(id, "agent-A");

        store().setTotalsAndBudgetOverride(id, SessionTotals.of(1, 3, TokenUsage.of(5, 6, 11)),
                ExecutionBudget.builder().maxTokens(1_000).build());
        assertThat(store().load(id).orElseThrow().getBudgetOverride()).isPresent();

        // Null is not "leave it alone". A session whose /budget override was lifted must fall back to the opener's
        // default on the next open -- keeping the stale override would silently re-impose a limit the user removed.
        store().setTotalsAndBudgetOverride(id, SessionTotals.of(2, 7, TokenUsage.of(9, 9, 18)), null);

        final SessionRecordView view = store().load(id).orElseThrow();
        assertThat(view.getSessionTotals().getTurnCount()).isEqualTo(2);
        assertThat(view.getSessionTotals().getIterations()).isEqualTo(7);
        assertThat(view.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(18);
        assertThat(view.getBudgetOverride()).isEmpty();
    }

    @Test
    @DisplayName("writes against a session that was never provisioned are no-ops, not creates")
    void sideFieldWritesDoNotCreateRecords() {
        final SessionId id = SessionId.of("rec-absent");

        store().setTotalsAndBudgetOverride(id, SessionTotals.of(1, 1, TokenUsage.of(1, 1, 2)), null);
        store().resetCompactionFailureCount(id);
        final int count = store().incrementCompactionFailureCount(id);

        // Zero is "nothing has been counted", which is what a caller asking about a session that does not exist should
        // hear. Upserting instead would leave a record holding one side field and no transcript, and the next claim
        // would find it already provisioned.
        assertThat(count).isZero();
        assertThat(store().exists(id)).isFalse();
        assertThat(store().load(id)).isEmpty();
    }

    @Test
    @DisplayName("each concurrent increment gets its own number, and reset returns to zero")
    void concurrentIncrementsAreDistinct() throws Exception {
        final SessionId id = SessionId.of("rec-counter");
        final SessionRecordStore store = store();
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
            // Each caller must see the value its own increment produced. A read-after-write would let another node's
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
    @DisplayName("a session handed to another node arrives with its history")
    void nodeHandoffCarriesTheTranscript() {
        final SessionId id = SessionId.of("rec-handoff");

        // Node A: claims the session, runs a turn, records what the turn cost.
        store().provision(id, "agent-A");
        store().mergeFromSnapshot(SessionSnapshot.of(id, "You are a helper.",
                List.of(Message.user("deploy the thing"), Message.assistant("deployed"))));
        store().setTotalsAndBudgetOverride(id, SessionTotals.of(1, 4, TokenUsage.of(120, 60, 180)),
                ExecutionBudget.builder().maxIterations(20).build());

        // Node B: its own instance, sharing nothing but the backend, as after an eviction or a restart.
        final SessionRecordView resumed = peerStore().load(id).orElseThrow();

        assertThat(resumed.getSystemPrompt()).isEqualTo("You are a helper.");
        assertThat(resumed.getMessages()).extracting(Message::getContent).containsExactly("deploy the thing",
                "deployed");
        assertThat(resumed.getAgentRef()).contains("agent-A");
        // Not just the messages: totals restore so status reporting resumes from where it was rather than from zero,
        // and the override wins over node B's opener default exactly as it did on node A.
        assertThat(resumed.getSessionTotals().getTurnCount()).isEqualTo(1);
        assertThat(resumed.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(180);
        assertThat(resumed.getBudgetOverride().orElseThrow().getMaxIterations()).contains(20);
    }

    @Test
    @DisplayName("load, delete, exists and listSessionIds agree on what exists")
    void enumerationAndRemoval() {
        assertThat(store().load(SessionId.of("nobody"))).isEmpty();
        assertThat(store().exists(SessionId.of("nobody"))).isFalse();

        store().provision(SessionId.of("rec-a"), "agent-A");
        store().provision(SessionId.of("rec-b"), "agent-A");
        assertThat(store().listSessionIds()).containsExactlyInAnyOrder(SessionId.of("rec-a"), SessionId.of("rec-b"));

        store().delete(SessionId.of("rec-a"));
        assertThat(store().listSessionIds()).containsExactly(SessionId.of("rec-b"));
        assertThat(store().exists(SessionId.of("rec-a"))).isFalse();
        // Deleting what is not there is a no-op, not an error: teardown paths run without first checking.
        store().delete(SessionId.of("rec-a"));
    }

    @Test
    @DisplayName("clear removes every record, or — where the store refuses it — removes none")
    void clearRemovesEveryRecord() {
        final SessionId a = SessionId.of("rec-clear-a");
        final SessionId b = SessionId.of("rec-clear-b");
        store().provision(a, "agent-A");
        store().provision(b, "agent-A");

        if (supportsClear()) {
            store().clear();
            assertThat(store().listSessionIds()).isEmpty();
            assertThat(store().exists(a)).isFalse();
            return;
        }
        // A refusal must come before any record is touched: a partial wipe that then threw would be the worst answer.
        assertThatThrownBy(() -> store().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(store().listSessionIds()).containsExactlyInAnyOrder(a, b);
        assertThat(store().exists(a)).isTrue();
        assertThat(store().exists(b)).isTrue();
    }

    @Test
    @DisplayName("an id containing ':' comes back whole from enumeration and loads")
    void idsRoundTripThroughEnumeration() {
        // Ids are caller-supplied, so some deployments' are structured strings. A store that derived ids from its keys
        // and split on a delimiter -- Redis keys are prefix + ':' + id -- would truncate them, and listSessionIds
        // would report sessions that cannot then be loaded.
        final SessionId id = SessionId.of("tenant:acme:sess:1");
        store().provision(id, "agent-A");

        assertThat(store().listSessionIds()).containsExactly(id);
        assertThat(store().load(id)).isPresent();
    }

    @Test
    @DisplayName("a record whose transcript was never written reads as an empty record, not a missing one")
    void provisionedButNeverWritten() {
        // Every session's first turn hits this: the claim path provisions before anything has a transcript to store,
        // and the load that follows must report empty history rather than an absent session.
        final SessionId id = SessionId.of("rec-fresh");
        store().provision(id, "agent-A");

        final Optional<SessionRecordView> view = store().load(id);

        assertThat(view).isPresent();
        assertThat(view.orElseThrow().getSystemPrompt()).isNull();
        assertThat(view.orElseThrow().getMessages()).isEmpty();
    }

    @Test
    @DisplayName("loadOwner is empty without a record or an owner, and reads the owner back once there is one")
    void loadOwnerReadsTheOwner() {
        final SessionId id = SessionId.of("rec-load-owner");
        final Principal alice = Principal.user("u-1", "Alice");

        assertThat(store().loadOwner(id)).isEmpty();
        store().provision(id, "agent-A");
        assertThat(store().loadOwner(id)).isEmpty();
        store().provision(id, "agent-A", alice);
        assertThat(store().loadOwner(id)).contains(alice);
        assertThat(store().loadOwner(id).orElseThrow().getDisplayName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("loadOwner reads the owner alone: a transcript that fails load does not fail it")
    void loadOwnerDoesNotDecodeTheTranscript() {
        final SessionId id = SessionId.of("rec-load-owner-narrow");
        final Principal alice = Principal.user("u-1", "Alice");
        store().provision(id, "agent-A", alice);
        corruptTranscript(id);

        assertThatThrownBy(() -> store().load(id)).isInstanceOf(SessionRecordStoreException.class);
        assertThat(store().loadOwner(id)).contains(alice);
    }

    @Test
    @DisplayName("an owner that will not decode fails loadOwner rather than reading as no owner")
    void loadOwnerRefusesACorruptOwner() {
        final SessionId id = SessionId.of("rec-load-owner-corrupt");
        store().provision(id, "agent-A", Principal.user("u-1", "Alice"));
        corruptOwner(id);

        assertThatThrownBy(() -> store().loadOwner(id)).isInstanceOf(SessionRecordStoreException.class)
                .hasMessageContaining("Failed to decode stored session owner for");
    }
}
