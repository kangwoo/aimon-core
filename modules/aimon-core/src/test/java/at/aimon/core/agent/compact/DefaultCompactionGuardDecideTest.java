package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.ContextPolicy;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * {@link DefaultCompactionGuard#decide}: the guard's ladder, lock and circuit breaker over a view the caller computed,
 * with the compaction left to the caller.
 */
class DefaultCompactionGuardDecideTest {

    private static final LlmModel MODEL = LlmModel.builder().name("tiny").build();
    private static final SessionId SESSION = SessionId.of("s-decide");

    /** Auto-compact at 1200 estimated tokens, warning at 1000, blocking at 1400. */
    private static final ModelContextLimits TINY = ModelContextLimits.builder().contextWindow(2000)
            .reservedOutputTokens(500).autoCompactRatio(0.8).warningBuffer(200).blockingBuffer(100).build();

    private final InMemoryCompactionFailureStore failures = new InMemoryCompactionFailureStore();
    @SuppressWarnings("deprecation")
    private final DefaultCompactionGuard guard = new DefaultCompactionGuard(request -> {
        throw new AssertionError("decide() must not call the guard's own engine");
    }, InMemoryModelContextWindowRegistry.builder().defaultLimits(TINY).build(), new HeuristicTokenEstimator(),
            DefaultCompactionGuard.DEFAULT_MAX_CONSECUTIVE_FAILURES,
            DefaultCompactionGuard.DEFAULT_MAX_TRACKED_SESSIONS, failures);

    private static List<Message> viewOf(int chars) {
        return List.of(Message.user("x".repeat(chars)));
    }

    private static CompactionResult success() {
        final Instant now = Instant.now();
        return CompactionResult.success("s",
                CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).startedAt(now).completedAt(now).build());
    }

    @Test
    void theLadderIsTheGuardsAndTheCompactionIsTheCallers() {
        final List<Boolean> forcedFlags = new ArrayList<>();

        assertThat(guard.decide(SESSION, "sys", viewOf(100), MODEL, false, forced -> {
            forcedFlags.add(forced);
            return success();
        }).getAction()).isEqualTo(CompactionDecision.Action.NONE);
        assertThat(guard.decide(SESSION, "sys", viewOf(3700), MODEL, false, forced -> success()).getAction())
                .as("warning band").isEqualTo(CompactionDecision.Action.WARN);
        assertThat(guard.decide(SESSION, "sys", viewOf(3700), MODEL, true, forced -> {
            forcedFlags.add(forced);
            return success();
        }).getAction()).as("budget-forced compacts from the warning band").isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(guard.decide(SESSION, "sys", viewOf(5000), MODEL, false, forced -> {
            forcedFlags.add(forced);
            return success();
        }).getAction()).as("blocking limit").isEqualTo(CompactionDecision.Action.COMPACT);

        assertThat(forcedFlags).containsExactly(false, true);
    }

    @Test
    @SuppressWarnings("deprecation")
    void anAgentsContextPolicyRatioMovesTheAutoThreshold() {
        // The policy's 0.6 puts auto at 900 (warning 700) on the same model, before the model's own 0.8 (1200).
        final DefaultCompactionGuard policyGuard = new DefaultCompactionGuard(request -> {
            throw new AssertionError("decide() must not call the guard's own engine");
        }, ContextPolicy.builder().autoCompactRatio(0.6).build()
                .applyTo(InMemoryModelContextWindowRegistry.builder().defaultLimits(TINY).build()),
                new HeuristicTokenEstimator(), DefaultCompactionGuard.DEFAULT_MAX_CONSECUTIVE_FAILURES,
                DefaultCompactionGuard.DEFAULT_MAX_TRACKED_SESSIONS, new InMemoryCompactionFailureStore());
        final List<Boolean> forcedFlags = new ArrayList<>();

        // 3700 chars sit between the policy's threshold (900) and the model's own (1200).
        assertThat(guard.decide(SESSION, "sys", viewOf(3700), MODEL, false, forced -> success()).getAction())
                .as("without the policy: still the warning band").isEqualTo(CompactionDecision.Action.WARN);
        assertThat(policyGuard.decide(SESSION, "sys", viewOf(3700), MODEL, false, forced -> {
            forcedFlags.add(forced);
            return success();
        }).getAction()).as("with the policy: auto compaction").isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(forcedFlags).as("an ordinary auto pass, not a forced one").containsExactly(false);
        assertThat(policyGuard.decide(SESSION, "sys", viewOf(100), MODEL, false, forced -> success()).getAction())
                .isEqualTo(CompactionDecision.Action.NONE);
    }

    @Test
    void aThrowingCompactorIsAFailureThatCountsTowardTheBreaker() {
        final CompactionDecision decision = guard.decide(SESSION, "sys", viewOf(4300), MODEL, false, forced -> {
            throw new IllegalStateException("boom");
        });

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(decision.getCompactionResult())
                .hasValueSatisfying(result -> assertThat(result.isFailure()).isTrue());
        assertThat(failures.get(SESSION)).isEqualTo(1);
    }

    @Test
    void aSummaryCancelledWithItsExecutionDoesNotMoveTheBreaker() {
        // EE-95: the execution was interrupted; that says nothing about whether compaction works.
        final Instant now = Instant.now();
        final CompactionResult cancelled = CompactionResult.failure(
                new at.aimon.core.llm.exception.LlmCallCancelledException("summary call aborted by cancellation"),
                CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).startedAt(now).completedAt(now).build());

        final CompactionDecision auto = guard.decide(SESSION, "sys", viewOf(4300), MODEL, false, forced -> cancelled);
        final CompactionDecision blocking = guard.decide(SESSION, "sys", viewOf(5000), MODEL, false,
                forced -> cancelled);

        assertThat(auto.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        // Over the blocking limit the answer is BLOCK, which both executors read as the interrupt it came from.
        assertThat(blocking.getAction()).isEqualTo(CompactionDecision.Action.BLOCK);
        assertThat(failures.get(SESSION)).isZero();
    }

    @Test
    void aConcurrentDecisionOnTheSameSessionAnswersNone() throws Exception {
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> guard.decide(SESSION, "sys", viewOf(4300), MODEL, false, forced -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success();
        }));
        holder.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        final AtomicReference<CompactionDecision> second = new AtomicReference<>(
                guard.decide(SESSION, "sys", viewOf(4300), MODEL, false, forced -> success()));
        release.countDown();
        holder.join(5000);

        assertThat(second.get().getAction()).isEqualTo(CompactionDecision.Action.NONE);
        assertThat(second.get().getReason()).contains("concurrent");
    }
}
