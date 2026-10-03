package at.aimon.core.tools.bash;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link BackgroundBashStore}: records in this process's memory. Correct for a single node, where every
 * record has its task right beside it; on several nodes each sees only its own.
 */
public final class InMemoryBackgroundBashStore implements BackgroundBashStore {

    private final Map<String, BackgroundBashRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(BackgroundBashRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        return records.putIfAbsent(record.getTaskId(), record) == null;
    }

    @Override
    public Optional<BackgroundBashRecord> find(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return Optional.ofNullable(records.get(taskId));
    }

    @Override
    public Optional<BackgroundBashRecord> settle(String taskId, BashTaskStatus terminal, Integer exitCode, Instant at) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(terminal, "terminal must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (terminal == BashTaskStatus.RUNNING || terminal == BashTaskStatus.NOT_FOUND) {
            throw new IllegalArgumentException("Not a status a command ends in: " + terminal);
        }
        return Optional.ofNullable(records.computeIfPresent(taskId,
                (id, current) -> current.getStatus() != BashTaskStatus.RUNNING
                        ? current
                        : current.toBuilder().status(terminal).exitCode(exitCode).finishedAt(at).build()));
    }

    @Override
    public void remove(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        records.remove(taskId);
    }
}
