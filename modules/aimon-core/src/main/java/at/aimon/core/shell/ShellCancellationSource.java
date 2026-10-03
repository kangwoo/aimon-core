package at.aimon.core.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The side that cancels: one source per command. Whoever starts the command keeps the source, puts its
 * {@link #token()} in the command's {@link ExecutionOptions}, and calls {@link #cancel()} to stop it.
 *
 * <p>
 * Thread-safe. {@link #cancel()} runs the registered listeners on the calling thread, so it returns once the shell has
 * <em>asked</em> the command to stop — for {@code LocalShell} that includes the SIGTERM grace period — not once the
 * command's {@code execute} has returned.
 */
public final class ShellCancellationSource {

    private static final Logger log = LoggerFactory.getLogger(ShellCancellationSource.class);

    private final Object lock = new Object();
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean cancelled;
    private final ShellCancellation token = new Token();

    private ShellCancellationSource() {
    }

    /**
     * @return a new source, not cancelled
     */
    public static ShellCancellationSource create() {
        return new ShellCancellationSource();
    }

    /**
     * @return the signal to hand to the shell (always the same instance)
     */
    public ShellCancellation token() {
        return token;
    }

    /**
     * Requests cancellation and runs every registered listener on this thread. Only the first call does anything. A
     * listener that throws is logged and does not stop the others; this method never throws.
     *
     * @return {@code true} if this call cancelled the signal, {@code false} if it was already cancelled
     */
    public boolean cancel() {
        final List<Runnable> toRun;
        synchronized (lock) {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            toRun = List.copyOf(listeners);
            listeners.clear();
        }
        toRun.forEach(ShellCancellationSource::runQuietly);
        return true;
    }

    private static void runQuietly(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException e) {
            log.warn("A shell cancellation listener failed: {}", e.toString(), e);
        }
    }

    private final class Token implements ShellCancellation {

        @Override
        public boolean isCancelled() {
            synchronized (lock) {
                return cancelled;
            }
        }

        @Override
        public Registration onCancel(Runnable listener) {
            Objects.requireNonNull(listener, "listener must not be null");
            synchronized (lock) {
                if (!cancelled) {
                    listeners.add(listener);
                    return () -> {
                        synchronized (lock) {
                            listeners.remove(listener);
                        }
                    };
                }
            }
            runQuietly(listener);
            return () -> {
            };
        }
    }
}
