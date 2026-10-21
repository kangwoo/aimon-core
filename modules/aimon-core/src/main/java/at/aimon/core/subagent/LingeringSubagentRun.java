package at.aimon.core.subagent;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;

/**
 * A subagent run that was asked to stop — its deadline passed, or its spawner cancelled it — and had not exited when
 * its {@linkplain SubagentExecutionPolicy#getExitGracePeriod() grace period} ran out.
 *
 * <p>
 * Asking a thread to stop is not stopping it. A tool or client that ignores both the cancellation signal and the thread
 * interrupt cannot be stopped safely from inside the JVM ({@code Thread.stop} is not used), so the run is left to
 * finish on its own, and this record is what says it is still there: {@link SubagentExecutionManager#lingeringRuns()}
 * lists it from the moment the grace ran out until the run actually returns. The caller was handed a result whose
 * {@code isExitConfirmed()} is {@code false}; a caller about to launch the same work again should look here first, or
 * it may run it twice.
 *
 * <p>
 * Node-local by nature: it describes a thread on this JVM.
 *
 * <p>
 * Immutable value object.
 */
public final class LingeringSubagentRun {

    private final String taskId;
    private final String subagentName;
    private final AgentRuntimeId agentRuntimeId;
    private final CompletionReason stopReason;
    private final boolean background;
    private final Instant reportedAt;

    private LingeringSubagentRun(Builder builder) {
        this.taskId = Objects.requireNonNull(builder.taskId, "taskId cannot be null");
        this.subagentName = Objects.requireNonNull(builder.subagentName, "subagentName cannot be null");
        this.agentRuntimeId = Objects.requireNonNull(builder.agentRuntimeId, "agentRuntimeId cannot be null");
        this.stopReason = Objects.requireNonNull(builder.stopReason, "stopReason cannot be null");
        this.background = builder.background;
        this.reportedAt = Objects.requireNonNull(builder.reportedAt, "reportedAt cannot be null");
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the task id the run was launched under */
    public String getTaskId() {
        return taskId;
    }

    /** @return the subagent that is still running */
    public String getSubagentName() {
        return subagentName;
    }

    /** @return the runtime that launched it */
    public AgentRuntimeId getAgentRuntimeId() {
        return agentRuntimeId;
    }

    /**
     * @return why it was asked to stop: {@link CompletionReason#WALL_CLOCK_EXCEEDED} for a deadline,
     *         {@link CompletionReason#INTERRUPTED} for a cancellation
     */
    public CompletionReason getStopReason() {
        return stopReason;
    }

    /** @return whether it is a background task (its worker is still occupied) rather than a foreground fork */
    public boolean isBackground() {
        return background;
    }

    /** @return when its grace period ran out */
    public Instant getReportedAt() {
        return reportedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LingeringSubagentRun that = (LingeringSubagentRun) o;
        return background == that.background && taskId.equals(that.taskId) && subagentName.equals(that.subagentName)
                && agentRuntimeId.equals(that.agentRuntimeId) && stopReason == that.stopReason
                && reportedAt.equals(that.reportedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, subagentName, agentRuntimeId, stopReason, background, reportedAt);
    }

    @Override
    public String toString() {
        return "LingeringSubagentRun{taskId='" + taskId + "', subagent='" + subagentName + "', stopReason=" + stopReason
                + ", background=" + background + ", reportedAt=" + reportedAt + '}';
    }

    /** Builder for {@link LingeringSubagentRun}. */
    public static final class Builder {
        private String taskId;
        private String subagentName;
        private AgentRuntimeId agentRuntimeId;
        private CompletionReason stopReason;
        private boolean background;
        private Instant reportedAt;

        private Builder() {
        }

        /**
         * Sets the task id.
         *
         * @param taskId
         *            the value
         * @return this builder
         */
        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        /**
         * Sets the subagent name.
         *
         * @param subagentName
         *            the value
         * @return this builder
         */
        public Builder subagentName(String subagentName) {
            this.subagentName = subagentName;
            return this;
        }

        /**
         * Sets the launching runtime.
         *
         * @param agentRuntimeId
         *            the value
         * @return this builder
         */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * Sets why the run was asked to stop.
         *
         * @param stopReason
         *            the value
         * @return this builder
         */
        public Builder stopReason(CompletionReason stopReason) {
            this.stopReason = stopReason;
            return this;
        }

        /**
         * Sets whether the run is a background task.
         *
         * @param background
         *            the value
         * @return this builder
         */
        public Builder background(boolean background) {
            this.background = background;
            return this;
        }

        /**
         * Sets when the grace period ran out.
         *
         * @param reportedAt
         *            the value
         * @return this builder
         */
        public Builder reportedAt(Instant reportedAt) {
            this.reportedAt = reportedAt;
            return this;
        }

        /** @return the record */
        public LingeringSubagentRun build() {
            return new LingeringSubagentRun(this);
        }
    }
}
