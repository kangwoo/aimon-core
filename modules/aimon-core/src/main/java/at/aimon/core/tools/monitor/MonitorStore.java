package at.aimon.core.tools.monitor;

import java.util.Optional;

/**
 * Where a {@link MonitorManager} records the monitors it runs — the part that can be shared between nodes.
 *
 * <p>
 * A store holds {@link MonitorRecord metadata} only. The source behind a monitor (a process, a socket, a subscription)
 * exists on one node and cannot be moved, so a node that finds a record it does not run can say the monitor runs
 * elsewhere, and nothing more. The default {@link InMemoryMonitorStore} serves a single node.
 *
 * <p>
 * Implementations must be thread-safe. A method that fails throws an unchecked exception: the manager refuses to start
 * a monitor it could not record, and otherwise carries on with what it knows locally.
 */
public interface MonitorStore {

    /**
     * Records a newly started monitor unless the id is taken.
     *
     * @param record
     *            the record (must not be null)
     * @return {@code true} if it was stored, {@code false} if the id already exists — the manager then draws another
     */
    boolean putIfAbsent(MonitorRecord record);

    /**
     * @param monitorId
     *            the monitor id (must not be null)
     * @return the record, or empty if there is none
     */
    Optional<MonitorRecord> find(String monitorId);

    /**
     * Removes a record — the monitor has ended. Does nothing if there is none.
     *
     * @param monitorId
     *            the monitor id (must not be null)
     */
    void remove(String monitorId);
}
