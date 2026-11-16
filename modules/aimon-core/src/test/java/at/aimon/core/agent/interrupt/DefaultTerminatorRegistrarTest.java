package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    @DisplayName("PF-3: close() waits for a terminator being fired by a trip, so none lands after it returns")
    void closeWaitsForTerminatorFiredByTrip() throws Exception {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean terminatorDone = new AtomicBoolean();
        registrar.register(blockingTerminator(entered, release, terminatorDone));
        Thread firer = new Thread(() -> coordinator.requestInterrupt(InterruptReason.USER_SIGINT));
        try {
            firer.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertCloseWaitsFor(registrar, release, terminatorDone);
        } finally {
            release.countDown();
            firer.join(5_000);
        }
    }

    @Test
    @DisplayName("PF-3: close() also waits for a terminator fired immediately by register() on a tripped signal")
    void closeWaitsForTerminatorFiredByRegister() throws Exception {
        DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean terminatorDone = new AtomicBoolean();
        Thread registering = new Thread(() -> registrar.register(blockingTerminator(entered, release, terminatorDone)));
        try {
            registering.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertCloseWaitsFor(registrar, release, terminatorDone);
        } finally {
            release.countDown();
            registering.join(5_000);
        }
    }

    /** A terminator that signals {@code entered}, blocks until {@code release}, then records that it finished. */
    private static Terminator blockingTerminator(CountDownLatch entered, CountDownLatch release, AtomicBoolean done) {
        return () -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.set(true);
        };
    }

    /**
     * Calls close() on another thread while a terminator is in flight, waits until that thread is blocked (it can only
     * block on the barrier), releases the terminator and checks close() returned only after it finished.
     */
    private static void assertCloseWaitsFor(TerminatorRegistrar registrar, CountDownLatch release,
            AtomicBoolean terminatorDone) throws InterruptedException {
        AtomicBoolean doneWhenCloseReturned = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            registrar.close();
            doneWhenCloseReturned.set(terminatorDone.get());
        });
        closer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (closer.getState() != Thread.State.BLOCKED && closer.isAlive() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(closer.getState()).as("close() must block on the in-flight terminator")
                .isEqualTo(Thread.State.BLOCKED);

        release.countDown();
        closer.join(5_000);
        assertThat(closer.isAlive()).isFalse();
        assertThat(doneWhenCloseReturned.get()).isTrue();
    }
}
