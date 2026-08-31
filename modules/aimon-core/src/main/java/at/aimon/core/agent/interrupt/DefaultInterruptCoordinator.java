package at.aimon.core.agent.interrupt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link InterruptCoordinator} used by the executor. Each instance owns exactly one
 * {@link DefaultCancellationSignal} and an active list of {@link DefaultTerminatorRegistrar}s.
 *
 * <p>
 * Interrupt requests flip the signal, then iterate a snapshot of active registrars and fire all their terminators.
 * Concurrent registration during a trip is handled inside {@link DefaultTerminatorRegistrar}: if a registrar's
 * {@code register} call races with the trip, the terminator is invoked immediately on the registering thread.
 */
public final class DefaultInterruptCoordinator implements InterruptCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DefaultInterruptCoordinator.class);

    private final DefaultCancellationSignal signal = new DefaultCancellationSignal();
    private final List<DefaultTerminatorRegistrar> activeRegistrars = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    @Override
    public CancellationSignal getSignal() {
        return signal;
    }

    @Override
    public void requestInterrupt(InterruptReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (closed) {
            log.debug("requestInterrupt({}) ignored; coordinator already closed", reason);
            return;
        }
        final boolean flipped = signal.trip(reason);
        if (!flipped) {
            log.debug("requestInterrupt({}) is a no-op; signal already tripped", reason);
            return;
        }
        // Snapshot to avoid holding locks while firing terminators.
        final List<DefaultTerminatorRegistrar> snapshot = new ArrayList<>(activeRegistrars);
        for (DefaultTerminatorRegistrar registrar : snapshot) {
            registrar.fireAll();
        }
    }

    @Override
    public TerminatorRegistrar newTerminatorRegistrar() {
        if (closed) {
            throw new IllegalStateException("Coordinator already closed");
        }
        final DefaultTerminatorRegistrar registrar = new DefaultTerminatorRegistrar(this, signal);
        activeRegistrars.add(registrar);
        // Close-race: if close() ran between the check and the add we need to remove ourselves and reject.
        if (closed) {
            activeRegistrars.remove(registrar);
            throw new IllegalStateException("Coordinator already closed");
        }
        return registrar;
    }

    @Override
    public void close() {
        closed = true;
        activeRegistrars.clear();
    }

    /**
     * Removes the given registrar from the active list. Invoked by {@link DefaultTerminatorRegistrar#close()}.
     *
     * @param registrar
     *            the registrar returning itself to the coordinator (never null)
     */
    void removeRegistrar(DefaultTerminatorRegistrar registrar) {
        activeRegistrars.remove(registrar);
    }
}
