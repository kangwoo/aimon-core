package at.aimon.core.agent.interrupt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link TerminatorRegistrar} implementation owned by a {@link DefaultInterruptCoordinator}.
 *
 * <p>
 * The registrar keeps its own list of {@link Terminator}s distinct from the coordinator's signal listeners, so that
 * closing the registrar at tool return time removes the registered terminators without affecting other registrars or
 * the signal. Registration that races with a concurrent interrupt request is resolved inside {@link #register}: if
 * the signal flips between the pre-check and the list insertion, the terminator is invoked immediately on the
 * registering thread.
 */
final class DefaultTerminatorRegistrar implements TerminatorRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DefaultTerminatorRegistrar.class);

    private final DefaultInterruptCoordinator coordinator;
    private final CancellationSignal signal;
    private final Object lock = new Object();
    private final List<Terminator> terminators = new ArrayList<>();
    private boolean fired;
    private boolean closed;

    DefaultTerminatorRegistrar(DefaultInterruptCoordinator coordinator, CancellationSignal signal) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.signal = Objects.requireNonNull(signal, "signal must not be null");
    }

    @Override
    public void register(Terminator terminator) {
        Objects.requireNonNull(terminator, "terminator must not be null");
        final boolean fireImmediately;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Registrar already closed");
            }
            if (fired || signal.isCancelled()) {
                fireImmediately = true;
            } else {
                terminators.add(terminator);
                fireImmediately = false;
            }
        }
        if (fireImmediately) {
            safeTerminate(terminator);
        }
    }

    @Override
    public void unregister(Terminator terminator) {
        Objects.requireNonNull(terminator, "terminator must not be null");
        synchronized (lock) {
            if (closed || fired) {
                return;
            }
            terminators.remove(terminator);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            terminators.clear();
        }
        coordinator.removeRegistrar(this);
    }

    /**
     * Fires every currently-registered terminator exactly once. Called by {@link DefaultInterruptCoordinator} when the
     * signal is tripped. After this call, subsequent {@link #register(Terminator)} calls fire immediately on the
     * registering thread.
     */
    void fireAll() {
        final List<Terminator> snapshot;
        synchronized (lock) {
            if (fired || closed) {
                return;
            }
            fired = true;
            snapshot = new ArrayList<>(terminators);
            terminators.clear();
        }
        for (Terminator terminator : snapshot) {
            safeTerminate(terminator);
        }
    }

    private static void safeTerminate(Terminator terminator) {
        try {
            terminator.terminate();
        } catch (RuntimeException e) {
            log.warn("Terminator threw an exception; continuing", e);
        }
    }
}
