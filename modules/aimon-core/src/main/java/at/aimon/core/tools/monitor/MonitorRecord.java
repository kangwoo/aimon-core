package at.aimon.core.tools.monitor;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.tools.BackgroundTaskOwner;

/**
 * What is known about a running monitor beyond the node that runs it: who owns it, where it runs, what kind it is and
 * until when.
 *
 * <p>
 * Carries no parameters — no command, no URL. Those may hold secrets, and a shared {@link MonitorStore} would keep them
 * beyond the node and the process they belong to. Immutable; thread-safe.
 */
public final class MonitorRecord {

    private final String monitorId;
    private final BackgroundTaskOwner owner;
    private final String nodeId;
    private final String kind;
    private final String description;
    private final Instant startedAt;
    private final Instant deadline;

    private MonitorRecord(Builder builder) {
        this.monitorId = Objects.requireNonNull(builder.monitorId, "monitorId must not be null");
        this.owner = Objects.requireNonNull(builder.owner, "owner must not be null");
        this.nodeId = Objects.requireNonNull(builder.nodeId, "nodeId must not be null");
        this.kind = Objects.requireNonNull(builder.kind, "kind must not be null");
        this.description = Objects.requireNonNull(builder.description, "description must not be null");
        this.startedAt = Objects.requireNonNull(builder.startedAt, "startedAt must not be null");
        this.deadline = Objects.requireNonNull(builder.deadline, "deadline must not be null");
    }

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the monitor id, {@code mon_} and eight hex digits (never null)
     */
    public String getMonitorId() {
        return monitorId;
    }

    /**
     * @return who the monitor was started for (never null)
     */
    public BackgroundTaskOwner getOwner() {
        return owner;
    }

    /**
     * @return the node running the monitor (never null)
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * @return the kind of source (never null)
     */
    public String getKind() {
        return kind;
    }

    /**
     * @return the model's description of what is watched (never null)
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return when the monitor started (never null)
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * @return when the monitor is stopped if nothing ends it first (never null)
     */
    public Instant getDeadline() {
        return deadline;
    }

    @Override
    public String toString() {
        return "MonitorRecord{id=" + monitorId + ", kind=" + kind + ", node=" + nodeId + ", owner=" + owner + '}';
    }

    /** Builder for {@link MonitorRecord}. */
    public static final class Builder {

        private String monitorId;
        private BackgroundTaskOwner owner;
        private String nodeId;
        private String kind;
        private String description;
        private Instant startedAt;
        private Instant deadline;

        private Builder() {
        }

        /**
         * @param monitorId
         *            the monitor id (must not be null)
         * @return this builder
         */
        public Builder monitorId(String monitorId) {
            this.monitorId = monitorId;
            return this;
        }

        /**
         * @param owner
         *            who the monitor was started for (must not be null)
         * @return this builder
         */
        public Builder owner(BackgroundTaskOwner owner) {
            this.owner = owner;
            return this;
        }

        /**
         * @param nodeId
         *            the node running the monitor (must not be null)
         * @return this builder
         */
        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /**
         * @param kind
         *            the kind of source (must not be null)
         * @return this builder
         */
        public Builder kind(String kind) {
            this.kind = kind;
            return this;
        }

        /**
         * @param description
         *            what is watched (must not be null)
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * @param startedAt
         *            when the monitor started (must not be null)
         * @return this builder
         */
        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        /**
         * @param deadline
         *            when the monitor is stopped at the latest (must not be null)
         * @return this builder
         */
        public Builder deadline(Instant deadline) {
            this.deadline = deadline;
            return this;
        }

        /**
         * @return the record
         * @throws NullPointerException
         *             if a field is missing
         */
        public MonitorRecord build() {
            return new MonitorRecord(this);
        }
    }
}
