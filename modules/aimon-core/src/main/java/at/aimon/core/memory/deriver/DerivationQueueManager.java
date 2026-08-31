package at.aimon.core.memory.deriver;

/**
 * Asynchronous work queue that funnels {@link DerivationTask}s into a
 * {@link Deriver}.
 *
 * <p>
 * Implementations must enforce two invariants:
 * <ul>
 * <li><strong>Redaction is mandatory.</strong> Every enqueued task has its
 * messages run through the configured redaction policy before any worker can
 * see them. Callers cannot bypass this gate. (design doc §6.5, §7)</li>
 * <li><strong>Per-work-unit serialization.</strong> Tasks sharing a
 * {@link DerivationWorkUnit} run one at a time; tasks with different work units
 * may run in parallel up to the worker pool size. (design doc §6.1.2)</li>
 * </ul>
 *
 * <p>
 * Lifecycle: {@link #start()} kicks off the worker pool, {@link #stop()}
 * stops accepting new claims and waits for in-flight work to finish.
 */
public interface DerivationQueueManager {

    /**
     * Schedules {@code task} for processing. Implementations must apply redaction
     * before the task becomes visible to any worker. May be called before
     * {@link #start()} — pending tasks are picked up once the pool starts.
     *
     * @throws IllegalStateException
     *             if the manager has been stopped
     */
    void enqueue(DerivationTask task);

    /** Starts the worker pool. Idempotent. */
    void start();

    /**
     * Stops accepting new work and drains in-flight tasks. After this call
     * {@link #enqueue(DerivationTask)} throws {@link IllegalStateException}.
     */
    void stop();

    /** Point-in-time snapshot of the queue. */
    QueueStats stats();
}
