/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.tools.scheduling;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.scheduling.RoutineStep;

/**
 * Data transfer object for scheduling a new task.
 */
public final class ScheduleTaskRequest {

    private final String name;
    private final String description;
    private final String cronExpression;
    private final String timezone;
    private final List<RoutineStep> routine;

    private ScheduleTaskRequest(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "Name cannot be null");
        this.description = builder.description;
        this.cronExpression = Objects.requireNonNull(builder.cronExpression, "Cron expression cannot be null");
        this.timezone = builder.timezone;
        this.routine = builder.routine != null ? List.copyOf(builder.routine) : List.of();

        if (routine.isEmpty()) {
            throw new IllegalArgumentException("Routine must contain at least one step");
        }
    }

    /**
     * Creates a request from a map of parameters (for tool input parsing).
     *
     * @param params
     *            the parameter map
     * @return a new request
     */
    @SuppressWarnings("unchecked")
    public static ScheduleTaskRequest fromMap(Map<String, Object> params) {
        Builder builder = builder().name((String) params.get("name")).description((String) params.get("description"));

        // Parse schedule object (new nested structure)
        Object scheduleObj = params.get("schedule");
        if (scheduleObj instanceof Map<?, ?> scheduleMap) {
            Map<String, Object> schedule = (Map<String, Object>) scheduleMap;
            builder.cronExpression((String) schedule.get("cron_expression"));
            builder.timezone((String) schedule.get("timezone"));
        } else {
            // Fallback for legacy flat structure
            builder.cronExpression((String) params.get("cron_expression"));
        }

        if (params.containsKey("workflow") && !params.containsKey("routine")) {
            throw new IllegalArgumentException("Parameter 'workflow' has been renamed to 'routine'");
        }

        Object routineObj = params.get("routine");
        if (routineObj instanceof List<?> routineList) {
            for (Object stepObj : routineList) {
                if (stepObj instanceof Map<?, ?> stepMap) {
                    Map<String, Object> step = (Map<String, Object>) stepMap;
                    String toolParams = Optional.ofNullable(step.get("tool_params")).map(Object::toString).orElse("");
                    RoutineStep.Builder stepBuilder = RoutineStep.builder().tool((String) step.get("tool"))
                            .toolParams(toolParams);

                    if (step.containsKey("id")) {
                        stepBuilder.id((String) step.get("id"));
                    }
                    if (step.containsKey("max_retries")) {
                        stepBuilder.maxRetries(((Number) step.get("max_retries")).intValue());
                    }
                    if (step.containsKey("timeout_ms")) {
                        stepBuilder.timeoutMs(((Number) step.get("timeout_ms")).longValue());
                    }

                    builder.addStep(stepBuilder.build());
                }
            }
        }

        return builder.build();
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public String getTimezone() {
        return timezone;
    }

    public List<RoutineStep> getRoutine() {
        return routine;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ScheduleTaskRequest that = (ScheduleTaskRequest) o;
        return name.equals(that.name) && Objects.equals(description, that.description)
                && cronExpression.equals(that.cronExpression) && Objects.equals(timezone, that.timezone)
                && routine.equals(that.routine);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, cronExpression, timezone, routine);
    }

    @Override
    public String toString() {
        return "ScheduleTaskRequest{name='" + name + "', cron='" + cronExpression + "', timezone='" + timezone
                + "', steps=" + routine.size() + "}";
    }

    /**
     * Builder for {@link ScheduleTaskRequest}.
     */
    public static final class Builder {

        private String name;
        private String description;
        private String cronExpression;
        private String timezone;
        private List<RoutineStep> routine = new ArrayList<>();

        private Builder() {
        }

        /** Sets the task name. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Sets the task description. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Sets the cron expression. */
        public Builder cronExpression(String cronExpression) {
            this.cronExpression = cronExpression;
            return this;
        }

        /** Sets the timezone. */
        public Builder timezone(String timezone) {
            this.timezone = timezone;
            return this;
        }

        /** Sets the routine steps. */
        public Builder routine(List<RoutineStep> routine) {
            this.routine = routine != null ? new ArrayList<>(routine) : new ArrayList<>();
            return this;
        }

        /** Adds a routine step. */
        public Builder addStep(RoutineStep step) {
            this.routine.add(step);
            return this;
        }

        /** Builds the ScheduleTaskRequest. */
        public ScheduleTaskRequest build() {
            return new ScheduleTaskRequest(this);
        }
    }
}
