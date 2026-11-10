package at.aimon.session.routing;

import java.time.Duration;

/**
 * Observes a node that has stopped taking new turns but keeps running the ones it already has — returned by
 * {@link SessionRouter#beginDrain()} (routing.md §8.4).
 *
 * <p>
 * Watching never acts: neither method interrupts a turn, returns a lease early or closes anything. A host that wants
 * the node gone after a deadline decides that itself and calls {@link SessionRouter#closeGracefully(Duration)} (or
 * {@link SessionRouter#close()}), whose semantics are unchanged by draining.
 *
 * <p>
 * <b>Quiescent means "nothing of the router's is still here".</b> No turn or drain pass is counted in flight, no turn
 * gate is held, this node holds no session lease, it owes no doorbell relay, and no drain hand-over is still queued. On
 * a {@code SINGLE_NODE} router it also means no forwarded turn is still waiting, since only this node can run it. It
 * does <em>not</em> cover work the router never sees: background subagent tasks, scripted workflows, monitors and other
 * {@code AgentRuntime}-owned activity that outlives a turn, and any {@link at.aimon.core.agent.session.LiveSession} the
 * host drives directly. A host that runs such work must combine its own in-flight state with this one before it stops
 * the process. On a {@code DISTRIBUTED} router, forwards this node is still waiting on ({@link
 * DrainSnapshot#getPendingForwards()}) are reported but do not block quiescence: they are callers waiting on turns
 * other nodes run, and closing fails them exactly as it does today.
 *
 * <p>
 * The same shape as the scheduling side's {@code SchedulingEngine.awaitIdle(Duration)}, so a host drains a worker by
 * calling both; routing.md §8.4 has the sequence.
 */
public interface DrainHandle {

    /**
     * Waits up to {@code timeout} for this node to become quiescent ({@link DrainSnapshot#isQuiescent()}).
     *
     * <p>
     * On timeout it simply returns {@code false}: running turns keep running, keep renewing their leases and keep
     * persisting their results. Calling it again later, or with a longer timeout, is the expected way to keep waiting.
     * A timeout too long to count in nanoseconds waits as if unbounded.
     *
     * @param timeout
     *            how long to wait (must not be null or negative); {@link Duration#ZERO} only checks
     * @return {@code true} when the node is quiescent, {@code false} when work is still running
     * @throws IllegalArgumentException
     *             if {@code timeout} is negative
     * @throws InterruptedException
     *             if the waiting thread is interrupted; nothing about the drain changes
     */
    boolean awaitIdle(Duration timeout) throws InterruptedException;

    /**
     * A point-in-time view of what is still keeping this node busy.
     *
     * @return the counts (never null)
     */
    DrainSnapshot snapshot();
}
