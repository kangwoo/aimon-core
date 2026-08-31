package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.llm.TokenUsage;

/**
 * Unit tests for the {@link LiveSessionStatus} value object and the {@link LiveSession#status()} interface default.
 */
@DisplayName("LiveSessionStatus value object + LiveSession#status() default")
class LiveSessionStatusTest {

    @Test
    @DisplayName("builder defaults to an idle-like view with empty optionals")
    void builderDefaults() {
        final LiveSessionStatus status = LiveSessionStatus.builder().sessionId(SessionId.of("c1"))
                .phase(LiveSessionStatus.Phase.IDLE).build();

        assertThat(status.getSessionId()).isEqualTo(SessionId.of("c1"));
        assertThat(status.getPhase()).isEqualTo(LiveSessionStatus.Phase.IDLE);
        assertThat(status.isInterruptible()).isFalse();
        assertThat(status.getQueueDepth()).isZero();
        assertThat(status.getOptions()).isEmpty();
        assertThat(status.getTurnProgress()).isEmpty();
        assertThat(status.getSessionTotals().getTurnCount()).isZero();
        assertThat(status.getSessionTotals().getTokenUsage().getTotalTokens()).isZero();
    }

    @Test
    @DisplayName("builder populates every field")
    void builderFull() {
        final LiveSessionOptions options = LiveSessionOptions.defaults();
        final LiveSessionStatus.TurnProgress progress = LiveSessionStatus.TurnProgress.of(3, TokenUsage.of(10, 5, 15),
                Duration.ofSeconds(2), ExecutionBudget.builder().maxTokens(100).build());

        final SessionTotals totals = SessionTotals.of(5, 12, TokenUsage.of(100, 50, 150));
        final LiveSessionStatus status = LiveSessionStatus.builder().sessionId(SessionId.of("c2"))
                .phase(LiveSessionStatus.Phase.RUNNING).interruptible(true).queueDepth(4).options(options)
                .turnProgress(progress).sessionTotals(totals).build();

        assertThat(status.getPhase()).isEqualTo(LiveSessionStatus.Phase.RUNNING);
        assertThat(status.isInterruptible()).isTrue();
        assertThat(status.getQueueDepth()).isEqualTo(4);
        assertThat(status.getOptions()).contains(options);
        assertThat(status.getTurnProgress()).containsSame(progress);
        assertThat(status.getSessionTotals()).isSameAs(totals);
    }

    @Test
    @DisplayName("builder rejects a missing sessionId or phase")
    void builderRequiresFields() {
        assertThatNullPointerException()
                .isThrownBy(() -> LiveSessionStatus.builder().phase(LiveSessionStatus.Phase.IDLE).build());
        assertThatNullPointerException()
                .isThrownBy(() -> LiveSessionStatus.builder().sessionId(SessionId.of("c")).build());
    }

    @Test
    @DisplayName("TurnProgress.of exposes its counters + budget and rejects null usage / elapsed / budget")
    void turnProgressFactory() {
        final ExecutionBudget budget = ExecutionBudget.builder().maxTokens(50).build();
        final LiveSessionStatus.TurnProgress p = LiveSessionStatus.TurnProgress.of(2, TokenUsage.of(1, 1, 2),
                Duration.ofMillis(500), budget);

        assertThat(p.getIterations()).isEqualTo(2);
        assertThat(p.getTokenUsage().getTotalTokens()).isEqualTo(2);
        assertThat(p.getElapsed()).isEqualTo(Duration.ofMillis(500));
        assertThat(p.getBudget().getMaxTokens()).contains(50);

        assertThatNullPointerException()
                .isThrownBy(() -> LiveSessionStatus.TurnProgress.of(1, null, Duration.ZERO, budget));
        assertThatNullPointerException()
                .isThrownBy(() -> LiveSessionStatus.TurnProgress.of(1, TokenUsage.empty(), null, budget));
        assertThatNullPointerException()
                .isThrownBy(() -> LiveSessionStatus.TurnProgress.of(1, TokenUsage.empty(), Duration.ZERO, null));
    }

    @Test
    @DisplayName("SessionTotals.empty/of/plusTurn accumulate turn count, iterations and tokens")
    void conversationTotalsAccumulation() {
        assertThat(SessionTotals.empty().getTurnCount()).isZero();
        assertThat(SessionTotals.empty().getIterations()).isZero();
        assertThat(SessionTotals.empty().getTokenUsage().getTotalTokens()).isZero();

        final SessionTotals afterTwo = SessionTotals.empty().plusTurn(2, TokenUsage.of(10, 5, 15)).plusTurn(3,
                TokenUsage.of(20, 10, 30));

        assertThat(afterTwo.getTurnCount()).isEqualTo(2);
        assertThat(afterTwo.getIterations()).isEqualTo(5);
        assertThat(afterTwo.getTokenUsage().getTotalTokens()).isEqualTo(45);

        assertThatNullPointerException().isThrownBy(() -> SessionTotals.of(1, 1, null));
        assertThatNullPointerException().isThrownBy(() -> SessionTotals.empty().plusTurn(1, null));
    }

    @Test
    @DisplayName("LiveSession#status() default returns a minimal IDLE status")
    void defaultStatusIsIdle() {
        final LiveSession session = new StubSession();

        final LiveSessionStatus status = session.status();

        assertThat(status.getSessionId()).isEqualTo(SessionId.of("stub-session"));
        assertThat(status.getPhase()).isEqualTo(LiveSessionStatus.Phase.IDLE);
        assertThat(status.isInterruptible()).isFalse();
        assertThat(status.getQueueDepth()).isZero();
        assertThat(status.getOptions()).isEmpty();
        assertThat(status.getTurnProgress()).isEmpty();
        assertThat(status.getSessionTotals().getTurnCount()).isZero();
    }

    /** Minimal LiveSession stub that relies on the default {@link LiveSession#status()} implementation. */
    private static final class StubSession implements LiveSession {
        private final SessionId id = SessionId.of("stub-session");

        @Override
        public SessionId getSessionId() {
            return id;
        }

        @Override
        public AgentExecutionResult submit(String input, SubmitOptions submitOptions) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public CompletionStage<AgentExecutionResult> submitAsync(String input, SubmitOptions submitOptions,
                Consumer<AgentExecutionEvent> listener) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public SubmitOutcome offerAsync(String input, SubmitOptions submitOptions,
                Consumer<AgentExecutionEvent> listener) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
