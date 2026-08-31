package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultTerminatorRegistrar Tests")
class DefaultTerminatorRegistrarTest {

    @Test
    @DisplayName("registered terminators fire once when the coordinator's signal is tripped")
    void registeredTerminatorsFireOnTrip() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();

        AtomicInteger fired = new AtomicInteger();
        registrar.register(fired::incrementAndGet);

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("unregister removes a pending terminator before the signal trips")
    void unregisterRemovesPendingTerminator() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();

        AtomicInteger fired = new AtomicInteger();
        Terminator terminator = fired::incrementAndGet;
        registrar.register(terminator);
        registrar.unregister(terminator);

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

        assertThat(fired.get()).isZero();
    }

    @Test
    @DisplayName("unregistering a terminator that was never registered is a no-op")
    void unregisterUnknownTerminatorIsNoop() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();

        assertThatCode(() -> registrar.unregister(() -> {
        })).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("registering after the signal has already tripped fires immediately")
    void registerAfterTripFiresImmediately() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();

        coordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED);

        AtomicInteger fired = new AtomicInteger();
        registrar.register(fired::incrementAndGet);

        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("close drops pending terminators without firing them")
    void closeDropsPendingTerminatorsWithoutFiring() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();

        AtomicInteger fired = new AtomicInteger();
        registrar.register(fired::incrementAndGet);

        registrar.close();

        assertThat(fired.get()).isZero();

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        assertThat(fired.get()).isZero();
    }

    @Test
    @DisplayName("register after close throws IllegalStateException")
    void registerAfterCloseThrows() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();

        registrar.close();

        assertThatThrownBy(() -> registrar.register(() -> {
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("unregister after close is a silent no-op")
    void unregisterAfterCloseIsNoop() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();
        registrar.close();

        assertThatCode(() -> registrar.unregister(() -> {
        })).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("close is idempotent")
    void closeIsIdempotent() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();

        assertThatCode(() -> {
            registrar.close();
            registrar.close();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("terminators that throw do not prevent sibling terminators from running")
    void throwingTerminatorDoesNotBlockOthers() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();

        AtomicInteger fired = new AtomicInteger();
        registrar.register(() -> {
            throw new RuntimeException("boom");
        });
        registrar.register(fired::incrementAndGet);

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("register and unregister reject null terminators")
    void nullTerminatorsRejected() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();

        assertThatThrownBy(() -> registrar.register(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registrar.unregister(null)).isInstanceOf(NullPointerException.class);
    }
}
