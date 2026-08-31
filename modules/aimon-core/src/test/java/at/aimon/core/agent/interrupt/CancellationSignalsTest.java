package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the contract every ReAct path now shares: the check reports cancellation from either source, and it
 * <b>always</b> consumes the thread interrupt flag. The consumption is load-bearing rather than incidental — a flag
 * left live makes the next blocking wait throw {@link InterruptedException}, and the hook executor's fail-open policy
 * turns that into a successful {@code HookResult}, silently downgrading a PreTool BLOCKED into an allow.
 */
@DisplayName("CancellationSignals Tests")
class CancellationSignalsTest {

    /** Start each test from a guaranteed-clean interrupt flag, regardless of what ran earlier in this fork. */
    @BeforeEach
    void clearInterruptBefore() {
        Thread.interrupted();
    }

    /** Clear any interrupt this test left on the (pooled) JUnit worker thread so it cannot leak into the next test. */
    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("returns false when neither the signal nor the thread is interrupted")
    void falseWhenIdle() {
        assertThat(CancellationSignals.isCancelledOrInterrupted(NoopCancellationSignal.INSTANCE)).isFalse();
    }

    @Test
    @DisplayName("returns true when the cancellation signal is tripped")
    void trueWhenSignalCancelled() {
        final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

        assertThat(CancellationSignals.isCancelledOrInterrupted(coordinator.getSignal())).isTrue();
    }

    @Test
    @DisplayName("returns true when the current thread is interrupted AND consumes (clears) the interrupt flag")
    void trueWhenThreadInterruptedAndClearsFlag() {
        Thread.currentThread().interrupt();

        final boolean result = CancellationSignals.isCancelledOrInterrupted(NoopCancellationSignal.INSTANCE);

        assertThat(result).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("clears the thread interrupt flag even when it returns true via the signal")
    void clearsThreadFlagEvenWhenSignalAlreadyCancelled() {
        final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        Thread.currentThread().interrupt();

        // Short-circuit hazard: `signal.isCancelled() || Thread.interrupted()` would never evaluate the second
        // operand here and would leave the flag set. The flag read must happen unconditionally.
        assertThat(CancellationSignals.isCancelledOrInterrupted(coordinator.getSignal())).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("a second call at the same checkpoint no longer sees the thread interrupt (it was consumed)")
    void secondCallDoesNotSeeTheConsumedFlag() {
        Thread.currentThread().interrupt();

        assertThat(CancellationSignals.isCancelledOrInterrupted(NoopCancellationSignal.INSTANCE)).isTrue();
        // Why callers must evaluate once per decision point and reuse the boolean — or promote it into the signal.
        assertThat(CancellationSignals.isCancelledOrInterrupted(NoopCancellationSignal.INSTANCE)).isFalse();
    }

    @Test
    @DisplayName("rejects a null signal")
    void rejectsNullSignal() {
        assertThatThrownBy(() -> CancellationSignals.isCancelledOrInterrupted(null))
                .isInstanceOf(NullPointerException.class);
    }
}
