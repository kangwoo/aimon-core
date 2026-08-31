package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NoopCancellationSignal Tests")
class NoopCancellationSignalTest {

    @Test
    @DisplayName("singleton INSTANCE never reports cancelled")
    void isNeverCancelled() {
        assertThat(NoopCancellationSignal.INSTANCE.isCancelled()).isFalse();
        assertThat(NoopCancellationSignal.INSTANCE.getReason()).isEmpty();
    }

    @Test
    @DisplayName("checkpoint is always safe")
    void checkpointNeverThrows() {
        assertThatCode(NoopCancellationSignal.INSTANCE::checkpoint).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("registered listeners are never invoked")
    void listenersAreNeverFired() {
        AtomicInteger invocations = new AtomicInteger();

        NoopCancellationSignal.INSTANCE.onCancel(invocations::incrementAndGet);

        assertThat(invocations.get()).isZero();
    }

    @Test
    @DisplayName("onCancel rejects null listener")
    void onCancelRejectsNull() {
        assertThatThrownBy(() -> NoopCancellationSignal.INSTANCE.onCancel(null))
                .isInstanceOf(NullPointerException.class);
    }
}
