package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ModelContextWindowRegistry;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Integration test for design §11.2 scenario H (AUTO/MANUAL concurrency).
 *
 * <p>
 * Verifies the per-{@link SessionId} {@link java.util.concurrent.locks.ReentrantLock} inside
 * {@link DefaultCompactionGuard}: when one thread is mid-compaction on a session, a second AUTO trigger on the same
 * session must short-circuit to {@code NONE("concurrent compaction in progress")} rather than running a second
 * concurrent engine call. Concurrent compactions on <i>different</i> sessions still run in parallel — the lock is
 * per-session, not global.
 *
 * <p>
 * Scope: only AUTO+AUTO contention is exercised here. {@code MANUAL} triggers bypass the guard entirely (the
 * {@code /compact} slash command calls the engine directly), so MANUAL+AUTO and MANUAL+MANUAL races are intentionally
 * out of scope for this scenario.
 *
 * <p>
 * Test doubles: a {@link BlockingEngine} that records call count and blocks inside {@code compact} on a release latch
 * so concurrency is deterministic without depending on real LLM timing.
 */
class CompactionConcurrencyScenarioHTest {

    private ExecutorService executor;
    private HookRegistry hookRegistry;
    private Environment environment;
    private ModelContextWindowRegistry modelContextWindowRegistry;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        hookRegistry = new DefaultHookRegistry();
        environment = Environment.createDefault();
        // Tiny limits so the fixed-cost stub estimator easily clears the auto-compact threshold.
        modelContextWindowRegistry = InMemoryModelContextWindowRegistry.builder()
                .defaultLimits(ModelContextLimits.builder().contextWindow(1000).reservedOutputTokens(200)
                        .autoCompactBuffer(100).warningBuffer(100).blockingBuffer(50).build())
                .build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void secondAutoTriggerOnSameSessionShortCircuitsToNoneWhileFirstHoldsLock() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        DefaultCompactionGuard guard = newGuard(engine);
        TranscriptBuffer memory = freshMemoryAboveThreshold();

        Future<CompactionDecision> first = executor
                .submit(() -> guard.maybeCompact(memory, model(), hookRegistry, environment));
        assertThat(engine.entered.await(2, TimeUnit.SECONDS)).as("engine should be entered by first thread").isTrue();

        // Second concurrent caller on the same session sees the lock held → NONE("concurrent compaction in progress").
        CompactionDecision second = guard.maybeCompact(memory, model(), hookRegistry, environment);
        assertThat(second.getAction()).isEqualTo(CompactionDecision.Action.NONE);
        assertThat(second.getReason()).contains("concurrent compaction in progress");

        engine.release.countDown();
        CompactionDecision firstResult = first.get(2, TimeUnit.SECONDS);
        assertThat(firstResult.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        // Engine was invoked exactly once across the race — the lock served its purpose.
        assertThat(engine.callCount.get()).isEqualTo(1);
    }

    @Test
    void concurrentCompactionsOnDifferentSessionsRunInParallel() throws Exception {
        BlockingEngine engine = new BlockingEngine(2);
        DefaultCompactionGuard guard = newGuard(engine);
        TranscriptBuffer memoryA = freshMemoryAboveThreshold();
        TranscriptBuffer memoryB = freshMemoryAboveThreshold();

        Future<CompactionDecision> futureA = executor
                .submit(() -> guard.maybeCompact(memoryA, model(), hookRegistry, environment));
        Future<CompactionDecision> futureB = executor
                .submit(() -> guard.maybeCompact(memoryB, model(), hookRegistry, environment));

        // Both threads must enter the engine before either is released — proves the locks are independent.
        assertThat(engine.entered.await(2, TimeUnit.SECONDS)).as("both engines should be entered concurrently")
                .isTrue();

        engine.release.countDown();
        engine.release.countDown();
        assertThat(futureA.get(2, TimeUnit.SECONDS).getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(futureB.get(2, TimeUnit.SECONDS).getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(engine.callCount.get()).isEqualTo(2);
    }

    @Test
    void afterFirstCompactionReleasesLockSecondCallerProceeds() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        DefaultCompactionGuard guard = newGuard(engine);
        TranscriptBuffer memory = freshMemoryAboveThreshold();

        Future<CompactionDecision> first = executor
                .submit(() -> guard.maybeCompact(memory, model(), hookRegistry, environment));
        assertThat(engine.entered.await(2, TimeUnit.SECONDS)).isTrue();
        engine.release.countDown();
        assertThat(first.get(2, TimeUnit.SECONDS).getAction()).isEqualTo(CompactionDecision.Action.COMPACT);

        // Lock has been released; second call (after replaceWith) below threshold → NONE without re-running engine.
        CompactionDecision second = guard.maybeCompact(memory, model(), hookRegistry, environment);
        assertThat(second.getAction()).isEqualTo(CompactionDecision.Action.NONE);
        // Engine should NOT have been invoked a second time — the post-compaction memory is small.
        assertThat(engine.callCount.get()).isEqualTo(1);
    }

    private DefaultCompactionGuard newGuard(CompactionEngine engine) {
        return new DefaultCompactionGuard(engine, modelContextWindowRegistry, new HighFixedTokenEstimator());
    }

    private static LlmModel model() {
        return LlmModel.builder().name("test-model").build();
    }

    private static TranscriptBuffer freshMemoryAboveThreshold() {
        TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("hello");
        memory.addAssistantMessage("hi");
        return memory;
    }

    /**
     * Returns a high token count for non-empty conversations so the guard always trips the auto-compact threshold,
     * and {@code 0} for the post-compaction marker pair so the second-call test sees the guard return {@code NONE}.
     */
    private static final class HighFixedTokenEstimator implements TokenEstimator {
        @Override
        public int estimate(String systemPrompt, List<Message> messages) {
            if (messages == null || messages.isEmpty()) {
                return 0;
            }
            for (Message m : messages) {
                if (m.getContent() != null && m.getContent().contains(CompactBoundary.BOUNDARY_OPEN_PREFIX)) {
                    return 0;
                }
            }
            return 900;
        }

        @Override
        public int estimateMessage(Message message) {
            return 0;
        }

        @Override
        public int estimateText(String text) {
            return 0;
        }
    }

    /**
     * Recording {@link CompactionEngine} that blocks inside {@code compact} until {@code release} is counted down.
     * {@code entered} is a single latch shared across all callers — the test arms it with the expected number of
     * concurrent entries.
     */
    private static final class BlockingEngine implements CompactionEngine {
        final AtomicInteger callCount = new AtomicInteger();
        final CountDownLatch entered;
        final CountDownLatch release;

        BlockingEngine() {
            this(1);
        }

        BlockingEngine(int concurrentEntries) {
            this.entered = new CountDownLatch(concurrentEntries);
            this.release = new CountDownLatch(concurrentEntries);
        }

        @Override
        public CompactionResult compact(CompactionRequest request) {
            callCount.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("blocking engine never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for release", e);
            }
            // Splice the canonical boundary+summary pair into memory so subsequent guard evaluations see the post-
            // compaction state (the test estimator returns 0 for messages that contain the boundary marker).
            String sessionUuid = java.util.UUID.randomUUID().toString();
            request.getTranscriptBuffer().replaceWith(
                    List.of(CompactBoundary.boundaryMessage(sessionUuid, request.getTrigger(), 0, 0, List.of()),
                            CompactBoundary.summaryMessage(sessionUuid, "stub")));
            CompactionMetadata metadata = CompactionMetadata.builder().trigger(request.getTrigger())
                    .startedAt(Instant.now()).completedAt(Instant.now()).build();
            return CompactionResult.success("stub", metadata);
        }
    }
}
