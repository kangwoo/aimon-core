package at.aimon.core.agent.interrupt;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import at.aimon.core.llm.LlmCancellation;

/**
 * Adapter that bridges an execution's {@link CancellationSignal} (agent side) to an {@link LlmCancellation} (llm side)
 * so a user/parent cancellation can actively abort the in-flight LLM HTTP call.
 *
 * <p>
 * The two interfaces are deliberately kept in separate packages — {@code at.aimon.core.llm} may not depend on
 * {@code at.aimon.core.agent.interrupt} (ArchUnit) — and this adapter, living on the agent side (which <em>may</em>
 * depend on {@code llm}), is where they meet. An executor creates one instance per execution, alongside the
 * execution's {@link CancellationSignal}, and passes it down through the {@code LlmCallGateway} to the provider.
 *
 * <h2>Why a single signal listener</h2>
 *
 * An execution issues many LLM calls (one per ReAct iteration, plus gateway retries), and each call wants to register
 * its own {@code StreamResponse.close()} abort. Registering one listener <em>per call</em> and leaving it there would
 * accumulate an unbounded number of listeners on the signal over a long execution, and on trip every one of them —
 * including those for calls that already completed — would fire. Instead this adapter registers <b>exactly one</b>
 * listener on the signal (in its constructor) and each call swaps its abort lever into a single
 * {@link AtomicReference}. When the signal trips, the one listener fires whichever abort is currently active;
 * completed calls have cleared theirs, so nothing stale is invoked.
 *
 * <h2>Lifetime</h2>
 *
 * An executor's instance lives as long as its signal does, so it never needs to take its listener back. An adapter
 * made for something shorter than the signal — one call on an execution's signal, as the compaction engine makes for
 * its summary call — must: {@link #close()} removes the listener ({@link CancellationSignal#onCancel(Runnable)}
 * returns the {@link CancellationSignal.Registration} for it), otherwise every such call leaves one behind on a
 * signal that lives for the whole execution.
 *
 * <h2>Threading</h2>
 *
 * The trip typically arrives on a different thread than the LLM worker. {@link #onCancel(Runnable)} publishes the abort
 * through an {@link AtomicReference}; the listener reads and runs it. Aborts must be idempotent (they are —
 * {@code StreamResponse.close()} is), because a set-then-already-cancelled race can fire the same abort twice.
 */
public final class SignalBackedLlmCancellation implements LlmCancellation, AutoCloseable {

    private final CancellationSignal signal;
    private final AtomicReference<Runnable> currentAbort = new AtomicReference<>();
    private final CancellationSignal.Registration registration;

    /**
     * Creates an adapter over the given execution signal and registers the single trip listener.
     *
     * @param signal
     *            the execution's cancellation signal (must not be {@code null})
     */
    public SignalBackedLlmCancellation(CancellationSignal signal) {
        this.signal = Objects.requireNonNull(signal, "signal");
        // Register exactly ONE listener for the whole execution (see class Javadoc). If the signal is already
        // tripped, CancellationSignal fires this synchronously now; currentAbort is still null at this point, so it is
        // a no-op and the first onCancel() below will handle the already-cancelled case itself.
        this.registration = signal.onCancel(this::fireCurrentAbort);
    }

    /**
     * Takes this adapter's listener off the signal and forgets the active abort. For an adapter that lives shorter
     * than its signal; after this the adapter still answers {@link #isCancelled()} but a trip no longer reaches an
     * abort through it. Idempotent.
     */
    @Override
    public void close() {
        registration.remove();
        currentAbort.set(null);
    }

    @Override
    public boolean isCancelled() {
        return signal.isCancelled();
    }

    @Override
    public void onCancel(Runnable abort) {
        Objects.requireNonNull(abort, "abort");
        currentAbort.set(abort);
        // Honour the LlmCancellation already-cancelled contract: if the signal tripped before (or while) this call
        // registered its abort, the shared listener may have run against a null/previous abort, so fire the just-set
        // abort now on the calling thread. Idempotent, so a redundant fire is harmless.
        if (signal.isCancelled()) {
            fireCurrentAbort();
        }
    }

    /**
     * Clears the currently active abort. An executor calls this after each LLM call returns or throws so that a signal
     * trip occurring <em>between</em> calls (e.g. while tools run) does not invoke a stale, already-closed stream's
     * abort. Because {@code StreamResponse.close()} is idempotent this is hygiene rather than strictly required, but it
     * keeps the reference from pinning a completed call's resources.
     */
    public void clearAbort() {
        currentAbort.set(null);
    }

    private void fireCurrentAbort() {
        final Runnable abort = currentAbort.get();
        if (abort != null) {
            abort.run();
        }
    }
}
