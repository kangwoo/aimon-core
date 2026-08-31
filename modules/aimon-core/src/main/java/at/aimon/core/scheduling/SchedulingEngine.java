/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.ScheduledTaskEventPublisher;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

/**
 * Main facade for the scheduling engine.
 *
 * <p>
 * This class integrates all scheduling components and provides a unified API for task scheduling, execution, and
 * monitoring.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * // Create engine
 * SchedulingEngine engine = SchedulingEngineBuilder.create().defaultMaxQuota(20).build();
 *
 * // Add event listener
 * engine.addEventListener(new ScheduledTaskEventListener() {
 *     &#64;Override
 *     public void onTaskFailed(TaskFailedEvent event) {
 *         System.err.println("Task failed: " + event.getTask().getName());
 *     }
 * });
 *
 * // Start engine
 * engine.start();
 *
 * // ... application runs ...
 *
 * // Shutdown
 * engine.close();
 * }</pre>
 */
public final class SchedulingEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SchedulingEngine.class);

    private final ScheduledTaskManager taskManager;
    private final RoutineExecutor routineExecutor;
    private final TaskScheduler taskScheduler;
    private final ScheduledTaskEventPublisher eventPublisher;

    /**
     * This node's registration on the interrupt bus, held so {@link #close()} can take it back.
     *
     * <p>
     * The engine is the subscriber rather than the {@link RoutineExecutor} because honouring a remote request and
     * making one must stay separate concerns. If the executor both listened and published, its own
     * {@code shutdown()} — which interrupts everything in flight here — would broadcast that to the cluster and stop
     * runs on nodes that are not shutting down at all.
     */
    private final ScheduledTaskInterruptBus.Subscription interruptSubscription;

    /**
     * Creates a new scheduling engine.
     *
     * <p>
     * This constructor is package-private. Use {@link SchedulingEngineBuilder} to create instances.
     * </p>
     */
    SchedulingEngine(ScheduledTaskManager taskManager, RoutineExecutor routineExecutor, TaskScheduler taskScheduler,
            ScheduledTaskEventPublisher eventPublisher, ScheduledTaskInterruptBus interruptBus) {

        this.taskManager = Objects.requireNonNull(taskManager, "Task manager cannot be null");
        this.routineExecutor = Objects.requireNonNull(routineExecutor, "Routine executor cannot be null");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "Task scheduler cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "Event publisher cannot be null");
        Objects.requireNonNull(interruptBus, "Interrupt bus cannot be null");

        // Subscribe through the parameter, not the field: a method reference on `this.routineExecutor` would publish a
        // half-built `this` to a bus that may already be delivering. Honouring a request is just the local interrupt,
        // whose answer (did anything stop here?) a fan-out has nowhere to return, so it is discarded on purpose.
        interruptSubscription = interruptBus.subscribe(routineExecutor::interrupt);

        log.debug("Scheduling engine created");
    }

    /**
     * Starts the scheduling engine.
     *
     * <p>
     * This starts the task scheduler, allowing scheduled tasks to execute.
     * </p>
     */
    public void start() {
        taskScheduler.start();
        log.info("Scheduling engine started");
    }

    /**
     * Closes the scheduling engine.
     *
     * <p>
     * This shuts down the task scheduler and routine executor gracefully.
     * </p>
     *
     * <p>
     * The bus subscription goes first, so that a stop request arriving mid-teardown cannot interleave with the
     * executor's own shutdown sweep. Nothing is lost by dropping it: the sweep that follows stops every run here
     * anyway, for a reason of its own.
     * </p>
     */
    @Override
    public void close() {
        interruptSubscription.close();
        taskScheduler.shutdown();
        routineExecutor.shutdown();
        log.info("Scheduling engine stopped");
    }

    /**
     * Returns the task manager.
     *
     * @return the task manager
     */
    public ScheduledTaskManager getTaskManager() {
        return taskManager;
    }

    /**
     * Adds an event listener.
     *
     * @param listener
     *            the listener to add
     */
    public void addEventListener(ScheduledTaskEventListener listener) {
        eventPublisher.addListener(listener);
    }

    /**
     * Removes an event listener.
     *
     * @param listener
     *            the listener to remove
     */
    public void removeEventListener(ScheduledTaskEventListener listener) {
        eventPublisher.removeListener(listener);
    }
}
