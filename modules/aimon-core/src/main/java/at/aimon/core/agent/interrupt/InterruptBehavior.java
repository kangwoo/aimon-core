package at.aimon.core.agent.interrupt;

/**
 * Declares how a {@link at.aimon.core.agent.tool.Tool} reacts to an external interrupt request delivered through a
 * {@link CancellationSignal}.
 *
 * <p>
 * The executor reads this declaration before invoking a tool and picks a propagation strategy via
 * {@link InterruptCoordinator}. Tools that do not override the default behavior are treated as
 * {@link #NON_INTERRUPTIBLE}, preserving existing tool semantics.
 *
 * @see CancellationSignal
 * @see InterruptCoordinator
 */
public enum InterruptBehavior {
    /**
     * The tool ignores interrupt signals. The coordinator lets the tool run to completion and checks the signal at the
     * next iteration boundary. Use for short, atomic operations where mid-execution cancellation is not meaningful
     * (e.g., file stat, in-memory reads).
     */
    NON_INTERRUPTIBLE,

    /**
     * The tool cooperatively polls the {@link CancellationSignal} obtained from its
     * {@link at.aimon.core.agent.tool.ToolContext} and returns a {@link at.aimon.core.agent.tool.ToolResult} early when
     * cancellation is observed. The coordinator trips the signal but does not otherwise interfere with the tool
     * thread.
     */
    COOPERATIVE,

    /**
     * The coordinator is permitted to invoke {@link Thread#interrupt()} on the tool thread. The tool's internal I/O
     * pathways or {@link java.util.concurrent.Future} plumbing must react to the interrupt and unwind. Appropriate for
     * tools wrapping a blocking {@link java.util.concurrent.Future#get()} or interruptible I/O.
     */
    THREAD_INTERRUPT,

    /**
     * The tool registers a {@link Terminator} with the coordinator's {@link TerminatorRegistrar} at execution start.
     * When interrupted the coordinator invokes every registered {@link Terminator#terminate()} out-of-band — it does
     * not block on the tool returning. Appropriate for tools managing subprocesses or subagents where an explicit
     * kill/cancel handle is more effective than thread interrupt.
     */
    EXTERNALLY_TERMINATED
}
