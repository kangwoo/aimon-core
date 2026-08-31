package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;

@DisplayName("SubagentInterrupts Tests")
class SubagentInterruptsTest {

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
        assertThat(SubagentInterrupts.isCancelledOrInterrupted(NoopCancellationSignal.INSTANCE)).isFalse();
    }

    @Test
    @DisplayName("returns true when the cancellation signal is tripped")
    void trueWhenSignalCancelled() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

        assertThat(SubagentInterrupts.isCancelledOrInterrupted(coordinator.getSignal())).isTrue();
    }

    @Test
    @DisplayName("returns true when the current thread is interrupted AND consumes (clears) the interrupt flag")
    void trueWhenThreadInterruptedAndClearsFlag() {
        Thread.currentThread().interrupt();

        boolean result = SubagentInterrupts.isCancelledOrInterrupted(NoopCancellationSignal.INSTANCE);

        assertThat(result).isTrue();
        // The helper must have cleared the flag so a pooled worker does not carry it into its next task.
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("clears the thread interrupt flag even when it returns true via the signal")
    void clearsThreadFlagEvenWhenSignalAlreadyCancelled() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        Thread.currentThread().interrupt();

        assertThat(SubagentInterrupts.isCancelledOrInterrupted(coordinator.getSignal())).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("rejects a null signal")
    void rejectsNullSignal() {
        assertThatThrownBy(() -> SubagentInterrupts.isCancelledOrInterrupted(null))
                .isInstanceOf(NullPointerException.class);
    }
}
