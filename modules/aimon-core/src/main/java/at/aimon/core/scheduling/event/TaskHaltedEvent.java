/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

import java.util.Objects;

import at.aimon.core.scheduling.RoutineResult;
import at.aimon.core.scheduling.ScheduledTask;

/**
 * Event published when a routine run stops early because one of its steps asked it to: the step's tool reported that
 * its work waits on something outside the run, such as a human approval ({@code ToolResult#haltingRoutine}).
 *
 * <p>
 * Neither {@link TaskFailedEvent} nor {@link TaskInterruptedEvent}. No step failed, so a subscriber that alerts on
 * failures should not alert on this; and nobody stopped the run, so it is not an interrupt either. The schedule is
 * untouched and the next fire runs as usual.
 */
public class TaskHaltedEvent extends ScheduledTaskEvent {

    private final RoutineResult result;

    /**
     * TaskHaltedEvent를 생성한다.
     *
     * @param task
     *            the task whose run halted
     * @param result
     *            the halted run's result
     */
    public TaskHaltedEvent(ScheduledTask task, RoutineResult result) {
        super(task);
        this.result = Objects.requireNonNull(result, "Result cannot be null");
    }

    /**
     * Returns the result of the halted run; its last step is the one that halted it.
     *
     * @return the routine result
     */
    public RoutineResult getResult() {
        return result;
    }

    /**
     * Returns the reason the halting step's tool gave.
     *
     * @return the halt reason
     */
    public String getReason() {
        return result.getHaltReason().orElse("");
    }
}
