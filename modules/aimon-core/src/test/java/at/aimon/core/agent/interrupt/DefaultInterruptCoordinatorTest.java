package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultInterruptCoordinator Tests")
class DefaultInterruptCoordinatorTest {

    @Test
    @DisplayName("getSignal returns the same signal across calls and starts uncancelled")
    void getSignalIsStable() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();

        CancellationSignal first = coordinator.getSignal();
        CancellationSignal second = coordinator.getSignal();

        assertThat(first).isSameAs(second);
        assertThat(first.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("requestInterrupt trips the signal with the given reason")
    void requestInterruptTripsSignal() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

        CancellationSignal signal = coordinator.getSignal();
        assertThat(signal.isCancelled()).isTrue();
        assertThat(signal.getReason()).contains(InterruptReason.USER_SIGINT);
    }

    @Test
    @DisplayName("requestInterrupt is idempotent and retains the first reason")
    void requestInterruptIsIdempotent() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        coordinator.requestInterrupt(InterruptReason.BUDGET_EXCEEDED);

        assertThat(coordinator.getSignal().getReason()).contains(InterruptReason.USER_SIGINT);
    }

    @Test
    @DisplayName("requestInterrupt fires every terminator registered on an active registrar exactly once")
    void requestInterruptFiresAllRegisteredTerminators() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrarA = coordinator.newTerminatorRegistrar();
        TerminatorRegistrar registrarB = coordinator.newTerminatorRegistrar();

        AtomicInteger fired = new AtomicInteger();
        registrarA.register(fired::incrementAndGet);
        registrarA.register(fired::incrementAndGet);
        registrarB.register(fired::incrementAndGet);

        coordinator.requestInterrupt(InterruptReason.NOW_PRIORITY_INPUT);

        assertThat(fired.get()).isEqualTo(3);

        // Second call must be a silent no-op — terminators do not fire again.
        coordinator.requestInterrupt(InterruptReason.BUDGET_EXCEEDED);
        assertThat(fired.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("closed registrars are not fired on interrupt")
    void closedRegistrarsAreNotFired() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();

        AtomicInteger fired = new AtomicInteger();
        registrar.register(fired::incrementAndGet);
        registrar.close();

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

        assertThat(fired.get()).isZero();
    }

    @Test
    @DisplayName("newTerminatorRegistrar after requestInterrupt yields a registrar that fires terminators immediately")
    void registrarAfterInterruptFiresImmediately() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        coordinator.requestInterrupt(InterruptReason.SYSTEM_SHUTDOWN);

        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();
        AtomicInteger fired = new AtomicInteger();
        registrar.register(fired::incrementAndGet);

        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("close makes newTerminatorRegistrar throw and silences subsequent interrupt requests")
    void closeBlocksFurtherUsage() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();
        AtomicInteger fired = new AtomicInteger();
        registrar.register(fired::incrementAndGet);

        coordinator.close();

        assertThatThrownBy(coordinator::newTerminatorRegistrar).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> coordinator.requestInterrupt(InterruptReason.USER_SIGINT)).doesNotThrowAnyException();
        assertThat(coordinator.getSignal().isCancelled()).isFalse();
        assertThat(fired.get()).isZero();
    }

    @Test
    @DisplayName("close is idempotent")
    void closeIsIdempotent() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();

        assertThatCode(() -> {
            coordinator.close();
            coordinator.close();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("requestInterrupt rejects null reason")
    void requestInterruptRejectsNull() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();

        assertThatThrownBy(() -> coordinator.requestInterrupt(null)).isInstanceOf(NullPointerException.class);
    }
}
