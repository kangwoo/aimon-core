/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

/**
 * Listener interface for scheduled task events.
 *
 * <p>
 * Implement this interface to receive notifications about task lifecycle events. All methods have default empty
 * implementations, so you only need to override the events you're interested in.
 * </p>
 */
public interface ScheduledTaskEventListener {

    /**
     * Called when a task is registered.
     *
     * @param event
     *            the registration event
     */
    default void onTaskRegistered(TaskRegisteredEvent event) {
    }

    /**
     * Called when a task execution starts.
     *
     * @param event
     *            the start event
     */
    default void onTaskStarted(TaskStartedEvent event) {
    }

    /**
     * Called when a task execution completes successfully.
     *
     * @param event
     *            the completion event
     */
    default void onTaskCompleted(TaskCompletedEvent event) {
    }

    /**
     * Called when a task execution fails.
     *
     * @param event
     *            the failure event
     */
    default void onTaskFailed(TaskFailedEvent event) {
    }

    /**
     * Called when a task is cancelled.
     *
     * @param event
     *            the cancellation event
     */
    default void onTaskCancelled(TaskCancelledEvent event) {
    }

    /**
     * Called when an in-flight routine run is interrupted before it finished. The task's schedule is untouched unless
     * a {@link TaskCancelledEvent} accompanies this one.
     *
     * @param event
     *            the interrupt event
     */
    default void onTaskInterrupted(TaskInterruptedEvent event) {
    }

    /**
     * Called when a routine step completes successfully.
     *
     * @param event
     *            the step completion event
     */
    default void onStepCompleted(StepCompletedEvent event) {
    }

    /**
     * Called when a routine step fails.
     *
     * @param event
     *            the step failure event
     */
    default void onStepFailed(StepFailedEvent event) {
    }
}
