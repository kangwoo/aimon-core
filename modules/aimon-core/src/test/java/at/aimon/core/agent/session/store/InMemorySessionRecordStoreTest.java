package at.aimon.core.agent.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;

class InMemorySessionRecordStoreTest {

    private InMemorySessionRecordStore repository;

    @BeforeEach
    void setUp() {
        repository = new InMemorySessionRecordStore();
    }

    @Test
    void testSaveAndLoad() {
        // Given
        SessionId id = new SessionId("conv-1");
        SessionRecord record = new SessionRecord(id, null,
                List.of(Message.user("Hello"), Message.assistant("Hi there!")));

        // When
        repository.save(record);
        Optional<SessionRecordView> loaded = repository.load(id);

        // Then
        assertTrue(loaded.isPresent());
        assertEquals(2, loaded.get().getMessages().size());
        assertEquals("Hello", loaded.get().getMessages().get(0).getContent());
        assertEquals("Hi there!", loaded.get().getMessages().get(1).getContent());
    }

    @Test
    void testLoadNonExistent() {
        // When
        Optional<SessionRecordView> loaded = repository.load(new SessionId("non-existent"));

        // Then
        assertFalse(loaded.isPresent());
    }

    @Test
    void testSaveOverwrite() {
        // Given
        SessionId id = new SessionId("conv-1");
        SessionRecord session1 = new SessionRecord(id, null, List.of(Message.user("First")));

        SessionRecord session2 = new SessionRecord(id, null, List.of(Message.user("Second")));

        // When
        repository.save(session1);
        repository.save(session2);
        Optional<SessionRecordView> loaded = repository.load(id);

        // Then
        assertTrue(loaded.isPresent());
        assertEquals(1, loaded.get().getMessages().size());
        assertEquals("Second", loaded.get().getMessages().get(0).getContent());
    }

    @Test
    void testDelete() {
        // Given
        SessionId id = new SessionId("conv-1");
        SessionRecord record = new SessionRecord(id, null, List.of(Message.user("Hello")));
        repository.save(record);

        // When
        repository.delete(id);
        Optional<SessionRecordView> loaded = repository.load(id);

        // Then
        assertFalse(loaded.isPresent());
    }

    @Test
    void testDeleteNonExistent() {
        // When & Then - should not throw
        assertDoesNotThrow(() -> repository.delete(new SessionId("non-existent")));
    }

    @Test
    void testListConversationIds() {
        // Given
        SessionId id1 = new SessionId("conv-1");
        SessionId id2 = new SessionId("conv-2");
        SessionId id3 = new SessionId("conv-3");

        repository.save(new SessionRecord(id1, null, List.of(Message.user("One"))));
        repository.save(new SessionRecord(id2, null, List.of(Message.user("Two"))));
        repository.save(new SessionRecord(id3, null, List.of(Message.user("Three"))));

        // When
        List<SessionId> ids = repository.listSessionIds();

        // Then
        assertEquals(3, ids.size());
        assertTrue(ids.contains(id1));
        assertTrue(ids.contains(id2));
        assertTrue(ids.contains(id3));
    }

    @Test
    void testListConversationIdsEmpty() {
        // When
        List<SessionId> ids = repository.listSessionIds();

        // Then
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void testExists() {
        // Given
        SessionId id = new SessionId("conv-1");
        SessionRecord record = new SessionRecord(id);
        repository.save(record);

        // Then
        assertTrue(repository.exists(id));
        assertFalse(repository.exists(new SessionId("non-existent")));
    }

    @Test
    void testClear() {
        // Given
        SessionId id1 = new SessionId("conv-1");
        SessionId id2 = new SessionId("conv-2");

        SessionRecord conv1 = new SessionRecord(id1);
        repository.save(conv1);

        SessionRecord conv2 = new SessionRecord(id2);
        repository.save(conv2);

        // When
        repository.clear();

        // Then
        assertEquals(0, repository.size());
        assertTrue(repository.listSessionIds().isEmpty());
        assertFalse(repository.exists(id1));
        assertFalse(repository.exists(id2));
    }

    @Test
    void testSize() {
        // Given
        assertEquals(0, repository.size());

        SessionId id1 = new SessionId("conv-1");
        SessionRecord conv1 = new SessionRecord(id1);
        repository.save(conv1);
        assertEquals(1, repository.size());

        SessionId id2 = new SessionId("conv-2");
        SessionRecord conv2 = new SessionRecord(id2);
        repository.save(conv2);
        assertEquals(2, repository.size());

        repository.delete(id1);
        assertEquals(1, repository.size());
    }

    @Test
    void testSaveWithNullConversation() {
        // When & Then
        assertThrows(NullPointerException.class, () -> repository.save(null));
    }

    @Test
    void testConversationIdWithEmptyValue() {
        // When & Then - validation happens in SessionId constructor
        assertThrows(IllegalArgumentException.class, () -> new SessionId(""));
        assertThrows(IllegalArgumentException.class, () -> new SessionId("   "));
    }

    @Test
    void testLoadWithNullId() {
        // When & Then
        assertThrows(NullPointerException.class, () -> repository.load(null));
    }

    @Test
    void testConversationIdWithNullValue() {
        // When & Then - validation happens in SessionId constructor
        assertThrows(NullPointerException.class, () -> new SessionId(null));
    }

    @Test
    void testDeleteWithNullId() {
        // When & Then
        assertThrows(NullPointerException.class, () -> repository.delete(null));
    }

    @Test
    void testExistsWithNullId() {
        // When & Then
        assertThrows(NullPointerException.class, () -> repository.exists(null));
    }

    @Test
    void testIsolationBetweenSaveAndLoad() {
        // Given - the history can no longer be mutated after construction, so the side fields are what the copy on
        // save has to isolate. They are the ones with real writers (the compaction store and the session manager).
        SessionId id = new SessionId("conv-1");
        SessionRecord record = new SessionRecord(id, null, List.of(Message.user("Original")));
        repository.save(record);

        // When - modify the original session after save
        record.setCompactionFailureCount(9);
        record.setAgentRef("agent-after-save");

        // Then - the stored record should not be affected
        Optional<SessionRecordView> loaded = repository.load(id);
        assertTrue(loaded.isPresent());
        assertEquals(0, loaded.get().getCompactionFailureCount());
        assertEquals(Optional.empty(), loaded.get().getAgentRef());
        assertEquals(1, loaded.get().getMessages().size());
        assertEquals("Original", loaded.get().getMessages().get(0).getContent());
    }

    @Test
    void testIsolationBetweenLoads() {
        // Given
        SessionId id = new SessionId("conv-1");
        SessionRecord record = new SessionRecord(id, null, List.of(Message.user("Original")));
        repository.save(record);

        // When - load and modify (cast to mutable SessionRecord since InMemorySessionRecordStore returns one)
        Optional<SessionRecordView> loaded1 = repository.load(id);
        assertTrue(loaded1.isPresent());
        ((SessionRecord) loaded1.get()).setCompactionFailureCount(9);
        ((SessionRecord) loaded1.get()).setAgentRef("agent-after-load");

        // Then - a second load should not be affected
        Optional<SessionRecordView> loaded2 = repository.load(id);
        assertTrue(loaded2.isPresent());
        assertEquals(0, loaded2.get().getCompactionFailureCount());
        assertEquals(Optional.empty(), loaded2.get().getAgentRef());
        assertEquals(1, loaded2.get().getMessages().size());
        assertEquals("Original", loaded2.get().getMessages().get(0).getContent());
    }

    @Test
    void testToString() {
        // Given
        SessionId id = new SessionId("conv-1");
        SessionRecord conv = new SessionRecord(id);
        repository.save(conv);

        // When
        String str = repository.toString();

        // Then
        assertNotNull(str);
        assertTrue(str.contains("InMemorySessionRecordStore"));
        assertTrue(str.contains("size=1"));
        assertTrue(str.contains("conv-1"));
    }

    @Test
    void testSaveAndLoadWithSystemPrompt() {
        // Given
        SessionId id = new SessionId("conv-1");
        String systemPrompt = "You are a helpful assistant.";
        SessionRecord record = new SessionRecord(id, systemPrompt, List.of(Message.user("Hello")));

        // When
        repository.save(record);
        Optional<SessionRecordView> loaded = repository.load(id);

        // Then
        assertTrue(loaded.isPresent());
        assertEquals(systemPrompt, loaded.get().getSystemPrompt());
        assertEquals(1, loaded.get().getMessages().size());
    }

    @Test
    void testSaveWithoutSystemPrompt() {
        // Given
        SessionId id = new SessionId("conv-1");
        SessionRecord record = new SessionRecord(id, null, List.of(Message.user("Hello")));

        // When
        repository.save(record);
        Optional<SessionRecordView> loaded = repository.load(id);

        // Then
        assertTrue(loaded.isPresent());
        assertNull(loaded.get().getSystemPrompt());
    }

    @Test
    void testSystemPromptIsolation() {
        // Given
        SessionId id = new SessionId("conv-1");
        SessionRecord record = new SessionRecord(id, "Original prompt", List.of(Message.user("Message")));
        repository.save(record);

        // When - modify original session's system prompt after save
        record.setSystemPrompt("Modified prompt");

        // Then - loaded session should not be affected
        Optional<SessionRecordView> loaded = repository.load(id);
        assertTrue(loaded.isPresent());
        assertEquals("Original prompt", loaded.get().getSystemPrompt());
    }

    @Test
    void testLoadedSystemPromptIsolation() {
        // Given
        SessionId id = new SessionId("conv-1");
        SessionRecord record = new SessionRecord(id, "Prompt");
        repository.save(record);

        // When - load and modify system prompt (cast to mutable SessionRecord)
        Optional<SessionRecordView> loaded1 = repository.load(id);
        assertTrue(loaded1.isPresent());
        ((SessionRecord) loaded1.get()).setSystemPrompt("Modified");

        // Then - second load should not be affected
        Optional<SessionRecordView> loaded2 = repository.load(id);
        assertTrue(loaded2.isPresent());
        assertEquals("Prompt", loaded2.get().getSystemPrompt());
    }

    @Test
    void testOverwriteSystemPrompt() {
        // Given
        SessionId id = new SessionId("conv-1");
        SessionRecord session1 = new SessionRecord(id, "First prompt", List.of(Message.user("First")));
        repository.save(session1);

        SessionRecord session2 = new SessionRecord(id, "Second prompt", List.of(Message.user("Second")));

        // When
        repository.save(session2);
        Optional<SessionRecordView> loaded = repository.load(id);

        // Then
        assertTrue(loaded.isPresent());
        assertEquals("Second prompt", loaded.get().getSystemPrompt());
        assertEquals(1, loaded.get().getMessages().size());
        assertEquals("Second", loaded.get().getMessages().get(0).getContent());
    }

    @Test
    void mergeFromSnapshotPreservesCompactionFailureCountAndAgentRef() {
        SessionId id = new SessionId("conv-merge");
        SessionRecord seed = new SessionRecord(id, "sys", List.of(Message.user("old")), 7, "agent-x");
        repository.save(seed);

        SessionSnapshot snapshot = SessionSnapshot.of(id, "sys-new", List.of(Message.user("new")));

        repository.mergeFromSnapshot(snapshot);

        SessionRecordView loaded = repository.load(id).orElseThrow();
        assertEquals(7, loaded.getCompactionFailureCount(), "compactionFailureCount must be preserved");
        assertEquals(Optional.of("agent-x"), loaded.getAgentRef(), "agentRef must be preserved");
        assertEquals("sys-new", loaded.getSystemPrompt());
        assertEquals(1, loaded.getMessages().size());
        assertEquals("new", loaded.getMessages().get(0).getContent());
    }

    @Test
    void mergeFromSnapshotCreatesRecordWhenAbsent() {
        SessionId id = new SessionId("conv-merge-new");
        SessionSnapshot snapshot = SessionSnapshot.of(id, "sys", List.of(at.aimon.core.llm.Message.user("hello")));

        repository.mergeFromSnapshot(snapshot);

        SessionRecordView loaded = repository.load(id).orElseThrow();
        assertEquals(0, loaded.getCompactionFailureCount());
        assertEquals(Optional.empty(), loaded.getAgentRef());
        assertEquals(1, loaded.getMessages().size());
    }

    @Test
    void provisionBindsAnUnboundRecordAndPreservesEverythingElse() {
        SessionId id = new SessionId("conv-ref");
        repository.save(new SessionRecord(id, "sys", List.of(at.aimon.core.llm.Message.user("u")), 3, null));

        SessionRecordView returned = repository.provision(id, "agent-y");

        assertEquals(Optional.of("agent-y"), returned.getAgentRef(), "the returned record shows the new binding");
        SessionRecordView loaded = repository.load(id).orElseThrow();
        assertEquals(Optional.of("agent-y"), loaded.getAgentRef());
        assertEquals(1, loaded.getMessages().size(), "message body must not be touched");
        assertEquals(3, loaded.getCompactionFailureCount());
        assertEquals("sys", loaded.getSystemPrompt());
    }

    @Test
    void provisionLeavesAnEstablishedBindingAlone() {
        SessionId id = new SessionId("conv-counter");
        repository.save(new SessionRecord(id, "sys",
                List.of(at.aimon.core.llm.Message.user("u"), at.aimon.core.llm.Message.assistant("a")), 4, "agent-x"));

        SessionRecordView returned = repository.provision(id, "agent-other");

        // The caller learns who actually owns the session instead of stealing it — this is what lets
        // DefaultSessionStore.claim answer AgentConflict from provision's return value alone.
        assertEquals(Optional.of("agent-x"), returned.getAgentRef());
        SessionRecordView loaded = repository.load(id).orElseThrow();
        assertEquals(Optional.of("agent-x"), loaded.getAgentRef());
        assertEquals(2, loaded.getMessages().size());
        assertEquals(4, loaded.getCompactionFailureCount());
    }

    @Test
    void provisionCreatesAnEmptyRecordWhenAbsent() {
        SessionId id = new SessionId("conv-counter-new");

        SessionRecordView returned = repository.provision(id, "agent-y");

        assertTrue(repository.exists(id));
        assertEquals(id, returned.getId());
        assertEquals(Optional.of("agent-y"), returned.getAgentRef());
        assertTrue(returned.getMessages().isEmpty());
    }

    @Test
    void provisionWithoutAnAgentRefCreatesTheRecordUnbound() {
        SessionId id = new SessionId("conv-ref-missing");

        SessionRecordView returned = repository.provision(id);

        // The drain path needs to hold a session before it may read the message that names the agent, so
        // provisioning and binding have to be separable.
        assertTrue(repository.exists(id), "provision must create the record even with nothing to bind");
        assertEquals(Optional.empty(), returned.getAgentRef());
    }

    @Test
    void provisionDoesNotHandOutTheStoredInstance() {
        SessionId id = new SessionId("conv-ref-copy");

        ((SessionRecord) repository.provision(id, "agent-y")).setSystemPrompt("mutated behind the repository's back");

        assertNull(repository.load(id).orElseThrow().getSystemPrompt());
    }

    @Test
    void provisionRejectsNullId() {
        assertThrows(NullPointerException.class, () -> repository.provision(null, "agent-y"));
        assertThrows(NullPointerException.class, () -> repository.provision(null));
    }

    @Test
    void sideFieldWriteAndSnapshotMergeAreMutuallyConsistent() throws Exception {
        // Stress-test: alternating side-field writes and message-snapshot merges from two threads must never lose
        // either, because both go through the repository's compute path.
        final SessionId id = new SessionId("conv-stress");
        repository.save(new SessionRecord(id, "sys", List.of(), 0, null));

        final int iterations = 200;
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);

        final Thread incrementer = new Thread(() -> {
            try {
                start.await();
                for (int i = 1; i <= iterations; i++) {
                    repository.setTotalsAndBudgetOverride(id, SessionTotals.of(i, i, TokenUsage.empty()), null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "stress-incrementer");

        final Thread merger = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    final SessionRecordView current = repository.load(id).orElseThrow();
                    final java.util.List<at.aimon.core.llm.Message> next = new java.util.ArrayList<>(
                            current.getMessages());
                    next.add(at.aimon.core.llm.Message.user("m" + i));
                    repository.mergeFromSnapshot(SessionSnapshot.of(id, "sys", next));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "stress-merger");

        incrementer.start();
        merger.start();
        start.countDown();
        incrementer.join(10_000);
        merger.join(10_000);
        assertFalse(incrementer.isAlive());
        assertFalse(merger.isAlive());

        final SessionRecordView finalView = repository.load(id).orElseThrow();
        assertEquals(iterations, finalView.getSessionTotals().getTurnCount(),
                "the last side-field write must survive every concurrent message merge");
        // Messages can be < iterations because the merger does its read outside compute; concurrent reads of the
        // same snapshot can collapse into a single write whose state is later overwritten. The remaining list,
        // however, must always be a strict prefix of the m0..m{iterations-1} sequence — any deviation would indicate
        // that mergeFromSnapshot mishandled the merge (e.g. dropped a message that was already persisted).
        final java.util.List<at.aimon.core.llm.Message> finalMessages = finalView.getMessages();
        assertTrue(finalMessages.size() > 0, "at least one merge must have landed");
        for (int i = 0; i < finalMessages.size(); i++) {
            assertEquals("m" + i, finalMessages.get(i).getContent(),
                    "messages must form a contiguous prefix m0..m" + (finalMessages.size() - 1));
        }
    }

    // =========================================================================
    // setTotalsAndBudgetOverride side-field tests (§12)
    // =========================================================================

    @Test
    void setTotalsAndBudgetOverrideWritesBothAndPreservesEverythingElse() {
        final SessionId id = new SessionId("conv-totals");
        repository.save(new SessionRecord(id, "sys", List.of(at.aimon.core.llm.Message.user("hello")), 3, "agent-a"));

        repository.setTotalsAndBudgetOverride(id, SessionTotals.of(2, 5, TokenUsage.of(10, 4, 14)),
                ExecutionBudget.builder().maxTokens(50_000).build());

        final SessionRecordView loaded = repository.load(id).orElseThrow();
        assertThat(loaded.getSessionTotals().getTurnCount()).isEqualTo(2);
        assertThat(loaded.getSessionTotals().getIterations()).isEqualTo(5);
        assertThat(loaded.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(14);
        assertThat(loaded.getBudgetOverride()).isPresent();
        assertThat(loaded.getBudgetOverride().orElseThrow().getMaxTokens()).contains(50_000);
        // The three fields this primitive must not touch — each has a different writer.
        assertEquals(3, loaded.getCompactionFailureCount(), "compactionFailureCount must be preserved");
        assertEquals(Optional.of("agent-a"), loaded.getAgentRef(), "agentRef must be preserved");
        assertEquals(1, loaded.getMessages().size(), "messages must be preserved");
        assertEquals("sys", loaded.getSystemPrompt(), "systemPrompt must be preserved");
    }

    @Test
    void setTotalsAndBudgetOverrideIsIdempotent() {
        // The write is absolute, never a delta — which is what lets a session repeat a flush without double-counting
        // the turn it just folded in.
        final SessionId id = new SessionId("conv-totals-idempotent");
        repository.save(new SessionRecord(id));
        final SessionTotals totals = SessionTotals.of(4, 9, TokenUsage.of(10, 5, 15));

        repository.setTotalsAndBudgetOverride(id, totals, null);
        repository.setTotalsAndBudgetOverride(id, totals, null);

        final SessionRecordView loaded = repository.load(id).orElseThrow();
        assertThat(loaded.getSessionTotals().getTurnCount()).isEqualTo(4);
        assertThat(loaded.getSessionTotals().getIterations()).isEqualTo(9);
        assertThat(loaded.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(15);
    }

    @Test
    void setTotalsAndBudgetOverrideIsNoOpWhenRecordAbsent() {
        final SessionId id = new SessionId("conv-totals-absent");

        repository.setTotalsAndBudgetOverride(id, SessionTotals.of(1, 1, TokenUsage.empty()),
                ExecutionBudget.unlimited());

        assertFalse(repository.exists(id), "setTotalsAndBudgetOverride must not provision a record when none exists");
    }

    @Test
    void setTotalsAndBudgetOverrideRejectsNullId() {
        assertThrows(NullPointerException.class,
                () -> repository.setTotalsAndBudgetOverride(null, SessionTotals.empty(), null));
    }

    @Test
    void setTotalsAndBudgetOverrideRejectsNullTotals() {
        final SessionId id = new SessionId("conv-totals-null");
        repository.save(new SessionRecord(id));
        assertThrows(NullPointerException.class, () -> repository.setTotalsAndBudgetOverride(id, null, null));
    }

    @Test
    void setTotalsAndBudgetOverrideNullOverrideClearsIt() {
        // null is how "no override" is spelled, so the same argument both leaves an unset field unset and clears a
        // field that was set.
        final SessionId id = new SessionId("conv-budget-clear");
        repository.save(new SessionRecord(id));
        repository.setTotalsAndBudgetOverride(id, SessionTotals.empty(),
                ExecutionBudget.builder().maxTokens(1000).build());
        assertThat(repository.load(id).orElseThrow().getBudgetOverride()).isPresent();

        repository.setTotalsAndBudgetOverride(id, SessionTotals.empty(), null);

        assertThat(repository.load(id).orElseThrow().getBudgetOverride()).isEmpty();
    }

    @Test
    void mergeFromSnapshotPreservesConversationTotalsAndBudgetOverride() {
        final SessionId id = new SessionId("conv-merge-side");
        final SessionRecord seed = new SessionRecord(id, "sys", List.of(), 0, null);
        repository.save(seed);

        repository.setTotalsAndBudgetOverride(id, SessionTotals.of(3, 7, TokenUsage.of(20, 8, 28)),
                ExecutionBudget.builder().maxIterations(10).build());

        final SessionSnapshot snapshot = SessionSnapshot.of(id, "sys-new",
                List.of(at.aimon.core.llm.Message.user("new-msg")));
        repository.mergeFromSnapshot(snapshot);

        final SessionRecordView loaded = repository.load(id).orElseThrow();
        assertThat(loaded.getSessionTotals().getTurnCount()).isEqualTo(3);
        assertThat(loaded.getSessionTotals().getIterations()).isEqualTo(7);
        assertThat(loaded.getBudgetOverride()).isPresent();
        assertThat(loaded.getBudgetOverride().orElseThrow().getMaxIterations()).contains(10);
        assertEquals(1, loaded.getMessages().size(), "snapshot messages must replace old ones");
    }

    @Test
    void setTotalsAndBudgetOverrideAndSnapshotMergeAreAtomicallyConsistent() throws Exception {
        // Concurrent pair writes from one thread and mergeFromSnapshot calls from another must not lose totals
        // updates, because both paths go through ConcurrentHashMap compute. This is not merely a multi-node concern:
        // in a single node the checkpoint writer thread merges messages while the thread that just finished a turn
        // flushes the pair.
        final SessionId id = new SessionId("conv-totals-stress");
        repository.save(new SessionRecord(id, "sys", List.of(), 0, null));

        final int iters = 100;
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger lastWrittenTurnCount = new java.util.concurrent.atomic.AtomicInteger(
                0);

        final Thread totalsWriter = new Thread(() -> {
            try {
                start.await();
                for (int i = 1; i <= iters; i++) {
                    repository.setTotalsAndBudgetOverride(id, SessionTotals.of(i, i * 2, TokenUsage.empty()), null);
                    lastWrittenTurnCount.set(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "totals-writer");

        final Thread merger = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < iters; i++) {
                    repository.mergeFromSnapshot(
                            SessionSnapshot.of(id, "sys", List.of(at.aimon.core.llm.Message.user("m" + i))));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "snapshot-merger");

        totalsWriter.start();
        merger.start();
        start.countDown();
        totalsWriter.join(10_000);
        merger.join(10_000);
        assertFalse(totalsWriter.isAlive());
        assertFalse(merger.isAlive());

        final SessionRecordView finalView = repository.load(id).orElseThrow();
        // The final totals must equal the last write from the totals-writer thread (mergeFromSnapshot preserves them).
        assertThat(finalView.getSessionTotals().getTurnCount()).isEqualTo(lastWrittenTurnCount.get());
    }

    // =========================================================================
    // compactionFailureCount — the compaction guard's circuit breaker
    // =========================================================================

    @Test
    void incrementCompactionFailureCountReturnsTheNewValueAndPreservesEverythingElse() {
        final SessionId id = new SessionId("conv-breaker");
        repository.save(new SessionRecord(id, "sys", List.of(at.aimon.core.llm.Message.user("hello")), 2, "agent-a"));
        repository.setTotalsAndBudgetOverride(id, SessionTotals.of(1, 3, TokenUsage.of(4, 2, 6)),
                ExecutionBudget.builder().maxTokens(1000).build());

        final int returned = repository.incrementCompactionFailureCount(id);

        assertThat(returned).isEqualTo(3);
        final SessionRecordView loaded = repository.load(id).orElseThrow();
        assertEquals(3, loaded.getCompactionFailureCount(), "the returned value must be the one that was stored");
        // The fields this primitive must not touch — each belongs to a different writer.
        assertEquals(Optional.of("agent-a"), loaded.getAgentRef(), "agentRef must be preserved");
        assertEquals(1, loaded.getMessages().size(), "messages must be preserved");
        assertEquals("sys", loaded.getSystemPrompt(), "systemPrompt must be preserved");
        assertThat(loaded.getSessionTotals().getTurnCount()).isEqualTo(1);
        assertThat(loaded.getBudgetOverride()).isPresent();
    }

    @Test
    void incrementCompactionFailureCountReturnsZeroWhenRecordAbsent() {
        // A run without a session of its own — a subagent fork, a skill fork, a scheduled routine — has no record to
        // count against. Reporting zero keeps the breaker permanently open there rather than provisioning a record
        // under a fabricated id.
        final SessionId id = new SessionId("conv-breaker-absent");

        assertThat(repository.incrementCompactionFailureCount(id)).isZero();

        assertFalse(repository.exists(id), "the increment must not provision a record");
    }

    @Test
    void resetCompactionFailureCountClearsItAndPreservesEverythingElse() {
        final SessionId id = new SessionId("conv-breaker-reset");
        repository.save(new SessionRecord(id, "sys", List.of(at.aimon.core.llm.Message.user("hello")), 5, "agent-a"));
        repository.setTotalsAndBudgetOverride(id, SessionTotals.of(2, 4, TokenUsage.empty()), null);

        repository.resetCompactionFailureCount(id);

        final SessionRecordView loaded = repository.load(id).orElseThrow();
        assertEquals(0, loaded.getCompactionFailureCount());
        assertEquals(Optional.of("agent-a"), loaded.getAgentRef(), "agentRef must be preserved");
        assertEquals(1, loaded.getMessages().size(), "messages must be preserved");
        assertEquals("sys", loaded.getSystemPrompt(), "systemPrompt must be preserved");
        assertThat(loaded.getSessionTotals().getTurnCount()).isEqualTo(2);
    }

    @Test
    void resetCompactionFailureCountOnAnAlreadyZeroCounterChangesNothing() {
        // The common case: a reset follows every successful compaction, so most calls have nothing to do.
        final SessionId id = new SessionId("conv-breaker-zero");
        repository.save(new SessionRecord(id, "sys", List.of(at.aimon.core.llm.Message.user("hello")), 0, "agent-a"));

        repository.resetCompactionFailureCount(id);

        final SessionRecordView loaded = repository.load(id).orElseThrow();
        assertEquals(0, loaded.getCompactionFailureCount());
        assertEquals(Optional.of("agent-a"), loaded.getAgentRef());
        assertEquals(1, loaded.getMessages().size());
    }

    @Test
    void resetCompactionFailureCountIsNoOpWhenRecordAbsent() {
        final SessionId id = new SessionId("conv-breaker-reset-absent");

        repository.resetCompactionFailureCount(id);

        assertFalse(repository.exists(id), "the reset must not provision a record");
    }

    @Test
    void compactionFailureCountWritersRejectNullId() {
        assertThrows(NullPointerException.class, () -> repository.incrementCompactionFailureCount(null));
        assertThrows(NullPointerException.class, () -> repository.resetCompactionFailureCount(null));
    }

    @Test
    void mergeFromSnapshotPreservesAnIncrementedFailureCount() {
        // The checkpoint writer owns the messages and the guard owns the counter; a checkpoint landing between two
        // failed compactions must not reopen the breaker.
        final SessionId id = new SessionId("conv-breaker-merge");
        repository.save(new SessionRecord(id, "sys", List.of(), 0, "agent-a"));
        repository.incrementCompactionFailureCount(id);
        repository.incrementCompactionFailureCount(id);

        repository.mergeFromSnapshot(SessionSnapshot.of(id, "sys", List.of(at.aimon.core.llm.Message.user("m"))));

        assertEquals(2, repository.load(id).orElseThrow().getCompactionFailureCount());
    }

    @Test
    void concurrentIncrementsEachCountAndEachSeeADistinctValue() throws Exception {
        // The delta is what the primitive exists for: consecutive failures are observed by different nodes across a
        // session's turns, so no writer holds a copy of the count to restate. A load-then-store version would collapse
        // these into 1, and two callers would be handed the same number.
        final SessionId id = new SessionId("conv-breaker-stress");
        repository.save(new SessionRecord(id, "sys", List.of(), 0, "agent-a"));

        final int threads = 4;
        final int perThread = 250;
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        final java.util.Set<Integer> observed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final List<Thread> workers = new java.util.ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        observed.add(repository.incrementCompactionFailureCount(id));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "breaker-incrementer-" + t);
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000);
            assertFalse(worker.isAlive());
        }

        final int total = threads * perThread;
        assertEquals(total, repository.load(id).orElseThrow().getCompactionFailureCount(),
                "every increment must land — this is the read-modify-write race the primitive removes");
        assertThat(observed).hasSize(total);
        assertThat(observed)
                .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1, total).boxed().toList());
    }
}
