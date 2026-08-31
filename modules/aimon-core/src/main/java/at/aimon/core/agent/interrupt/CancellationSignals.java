package at.aimon.core.agent.interrupt;

import java.util.Objects;

/**
 * Shared cancellation/interrupt check for every execution path that drives an agent execution — the main ReAct loop
 * ({@code at.aimon.core.agent.impl.orca.OrcaAgentExecutor}), which runs a session's turn, plus the subagent ReAct path
 * ({@code at.aimon.core.subagent.execution.DefaultSubagentExecutor}) and the subagent code-behavior path
 * ({@code at.aimon.core.subagent.behavior.SubagentBehaviorRunner}), which run forks that belong to no session.
 * Centralising the check keeps those paths' interrupt semantics identical by construction rather than by parallel
 * copies that can silently drift.
 *
 * <p>
 * The check always evaluates {@link Thread#interrupted()}, which CONSUMES (clears) the calling thread's interrupt
 * flag. That consumption is the point, not a side effect:
 *
 * <ul>
 * <li><b>A live flag is a booby trap for the rest of the execution.</b> Anything downstream that blocks — a hook
 * running under {@code Future#get(timeout)}, a queue poll, a sleep — throws {@link InterruptedException} immediately.
 * Where that exception is mapped to a benign outcome (the hook executor's fail-open policy turns it into a
 * {@code HookResult.success()}), a lingering flag silently converts a PreTool <em>block</em> into an <em>allow</em>.
 * Consuming the flag at every loop checkpoint closes that window.
 * <li><b>A caller-supplied pool may not clear it for us.</b> A standard {@link java.util.concurrent.ThreadPoolExecutor}
 * already drops a stale flag before dispatching the next task, so the in-tree tool-dispatch, subagent and
 * streaming-overlap pools are covered by the runtime. That guarantee is the runtime's, not this framework's: an
 * embedder that supplies its own {@link java.util.concurrent.ExecutorService}, or a pool already shutting down, can
 * hand a worker its next task with the previous one's interrupt still set — where a pre-flight check reads the stale
 * interrupt and aborts spuriously before any work runs. Consuming it at the source keeps the framework correct
 * without depending on which executor it was handed.
 * </ul>
 *
 * <p>
 * Because the flag is consumed, callers must evaluate this <b>once</b> per decision point and reuse the result; a
 * second call at the same checkpoint reads {@code false} for the thread-interrupt half. Callers that need the
 * execution to stay interrupted after the flag is gone must promote it into the execution-scoped signal via
 * {@link InterruptCoordinator#requestInterrupt(InterruptReason)}.
 */
public final class CancellationSignals {

    private CancellationSignals() {
    }

    /**
     * Returns {@code true} if the execution-scoped signal is tripped or the current thread carries an interrupt. Always
     * evaluates (and thereby clears) the thread interrupt flag via {@link Thread#interrupted()}, even when the signal
     * is already tripped, so no path can leave a live flag behind.
     *
     * @param cancellationSignal
     *            the execution-scoped cancellation signal (must not be null)
     * @return {@code true} if cancellation has been requested by signal or thread interrupt
     */
    public static boolean isCancelledOrInterrupted(CancellationSignal cancellationSignal) {
        Objects.requireNonNull(cancellationSignal, "cancellationSignal cannot be null");
        final boolean threadInterrupted = Thread.interrupted();
        return cancellationSignal.isCancelled() || threadInterrupted;
    }
}
