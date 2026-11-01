/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

import java.util.Objects;

import at.aimon.core.scheduling.ScheduledTask;

/**
 * Event published when the engine disables a task on its own, rather than at its owner's request.
 *
 * <p>
 * Today the one cause is a task whose bound agent runtime cannot be resolved when it fires, under
 * {@code UnresolvableRuntimePolicy#DISABLE}. The task's record is kept, disabled, so its owner can see it and enable it
 * again once the runtime exists; it does not fire until then. An owner's own {@code setEnabled(false)} publishes
 * nothing.
 */
public class TaskDisabledEvent extends ScheduledTaskEvent {

    private final String reason;

    /**
     * TaskDisabledEvent를 생성한다.
     *
     * @param task
     *            the task as it now stands, disabled
     * @param reason
     *            why the engine disabled it
     */
    public TaskDisabledEvent(ScheduledTask task, String reason) {
        super(task);
        this.reason = Objects.requireNonNull(reason, "Reason cannot be null");
    }

    /**
     * Returns why the engine disabled the task.
     *
     * @return the reason
     */
    public String getReason() {
        return reason;
    }
}
