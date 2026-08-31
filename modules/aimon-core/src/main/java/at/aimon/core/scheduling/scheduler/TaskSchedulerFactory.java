/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.scheduler;

/**
 * Builds a {@link TaskScheduler} once the executor it has to dispatch to exists.
 *
 * <p>
 * The three parts of scheduling form a cycle: a {@link TaskScheduler} needs a {@link ScheduledTaskExecutor} to
 * call when a trigger fires, that executor is the task manager, and the task manager needs the scheduler. Something has
 * to be built second, and this interface is the seam that decides which.
 *
 * <p>
 * A caller that hands over a finished {@code TaskScheduler} has already had to solve the cycle itself — it needed an
 * executor before the manager existed, and the only executor available at that point is one that closes over a mutable
 * reference nobody has filled in yet. Every embedder hits this, and getting it wrong is silent: the scheduler starts,
 * triggers fire, and each firing dies on a null executor. Handing over a factory instead moves the hole to the one
 * place that can dig it correctly — {@code SchedulingEngineBuilder}, which builds the manager and can therefore pass it
 * in directly.
 *
 * <p>
 * Implementations must not start the returned scheduler; the engine starts it.
 */
@FunctionalInterface
public interface TaskSchedulerFactory {

    /**
     * Creates the scheduler that will dispatch fired triggers to {@code executor}.
     *
     * @param executor
     *            the executor to call on each firing (never null)
     * @return the scheduler, not yet started
     */
    TaskScheduler create(ScheduledTaskExecutor executor);
}
