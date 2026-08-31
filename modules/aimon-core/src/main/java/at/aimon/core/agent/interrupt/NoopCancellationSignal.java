package at.aimon.core.agent.interrupt;

import java.util.Optional;

/**
 * Stateless {@link CancellationSignal} that is never tripped.
 *
 * <p>
 * Used as a safe default when a tool queries its {@link at.aimon.core.agent.tool.ToolContext} for a signal that has
 * not been injected — typically tools executed outside the ReAct loop (unit tests, CLI-invoked diagnostic tools).
 * Listeners are accepted but never fired.
 */
public final class NoopCancellationSignal implements CancellationSignal {

    /** Singleton instance. */
    public static final NoopCancellationSignal INSTANCE = new NoopCancellationSignal();

    private NoopCancellationSignal() {
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public Optional<InterruptReason> getReason() {
        return Optional.empty();
    }

    @Override
    public void checkpoint() {
        // no-op
    }

    @Override
    public Registration onCancel(Runnable listener) {
        // Intentionally swallow — this signal never trips so a listener would never be notified. Keeping the
        // registration silent lets callers uniformly attach listeners without checking which signal instance they
        // hold. Nothing is retained, so the deregistration handle is a no-op.
        if (listener == null) {
            throw new NullPointerException("listener must not be null");
        }
        return Registration.NONE;
    }
}
