package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.BudgetTracker;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecord;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.StreamingAgentExecutor;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Verifies how {@link DefaultLiveSession} handles the two durable fields it owns — the cumulative
 * {@link SessionTotals} and the runtime budget override — which it hydrates from, and flushes back to, the
 * session record (§12):
 * <ul>
 * <li>(a) hydration from a pre-seeded repository is reflected in {@link LiveSessionStatus#getSessionTotals()} and
 * {@link DefaultLiveSession#getOptions()};
 * <li>(b) the end-of-turn flush accumulates correctly, and — because totals and override are written as one pair —
 * does not erase an override the session merely hydrated;
 * <li>(c) {@link DefaultLiveSession#setOptions(LiveSessionOptions)} and
 * {@link DefaultLiveSession#clearBudgetOverride()} flush out of turn;
 * <li>(d) restart simulation — a new session over the same repository restores totals and budget;
 * <li>(e) a session wired with no repository behaves as in-memory-only;
 * <li>(f) a failing repository never breaks a turn or the session.
 * </ul>
 */
@DisplayName("DefaultLiveSession — durable session state (§12)")
class DefaultLiveSessionPersistenceTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // (a) hydrate: pre-seeded totals + budget override reflected at open
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("(a) hydrate — persisted totals seed the in-memory accumulator at open")
    void hydrateRestoresTotals() {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final SessionId id = SessionId.of("hydr-totals");

        // Pre-seed: 3 turns, 7 iterations, 28 total tokens.
        repo.save(new SessionRecord(id));
        repo.setTotalsAndBudgetOverride(id, SessionTotals.of(3, 7, TokenUsage.of(10, 8, 28)), null);

        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(),
                new CapturingExecutor(newTracker()), LiveSessionOptions.defaults(), null, null, repo)) {

            final LiveSessionStatus status = session.status();
            assertThat(status.getSessionTotals().getTurnCount()).isEqualTo(3);
            assertThat(status.getSessionTotals().getIterations()).isEqualTo(7);
            assertThat(status.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(28);
        }
    }

    @Test
    @DisplayName("(a) hydrate — persisted budget override wins over opener default")
    void hydrateAppliesBudgetOverride() {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final SessionId id = SessionId.of("hydr-budget");

        repo.save(new SessionRecord(id));
        final ExecutionBudget override = ExecutionBudget.builder().maxTokens(50_000).build();
        repo.setTotalsAndBudgetOverride(id, SessionTotals.empty(), override);

        final LiveSessionOptions openerDefaults = LiveSessionOptions.builder()
                .budget(ExecutionBudget.builder().maxTokens(200_000).build()).build();
        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(),
                new CapturingExecutor(newTracker()), openerDefaults, null, null, repo)) {

            // The persisted 50 k override must win over the opener's 200 k.
            assertThat(session.getOptions().getBudget().getMaxTokens()).contains(50_000);
        }
    }

    @Test
    @DisplayName("(a) hydrate — fresh session (no record) starts with empty totals, opener budget")
    void hydrateFreshConversationStartsEmpty() {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final SessionId id = SessionId.of("hydr-fresh");

        final LiveSessionOptions openerDefaults = LiveSessionOptions.builder()
                .budget(ExecutionBudget.builder().maxTokens(100_000).build()).build();
        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(),
                new CapturingExecutor(newTracker()), openerDefaults, null, null, repo)) {

            assertThat(session.status().getSessionTotals().getTurnCount()).isZero();
            // Opener budget is not overridden when there is no record.
            assertThat(session.getOptions().getBudget().getMaxTokens()).contains(100_000);
        }
    }

    // -------------------------------------------------------------------------
    // (b) end-of-turn flush
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("(b) the end-of-turn flush writes the correct cumulative value")
    void flushOnTurnEnd() throws Exception {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final SessionId id = SessionId.of("persist-totals");
        // Provision the record so the pair write is not a documented no-op.
        repo.save(new SessionRecord(id));

        final BudgetTracker tracker = newTracker();
        tracker.recordIteration();
        tracker.recordIteration();
        tracker.recordTokens(TokenUsage.of(10, 5, 15));
        final CapturingExecutor executor = new CapturingExecutor(tracker);

        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(), executor,
                LiveSessionOptions.defaults(), null, null, repo)) {

            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            executor.completeNext(makeResult(id));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }

        // After session close the repo must hold the accumulated totals.
        final SessionRecordView stored = repo.load(id).orElseThrow();
        assertThat(stored.getSessionTotals().getTurnCount()).isEqualTo(1);
        assertThat(stored.getSessionTotals().getIterations()).isEqualTo(2);
        assertThat(stored.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(15);
    }

    @Test
    @DisplayName("(b) totals accumulate correctly across two sequential turns")
    void persistTotalsAccumulatesAcrossTurns() throws Exception {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final SessionId id = SessionId.of("persist-totals-2");
        repo.save(new SessionRecord(id));

        final BudgetTracker t1 = newTracker();
        t1.recordIteration();
        t1.recordTokens(TokenUsage.of(10, 5, 15));
        final BudgetTracker t2 = newTracker();
        t2.recordIteration();
        t2.recordIteration();
        t2.recordTokens(TokenUsage.of(20, 10, 30));
        final CapturingExecutor executor = new CapturingExecutor(t1, t2);

        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(), executor,
                LiveSessionOptions.defaults(), null, null, repo)) {

            final CompletionStage<?> first = session.submitAsync("a", e -> {
            });
            executor.completeNext(makeResult(id));
            first.toCompletableFuture().get(1, TimeUnit.SECONDS);

            final CompletionStage<?> second = session.submitAsync("b", e -> {
            });
            executor.completeNext(makeResult(id));
            second.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }

        final SessionRecordView stored = repo.load(id).orElseThrow();
        assertThat(stored.getSessionTotals().getTurnCount()).isEqualTo(2);
        assertThat(stored.getSessionTotals().getIterations()).isEqualTo(3);
        assertThat(stored.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(45);
    }

    @Test
    @DisplayName("(b) the end-of-turn flush preserves a budget override the session merely hydrated")
    void flushOnTurnEndPreservesHydratedBudgetOverride() throws Exception {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final SessionId id = SessionId.of("flush-keeps-override");
        repo.save(new SessionRecord(id));
        repo.setTotalsAndBudgetOverride(id, SessionTotals.empty(), ExecutionBudget.builder().maxTokens(50_000).build());

        final BudgetTracker tracker = newTracker();
        tracker.recordIteration();
        tracker.recordTokens(TokenUsage.of(1, 1, 2));
        final CapturingExecutor executor = new CapturingExecutor(tracker);

        // This turn never touches the budget. The two fields are written as one pair, so a session that did not
        // remember the override it hydrated would write a null override here and silently erase it.
        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(), executor,
                LiveSessionOptions.defaults(), null, null, repo)) {

            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            executor.completeNext(makeResult(id));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }

        final SessionRecordView stored = repo.load(id).orElseThrow();
        assertThat(stored.getSessionTotals().getTurnCount()).isEqualTo(1);
        assertThat(stored.getBudgetOverride()).isPresent();
        assertThat(stored.getBudgetOverride().orElseThrow().getMaxTokens()).contains(50_000);
    }

    // -------------------------------------------------------------------------
    // (c) out-of-turn flushes: setOptions / clearBudgetOverride
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("(c) setOptions flushes the new budget override to the repository")
    void setOptionsFlushesBudgetOverride() {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final SessionId id = SessionId.of("persist-budget");
        repo.save(new SessionRecord(id));

        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(),
                new CapturingExecutor(newTracker()), LiveSessionOptions.defaults(), null, null, repo)) {

            final ExecutionBudget newBudget = ExecutionBudget.builder().maxTokens(75_000).build();
            session.setOptions(LiveSessionOptions.builder().budget(newBudget).build());
        }

        final SessionRecordView stored = repo.load(id).orElseThrow();
        assertThat(stored.getBudgetOverride()).isPresent();
        assertThat(stored.getBudgetOverride().orElseThrow().getMaxTokens()).contains(75_000);
    }

    @Test
    @DisplayName("(c) clearBudgetOverride reverts to the opener default and erases the persisted override")
    void clearBudgetOverrideRevertsToOpenerDefault() {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final SessionId id = SessionId.of("clear-budget");
        repo.save(new SessionRecord(id));

        final LiveSessionOptions opener = LiveSessionOptions.builder()
                .budget(ExecutionBudget.builder().maxTokens(100_000).build()).build();

        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(),
                new CapturingExecutor(newTracker()), opener, null, null, repo)) {

            // Install a runtime override, then clear it.
            session.setOptions(
                    LiveSessionOptions.builder().budget(ExecutionBudget.builder().maxTokens(50_000).build()).build());
            assertThat(repo.load(id).orElseThrow().getBudgetOverride()).isPresent();

            session.clearBudgetOverride();

            // In-memory budget reverts to the opener default; the persisted override is erased.
            assertThat(session.getOptions().getBudget().getMaxTokens()).contains(100_000);
            assertThat(repo.load(id).orElseThrow().getBudgetOverride()).isEmpty();
        }

        // Restart over the same repo with the same opener default: no override is hydrated.
        try (DefaultLiveSession restarted = new DefaultLiveSession(id, createContext(),
                new CapturingExecutor(newTracker()), opener, null, null, repo)) {
            assertThat(restarted.getOptions().getBudget().getMaxTokens()).contains(100_000);
        }
    }

    // -------------------------------------------------------------------------
    // (d) restart simulation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("(d) restart — new session over same repo restores totals and budget override")
    void restartRestoresTotalsAndBudget() throws Exception {
        final InMemorySessionRecordStore repo = new InMemorySessionRecordStore();
        final SessionId id = SessionId.of("restart-sim");
        repo.save(new SessionRecord(id));

        final BudgetTracker tracker = newTracker();
        tracker.recordIteration();
        tracker.recordTokens(TokenUsage.of(5, 3, 8));
        final CapturingExecutor executor1 = new CapturingExecutor(tracker);

        // First session: complete a turn and set a budget override.
        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(), executor1,
                LiveSessionOptions.defaults(), null, null, repo)) {

            final CompletionStage<?> stage = session.submitAsync("hello", e -> {
            });
            executor1.completeNext(makeResult(id));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);

            session.setOptions(
                    LiveSessionOptions.builder().budget(ExecutionBudget.builder().maxTokens(30_000).build()).build());
        }

        // Second session (restart): same repo, fresh session object. Pre-load the turn's tracker so the
        // post-restart turn folds a known delta on top of the hydrated totals.
        final BudgetTracker tracker2 = newTracker();
        tracker2.recordIteration();
        tracker2.recordTokens(TokenUsage.of(2, 1, 3));
        final CapturingExecutor executor2 = new CapturingExecutor(tracker2);
        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(), executor2,
                LiveSessionOptions.defaults(), null, null, repo)) {

            final LiveSessionStatus status = session.status();
            assertThat(status.getSessionTotals().getTurnCount()).isEqualTo(1);
            assertThat(status.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(8);
            // Budget override from the first session must be restored.
            assertThat(session.getOptions().getBudget().getMaxTokens()).contains(30_000);

            // A turn after restart must fold ON TOP of the hydrated value — the accumulator is seeded
            // absolutely from the persisted totals, so this proves there is no double-count (would give
            // turnCount 3 / 16 tokens) and no reset (would give turnCount 1 / 3 tokens). See design §9.
            final CompletionStage<?> stage = session.submitAsync("again", e -> {
            });
            executor2.completeNext(makeResult(id));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);

            final LiveSessionStatus afterTurn = session.status();
            assertThat(afterTurn.getSessionTotals().getTurnCount()).isEqualTo(2);
            assertThat(afterTurn.getSessionTotals().getIterations()).isEqualTo(2);
            assertThat(afterTurn.getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(11);
        }
    }

    // -------------------------------------------------------------------------
    // (e) no repository wired behaves as in-memory-only
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("(e) a session with no repository wired accumulates in memory only")
    void noRepositoryBehavesAsInMemoryOnly() throws Exception {
        final SessionId id = SessionId.of("noop-legacy");
        final BudgetTracker tracker = newTracker();
        tracker.recordIteration();
        tracker.recordTokens(TokenUsage.of(3, 1, 4));
        final CapturingExecutor executor = new CapturingExecutor(tracker);

        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(), executor,
                LiveSessionOptions.defaults())) {

            // Nothing to hydrate from: starts at empty totals.
            assertThat(session.status().getSessionTotals().getTurnCount()).isZero();

            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            executor.completeNext(makeResult(id));
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);

            // In-memory accumulation still works.
            assertThat(session.status().getSessionTotals().getTurnCount()).isEqualTo(1);
            assertThat(session.status().getSessionTotals().getTokenUsage().getTotalTokens()).isEqualTo(4);
        }
    }

    // -------------------------------------------------------------------------
    // (f) a failing repository does not break a turn or the session
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("(f) a failing end-of-turn flush is swallowed — the turn completes normally")
    void failingFlushDoesNotBreakTurn() throws Exception {
        final SessionId id = SessionId.of("persist-err-totals");
        final BudgetTracker tracker = newTracker();
        tracker.recordIteration();
        tracker.recordTokens(TokenUsage.of(1, 1, 2));
        final CapturingExecutor executor = new CapturingExecutor(tracker);

        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(), executor,
                LiveSessionOptions.defaults(), null, null, new FlushFailingRepository())) {

            final CompletionStage<?> stage = session.submitAsync("hi", e -> {
            });
            executor.completeNext(makeResult(id));

            // The stage must complete without exception despite the failing flush.
            assertThatCode(() -> stage.toCompletableFuture().get(1, TimeUnit.SECONDS)).doesNotThrowAnyException();

            // The in-memory accumulator is still updated.
            assertThat(session.status().getSessionTotals().getTurnCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("(f) a failing out-of-turn flush is swallowed — setOptions completes normally")
    void failingFlushDoesNotBreakSetOptions() {
        final SessionId id = SessionId.of("persist-err-budget");

        try (DefaultLiveSession session = new DefaultLiveSession(id, createContext(),
                new CapturingExecutor(newTracker()), LiveSessionOptions.defaults(), null, null,
                new FlushFailingRepository())) {

            final LiveSessionOptions newOpts = LiveSessionOptions.builder()
                    .budget(ExecutionBudget.builder().maxTokens(1000).build()).build();
            // Must not throw even though the flush fails.
            assertThatCode(() -> session.setOptions(newOpts)).doesNotThrowAnyException();
            // The in-memory options are still updated.
            assertThat(session.getOptions().getBudget().getMaxTokens()).contains(1000);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * A repository whose pair write always fails. Everything else behaves normally — hydration included — so the
     * failure the tests observe is isolated to the flush.
     */
    private static final class FlushFailingRepository extends InMemorySessionRecordStore {

        @Override
        public void setTotalsAndBudgetOverride(SessionId sessionId, SessionTotals totals,
                ExecutionBudget budgetOverride) {
            throw new RuntimeException("disk full");
        }
    }

    private BudgetTracker newTracker() {
        return new BudgetTracker(ExecutionBudget.unlimited(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static OrcaAgentExecutionResult makeResult(SessionId sessionId) {
        return OrcaAgentExecutionResult.success("done", SessionSnapshot.of(sessionId),
                ExecutionMetadata.simple(Duration.ZERO, Instant.EPOCH, Instant.EPOCH));
    }

    private OrcaAgentRuntime createContext() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:test-persistence"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    /**
     * Minimal streaming executor stub: publishes a {@link DefaultInterruptCoordinator} and a caller-supplied
     * {@link BudgetTracker}, then parks on a future released via {@link #completeNext}. Mirrors the
     * {@code CapturingExecutor} in {@link DefaultLiveSessionStatusTest}.
     */
    private static final class CapturingExecutor
            implements
                AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult>,
                StreamingAgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> {

        private final List<BudgetTracker> trackers;
        private final List<CompletableFuture<OrcaAgentExecutionResult>> inflight = new ArrayList<>();
        private int invocations;

        CapturingExecutor(BudgetTracker... trackers) {
            this.trackers = List.of(trackers);
        }

        @Override
        public OrcaAgentExecutionResult execute(OrcaAgentRuntime ctx, OrcaAgentExecutionRequest req) {
            throw new UnsupportedOperationException("streaming only");
        }

        @Override
        public java.util.concurrent.Flow.Publisher<AgentExecutionEvent> events(OrcaAgentRuntime ctx,
                OrcaAgentExecutionRequest req) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public synchronized CompletionStage<OrcaAgentExecutionResult> executeAsync(OrcaAgentRuntime ctx,
                OrcaAgentExecutionRequest req, Consumer<AgentExecutionEvent> listener) {
            invocations++;
            final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
            final BudgetTracker tracker = trackers.get(Math.min(invocations - 1, trackers.size() - 1));
            req.getInterruptObserver().accept(coordinator);
            req.getBudgetObserver().accept(tracker);
            final CompletableFuture<OrcaAgentExecutionResult> future = new CompletableFuture<>();
            inflight.add(future);
            future.whenComplete((r, t) -> coordinator.close());
            return future;
        }

        synchronized void completeNext(OrcaAgentExecutionResult result) {
            for (final CompletableFuture<OrcaAgentExecutionResult> f : inflight) {
                if (!f.isDone()) {
                    f.complete(result);
                    return;
                }
            }
        }
    }
}
