/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.util.Map;

import at.aimon.core.agent.ExecutionId;

/**
 * Host-supplied execution attributes for one run of a routine — what {@code SubmitOptions.executionAttribute(...)} is
 * for a session turn (issue #252).
 *
 * <p>
 * A routine has no submit call to attach them to, and its execution id is minted at fire time, so the host is asked
 * instead: once per run, after the id is minted and before the first step. The answer is used twice. Its
 * {@code environment.*} subset, prefix removed, becomes the run's
 * {@link at.aimon.core.environment.EnvironmentRequest#requestAttributes() request attributes}; the whole map is put on
 * the steps' tool context under {@code ToolContextKeys.EXECUTION_ATTRIBUTES_KEY}, as a turn's tools see theirs.
 *
 * <p>
 * The attributes come from here and never from the {@link ScheduledTask}, because the model creates tasks: the
 * {@code environment.*} namespace is placement input, and a host must not fill it from model output. A host that keeps
 * per-task placement looks it up here by {@link ScheduledTask#getId()}.
 *
 * <p>
 * Called on every fire, on the firing node, on the run's own thread before its first cancellation gate — so it should
 * be fast. A resolver that throws or returns null does not abort the run: the run's environment becomes unavailable
 * with the cause, as a failing provider's would, and its steps see no execution attributes.
 */
@FunctionalInterface
public interface RoutineExecutionAttributes {

    /** Attaches nothing; the default. */
    RoutineExecutionAttributes NONE = (task, executionId) -> Map.of();

    /**
     * Returns the execution attributes of one run.
     *
     * @param task
     *            the task being fired
     * @param executionId
     *            the run's execution id, the same value its steps see under {@code ToolContextKeys.EXECUTION_ID}
     * @return the attributes (must not be null; {@code environment.*} values must be {@code String}s)
     */
    Map<String, Object> resolve(ScheduledTask task, ExecutionId executionId);
}
