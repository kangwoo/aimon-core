package at.aimon.core.agent.interrupt;

/**
 * Execution-scoped orchestrator for {@link CancellationSignal} dispatch and {@link Terminator} registration. The
 * execution is a session's turn on the main ReAct loop and a fork on the subagent paths.
 *
 * <p>
 * The executor constructs a fresh coordinator at execution start and disposes it at execution end via
 * {@link #close()}. Its {@link CancellationSignal} instance is never reused across executions so a prior execution's
 * cancellation cannot leak into the next. Implementations are thread-safe because interrupt requests may arrive from
 * threads other than the executor thread (SIGINT handler, queue listener callback, parent coordinator cascade, ...).
 *
 * <h2>Lifecycle</h2>
 * <ol>
 * <li>Executor constructs the coordinator at execution start.
 * <li>For each tool execution the executor calls {@link #newTerminatorRegistrar()} to obtain a fresh registrar and
 * injects {@link #getSignal()} into the {@link at.aimon.core.agent.tool.ToolContext}.
 * <li>Any thread may call {@link #requestInterrupt(InterruptReason)} to trip the signal; the coordinator fires every
 * currently-registered {@link Terminator}.
 * <li>At tool return the executor closes the per-tool registrar. At execution end the executor closes the coordinator
 * itself — remaining registrars and further interrupt requests become no-ops.
 * </ol>
 */
public interface InterruptCoordinator extends AutoCloseable {

    /**
     * @return the read-side {@link CancellationSignal} for this execution (never null; the same instance across all
     *         calls on a given coordinator)
     */
    CancellationSignal getSignal();

    /**
     * Trip the signal with the given reason and fire all currently-registered terminators. Idempotent — the first
     * successful caller flips the flag; subsequent calls are silent no-ops.
     *
     * <p>
     * Safe to call from any thread. Calls made after {@link #close()} are no-ops.
     *
     * @param reason
     *            the interrupt reason (never null)
     * @throws NullPointerException
     *             if {@code reason} is null
     */
    void requestInterrupt(InterruptReason reason);

    /**
     * Create a fresh {@link TerminatorRegistrar} scoped to one tool execution. The executor obtains one before
     * invoking a {@link InterruptBehavior#THREAD_INTERRUPT} or {@link InterruptBehavior#EXTERNALLY_TERMINATED} tool
     * and closes it when the tool returns. If {@link #requestInterrupt(InterruptReason)} has already fired on this
     * coordinator the returned registrar is already "tripped" — registered terminators will fire immediately.
     *
     * @return a fresh registrar (never null)
     * @throws IllegalStateException
     *             if the coordinator has already been {@link #close() closed}
     */
    TerminatorRegistrar newTerminatorRegistrar();

    /**
     * Dispose of this coordinator. Clears any still-active registrars (without invoking their terminators), blocks
     * further {@link #requestInterrupt(InterruptReason)} calls, and renders {@link #newTerminatorRegistrar()} unusable.
     * Idempotent — additional calls become no-ops.
     */
    @Override
    void close();
}
