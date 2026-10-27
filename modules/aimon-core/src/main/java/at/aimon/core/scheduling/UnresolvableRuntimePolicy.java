/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

/**
 * What {@link ScheduledTaskManager} does when a task fires and its bound agent runtime is not registered.
 *
 * <p>
 * Registration refuses such a task outright (see {@link SchedulingEngineBuilder#validateBoundRuntime(boolean)}); this
 * covers a task that was resolvable when it was stored and is not any more — its agent was removed, or this node has
 * not created that runtime yet.
 *
 * <p>
 * The second case is why {@link #KEEP_SCHEDULED} is the default. A runtime that is registered lazily — on the first
 * session opened for its agent, as a web host does — is absent after every restart until that session arrives, so a
 * policy that disabled on the first miss would disable every such task at the first fire after a restart.
 */
public enum UnresolvableRuntimePolicy {

    /**
     * The default. The fire runs, its first step fails with "No agent runtime registered for binding", and the task
     * stays scheduled, so a runtime registered later is picked up at the next fire.
     */
    KEEP_SCHEDULED,

    /**
     * The fire is skipped, the task is disabled and unscheduled, and a
     * {@link at.aimon.core.scheduling.event.TaskDisabledEvent} is published. The record is kept; enabling the task
     * again schedules it again. Choose this only where every runtime is registered at start.
     */
    DISABLE
}
