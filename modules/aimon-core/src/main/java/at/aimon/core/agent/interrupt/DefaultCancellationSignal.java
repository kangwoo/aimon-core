package at.aimon.core.agent.interrupt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link CancellationSignal} implementation.
 *
 * <p>
 * The signal is created unset. {@link InterruptCoordinator} is the only entity permitted to trip it via the
 * package-private {@link #trip(InterruptReason)} method. Once tripped, the signal stays tripped for its remaining
 * lifetime — there is no reset.
 *
 * <p>
 * The implementation uses a single intrinsic lock to serialise the transition from "not cancelled" to "cancelled" so
 * every registered listener fires exactly once in registration order. {@link #isCancelled()} and
 * {@link #getReason()} are lock-free reads of a {@code volatile} reference for minimum observation cost.
 */
public final class DefaultCancellationSignal implements CancellationSignal {

    private static final Logger log = LoggerFactory.getLogger(DefaultCancellationSignal.class);

    private final Object lock = new Object();
    private final List<Runnable> listeners = new ArrayList<>();
    private volatile InterruptReason reason;

    @Override
    public boolean isCancelled() {
        return reason != null;
    }

    @Override
    public Optional<InterruptReason> getReason() {
        return Optional.ofNullable(reason);
    }

    @Override
    public void checkpoint() {
        final InterruptReason snapshot = reason;
        if (snapshot != null) {
            throw new CancelledExecutionException(snapshot);
        }
    }

    @Override
    public Registration onCancel(Runnable listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        final boolean fireImmediately;
        synchronized (lock) {
            if (reason != null) {
                fireImmediately = true;
            } else {
                listeners.add(listener);
                fireImmediately = false;
            }
        }
        if (fireImmediately) {
            safeRun(listener);
            // Already tripped: the listener was fired and never added to the list, so there is nothing to remove.
            return Registration.NONE;
        }
        // Remove by identity under the lock. Idempotent: a second remove(), or a remove() after trip() cleared the
        // list, simply finds nothing.
        return () -> {
            synchronized (lock) {
                listeners.remove(listener);
            }
        };
    }

    /**
     * Trips the signal with the given reason. Package-private so only {@link InterruptCoordinator} implementations in
     * this package can flip the flag.
     *
     * @param newReason
     *            the reason (never null)
     * @return {@code true} if this call flipped the signal, {@code false} if it was already tripped
     * @throws NullPointerException
     *             if {@code newReason} is null
     */
    boolean trip(InterruptReason newReason) {
        Objects.requireNonNull(newReason, "reason must not be null");
        final List<Runnable> toFire;
        synchronized (lock) {
            if (reason != null) {
                return false;
            }
            reason = newReason;
            toFire = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (Runnable listener : toFire) {
            safeRun(listener);
        }
        return true;
    }

    private static void safeRun(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            log.warn("Cancellation listener threw an exception; continuing", e);
        }
    }
}
