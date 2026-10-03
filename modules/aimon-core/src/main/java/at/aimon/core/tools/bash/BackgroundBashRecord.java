package at.aimon.core.tools.bash;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * What a {@link BackgroundBashStore} keeps about one background command: who started it, on which node it runs and how
 * it ended. This is the part of a background task that nodes can share. The process, its cancellation handle and its
 * output stay on the node that started it ({@link BackgroundBashTask}).
 *
 * <p>
 * It deliberately carries no command text: a command line can hold a credential ({@code curl -H "Authorization: ..."},
 * {@code PGPASSWORD=... psql}), and a shared store is a place it would outlive the process in. The node that runs the
 * command keeps the text in its {@link BackgroundBashTask}.
 */
public final class BackgroundBashRecord {

    private final String taskId;
    private final AgentRuntimeId ownerRuntimeId;
    private final String nodeId;
    private final Instant startedAt;
    private final Instant expiresAt;
    private final BashTaskStatus status;
    private final Integer exitCode;
    private final Instant finishedAt;

    private BackgroundBashRecord(Builder builder) {
        this.taskId = Objects.requireNonNull(builder.taskId, "taskId must not be null");
        this.ownerRuntimeId = builder.ownerRuntimeId;
        this.nodeId = Objects.requireNonNull(builder.nodeId, "nodeId must not be null");
        this.startedAt = Objects.requireNonNull(builder.startedAt, "startedAt must not be null");
        this.expiresAt = builder.expiresAt;
        this.status = Objects.requireNonNull(builder.status, "status must not be null");
        this.exitCode = builder.exitCode;
        this.finishedAt = builder.finishedAt;
    }

    /** @return a new builder for a record in {@link BashTaskStatus#RUNNING} */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder seeded with every field of this record */
    public Builder toBuilder() {
        return new Builder().taskId(taskId).ownerRuntimeId(ownerRuntimeId).nodeId(nodeId).startedAt(startedAt)
                .expiresAt(expiresAt).status(status).exitCode(exitCode).finishedAt(finishedAt);
    }

    /** @return the task id the model was given */
    public String getTaskId() {
        return taskId;
    }

    /**
     * @return the runtime whose execution started the command, or empty for a command started outside any runtime;
     *         only an execution of the same runtime may read or stop the task
     */
    public Optional<AgentRuntimeId> getOwnerRuntimeId() {
        return Optional.ofNullable(ownerRuntimeId);
    }

    /** @return the node whose process runs the command */
    public String getNodeId() {
        return nodeId;
    }

    /** @return when the command was started */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * @return when the command's timeout ends it at the latest, or empty if it has none — a record still
     *         {@link BashTaskStatus#RUNNING} past this instant belongs to a node that never reported the end
     */
    public Optional<Instant> getExpiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    /** @return {@link BashTaskStatus#RUNNING} or the status the command ended in */
    public BashTaskStatus getStatus() {
        return status;
    }

    /** @return the exit code, when the command ended with one */
    public Optional<Integer> getExitCode() {
        return Optional.ofNullable(exitCode);
    }

    /** @return when the command ended, or empty while it runs */
    public Optional<Instant> getFinishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    @Override
    public String toString() {
        return "BackgroundBashRecord{" + taskId + ", owner=" + ownerRuntimeId + ", node=" + nodeId + ", status="
                + status + '}';
    }

    /** Builder for {@link BackgroundBashRecord}. */
    public static final class Builder {
        private String taskId;
        private AgentRuntimeId ownerRuntimeId;
        private String nodeId;
        private Instant startedAt;
        private Instant expiresAt;
        private BashTaskStatus status = BashTaskStatus.RUNNING;
        private Integer exitCode;
        private Instant finishedAt;

        private Builder() {
        }

        /**
         * @param taskId
         *            the task id (required)
         * @return this builder
         */
        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        /**
         * @param ownerRuntimeId
         *            the runtime that started the command, or null for none
         * @return this builder
         */
        public Builder ownerRuntimeId(AgentRuntimeId ownerRuntimeId) {
            this.ownerRuntimeId = ownerRuntimeId;
            return this;
        }

        /**
         * @param nodeId
         *            the node running the command (required)
         * @return this builder
         */
        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /**
         * @param startedAt
         *            when the command was started (required)
         * @return this builder
         */
        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        /**
         * @param expiresAt
         *            when the command's timeout ends it at the latest, or null for none
         * @return this builder
         */
        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /**
         * @param status
         *            the status (default {@link BashTaskStatus#RUNNING})
         * @return this builder
         */
        public Builder status(BashTaskStatus status) {
            this.status = status;
            return this;
        }

        /**
         * @param exitCode
         *            the exit code, or null for none
         * @return this builder
         */
        public Builder exitCode(Integer exitCode) {
            this.exitCode = exitCode;
            return this;
        }

        /**
         * @param finishedAt
         *            when the command ended, or null while it runs
         * @return this builder
         */
        public Builder finishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
            return this;
        }

        /** @return the record */
        public BackgroundBashRecord build() {
            return new BackgroundBashRecord(this);
        }
    }
}
