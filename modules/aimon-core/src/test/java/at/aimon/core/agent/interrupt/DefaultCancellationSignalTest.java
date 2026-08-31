package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultCancellationSignal Tests")
class DefaultCancellationSignalTest {

    @Test
    @DisplayName("fresh signal is not cancelled and carries no reason")
    void freshSignalIsNotCancelled() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();

        assertThat(signal.isCancelled()).isFalse();
        assertThat(signal.getReason()).isEmpty();
        assertThatCode(signal::checkpoint).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("trip flips state exactly once and returns false on subsequent calls")
    void tripIsIdempotent() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();

        assertThat(signal.trip(InterruptReason.USER_SIGINT)).isTrue();
        assertThat(signal.isCancelled()).isTrue();
        assertThat(signal.getReason()).contains(InterruptReason.USER_SIGINT);

        // Second trip reports already-tripped and does not overwrite the reason.
        assertThat(signal.trip(InterruptReason.BUDGET_EXCEEDED)).isFalse();
        assertThat(signal.getReason()).contains(InterruptReason.USER_SIGINT);
    }

    @Test
    @DisplayName("checkpoint throws CancelledExecutionException once tripped")
    void checkpointThrowsWhenCancelled() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();
        signal.trip(InterruptReason.NOW_PRIORITY_INPUT);

        assertThatThrownBy(signal::checkpoint).isInstanceOf(CancelledExecutionException.class).matches(ex -> {
            CancelledExecutionException cancelled = (CancelledExecutionException) ex;
            return cancelled.getReason() == InterruptReason.NOW_PRIORITY_INPUT;
        });
    }

    @Test
    @DisplayName("listeners registered before trip fire in registration order")
    void listenersFireInOrder() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();
        List<Integer> fired = new ArrayList<>();

        signal.onCancel(() -> fired.add(1));
        signal.onCancel(() -> fired.add(2));
        signal.onCancel(() -> fired.add(3));

        assertThat(fired).isEmpty();
        signal.trip(InterruptReason.USER_SIGINT);
        assertThat(fired).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("onCancel returns a Registration whose remove() deregisters the listener before trip")
    void registrationRemovesListenerBeforeTrip() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();
        AtomicInteger invocations = new AtomicInteger();

        CancellationSignal.Registration registration = signal.onCancel(invocations::incrementAndGet);
        registration.remove();

        signal.trip(InterruptReason.USER_SIGINT);
        assertThat(invocations.get()).isZero();
    }

    @Test
    @DisplayName("remove() is idempotent and safe to call after trip")
    void registrationRemoveIsIdempotent() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();
        AtomicInteger invocations = new AtomicInteger();

        CancellationSignal.Registration registration = signal.onCancel(invocations::incrementAndGet);
        signal.trip(InterruptReason.USER_SIGINT);
        assertThat(invocations.get()).isEqualTo(1);

        // Removing after the listener has already fired (and the list was cleared on trip) is a harmless no-op,
        // and a second remove() must not throw.
        assertThatCode(registration::remove).doesNotThrowAnyException();
        assertThatCode(registration::remove).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Registration.NONE.remove() is a no-op")
    void registrationNoneIsNoOp() {
        assertThatCode(CancellationSignal.Registration.NONE::remove).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("listener registered after trip fires immediately on registering thread")
    void lateListenerFiresImmediately() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();
        signal.trip(InterruptReason.PARENT_CANCELLED);

        AtomicInteger invocations = new AtomicInteger();
        signal.onCancel(invocations::incrementAndGet);

        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("listeners fire exactly once even if trip is invoked multiple times")
    void listenersFireExactlyOnce() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();
        AtomicInteger invocations = new AtomicInteger();
        signal.onCancel(invocations::incrementAndGet);

        signal.trip(InterruptReason.USER_SIGINT);
        signal.trip(InterruptReason.NOW_PRIORITY_INPUT);
        signal.trip(InterruptReason.BUDGET_EXCEEDED);

        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("listener exceptions do not block other listeners")
    void listenerExceptionDoesNotBlockOthers() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();
        AtomicInteger invocations = new AtomicInteger();

        signal.onCancel(() -> {
            throw new RuntimeException("boom");
        });
        signal.onCancel(invocations::incrementAndGet);

        signal.trip(InterruptReason.USER_SIGINT);

        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("onCancel rejects null listener")
    void onCancelRejectsNull() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();

        assertThatThrownBy(() -> signal.onCancel(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("trip rejects null reason")
    void tripRejectsNull() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();

        assertThatThrownBy(() -> signal.trip(null)).isInstanceOf(NullPointerException.class);
    }
}
