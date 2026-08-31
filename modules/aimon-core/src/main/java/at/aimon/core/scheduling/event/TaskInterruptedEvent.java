/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

import java.util.Objects;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.scheduling.RoutineResult;
import at.aimon.core.scheduling.ScheduledTask;

/**
 * Event published when an in-flight routine run is stopped before it finished.
 *
 * <p>
 * This is not {@link TaskCancelledEvent}, and the two are kept apart on purpose because they answer different
 * questions. {@code TaskCancelledEvent} says the <em>schedule</em> is gone: the task was unscheduled and deleted, and
 * it will not fire again. This event says one <em>run</em> was stopped, and says nothing about whether the task
 * survives &mdash; interrupting a run leaves the schedule in place, while cancelling the task publishes both.
 *
 * <p>
 * Nor is it {@link TaskFailedEvent}. A cancelled run has no faulty step to point at, so a subscriber that alerts on
 * failures would be alerting on somebody having pressed stop.
 */
public class TaskInterruptedEvent extends ScheduledTaskEvent {

    private final RoutineResult result;
    private final InterruptReason reason;

    /** TaskInterruptedEvent를 생성한다. */
    public TaskInterruptedEvent(ScheduledTask task, RoutineResult result, InterruptReason reason) {
        super(task);
        this.result = Objects.requireNonNull(result, "Result cannot be null");
        this.reason = Objects.requireNonNull(reason, "Reason cannot be null");
    }

    /**
     * Returns the partial result of the interrupted run — the steps that had been attempted before it unwound.
     *
     * @return the routine result
     */
    public RoutineResult getResult() {
        return result;
    }

    /**
     * Returns why the run was interrupted.
     *
     * @return the interrupt reason
     */
    public InterruptReason getReason() {
        return reason;
    }
}
