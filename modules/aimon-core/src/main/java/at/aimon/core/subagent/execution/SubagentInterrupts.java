package at.aimon.core.subagent.execution;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.CancellationSignals;

/**
 * Subagent-side alias for {@link CancellationSignals}, kept as the name the subagent execution paths call — the ReAct
 * loop ({@link DefaultSubagentExecutor}) and the code-behavior path
 * ({@code at.aimon.core.subagent.behavior.SubagentBehaviorRunner} via its support facade).
 *
 * <p>
 * The implementation moved to {@link CancellationSignals} (which sits next to {@link CancellationSignal} itself)
 * because the main ReAct loop needs the identical check and must not reach into the subagent package to get it. See
 * that class for the full contract — in particular that the check CONSUMES the calling thread's interrupt flag and so
 * must be evaluated once per decision point.
 */
public final class SubagentInterrupts {

    private SubagentInterrupts() {
    }

    /**
     * Returns {@code true} if the execution-scoped signal is tripped or the current thread carries an interrupt. Always
     * evaluates (and thereby clears) the thread interrupt flag via {@link Thread#interrupted()} so a pooled background
     * thread does not leak the interrupt into a subsequent task.
     *
     * @param cancellationSignal
     *            the execution-scoped cancellation signal (must not be null)
     * @return {@code true} if cancellation has been requested by signal or thread interrupt
     */
    public static boolean isCancelledOrInterrupted(CancellationSignal cancellationSignal) {
        return CancellationSignals.isCancelledOrInterrupted(cancellationSignal);
    }
}
