package at.aimon.core.agent.interrupt;

/**
 * Per-tool-execution registry handed to tools whose {@link InterruptBehavior} is
 * {@link InterruptBehavior#THREAD_INTERRUPT} or {@link InterruptBehavior#EXTERNALLY_TERMINATED}.
 *
 * <p>
 * A fresh registrar is obtained from {@link InterruptCoordinator#newTerminatorRegistrar()} for each tool invocation.
 * When the coordinator's signal is tripped, every still-registered {@link Terminator} is invoked once in registration
 * order. Registrars must be closed at tool return time so their terminators do not leak into subsequent tool calls.
 *
 * <p>
 * Registering a terminator after the signal has already been tripped invokes it immediately on the registering
 * thread. Registering after {@link #close()} throws {@link IllegalStateException}.
 *
 * <p>
 * Implementations are thread-safe.
 */
public interface TerminatorRegistrar extends AutoCloseable {

    /**
     * Registers the terminator with this registrar. If the coordinator's signal has already been tripped the
     * terminator is invoked immediately on the registering thread.
     *
     * @param terminator
     *            the termination callback (never null)
     * @throws NullPointerException
     *             if {@code terminator} is null
     * @throws IllegalStateException
     *             if this registrar has already been {@link #close() closed}
     */
    void register(Terminator terminator);

    /**
     * Removes a previously registered terminator. Idempotent: unregistering a terminator that is not currently
     * registered is a no-op. Unregistering after {@link #close()} is also a no-op (every terminator is already gone).
     *
     * @param terminator
     *            the terminator to remove (never null)
     * @throws NullPointerException
     *             if {@code terminator} is null
     */
    void unregister(Terminator terminator);

    /**
     * Releases this registrar. Any still-registered terminators are dropped without being invoked. Subsequent
     * {@link #register(Terminator)} calls throw {@link IllegalStateException}; {@link #unregister(Terminator)} and
     * further {@link #close()} calls become no-ops.
     */
    @Override
    void close();
}
