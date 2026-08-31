package at.aimon.core.subagent;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

import at.aimon.core.subagent.task.BackgroundTaskStore;
import at.aimon.core.subagent.task.InMemoryBackgroundTaskStore;
import at.aimon.core.subagent.task.NoopTaskStopSignal;
import at.aimon.core.subagent.task.TaskLeaseConfig;
import at.aimon.core.subagent.task.TaskStopSignal;

/**
 * Immutable holder grouping the cohesive "background execution + multi-instance recovery" parameters of
 * {@link DefaultSubagentExecutionManager}: the background {@link ExecutorService}, the durable
 * {@link BackgroundTaskStore},
 * the cross-node {@link TaskStopSignal}, and the optional zombie-recovery {@link TaskLeaseConfig}.
 *
 * <p>
 * The bundle keeps the single-node defaults byte-for-byte identical to the previous constructor behaviour: when a task
 * store is not supplied it defaults to a node-local {@link InMemoryBackgroundTaskStore}, the stop signal defaults to
 * {@link NoopTaskStopSignal#INSTANCE} (local stops only), and lease recovery is off ({@code leaseConfig == null}).
 *
 * <p>
 * The {@code executorService} is the only required field.
 */
public final class SubagentBackgroundExecutionOptions {

    private final ExecutorService executorService;
    private final BackgroundTaskStore taskStore;
    private final TaskStopSignal taskStopSignal;
    private final TaskLeaseConfig leaseConfig;

    private SubagentBackgroundExecutionOptions(Builder builder) {
        this.executorService = Objects.requireNonNull(builder.executorService, "Executor service cannot be null");
        this.taskStore = builder.taskStore != null ? builder.taskStore : new InMemoryBackgroundTaskStore();
        this.taskStopSignal = builder.taskStopSignal != null ? builder.taskStopSignal : NoopTaskStopSignal.INSTANCE;
        this.leaseConfig = builder.leaseConfig;
    }

    /**
     * Creates a bundle carrying just the background executor, defaulting the task store to a node-local
     * {@link InMemoryBackgroundTaskStore}, the stop signal to {@link NoopTaskStopSignal#INSTANCE} and lease recovery
     * off.
     *
     * @param executorService
     *            the background executor service (must not be null)
     * @return the options bundle
     */
    public static SubagentBackgroundExecutionOptions of(ExecutorService executorService) {
        return builder().executorService(executorService).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The background executor service (never null). */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /** The durable background task metadata store (never null). */
    public BackgroundTaskStore getTaskStore() {
        return taskStore;
    }

    /** The cross-node stop signal (never null; {@link NoopTaskStopSignal#INSTANCE} for local-only). */
    public TaskStopSignal getTaskStopSignal() {
        return taskStopSignal;
    }

    /** The zombie-recovery lease configuration, or {@code null} when lease heartbeat/reaping is disabled. */
    public TaskLeaseConfig getLeaseConfig() {
        return leaseConfig;
    }

    /** Builder for {@link SubagentBackgroundExecutionOptions}. */
    public static final class Builder {
        private ExecutorService executorService;
        private BackgroundTaskStore taskStore;
        private TaskStopSignal taskStopSignal;
        private TaskLeaseConfig leaseConfig;

        private Builder() {
        }

        /**
         * @param executorService
         *            the background executor service (must not be null)
         */
        public Builder executorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * @param taskStore
         *            the background task metadata store; null defaults to a node-local
         *            {@link InMemoryBackgroundTaskStore}
         */
        public Builder taskStore(BackgroundTaskStore taskStore) {
            this.taskStore = taskStore;
            return this;
        }

        /**
         * @param taskStopSignal
         *            the cross-node stop signal; null defaults to {@link NoopTaskStopSignal#INSTANCE} (local-only)
         */
        public Builder taskStopSignal(TaskStopSignal taskStopSignal) {
            this.taskStopSignal = taskStopSignal;
            return this;
        }

        /**
         * @param leaseConfig
         *            the zombie-recovery lease configuration; null disables lease heartbeat/reaping
         */
        public Builder leaseConfig(TaskLeaseConfig leaseConfig) {
            this.leaseConfig = leaseConfig;
            return this;
        }

        public SubagentBackgroundExecutionOptions build() {
            return new SubagentBackgroundExecutionOptions(this);
        }
    }
}
