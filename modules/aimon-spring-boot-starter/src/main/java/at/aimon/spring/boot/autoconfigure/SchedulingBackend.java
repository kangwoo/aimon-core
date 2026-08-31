package at.aimon.spring.boot.autoconfigure;

/**
 * Engine that runs the agent's scheduled tasks, selected by {@code aimon.scheduling.backend}.
 *
 * <p>
 * All three are honoured. {@link #NONE} is the default, and the reason is cost rather than caution: the engine
 * holds a scheduler thread pool and its shutdown drains running jobs, which a stack that never registers a cron
 * task should not pay for.
 *
 * <p>
 * Whichever value is chosen, the scheduled <em>tasks</em> live in memory and are gone after a restart — the
 * scheduler decides where the triggers live, not the tasks. The stack announces that as its
 * {@code scheduling-durability} degradation rather than leaving it to be found.
 */
public enum SchedulingBackend {

    /**
     * No scheduling engine. Nothing registers cron tasks, no scheduler thread starts, and shutdown has no jobs
     * to drain — the last of which is one of the two unbounded contributors to stack shutdown time.
     */
    NONE,

    /**
     * The engine's own in-memory scheduler. Survives neither a restart nor a second instance: two nodes each
     * run the same cron, and a task scheduled before a deploy is gone after it.
     */
    IN_MEMORY,

    /**
     * A clustered Quartz scheduler from {@code aimon-scheduling-quartz}. Both prerequisites are now met: the
     * adapter translates the core's five-field expressions into Quartz's dialect instead of re-validating them
     * in it, and scheduler names are derived per instance rather than shared, so two application contexts in
     * one JVM no longer resolve to the same scheduler. Clustered deployments must still set an explicit
     * {@code instanceName} — that is what makes a set of nodes one cluster — and the adapter refuses to build
     * a clustered scheduler without it.
     *
     * <p>
     * By default AIMON borrows the application's {@code Scheduler} bean rather than standing a second Quartz
     * instance beside it; {@code aimon.scheduling.quartz.use-application-scheduler=false} builds its own. The
     * borrowed one is neither started nor stopped by AIMON, so an application that leaves its scheduler in
     * standby gets no firings.
     */
    QUARTZ
}
