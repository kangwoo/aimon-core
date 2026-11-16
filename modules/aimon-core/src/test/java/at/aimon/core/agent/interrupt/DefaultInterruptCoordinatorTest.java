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
    @DisplayName("requestInterrupt with a detail keeps it, normalised, beside the reason")
    void requestInterruptKeepsNormalisedDetail() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT, "  wrong\ndirectory  ");

        CancellationSignal signal = coordinator.getSignal();
        assertThat(signal.getReason()).contains(InterruptReason.USER_SIGINT);
        assertThat(signal.getDetail()).contains("wrong directory");
    }

    @Test
    @DisplayName("the first interrupt fixes the detail; a later one's detail is dropped with it")
    void firstInterruptFixesDetail() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        coordinator.requestInterrupt(InterruptReason.TASK_CANCELLED, "too late");

        assertThat(coordinator.getSignal().getReason()).contains(InterruptReason.USER_SIGINT);
        assertThat(coordinator.getSignal().getDetail()).isEmpty();
    }

    @Test
    @DisplayName("a blank detail is no detail")
    void blankDetailIsAbsent() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT, " \t ");

        assertThat(coordinator.getSignal().isCancelled()).isTrue();
        assertThat(coordinator.getSignal().getDetail()).isEmpty();
    }

    @Test
    @DisplayName("a detail given after close is dropped with the interrupt")
    void detailAfterCloseIsDropped() {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        coordinator.close();

        coordinator.requestInterrupt(InterruptReason.USER_SIGINT, "ignored");

        assertThat(coordinator.getSignal().isCancelled()).isFalse();
        assertThat(coordinator.getSignal().getDetail()).isEmpty();
    }

    @Test
    @DisplayName("the interface default drops the detail and still interrupts")
    void interfaceDefaultDelegates() {
        DefaultInterruptCoordinator delegate = new DefaultInterruptCoordinator();
        InterruptCoordinator legacy = new InterruptCoordinator() {
            @Override
            public CancellationSignal getSignal() {
                return delegate.getSignal();
            }

            @Override
            public void requestInterrupt(InterruptReason reason) {
                delegate.requestInterrupt(reason);
            }

            @Override
            public TerminatorRegistrar newTerminatorRegistrar() {
                return delegate.newTerminatorRegistrar();
            }

            @Override
            public void close() {
                delegate.close();
            }
        };

        legacy.requestInterrupt(InterruptReason.USER_SIGINT, "dropped");

        assertThat(legacy.getSignal().getReason()).contains(InterruptReason.USER_SIGINT);
        assertThat(legacy.getSignal().getDetail()).isEmpty();
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

    @Test
    @DisplayName("isClosed turns true with close(), and a coordinator that does not implement it reads as open (EE-93)")
    void isClosedFollowsClose() {
        final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        assertThat(coordinator.isClosed()).isFalse();

        coordinator.close();
        coordinator.close();

        assertThat(coordinator.isClosed()).isTrue();

        // A coordinator written before the method existed keeps answering what was assumed of every coordinator.
        final InterruptCoordinator legacy = new InterruptCoordinator() {
            @Override
            public CancellationSignal getSignal() {
                return NoopCancellationSignal.INSTANCE;
            }

            @Override
            public void requestInterrupt(InterruptReason reason) {
            }

            @Override
            public TerminatorRegistrar newTerminatorRegistrar() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };
        legacy.close();
        assertThat(legacy.isClosed()).isFalse();
    }
}
