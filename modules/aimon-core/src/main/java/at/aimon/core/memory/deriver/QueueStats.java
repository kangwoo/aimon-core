package at.aimon.core.memory.deriver;

/**
 * Snapshot of {@link DerivationQueueManager} runtime state. All counts are
 * point-in-time and may go stale immediately after observation.
 */
public final class QueueStats {

    private final int queueSize;
    private final int activeWorkers;
    private final long completedTasks;
    private final long failedTasks;

    private QueueStats(int queueSize, int activeWorkers, long completedTasks, long failedTasks) {
        if (queueSize < 0) {
            throw new IllegalArgumentException("queueSize must be >= 0, got " + queueSize);
        }
        if (activeWorkers < 0) {
            throw new IllegalArgumentException("activeWorkers must be >= 0, got " + activeWorkers);
        }
        if (completedTasks < 0) {
            throw new IllegalArgumentException("completedTasks must be >= 0, got " + completedTasks);
        }
        if (failedTasks < 0) {
            throw new IllegalArgumentException("failedTasks must be >= 0, got " + failedTasks);
        }
        this.queueSize = queueSize;
        this.activeWorkers = activeWorkers;
        this.completedTasks = completedTasks;
        this.failedTasks = failedTasks;
    }

    public static QueueStats of(int queueSize, int activeWorkers, long completedTasks, long failedTasks) {
        return new QueueStats(queueSize, activeWorkers, completedTasks, failedTasks);
    }

    public int getQueueSize() {
        return queueSize;
    }

    public int getActiveWorkers() {
        return activeWorkers;
    }

    public long getCompletedTasks() {
        return completedTasks;
    }

    public long getFailedTasks() {
        return failedTasks;
    }

    @Override
    public String toString() {
        return "QueueStats{queue=" + queueSize + ", active=" + activeWorkers + ", completed=" + completedTasks
                + ", failed=" + failedTasks + "}";
    }
}
