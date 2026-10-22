package at.aimon.core.tools.monitor;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link MonitorStore}: records in this process's memory. Correct for a single node; on several nodes each
 * sees only its own.
 */
public final class InMemoryMonitorStore implements MonitorStore {

    private final Map<String, MonitorRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(MonitorRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        return records.putIfAbsent(record.getMonitorId(), record) == null;
    }

    @Override
    public Optional<MonitorRecord> find(String monitorId) {
        Objects.requireNonNull(monitorId, "monitorId must not be null");
        return Optional.ofNullable(records.get(monitorId));
    }

    @Override
    public void remove(String monitorId) {
        Objects.requireNonNull(monitorId, "monitorId must not be null");
        records.remove(monitorId);
    }
}
