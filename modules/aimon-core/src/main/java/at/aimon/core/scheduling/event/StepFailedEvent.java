/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.event;

import java.util.Objects;

import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.StepResult;

/**
 * Event published when a routine step fails.
 */
public class StepFailedEvent extends ScheduledTaskEvent {

    private final RoutineStep step;
    private final StepResult result;
    private final int stepIndex;

    /** StepFailedEvent를 생성한다. */
    public StepFailedEvent(ScheduledTask task, RoutineStep step, StepResult result, int stepIndex) {
        super(task);
        this.step = Objects.requireNonNull(step, "Step cannot be null");
        this.result = Objects.requireNonNull(result, "Result cannot be null");
        this.stepIndex = stepIndex;
    }

    /**
     * Returns the routine step.
     *
     * @return the routine step
     */
    public RoutineStep getStep() {
        return step;
    }

    /**
     * Returns the step execution result.
     *
     * @return the step result
     */
    public StepResult getResult() {
        return result;
    }

    /**
     * Returns the step index in the routine.
     *
     * @return the step index (0-based)
     */
    public int getStepIndex() {
        return stepIndex;
    }
}
