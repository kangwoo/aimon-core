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
 *
 * <p>
 * {@link #close()} is also a barrier: when it returns, no terminator of this registrar is running and none will run.
 * {@link #fireAll()} invokes terminators outside {@code lock} (so a slow terminator never holds up registration), and
 * without the barrier a terminator from a snapshot taken just before {@code close()} could still land afterwards —
 * for {@code Thread::interrupt}, on a thread that has already moved on to PostTool hooks (PF-3). The barrier is a
 * second monitor, {@code fireLock}, held for the whole of {@code fireAll} and of the immediate fire in
 * {@link #register}; terminators return promptly by contract, so the wait is short. Lock order is always
 * {@code fireLock} then {@code lock}; {@code close()} takes them one after the other, never nested.
 */
final class DefaultTerminatorRegistrar implements TerminatorRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DefaultTerminatorRegistrar.class);

    private final DefaultInterruptCoordinator coordinator;
    private final CancellationSignal signal;
    private final Object lock = new Object();
    private final Object fireLock = new Object();
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
        // Under fireLock, like fireAll: an immediate fire is still a fire, and close() must be able to wait it out.
        synchronized (fireLock) {
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
        // Wait out a fireAll that took its snapshot before we closed; one that has not yet taken it sees closed.
        synchronized (fireLock) {
            // barrier only
        }
        coordinator.removeRegistrar(this);
    }

    /**
     * Fires every currently-registered terminator exactly once. Called by {@link DefaultInterruptCoordinator} when the
     * signal is tripped. After this call, subsequent {@link #register(Terminator)} calls fire immediately on the
     * registering thread.
     */
    void fireAll() {
        synchronized (fireLock) {
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
    }

    private static void safeTerminate(Terminator terminator) {
        try {
            terminator.terminate();
        } catch (RuntimeException e) {
            log.warn("Terminator threw an exception; continuing", e);
        }
    }
}
