package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.CancelledExecutionException;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;

@DisplayName("LeafConcurrencyLimiter — global leaf (LLM) permit ceiling, cancelled/interrupted give-up paths")
class LeafConcurrencyLimiterTest {

    @Test
    @DisplayName("The permit ceiling bounds concurrent leaves to maxConcurrency across competing threads")
    void ceilingBoundsConcurrentLeaves() {
        final LeafConcurrencyLimiter limiter = new LeafConcurrencyLimiter(1);
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final Semaphore entered = new Semaphore(0);
        final Semaphore proceed = new Semaphore(0);

        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final Thread thread = new Thread(() -> limiter.around(NoopCancellationSignal.INSTANCE, () -> {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                entered.release();
                acquire(proceed); // leaf is gated: it stays in flight until the main thread releases it
                inFlight.decrementAndGet();
                return completed.incrementAndGet();
            }));
            thread.start();
            threads.add(thread);
        }

        // Release the leaves one handshake at a time. Each 'entered' hand-off proves exactly one leaf is inside:
        // the single permit is held across the gated leaf, so no second leaf can increment inFlight meanwhile.
        for (int i = 0; i < 3; i++) {
            acquire(entered);
            assertThat(inFlight.get()).isEqualTo(1);
            proceed.release();
        }
        for (final Thread thread : threads) {
            join(thread);
        }

        assertThat(maxInFlight.get()).isEqualTo(1);
        assertThat(completed.get()).isEqualTo(3);
        assertThat(availablePermits(limiter)).isEqualTo(1);
    }

    @Test
    @DisplayName("a pre-tripped signal runs the leaf ungated: no permit is taken and the result is returned")
    void preTrippedSignalRunsLeafWithoutPermit() {
        final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        final LeafConcurrencyLimiter limiter = new LeafConcurrencyLimiter(2);
        final AtomicInteger permitsDuringLeaf = new AtomicInteger(-1);

        final Integer result = limiter.around(coordinator.getSignal(), () -> {
            permitsDuringLeaf.set(availablePermits(limiter));
            return 42;
        });

        assertThat(result).isEqualTo(42);
        assertThat(permitsDuringLeaf.get()).as("leaf must run without holding a permit").isEqualTo(2);
        assertThat(availablePermits(limiter)).isEqualTo(2);
    }

    @Test
    @DisplayName("an interrupt while parked with an untripped signal aborts with CancelledExecutionException, "
            + "restores the interrupt flag and leaks no permit")
    void interruptWhileParkedWithUntrippedSignalAbortsWithoutRunningLeaf() {
        final LeafConcurrencyLimiter limiter = new LeafConcurrencyLimiter(1);
        final Semaphore holderEntered = new Semaphore(0);
        final Semaphore holderProceed = new Semaphore(0);
        final Thread holder = new Thread(() -> limiter.around(NoopCancellationSignal.INSTANCE, () -> {
            holderEntered.release();
            acquire(holderProceed);
            return 0;
        }));
        holder.start();
        acquire(holderEntered); // the only permit is now held across the holder's gated leaf

        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicBoolean leafRan = new AtomicBoolean();
        final AtomicBoolean interruptFlagRestored = new AtomicBoolean();
        final Thread waiter = new Thread(() -> {
            try {
                limiter.around(NoopCancellationSignal.INSTANCE, () -> {
                    leafRan.set(true);
                    return 1;
                });
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                interruptFlagRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        // Teardown-style interrupt. The permit is never released while the waiter parks, so wherever the interrupt
        // lands, the waiter's next tryAcquire observes it and the untripped-signal abort path must be taken.
        waiter.interrupt();
        join(waiter);

        assertThat(thrown.get()).isInstanceOf(CancelledExecutionException.class);
        assertThat(((CancelledExecutionException) thrown.get()).getReason()).isEqualTo(InterruptReason.SYSTEM_SHUTDOWN);
        assertThat(leafRan.get()).as("the leaf must NOT run on the untripped-signal abort path").isFalse();
        assertThat(interruptFlagRestored.get()).isTrue();

        holderProceed.release();
        join(holder);
        assertThat(availablePermits(limiter)).as("no permit may leak on the abort path").isEqualTo(1);
    }

    @Test
    @DisplayName("a throwing leaf releases its permit: the next around() acquires immediately")
    void throwingLeafReleasesPermit() {
        final LeafConcurrencyLimiter limiter = new LeafConcurrencyLimiter(1);

        assertThatThrownBy(() -> limiter.around(NoopCancellationSignal.INSTANCE, () -> {
            throw new IllegalStateException("leaf boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("leaf boom");

        assertThat(availablePermits(limiter)).isEqualTo(1);
        final Integer result = limiter.around(NoopCancellationSignal.INSTANCE, () -> 7);
        assertThat(result).isEqualTo(7);
    }

    @Test
    @DisplayName("rejects maxConcurrency < 1")
    void rejectsNonPositiveMaxConcurrency() {
        assertThatThrownBy(() -> new LeafConcurrencyLimiter(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrency");
    }

    /**
     * White-box permit count: the limiter deliberately exposes no accessor (nothing in production may consult it), so
     * the no-leak invariant is pinned by reading the private semaphore directly.
     */
    private static int availablePermits(LeafConcurrencyLimiter limiter) {
        try {
            final Field field = LeafConcurrencyLimiter.class.getDeclaredField("permits");
            field.setAccessible(true);
            return ((Semaphore) field.get(limiter)).availablePermits();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot inspect limiter permits", e);
        }
    }

    private static void acquire(Semaphore semaphore) {
        try {
            if (!semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Semaphore not released within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (thread.isAlive()) {
            throw new IllegalStateException("Thread did not terminate within 5s: " + thread.getName());
        }
    }
}
